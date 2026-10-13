"""A camera wrapper that reopens the device after failures."""

import logging
import threading
import time
from typing import Callable, Optional

from .base import Camera

log = logging.getLogger(__name__)


class ReopeningCamera(Camera):
    # Consecutive empty reads before the device is treated as stuck.
    FAILURES_BEFORE_REOPEN = 3

    def __init__(self, factory: Callable[[], Camera],
                 retry_interval_s: float = 10.0,
                 clock=time.monotonic):
        self._factory = factory
        self.retry_interval_s = retry_interval_s
        self._clock = clock
        self._lock = threading.Lock()

        self._camera: Optional[Camera] = None
        self._last_attempt: Optional[float] = None
        self._failures = 0
        self._opened_before = False

        self._attempt()

    @property
    def available(self) -> bool:
        return self._working() is not None

    def read_jpeg(self) -> Optional[bytes]:
        camera = self._working()
        if camera is None:
            return None

        try:
            frame = camera.read_jpeg()
        except Exception as e:
            log.warning("camera read failed: %s", e)
            frame = None

        with self._lock:
            if frame is None:
                self._failures += 1
                if self._failures >= self.FAILURES_BEFORE_REOPEN:
                    log.info("camera returned nothing %d times — reopening",
                             self._failures)
                    self._discard()
            else:
                self._failures = 0
        return frame

    def close(self) -> None:
        with self._lock:
            self._discard()
            self._factory = _closed_factory

    def _working(self) -> Optional[Camera]:
        with self._lock:
            if self._camera is not None and self._camera.available:
                return self._camera
            return self._attempt()

    def _attempt(self) -> Optional[Camera]:
        now = self._clock()
        if (self._last_attempt is not None
                and now - self._last_attempt < self.retry_interval_s):
            return None
        self._last_attempt = now

        self._discard()
        camera = self._factory()
        if not camera.available:
            camera.close()
            return None

        self._camera = camera
        self._failures = 0
        if self._opened_before:
            log.info("camera is back")
        self._opened_before = True
        return camera

    def _discard(self) -> None:
        if self._camera is not None:
            try:
                self._camera.close()
            except Exception:
                pass
            self._camera = None
        self._failures = 0


def _closed_factory() -> Camera:
    return _NoCamera()


class _NoCamera(Camera):
    def read_jpeg(self) -> Optional[bytes]:
        return None

    @property
    def available(self) -> bool:
        return False
