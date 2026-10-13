"""Background polling: sensors in, events into the store."""

import logging
import threading
import time
from typing import Dict, Optional

from .meals import MealDetector
from .sensors.base import SensorSet
from .snapshots import SnapshotStore
from .store import (KIND_FEEDING, KIND_LOCATION, KIND_MOTION, KIND_WEIGHT,
                    EventStore)

log = logging.getLogger(__name__)


class Monitor:
    def __init__(
        self,
        sensors: SensorSet,
        store: EventStore,
        poll_interval_s: float = 0.2,
        motion_debounce_s: float = 30.0,
        weight_log_interval_s: float = 60.0,
        location_log_interval_s: float = 300.0,
        prune_interval_s: float = 3600.0,
        detector: Optional[MealDetector] = None,
        snapshots: Optional[SnapshotStore] = None,
    ):
        self._sensors = sensors
        self._store = store
        self._poll_interval = poll_interval_s
        self._motion_debounce_s = motion_debounce_s
        self._weight_log_interval = weight_log_interval_s
        self._location_log_interval = location_log_interval_s
        self._prune_interval = prune_interval_s
        self._detector = detector or MealDetector()
        self._snapshots = snapshots

        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

        self._last_motion_at: Dict[str, float] = {}
        self._last_weight_log = 0.0
        self._last_location_log = 0.0
        self._last_prune = 0.0
        self._latest_weight: Optional[float] = None

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="monitor", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 2.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=timeout)
            self._thread = None

    @property
    def latest_weight_grams(self) -> Optional[float]:
        return self._latest_weight

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.poll_once()
            except Exception:
                # One bad reading must not stop the loop.
                log.exception("poll failed")
            self._stop.wait(self._poll_interval)

    def poll_once(self, now: Optional[float] = None) -> None:
        if now is None:
            now = time.time()
        now_ms = int(now * 1000)

        self._poll_scale(now, now_ms)
        self._poll_motion(now, now_ms)
        self._poll_location(now, now_ms)

        # Close a hold feeder that was never told to close.
        feeder_tick = getattr(self._sensors.feeder, "tick", None)
        if feeder_tick is not None:
            feeder_tick()

        if now - self._last_prune >= self._prune_interval:
            self._last_prune = now
            removed = self._store.prune(now_ms)
            if removed:
                log.info("pruned %d old events", removed)
            if self._snapshots is not None:
                removed = self._snapshots.prune(now)
                if removed:
                    log.info("pruned %d old snapshots", removed)

    def _poll_scale(self, now: float, now_ms: int) -> None:
        grams = self._sensors.scale.read_grams()
        if grams is None:
            return
        self._latest_weight = grams

        event = self._detector.update(now_ms, grams)
        if event is not None:
            self._store.append(
                KIND_FEEDING,
                {"grams": event.grams, "type": event.kind},
                timestamp_ms=event.timestamp_ms,
                source="load-cell",
            )
            log.info("%s %d g", event.kind, event.grams)

        if now - self._last_weight_log >= self._weight_log_interval:
            self._last_weight_log = now
            self._store.append(
                KIND_WEIGHT,
                {"grams": round(grams, 1)},
                timestamp_ms=now_ms,
                source="load-cell",
            )

    def _poll_motion(self, now: float, now_ms: int) -> None:
        for sensor in self._sensors.motion_sensors:
            if not sensor.poll():
                continue

            last = self._last_motion_at.get(sensor.room)
            if last is not None and now - last < self._motion_debounce_s:
                continue

            self._last_motion_at[sensor.room] = now

            payload = {"room": sensor.room}
            snapshot = self._capture(sensor.room, now_ms)
            if snapshot is not None:
                payload["snapshot"] = snapshot

            self._store.append(
                KIND_MOTION,
                payload,
                timestamp_ms=now_ms,
                source="pir",
            )
            log.info("motion in %s%s", sensor.room,
                     " (snapshot)" if snapshot else "")

    def _poll_location(self, now: float, now_ms: int) -> None:
        location = self._sensors.location
        if location is None or not location.available:
            return
        if now - self._last_location_log < self._location_log_interval:
            return
        self._last_location_log = now

        fix = location.read_fix()
        if fix is None:
            return

        self._store.append(KIND_LOCATION, fix, timestamp_ms=now_ms, source="gps")
        log.info("fix at %.5f, %.5f from %d satellites",
                 fix["lat"], fix["lon"], fix.get("satellites", 0))

    def _capture(self, room: str, now_ms: int) -> Optional[str]:
        if self._snapshots is None:
            return None

        camera = self._sensors.camera_for_room(room)
        if camera is None or not camera.available:
            return None

        try:
            frame = camera.read_jpeg()
        except Exception:
            log.exception("could not read a frame for %s", room)
            return None

        if frame is None:
            return None
        return self._snapshots.save(frame, room, now_ms)

    def dispense(self, grams: int) -> dict:
        if not self._sensors.feeder.available:
            raise RuntimeError("feeder unavailable")
        self._sensors.feeder.dispense(grams)
        return {"requested_grams": grams, "note": "actual amount comes from the load cell"}

    def tare(self) -> None:
        self._sensors.scale.tare()
        self._detector.reset(0.0)
