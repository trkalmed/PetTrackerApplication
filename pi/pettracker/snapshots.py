"""JPEGs saved when something moves.

A motion event says "the kitchen sensor fired at 15:20". A snapshot says what
was in the kitchen at 15:20, which is the difference between a log and an
answer — it is how you find out that the thing setting off the hall sensor every
night is the other cat.

Two things this has to get right, and neither is the camera:

**Names have to be safe to serve.** A file name arriving in a URL is
attacker-controlled input, even on a home LAN. Every name is generated here to
one strict shape and validated against that same shape on the way back out, so
``?name=../../etc/passwd`` cannot resolve to anything.

**They have to stop growing.** Motion is debounced to one event per room per
30 s, so the worst case is around 2,800 files per room per day. At ~50 KB each
that is well over a gigabyte a week on a card that also holds the operating
system. Old ones are deleted on the same schedule as the events they belong to.
"""

import logging
import os
import re
import time
from typing import Optional

log = logging.getLogger(__name__)

#: The one shape a snapshot file name may take, e.g.
#: ``motion-20260828-152031-812-kitchen.jpg``. Anything else is refused rather
#: than cleaned up: a name that does not match was not written by this class.
NAME_PATTERN = re.compile(r"^motion-\d{8}-\d{6}-\d{3}-[a-z0-9-]{1,40}\.jpg$")


class SnapshotStore:
    """Writes, finds and expires the JPEGs taken when a sensor fires."""

    def __init__(self, directory: str = "snapshots", retention_days: int = 30):
        self._directory = os.path.abspath(directory)
        self._retention_days = retention_days
        os.makedirs(self._directory, exist_ok=True)

    @property
    def directory(self) -> str:
        return self._directory

    def save(self, jpeg: bytes, room: str, timestamp_ms: int) -> Optional[str]:
        """Writes one frame and returns its name, or None if it could not.

        None rather than an exception: a full card or a camera that returned
        half a frame must cost the picture, never the motion event it belongs
        to. Knowing the cat was in the kitchen is worth more than the photo.
        """
        if not jpeg:
            return None

        name = self._name_for(room, timestamp_ms)
        path = os.path.join(self._directory, name)
        try:
            # Written whole under a temporary name and then moved into place, so
            # a snapshot being written while the app asks for it is never served
            # half-finished. os.replace is atomic within a filesystem.
            temporary = path + ".part"
            with open(temporary, "wb") as out:
                out.write(jpeg)
            os.replace(temporary, path)
            return name
        except OSError as e:
            log.warning("could not save snapshot: %s", e)
            return None

    def path_for(self, name: str) -> Optional[str]:
        """The file for a name from a URL, or None if there is not one.

        The name is matched against :data:`NAME_PATTERN` before it is joined to
        anything, and the result is checked to be inside the snapshot directory
        afterwards. Either check alone would do; both is cheap.
        """
        if not name or not NAME_PATTERN.match(name):
            return None

        path = os.path.abspath(os.path.join(self._directory, name))
        if os.path.dirname(path) != self._directory:
            return None
        return path if os.path.isfile(path) else None

    def prune(self, now: Optional[float] = None) -> int:
        """Deletes snapshots past the retention window. Returns how many."""
        if now is None:
            now = time.time()
        cutoff = now - self._retention_days * 24 * 60 * 60

        removed = 0
        try:
            names = os.listdir(self._directory)
        except OSError as e:
            log.warning("could not list snapshots: %s", e)
            return 0

        for name in names:
            path = os.path.join(self._directory, name)
            try:
                # .part files are included deliberately: one left behind means a
                # write died mid-frame, and it is of no use to anybody.
                if os.path.getmtime(path) < cutoff:
                    os.remove(path)
                    removed += 1
            except OSError:
                # Vanished under us, or never ours to delete. Either way the
                # next pass can try again.
                continue
        return removed

    def _name_for(self, room: str, timestamp_ms: int) -> str:
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime(timestamp_ms / 1000))
        millis = f"{timestamp_ms % 1000:03d}"
        return f"motion-{stamp}-{millis}-{_slug(room)}.jpg"


def _slug(room: str) -> str:
    """"Living room" -> "living-room".

    Rooms are named by whoever ran the service and can contain anything a shell
    argument can. The name has to survive being a file name and a URL parameter,
    so it is reduced to the characters :data:`NAME_PATTERN` allows rather than
    trusted.
    """
    slug = re.sub(r"[^a-z0-9]+", "-", room.strip().lower()).strip("-")
    return (slug or "room")[:40]
