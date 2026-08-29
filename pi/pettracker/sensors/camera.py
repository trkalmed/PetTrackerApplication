"""A camera that keeps trying.

Opening a video device fails for reasons that go away on their own. At boot the
Pi races USB enumeration and usually wins; a webcam gets knocked out of its
socket and put back; another process holds the device for a few seconds while it
exits. None of those are permanent, and none of them should need a human.

The original design opened the camera once at startup and gave up forever if it
was not there. That produced two failures that looked unrelated and were the
same bug: /stream answering "camera unavailable" until somebody restarted the
service, and motion snapshots being silently switched off for a whole run —
quietly, because the events kept arriving and only the pictures were missing.

So the camera is wrapped in something that reopens it. Two rules keep that from
becoming its own problem:

**Attempts are rate limited.** /health asks whether the camera is available on
every poll, and opening a device that is not there costs a second of a request
thread. One attempt per :attr:`ReopeningCamera.retry_interval_s` at most.

**A camera that stops producing is reopened, not just reported.** A webcam
pulled mid-run keeps its file handle and returns nothing from every read, so
"available" stays true and the frames stay empty. After a few consecutive empty
reads the device is closed and opened again.
"""

import logging
import threading
import time
from typing import Callable, Optional

from .base import Camera

log = logging.getLogger(__name__)


class ReopeningCamera(Camera):
    """Wraps a camera factory and reopens the device when it goes away."""

    #: Consecutive empty reads before the device is presumed wedged. Three is
    #: comfortably more than the odd dropped frame a working camera produces,
    #: and at 10 fps it is a third of a second before recovery starts.
    FAILURES_BEFORE_REOPEN = 3

    def __init__(self, factory: Callable[[], Camera],
                 retry_interval_s: float = 10.0,
                 clock=time.monotonic):
        """
        :param factory: makes a fresh camera. Called again on every retry, so it
            must be safe to call repeatedly — each real camera class already
            reports itself unavailable rather than raising.
        :param retry_interval_s: the least time between attempts to open a
            device that is not there.
        """
        self._factory = factory
        self.retry_interval_s = retry_interval_s
        self._clock = clock
        self._lock = threading.Lock()

        self._camera: Optional[Camera] = None
        self._last_attempt: Optional[float] = None
        self._failures = 0
        self._opened_before = False

        self._attempt()

    # ── Camera ──────────────────────────────────────────────────────────────

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
            # A device that has been unplugged can raise rather than return
            # nothing, depending on the driver. Either way it is not a frame.
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
            # Deliberately no further attempts after an explicit close: this is
            # the service shutting down, not a camera falling out.
            self._factory = _closed_factory

    # ── the reopening ───────────────────────────────────────────────────────

    def _working(self) -> Optional[Camera]:
        with self._lock:
            if self._camera is not None and self._camera.available:
                return self._camera
            return self._attempt()

    def _attempt(self) -> Optional[Camera]:
        """Opens the device, at most once per retry interval. Caller holds the lock."""
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
        """Closes whatever is held. Caller holds the lock."""
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
