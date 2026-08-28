"""Background polling: sensors in, events in the store.

One thread does all the polling. Three sensors at a few hertz is nowhere near
enough work to justify a thread each, and a single loop means the readings share
a clock and cannot interleave confusingly in the log.

Nothing here talks HTTP and nothing here talks to hardware directly — it takes a
:class:`SensorSet`, so it runs identically against fakes and against a Pi.
"""

import logging
import threading
import time
from typing import Dict, Optional

from .meals import MealDetector
from .sensors.base import SensorSet
from .snapshots import SnapshotStore
from .store import KIND_FEEDING, KIND_MOTION, KIND_WEIGHT, EventStore

log = logging.getLogger(__name__)


class Monitor:
    """Polls the sensors and appends what it finds."""

    def __init__(
        self,
        sensors: SensorSet,
        store: EventStore,
        poll_interval_s: float = 0.2,
        motion_debounce_s: float = 30.0,
        weight_log_interval_s: float = 60.0,
        prune_interval_s: float = 3600.0,
        detector: Optional[MealDetector] = None,
        snapshots: Optional[SnapshotStore] = None,
    ):
        """
        :param motion_debounce_s: a PIR latches high for seconds and retriggers
            constantly while an animal is in the room. Without a debounce one
            visit becomes hundreds of rows saying the same thing.
        :param weight_log_interval_s: raw weights are recorded occasionally for
            diagnostics. Meals come from the detector, not from these.
        :param snapshots: where motion snapshots are written, or None for a rig
            with no camera or with snapshots turned off. The picture is taken on
            the same debounce as the event, so a cat that settles in one room
            produces one photo every 30 s rather than one per poll.
        """
        self._sensors = sensors
        self._store = store
        self._poll_interval = poll_interval_s
        self._motion_debounce_s = motion_debounce_s
        self._weight_log_interval = weight_log_interval_s
        self._prune_interval = prune_interval_s
        self._detector = detector or MealDetector()
        self._snapshots = snapshots

        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

        self._last_motion_at: Dict[str, float] = {}
        self._last_weight_log = 0.0
        self._last_prune = 0.0
        self._latest_weight: Optional[float] = None

    # ── lifecycle ───────────────────────────────────────────────────────────

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

    # ── the loop ────────────────────────────────────────────────────────────

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.poll_once()
            except Exception:
                # One bad reading must not kill the loop. A Pi that stops
                # logging because a wire came loose is worse than one that
                # keeps going with a gap.
                log.exception("poll failed")
            self._stop.wait(self._poll_interval)

    def poll_once(self, now: Optional[float] = None) -> None:
        """One pass over every sensor. Separate from the loop so tests can step it."""
        if now is None:
            now = time.time()
        now_ms = int(now * 1000)

        self._poll_scale(now, now_ms)
        self._poll_motion(now, now_ms)

        if now - self._last_prune >= self._prune_interval:
            self._last_prune = now
            removed = self._store.prune(now_ms)
            if removed:
                log.info("pruned %d old events", removed)
            if self._snapshots is not None:
                # On the same schedule as the events: a snapshot outliving the
                # event that explains it is just a file nobody can interpret.
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

    def _capture(self, room: str, now_ms: int) -> Optional[str]:
        """One frame, if there is a camera and it is willing.

        Every failure here is swallowed on purpose. A camera that has been
        unplugged, a driver that returns nothing, a full SD card — none of those
        should cost the motion event, which is the reading that actually
        matters. The event simply arrives without a picture.
        """
        if self._snapshots is None:
            return None

        camera = self._sensors.camera
        if not camera.available:
            return None

        try:
            frame = camera.read_jpeg()
        except Exception:
            log.exception("could not read a frame for %s", room)
            return None

        if frame is None:
            return None
        return self._snapshots.save(frame, room, now_ms)

    # ── commands ────────────────────────────────────────────────────────────

    def dispense(self, grams: int) -> dict:
        """Runs the feeder.

        Deliberately does not record a feeding event. The servo controls how
        long the hopper is open, not how much comes out; the load cell measures
        what actually landed and the detector logs that. Recording the requested
        amount here would double-count, and would record a number nothing
        verified.
        """
        if not self._sensors.feeder.available:
            raise RuntimeError("feeder unavailable")
        self._sensors.feeder.dispense(grams)
        return {"requested_grams": grams, "note": "actual amount comes from the load cell"}

    def tare(self) -> None:
        self._sensors.scale.tare()
        self._detector.reset(0.0)
