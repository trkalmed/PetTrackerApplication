"""The HTTP contract, exercised against a real server on a real socket.

Runs the actual :class:`PetTrackerServer` with simulated sensors rather than
calling handlers directly, so routing, status codes and JSON shapes are all
covered. This is the contract the Android side codes against, so a change that
breaks it should break a test.
"""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.monitor import Monitor
from pettracker.sensors.fake import build_fake_sensor_set
from pettracker.server import PetTrackerServer
from pettracker.store import KIND_FEEDING, KIND_MOTION, EventStore


class ServerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        handle, cls.path = tempfile.mkstemp(suffix=".db")
        os.close(handle)
        os.unlink(cls.path)

        cls.sensors = build_fake_sensor_set(seed=7)
        cls.store = EventStore(cls.path)
        cls.monitor = Monitor(cls.sensors, cls.store)
        # Port 0 lets the OS pick a free one, so the suite cannot collide with a
        # service already running on 8080.
        cls.server = PetTrackerServer(
            cls.sensors, cls.store, cls.monitor, host="127.0.0.1", port=0)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.store.close()
        for suffix in ("", "-wal", "-shm"):
            try:
                os.unlink(cls.path + suffix)
            except OSError:
                pass

    def url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def get(self, path):
        with urllib.request.urlopen(self.url(path), timeout=5) as response:
            return response.status, json.loads(response.read().decode())

    def post(self, path, payload=None):
        data = json.dumps(payload).encode() if payload is not None else b""
        request = urllib.request.Request(
            self.url(path), data=data, method="POST",
            headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode())

    # ── /health ─────────────────────────────────────────────────────────────

    def test_health_reports_every_sensor(self):
        status, body = self.get("/health")

        self.assertEqual(200, status)
        self.assertTrue(body["ok"])
        # Which sensors are live is the whole point: it is how you tell a Pi
        # with the load cell unplugged from one that is simply not being used.
        for key in ("scale", "feeder", "camera", "motion"):
            self.assertIn(key, body["sensors"])
        self.assertIn("Kitchen", body["sensors"]["motion"])

    # ── /events ─────────────────────────────────────────────────────────────

    def test_events_starts_empty_and_reports_a_cursor(self):
        status, body = self.get("/events?since=0")

        self.assertEqual(200, status)
        self.assertIn("events", body)
        self.assertIn("next_cursor", body)
        self.assertIn("has_more", body)

    def test_events_returns_what_was_appended(self):
        self.store.append(KIND_FEEDING, {"grams": 40, "type": "EATEN"},
                          timestamp_ms=1234, source="load-cell")

        _, body = self.get("/events?since=0")
        feeding = [e for e in body["events"] if e["kind"] == KIND_FEEDING]

        self.assertTrue(feeding)
        self.assertEqual(40, feeding[-1]["grams"])
        self.assertEqual("EATEN", feeding[-1]["type"])

    def test_the_cursor_advances_and_does_not_repeat(self):
        _, first = self.get("/events?since=0")
        cursor = first["next_cursor"]

        self.store.append(KIND_MOTION, {"room": "Hall"})
        _, second = self.get(f"/events?since={cursor}")

        self.assertTrue(second["events"])
        self.assertTrue(all(e["id"] > cursor for e in second["events"]))

    def test_polling_with_the_latest_cursor_returns_nothing(self):
        self.store.append(KIND_MOTION, {"room": "Kitchen"})
        _, body = self.get("/events?since=0")
        cursor = body["next_cursor"]

        _, again = self.get(f"/events?since={cursor}")

        self.assertEqual([], again["events"])
        self.assertEqual(cursor, again["next_cursor"])

    def test_a_bad_cursor_is_rejected_rather_than_ignored(self):
        request = urllib.request.Request(self.url("/events?since=banana"))
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=5)

        self.assertEqual(400, caught.exception.code)

    def test_a_missing_cursor_defaults_to_the_beginning(self):
        status, body = self.get("/events")

        self.assertEqual(200, status)
        self.assertIn("events", body)

    # ── /feed ───────────────────────────────────────────────────────────────

    def test_feeding_runs_the_servo(self):
        before = len(self.sensors.feeder.dispensed_calls)

        status, body = self.post("/feed", {"grams": 30})

        self.assertEqual(200, status)
        self.assertEqual(30, body["requested_grams"])
        self.assertEqual(before + 1, len(self.sensors.feeder.dispensed_calls))

    def test_feeding_does_not_itself_record_a_meal(self):
        # The servo controls time open, not grams. Recording the request would
        # log a number nothing measured, and double-count against the load cell.
        _, before = self.get("/events?since=0")
        feeding_before = len([e for e in before["events"] if e["kind"] == KIND_FEEDING])

        self.post("/feed", {"grams": 25})

        _, after = self.get("/events?since=0")
        feeding_after = len([e for e in after["events"] if e["kind"] == KIND_FEEDING])
        self.assertEqual(feeding_before, feeding_after)

    def test_a_missing_or_silly_amount_is_rejected(self):
        for payload in ({}, {"grams": 0}, {"grams": -5}, {"grams": "lots"}):
            status, _ = self.post("/feed", payload)
            self.assertEqual(400, status, f"payload {payload} should be rejected")

    def test_an_absurd_amount_is_refused(self):
        # A jammed gate empties the hopper; do not take any number on trust.
        status, _ = self.post("/feed", {"grams": 5000})

        self.assertEqual(400, status)

    # ── /tare ───────────────────────────────────────────────────────────────

    def test_tare_succeeds(self):
        status, body = self.post("/tare")

        self.assertEqual(200, status)
        self.assertTrue(body["ok"])

    # ── routing ─────────────────────────────────────────────────────────────

    def test_unknown_paths_are_404(self):
        for path in ("/nope", "/events/extra"):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(self.url(path), timeout=5)
            self.assertEqual(404, caught.exception.code)

    # ── /stream ─────────────────────────────────────────────────────────────

    def test_the_stream_serves_parseable_jpeg_frames(self):
        # Parsed the way MjpegInputStream.readJpegFrame does: scan for the SOI
        # and EOI markers rather than trusting the multipart headers.
        response = urllib.request.urlopen(self.url("/stream"), timeout=5)
        try:
            self.assertIn("multipart/x-mixed-replace",
                          response.headers.get("Content-Type"))

            buffer, frames = b"", []
            while len(frames) < 2 and len(buffer) < 200_000:
                chunk = response.read(2048)
                if not chunk:
                    break
                buffer += chunk
                while True:
                    start = buffer.find(b"\xff\xd8")
                    if start < 0:
                        break
                    end = buffer.find(b"\xff\xd9", start + 2)
                    if end < 0:
                        break
                    frames.append(buffer[start:end + 2])
                    buffer = buffer[end + 2:]
        finally:
            response.close()

        self.assertGreaterEqual(len(frames), 2)
        self.assertTrue(all(f[:2] == b"\xff\xd8" for f in frames))
        self.assertTrue(all(f[-2:] == b"\xff\xd9" for f in frames))


if __name__ == "__main__":
    unittest.main()
