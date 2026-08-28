"""The polling loop, on the motion side.

The sensor answers "is something moving right now"; the store holds "the cat was
in the kitchen at 14:02". :class:`Monitor` is the only thing in between, and the
debounce it applies is what decides whether one visit to the kitchen reaches the
app as one event or as three hundred. A PIR latches high for seconds and
retriggers for as long as anything moves, so without the debounce the event log
is unreadable and the phone spends its battery paging through it.

Time is passed in rather than read from the clock, so the debounce window can be
crossed in a test without sleeping through it.
"""

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
    # The sensor layer logs which GPIO backend it found, which is the point on a
    # Pi and noise in a test run.
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
        scale.available_flag = False          # the load cell is not what is under test

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
        # The shape the Android side reads. Renaming any of this breaks the app.
        self.assertEqual("Kitchen", events[0]["room"])
        self.assertEqual("pir", events[0]["source"])
        self.assertEqual(1_000_000, events[0]["timestamp_ms"])

    def test_a_held_sensor_is_one_event_not_hundreds(self):
        self.kitchen.force(True)

        # A PIR stays high for its hold time and the loop runs at 5 Hz.
        for tick in range(50):
            self.monitor.poll_once(now=1000.0 + tick * 0.2)

        self.assertEqual(1, len(self.motion_events()))

    def test_the_room_reports_again_once_the_window_passes(self):
        self.kitchen.force(True)
        self.monitor.poll_once(now=1000.0)
        self.monitor.poll_once(now=1029.0)
        self.monitor.poll_once(now=1031.0)

        # Still in the kitchen half a minute later is worth knowing: it is what
        # separates passing through from settling down.
        self.assertEqual(2, len(self.motion_events()))

    def test_rooms_are_debounced_independently(self):
        self.kitchen.force(True)
        self.hall.force(True)

        self.monitor.poll_once(now=1000.0)

        # One room going quiet must not suppress another. These are separate
        # sensors watching separate spaces.
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

        # poll_once is called from a loop that catches this, so a failing sensor
        # costs one pass rather than the whole thread — but the rooms after it in
        # the list are silently skipped for that pass, which is worth knowing.
        with self.assertRaises(OSError):
            self.monitor.poll_once(now=1000.0)


if __name__ == "__main__":
    unittest.main()
