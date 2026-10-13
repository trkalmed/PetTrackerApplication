"""Durable SQLite event buffer that the app polls with a cursor."""

import sqlite3
import threading
import time
from typing import Any, Dict, List, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kind         TEXT    NOT NULL,
    timestamp_ms INTEGER NOT NULL,
    payload      TEXT    NOT NULL,
    source       TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_id ON events (id);
"""

# Event kinds are the contract with the app: adding one is safe, renaming one is not.
KIND_FEEDING = "feeding"
KIND_MOTION = "motion"
KIND_WEIGHT = "weight"
KIND_LOCATION = "location"


class EventStore:
    def __init__(self, path: str = "pettracker.db", retention_days: int = 30):
        self._path = path
        self._retention_days = retention_days
        self._local = threading.local()
        self._write_lock = threading.Lock()
        with self._connect() as conn:
            conn.executescript(SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self._path, timeout=10)
            conn.row_factory = sqlite3.Row
            # WAL lets the HTTP thread read while the sensor threads write.
            conn.execute("PRAGMA journal_mode=WAL")
            self._local.conn = conn
        return conn

    def append(
        self,
        kind: str,
        payload: Dict[str, Any],
        timestamp_ms: Optional[int] = None,
        source: Optional[str] = None,
    ) -> int:
        import json

        if timestamp_ms is None:
            timestamp_ms = int(time.time() * 1000)

        conn = self._connect()
        with self._write_lock:
            cursor = conn.execute(
                "INSERT INTO events (kind, timestamp_ms, payload, source)"
                " VALUES (?, ?, ?, ?)",
                (kind, timestamp_ms, json.dumps(payload), source),
            )
            conn.commit()
            return int(cursor.lastrowid)

    def since(self, cursor: int = 0, limit: int = 500) -> List[Dict[str, Any]]:
        import json

        conn = self._connect()
        rows = conn.execute(
            "SELECT id, kind, timestamp_ms, payload, source FROM events"
            " WHERE id > ? ORDER BY id ASC LIMIT ?",
            (cursor, limit),
        ).fetchall()

        return [
            {
                "id": row["id"],
                "kind": row["kind"],
                "timestamp_ms": row["timestamp_ms"],
                "source": row["source"],
                **json.loads(row["payload"]),
            }
            for row in rows
        ]

    def latest_cursor(self) -> int:
        conn = self._connect()
        row = conn.execute("SELECT COALESCE(MAX(id), 0) AS c FROM events").fetchone()
        return int(row["c"])

    def count(self) -> int:
        conn = self._connect()
        row = conn.execute("SELECT COUNT(*) AS n FROM events").fetchone()
        return int(row["n"])

    def prune(self, now_ms: Optional[int] = None) -> int:
        if now_ms is None:
            now_ms = int(time.time() * 1000)
        cutoff = now_ms - self._retention_days * 24 * 60 * 60 * 1000

        conn = self._connect()
        with self._write_lock:
            cursor = conn.execute("DELETE FROM events WHERE timestamp_ms < ?", (cutoff,))
            conn.commit()
            return cursor.rowcount

    def close(self) -> None:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
            self._local.conn = None
