"""The HTTP service the Android app talks to. Standard library only."""

import json
import logging
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional
from urllib.parse import parse_qs, urlparse

from .monitor import Monitor
from .sensors.base import SensorSet
from .snapshots import SnapshotStore
from .store import EventStore

log = logging.getLogger(__name__)

# Must match PiConnectionManager.STREAM_PORT in the app.
DEFAULT_PORT = 8080

BOUNDARY = "pettrackerframe"
MAX_EVENTS_PER_REQUEST = 500


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def store(self) -> EventStore:
        return self.server.store          # type: ignore[attr-defined]

    @property
    def monitor(self) -> Monitor:
        return self.server.monitor        # type: ignore[attr-defined]

    @property
    def sensors(self) -> SensorSet:
        return self.server.sensors        # type: ignore[attr-defined]

    @property
    def snapshots(self) -> Optional[SnapshotStore]:
        return self.server.snapshots      # type: ignore[attr-defined]

    def log_message(self, fmt, *args):
        # Silence per request logging, which would flood the journal.
        log.debug(fmt, *args)

    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}

    def do_GET(self):
        route = urlparse(self.path)
        if route.path == "/health":
            return self._health()
        if route.path == "/events":
            return self._events(parse_qs(route.query))
        if route.path == "/snapshot":
            return self._snapshot(parse_qs(route.query))
        if route.path == "/stream":
            return self._stream(parse_qs(route.query))
        self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        route = urlparse(self.path)
        if route.path == "/feed":
            return self._feed()
        if route.path == "/tare":
            return self._tare()
        if route.path == "/close":
            return self._close_feeder()
        self._send_json({"error": "not found"}, 404)

    def _health(self) -> None:
        self._send_json({
            "ok": True,
            "time_ms": int(time.time() * 1000),
            "uptime_s": int(time.time() - self.server.started_at),  # type: ignore
            "sensors": self.sensors.health(),
            "latest_cursor": self.store.latest_cursor(),
            "latest_weight_grams": self.monitor.latest_weight_grams,
            "portion_grams": getattr(self.sensors.feeder, "grams_per_cycle", None),
            "feeder_open": bool(getattr(self.sensors.feeder, "is_open", False)),
            "feeder_kind": getattr(self.sensors.feeder, "kind", None),
        })

    def _events(self, query: dict) -> None:
        raw = (query.get("since") or ["0"])[0]
        try:
            since = max(0, int(raw))
        except ValueError:
            return self._send_json({"error": "since must be an integer"}, 400)

        events = self.store.since(since, limit=MAX_EVENTS_PER_REQUEST)
        # next_cursor is the last id returned, so the client comes back for the rest.
        next_cursor = events[-1]["id"] if events else since
        self._send_json({
            "events": events,
            "next_cursor": next_cursor,
            "has_more": len(events) == MAX_EVENTS_PER_REQUEST,
        })

    def _feed(self) -> None:
        body = self._read_json()
        grams = body.get("grams")
        if not isinstance(grams, int) or grams <= 0:
            return self._send_json({"error": "grams must be a positive integer"}, 400)
        if grams > 500:
            # Refuse absurd amounts: a jammed servo empties the hopper.
            return self._send_json({"error": "grams exceeds the per-request limit"}, 400)

        try:
            result = self.monitor.dispense(grams)
        except RuntimeError as e:
            return self._send_json({"error": str(e)}, 503)
        self._send_json(result)

    def _close_feeder(self) -> None:
        feeder = self.sensors.feeder
        if not feeder.available:
            return self._send_json({"error": "feeder unavailable"}, 503)

        closer = getattr(feeder, "close_gate", None)
        if closer is None:
            return self._send_json({"ok": True, "note": "this feeder closes itself"})

        try:
            closer()
        except RuntimeError as e:
            return self._send_json({"error": str(e)}, 503)
        self._send_json({"ok": True, "open": False})

    def _tare(self) -> None:
        self.monitor.tare()
        self._send_json({"ok": True})

    def _snapshot(self, query: dict) -> None:
        name = (query.get("name") or [""])[0]
        path = self.snapshots.path_for(name) if self.snapshots else None
        if path is None:
            return self._send_json({"error": "not found"}, 404)

        try:
            with open(path, "rb") as image:
                body = image.read()
        except OSError:
            return self._send_json({"error": "not found"}, 404)

        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        # Snapshots never change, so clients may cache them.
        self.send_header("Cache-Control", "public, max-age=86400, immutable")
        self.end_headers()
        self.wfile.write(body)

    def _stream(self, query: Optional[dict] = None) -> None:
        name = ((query or {}).get("camera") or [""])[0]
        camera = self.sensors.camera_named(name)
        if camera is None:
            return self._send_json(
                {"error": f"no camera named '{name}'",
                 "cameras": list(self.sensors.cameras)}, 404)
        if not camera.available:
            return self._send_json({"error": "camera unavailable"}, 503)

        self.send_response(200)
        self.send_header("Age", "0")
        self.send_header("Cache-Control", "no-cache, private")
        self.send_header("Pragma", "no-cache")
        self.send_header(
            "Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
        self.end_headers()

        interval = 1.0 / self.server.stream_fps          # type: ignore[attr-defined]
        try:
            while True:
                frame = camera.read_jpeg()
                if frame is None:
                    break
                self.wfile.write(f"--{BOUNDARY}\r\n".encode())
                self.wfile.write(b"Content-Type: image/jpeg\r\n")
                self.wfile.write(f"Content-Length: {len(frame)}\r\n\r\n".encode())
                self.wfile.write(frame)
                self.wfile.write(b"\r\n")
                time.sleep(interval)
        except ConnectionError:
            log.debug("stream client disconnected")


class PetTrackerServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, sensors: SensorSet, store: EventStore, monitor: Monitor,
                 host: str = "0.0.0.0", port: int = DEFAULT_PORT,
                 stream_fps: int = 10, snapshots: Optional[SnapshotStore] = None):
        super().__init__((host, port), _Handler)
        self.sensors = sensors
        self.store = store
        self.monitor = monitor
        self.stream_fps = stream_fps
        self.snapshots = snapshots
        self.started_at = time.time()


def local_ip() -> Optional[str]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()
