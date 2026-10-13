"""JPEG snapshots saved on motion events."""

import logging
import os
import re
import time
from typing import Optional

log = logging.getLogger(__name__)

# The only allowed snapshot file name shape. Anything else is refused.
NAME_PATTERN = re.compile(r"^motion-\d{8}-\d{6}-\d{3}-[a-z0-9-]{1,40}\.jpg$")


class SnapshotStore:
    def __init__(self, directory: str = "snapshots", retention_days: int = 30):
        self._directory = os.path.abspath(directory)
        self._retention_days = retention_days
        os.makedirs(self._directory, exist_ok=True)

    @property
    def directory(self) -> str:
        return self._directory

    def save(self, jpeg: bytes, room: str, timestamp_ms: int) -> Optional[str]:
        if not jpeg:
            return None

        name = self._name_for(room, timestamp_ms)
        path = os.path.join(self._directory, name)
        try:
            # Written under a temporary name and renamed, so a partial file is never served.
            temporary = path + ".part"
            with open(temporary, "wb") as out:
                out.write(jpeg)
            os.replace(temporary, path)
            return name
        except OSError as e:
            log.warning("could not save snapshot: %s", e)
            return None

    def path_for(self, name: str) -> Optional[str]:
        if not name or not NAME_PATTERN.match(name):
            return None

        path = os.path.abspath(os.path.join(self._directory, name))
        if os.path.dirname(path) != self._directory:
            return None
        return path if os.path.isfile(path) else None

    def prune(self, now: Optional[float] = None) -> int:
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
                if os.path.getmtime(path) < cutoff:
                    os.remove(path)
                    removed += 1
            except OSError:
                continue
        return removed

    def _name_for(self, room: str, timestamp_ms: int) -> str:
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime(timestamp_ms / 1000))
        millis = f"{timestamp_ms % 1000:03d}"
        return f"motion-{stamp}-{millis}-{_slug(room)}.jpg"


def _slug(room: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", room.strip().lower()).strip("-")
    return (slug or "room")[:40]
