"""Event buffer behaviour, especially the cursor the app polls with."""

import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.store import KIND_FEEDING, KIND_MOTION, EventStore


class EventStoreTest(unittest.TestCase):

    def setUp(self):
        handle, self.path = tempfile.mkstemp(suffix=".db")
        os.close(handle)
        os.unlink(self.path)
        self.store = EventStore(self.path)

    def tearDown(self):
        self.store.close()
        for suffix in ("", "-wal", "-shm"):
            try:
                os.unlink(self.path + suffix)
            except OSError:
                pass

    def test_an_empty_store_returns_nothing(self):
        self.assertEqual([], self.store.since(0))
        self.assertEqual(0, self.store.latest_cursor())

    def test_appended_events_come_back_with_their_payload(self):
        self.store.append(KIND_FEEDING, {"grams": 40, "type": "EATEN"},
                          timestamp_ms=1000, source="load-cell")

        events = self.store.since(0)

        self.assertEqual(1, len(events))
        self.assertEqual(KIND_FEEDING, events[0]["kind"])
        self.assertEqual(40, events[0]["grams"])
        self.assertEqual("EATEN", events[0]["type"])
        self.assertEqual(1000, events[0]["timestamp_ms"])
        self.assertEqual("load-cell", events[0]["source"])

    def test_the_cursor_is_exclusive(self):
        # An app that re-sends its last cursor must get nothing, not a repeat.
        first = self.store.append(KIND_MOTION, {"room": "Kitchen"})
        self.store.append(KIND_MOTION, {"room": "Hall"})

        events = self.store.since(first)

        self.assertEqual(1, len(events))
        self.assertEqual("Hall", events[0]["room"])

    def test_events_come_back_oldest_first(self):
        for room in ("Kitchen", "Hall", "Living room"):
            self.store.append(KIND_MOTION, {"room": room})

        rooms = [e["room"] for e in self.store.since(0)]

        self.assertEqual(["Kitchen", "Hall", "Living room"], rooms)

    def test_ids_increase_even_when_timestamps_do_not(self):
        # The Pi has no real-time clock, so its clock jumps backwards when NTP
        # first syncs. The cursor must not care.
        self.store.append(KIND_MOTION, {"room": "Kitchen"}, timestamp_ms=9_000_000)
        self.store.append(KIND_MOTION, {"room": "Hall"}, timestamp_ms=1_000)

        events = self.store.since(0)

        self.assertEqual([1, 2], [e["id"] for e in events])
        self.assertEqual(["Kitchen", "Hall"], [e["room"] for e in events])

    def test_the_limit_is_respected(self):
        for i in range(20):
            self.store.append(KIND_MOTION, {"room": f"room{i}"})

        self.assertEqual(5, len(self.store.since(0, limit=5)))

    def test_paging_through_with_the_cursor_sees_everything_once(self):
        for i in range(25):
            self.store.append(KIND_MOTION, {"room": f"room{i}"})

        seen, cursor = [], 0
        while True:
            page = self.store.since(cursor, limit=10)
            if not page:
                break
            seen.extend(page)
            cursor = page[-1]["id"]

        self.assertEqual(25, len(seen))
        self.assertEqual(25, len({e["id"] for e in seen}))

    def test_pruning_drops_only_old_events(self):
        day_ms = 24 * 60 * 60 * 1000
        now = 100 * day_ms
        self.store.append(KIND_MOTION, {"room": "old"}, timestamp_ms=now - 60 * day_ms)
        self.store.append(KIND_MOTION, {"room": "recent"}, timestamp_ms=now - day_ms)

        removed = self.store.prune(now_ms=now)

        self.assertEqual(1, removed)
        rooms = [e["room"] for e in self.store.since(0)]
        self.assertEqual(["recent"], rooms)

    def test_it_survives_writes_from_several_threads(self):
        # The monitor thread appends while the HTTP threads read; SQLite
        # connections cannot be shared across threads, hence thread-local ones.
        def writer(n):
            for i in range(20):
                self.store.append(KIND_MOTION, {"room": f"t{n}-{i}"})

        threads = [threading.Thread(target=writer, args=(n,)) for n in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(80, self.store.count())
        ids = [e["id"] for e in self.store.since(0, limit=1000)]
        self.assertEqual(80, len(set(ids)))

    def test_reopening_keeps_what_was_written(self):
        # The phone may be away for days; a Pi reboot must not lose the backlog.
        self.store.append(KIND_FEEDING, {"grams": 25, "type": "EATEN"})
        self.store.close()

        reopened = EventStore(self.path)
        try:
            self.assertEqual(1, len(reopened.since(0)))
        finally:
            reopened.close()


if __name__ == "__main__":
    unittest.main()
