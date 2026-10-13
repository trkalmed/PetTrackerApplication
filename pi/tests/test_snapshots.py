import logging
import os
import shutil
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.monitor import Monitor
from pettracker.sensors.base import SensorSet
from pettracker.sensors.fake import FakeCamera, FakeFeeder, FakeMotionSensor, FakeScale
from pettracker.snapshots import SnapshotStore
from pettracker.store import KIND_MOTION, EventStore

JPEG = b"\xff\xd8\xff\xe0 not really a jpeg \xff\xd9"


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class SnapshotStoreTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.mkdtemp()
        self.store = SnapshotStore(self.directory)

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_saves_a_frame_and_returns_its_name(self):
        name = self.store.save(JPEG, "Kitchen", 1_752_000_000_000)

        self.assertTrue(name.endswith("-kitchen.jpg"))
        with open(os.path.join(self.directory, name), "rb") as saved:
            self.assertEqual(JPEG, saved.read())

    def test_a_room_name_becomes_a_safe_slug(self):
        name = self.store.save(JPEG, "Living room", 1_752_000_000_000)
        self.assertTrue(name.endswith("-living-room.jpg"))

        odd = self.store.save(JPEG, "../../etc", 1_752_000_000_000)
        self.assertIsNotNone(self.store.path_for(odd))
        self.assertNotIn("/", odd)
        self.assertNotIn("..", odd)

    def test_two_rooms_at_the_same_moment_do_not_collide(self):
        first = self.store.save(JPEG, "Kitchen", 1_752_000_000_000)
        second = self.store.save(JPEG, "Hall", 1_752_000_000_000)

        self.assertNotEqual(first, second)
        self.assertEqual(2, len(os.listdir(self.directory)))

    def test_an_empty_frame_is_not_written(self):
        self.assertIsNone(self.store.save(b"", "Kitchen", 1_752_000_000_000))
        self.assertEqual([], os.listdir(self.directory))

    def test_a_half_written_frame_is_never_served(self):
        self.store.save(JPEG, "Kitchen", 1_752_000_000_000)

        self.assertFalse(any(n.endswith(".part") for n in os.listdir(self.directory)))

    def test_finds_a_saved_snapshot_by_name(self):
        name = self.store.save(JPEG, "Kitchen", 1_752_000_000_000)

        self.assertIsNotNone(self.store.path_for(name))

    def test_refuses_a_name_that_climbs_out_of_the_directory(self):
        for attempt in ("../../etc/passwd",
                        "..\\..\\windows\\system32\\config\\sam",
                        "/etc/passwd",
                        "motion-20260828-152031-812-kitchen.jpg/../../x",
                        "subdir/motion-20260828-152031-812-kitchen.jpg"):
            self.assertIsNone(self.store.path_for(attempt), attempt)

    def test_refuses_a_name_of_the_wrong_shape(self):
        for attempt in ("", "kitchen.jpg", "motion.jpg", "motion-1-2-3-x.png"):
            self.assertIsNone(self.store.path_for(attempt), attempt)

    def test_a_name_that_is_gone_is_simply_not_there(self):
        self.assertIsNone(
            self.store.path_for("motion-20200101-000000-000-kitchen.jpg"))

    def test_old_snapshots_are_deleted(self):
        name = self.store.save(JPEG, "Kitchen", 1_752_000_000_000)
        path = os.path.join(self.directory, name)
        long_ago = time.time() - 40 * 24 * 60 * 60
        os.utime(path, (long_ago, long_ago))

        self.assertEqual(1, self.store.prune())
        self.assertEqual([], os.listdir(self.directory))

    def test_recent_snapshots_are_kept(self):
        self.store.save(JPEG, "Kitchen", int(time.time() * 1000))

        self.assertEqual(0, self.store.prune())
        self.assertEqual(1, len(os.listdir(self.directory)))

    def test_an_abandoned_part_file_is_cleaned_up_eventually(self):
        stray = os.path.join(self.directory, "motion-20260828-152031-812-x.jpg.part")
        with open(stray, "wb") as out:
            out.write(b"half a frame")
        long_ago = time.time() - 40 * 24 * 60 * 60
        os.utime(stray, (long_ago, long_ago))

        self.assertEqual(1, self.store.prune())


class MotionSnapshotTest(unittest.TestCase):
    def setUp(self):
        handle, self.db = tempfile.mkstemp(suffix=".db")
        os.close(handle)
        os.unlink(self.db)
        self.directory = tempfile.mkdtemp()

        self.store = EventStore(self.db)
        self.snapshots = SnapshotStore(self.directory)
        self.kitchen = FakeMotionSensor("Kitchen", trigger_probability=0.0)
        self.camera = FakeCamera()
        scale = FakeScale()
        scale.available_flag = False

        self.sensors = SensorSet(
            scale=scale,
            motion_sensors=[self.kitchen],
            feeder=FakeFeeder(scale=scale),
            camera=self.camera,
        )
        self.monitor = Monitor(self.sensors, self.store,
                               snapshots=self.snapshots)

    def tearDown(self):
        self.store.close()
        shutil.rmtree(self.directory, ignore_errors=True)
        try:
            os.unlink(self.db)
        except OSError:
            pass

    def motion_events(self):
        return [e for e in self.store.since(0) if e["kind"] == KIND_MOTION]

    def test_motion_saves_a_picture_and_names_it_in_the_event(self):
        self.kitchen.force(True)

        self.monitor.poll_once(now=1000.0)

        event = self.motion_events()[0]
        self.assertIn("snapshot", event)
        self.assertIsNotNone(self.snapshots.path_for(event["snapshot"]))

    def test_one_picture_per_event_not_per_poll(self):
        self.kitchen.force(True)
        for tick in range(50):
            self.monitor.poll_once(now=1000.0 + tick * 0.2)

        self.assertEqual(1, len(os.listdir(self.directory)))

    def test_no_camera_means_an_event_without_a_picture(self):
        self.camera.available_flag = False
        self.kitchen.force(True)

        self.monitor.poll_once(now=1000.0)

        event = self.motion_events()[0]
        self.assertEqual("Kitchen", event["room"])
        self.assertNotIn("snapshot", event)

    def test_a_camera_that_throws_does_not_cost_the_event(self):
        def explode():
            raise OSError("device disconnected")

        self.camera.read_jpeg = explode
        self.kitchen.force(True)

        self.monitor.poll_once(now=1000.0)

        self.assertEqual(1, len(self.motion_events()))
        self.assertNotIn("snapshot", self.motion_events()[0])

    def test_snapshots_can_be_turned_off_entirely(self):
        monitor = Monitor(self.sensors, self.store, snapshots=None)
        self.kitchen.force(True)

        monitor.poll_once(now=1000.0)

        self.assertNotIn("snapshot", self.motion_events()[0])
        self.assertEqual([], os.listdir(self.directory))


if __name__ == "__main__":
    unittest.main()
