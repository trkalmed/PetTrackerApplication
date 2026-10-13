import logging
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.monitor import Monitor
from pettracker.sensors.base import SensorSet
from pettracker.sensors.fake import FakeCamera, FakeFeeder, FakeMotionSensor, FakeScale
from pettracker.store import KIND_MOTION, EventStore


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class MotionMonitorTest(unittest.TestCase):

    def setUp(self):
        handle, self.path = tempfile.mkstemp(suffix=".db")
        os.close(handle)
        os.unlink(self.path)

        self.store = EventStore(self.path)
        self.kitchen = FakeMotionSensor("Kitchen", trigger_probability=0.0)
        self.hall = FakeMotionSensor("Hall", trigger_probability=0.0)
        scale = FakeScale()
        scale.available_flag = False

        self.sensors = SensorSet(
            scale=scale,
            motion_sensors=[self.kitchen, self.hall],
            feeder=FakeFeeder(scale=scale),
            camera=FakeCamera(),
        )
        self.monitor = Monitor(self.sensors, self.store, motion_debounce_s=30.0)

    def tearDown(self):
        self.store.close()
        try:
            os.unlink(self.path)
        except OSError:
            pass

    def motion_events(self):
        return [e for e in self.store.since(0) if e["kind"] == KIND_MOTION]

    def test_a_quiet_room_produces_nothing(self):
        self.monitor.poll_once(now=1000.0)

        self.assertEqual([], self.motion_events())

    def test_motion_is_recorded_with_its_room(self):
        self.kitchen.force(True)

        self.monitor.poll_once(now=1000.0)

        events = self.motion_events()
        self.assertEqual(1, len(events))
        self.assertEqual("Kitchen", events[0]["room"])
        self.assertEqual("pir", events[0]["source"])
        self.assertEqual(1_000_000, events[0]["timestamp_ms"])

    def test_a_held_sensor_is_one_event_not_hundreds(self):
        self.kitchen.force(True)

        for tick in range(50):
            self.monitor.poll_once(now=1000.0 + tick * 0.2)

        self.assertEqual(1, len(self.motion_events()))

    def test_the_room_reports_again_once_the_window_passes(self):
        self.kitchen.force(True)
        self.monitor.poll_once(now=1000.0)
        self.monitor.poll_once(now=1029.0)
        self.monitor.poll_once(now=1031.0)

        self.assertEqual(2, len(self.motion_events()))

    def test_rooms_are_debounced_independently(self):
        self.kitchen.force(True)
        self.hall.force(True)

        self.monitor.poll_once(now=1000.0)

        self.assertEqual({"Kitchen", "Hall"},
                         {e["room"] for e in self.motion_events()})

    def test_an_unavailable_sensor_contributes_nothing(self):
        self.hall.available_flag = False
        self.hall.force(True)
        self.kitchen.force(True)

        self.monitor.poll_once(now=1000.0)

        self.assertEqual(["Kitchen"], [e["room"] for e in self.motion_events()])

    def test_a_sensor_that_raises_does_not_stop_the_others(self):
        class BrokenSensor(FakeMotionSensor):
            def poll(self):
                raise OSError("GPIO busy")

        broken = BrokenSensor("Study")
        self.sensors.motion_sensors.insert(0, broken)
        self.kitchen.force(True)

        with self.assertRaises(OSError):
            self.monitor.poll_once(now=1000.0)


if __name__ == "__main__":
    unittest.main()
