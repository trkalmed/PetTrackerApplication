"""The HTTP service the Android app talks to.

Standard library only, on purpose. Nothing to pip install on a fresh Raspberry
Pi OS image, nothing to keep up to date, and no chance of a dependency that
happens not to have an ARM wheel. The API is four endpoints and an MJPEG
stream; a framework would not earn its place.

Endpoints — see README.md for the full contract:

    GET  /health              is it alive, which sensors are reporting
    GET  /events?since=<id>   readings since a cursor, plus the next cursor
    GET  /stream              MJPEG, the URL PiConnectionManager already builds
    POST /feed  {"grams": N}  run the feeder
    POST /tare                zero the scale
"""

import json
import logging
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional
from urllib.parse import parse_qs, urlparse

from .monitor import Monitor
from .sensors.base import SensorSet
from .store import EventStore

log = logging.getLogger(__name__)

# Matches PiConnectionManager.STREAM_PORT on the Android side. Changing it means
# changing both.
DEFAULT_PORT = 8080

BOUNDARY = "pettrackerframe"
MAX_EVENTS_PER_REQUEST = 500


class _Handler(BaseHTTPRequestHandler):
    """One request. The service objects are attached to the server."""

    protocol_version = "HTTP/1.1"

    # ── plumbing ────────────────────────────────────────────────────────────

    @property
    def store(self) -> EventStore:
        return self.server.store          # type: ignore[attr-defined]

    @property
    def monitor(self) -> Monitor:
        return self.server.monitor        # type: ignore[attr-defined]

    @property
    def sensors(self) -> SensorSet:
        return self.server.sensors        # type: ignore[attr-defined]

    def log_message(self, fmt, *args):
        # BaseHTTPRequestHandler writes to stderr by default, which on a Pi
        # running under systemd means every polled request lands in the journal.
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

    # ── routes ──────────────────────────────────────────────────────────────

    def do_GET(self):
        route = urlparse(self.path)
        if route.path == "/health":
            return self._health()
        if route.path == "/events":
            return self._events(parse_qs(route.query))
        if route.path == "/stream":
            return self._stream()
        self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        route = urlparse(self.path)
        if route.path == "/feed":
            return self._feed()
        if route.path == "/tare":
            return self._tare()
        self._send_json({"error": "not found"}, 404)

    def _health(self) -> None:
        self._send_json({
            "ok": True,
            "time_ms": int(time.time() * 1000),
            "uptime_s": int(time.time() - self.server.started_at),  # type: ignore
            "sensors": self.sensors.health(),
            "latest_cursor": self.store.latest_cursor(),
            "latest_weight_grams": self.monitor.latest_weight_grams,
        })

    def _events(self, query: dict) -> None:
        raw = (query.get("since") or ["0"])[0]
        try:
            since = max(0, int(raw))
        except ValueError:
            return self._send_json({"error": "since must be an integer"}, 400)

        events = self.store.since(since, limit=MAX_EVENTS_PER_REQUEST)
        # next_cursor is the last id handed out, not the store's maximum: with
        # more than one page pending, the client must come back for the rest.
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
            # A servo jammed open empties the hopper. Refuse absurd amounts
            # rather than trusting whatever asked.
            return self._send_json({"error": "grams exceeds the per-request limit"}, 400)

        try:
            result = self.monitor.dispense(grams)
        except RuntimeError as e:
            return self._send_json({"error": str(e)}, 503)
        self._send_json(result)

    def _tare(self) -> None:
        self.monitor.tare()
        self._send_json({"ok": True})

    def _stream(self) -> None:
        """multipart/x-mixed-replace, the format MjpegInputStream expects."""
        camera = self.sensors.camera
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
            # The phone closed the stream. Entirely normal — it happens every
            # time the live screen is left. Catch the base class, not the
            # individual subclasses: a disconnect surfaces as BrokenPipeError
            # on Linux and ConnectionAbortedError on Windows, and only the
            # former was caught before, so a dropped viewer printed a traceback.
            log.debug("stream client disconnected")


class PetTrackerServer(ThreadingHTTPServer):
    """Threaded so a client sitting on /stream cannot block /events.

    That matters more than it sounds: the stream response never ends, so a
    single-threaded server would serve one viewer and nothing else, forever.
    """

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, sensors: SensorSet, store: EventStore, monitor: Monitor,
                 host: str = "0.0.0.0", port: int = DEFAULT_PORT,
                 stream_fps: int = 10):
        super().__init__((host, port), _Handler)
        self.sensors = sensors
        self.store = store
        self.monitor = monitor
        self.stream_fps = stream_fps
        self.started_at = time.time()


def local_ip() -> Optional[str]:
    """The address to type into the app's Pi Connection dialog.

    No packet is actually sent; connect() on a UDP socket just picks the route
    the kernel would use, which is the interface facing the LAN.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()
