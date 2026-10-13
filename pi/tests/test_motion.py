import io
import logging
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.bringup import check_motion
from pettracker.sensors.fake import FakeMotionSensor
from pettracker.sensors.gpio import held_elsewhere
from pettracker.sensors.real import (
    GpioMotionSensor,
    build_motion_sensors,
    parse_pins,
    pin_map,
)


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class FakePin:
    backend = "test"

    def __init__(self, high: bool = False):
        self.high = high
        self.closed = False
        self.raises = None

    def read(self) -> bool:
        if self.raises is not None:
            raise self.raises
        return self.high

    def close(self) -> None:
        self.closed = True


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class WarmUpTest(unittest.TestCase):

    def setUp(self):
        self.clock = Clock()
        self.pin = FakePin()
        self.sensor = GpioMotionSensor(
            "Kitchen", 17, warmup_s=60.0, clock=self.clock, pin_input=self.pin)

    def test_motion_during_warm_up_is_not_reported(self):
        self.pin.high = True
        self.clock.advance(10)

        self.assertFalse(self.sensor.poll())

    def test_warm_up_pulses_are_discarded_not_queued(self):
        self.pin.high = True
        self.clock.advance(10)
        self.sensor.poll()

        self.pin.high = False
        self.clock.advance(60)
        self.assertFalse(self.sensor.poll())

    def test_motion_after_warm_up_is_reported(self):
        self.clock.advance(61)
        self.pin.high = True

        self.assertTrue(self.sensor.poll())

    def test_the_raw_pin_is_readable_during_warm_up(self):
        self.pin.high = True

        self.assertTrue(self.sensor.read_raw())
        self.assertFalse(self.sensor.poll())

    def test_warm_up_can_be_waived(self):
        sensor = GpioMotionSensor(
            "Kitchen", 17, warmup_s=0.0, clock=self.clock, pin_input=self.pin)
        self.pin.high = True

        self.assertTrue(sensor.poll())

    def test_warm_up_remaining_counts_down_and_stops_at_zero(self):
        self.assertAlmostEqual(60.0, self.sensor.warmup_remaining_s)
        self.clock.advance(45)
        self.assertAlmostEqual(15.0, self.sensor.warmup_remaining_s)
        self.clock.advance(30)
        self.assertEqual(0.0, self.sensor.warmup_remaining_s)


class UnavailableSensorTest(unittest.TestCase):
    def setUp(self):
        self.sensor = GpioMotionSensor("Hall", 22, warmup_s=0.0, pin_input=None)

    def test_reports_itself_unavailable(self):
        self.assertFalse(self.sensor.available)

    def test_polling_is_quiet_rather_than_an_error(self):
        self.assertFalse(self.sensor.poll())
        self.assertFalse(self.sensor.read_raw())

    def test_closing_is_safe(self):
        self.sensor.close()
        self.sensor.close()


class ReadFailureTest(unittest.TestCase):

    def test_a_failing_read_does_not_escape(self):
        pin = FakePin()
        pin.raises = OSError("GPIO busy")
        sensor = GpioMotionSensor("Kitchen", 17, warmup_s=0.0, pin_input=pin)

        self.assertFalse(sensor.poll())


class CloseTest(unittest.TestCase):

    def test_close_releases_the_pin_once(self):
        pin = FakePin()
        sensor = GpioMotionSensor("Kitchen", 17, warmup_s=0.0, pin_input=pin)

        sensor.close()
        sensor.close()

        self.assertTrue(pin.closed)
        self.assertFalse(sensor.available)


class PinConfigurationTest(unittest.TestCase):

    def test_parses_a_pin_list(self):
        self.assertEqual([17, 27, 22], parse_pins(" 17, 27 ,22 "))

    def test_rejects_a_non_number(self):
        with self.assertRaises(ValueError):
            parse_pins("17,twenty-seven")

    def test_rejects_pins_that_are_not_usable_header_pins(self):
        for spec in ("0", "1", "28"):
            with self.assertRaises(ValueError):
                parse_pins(spec)

    def test_rejects_a_pin_list_that_does_not_match_the_rooms(self):
        with self.assertRaises(ValueError):
            pin_map(["Kitchen", "Hall", "Study"], [17, 27])

    def test_rejects_the_same_pin_twice(self):
        with self.assertRaises(ValueError):
            pin_map(["Kitchen", "Hall"], [17, 17])

    def test_rooms_keep_their_pin_order(self):
        self.assertEqual([("Kitchen", 17), ("Hall", 27)],
                         pin_map(["Kitchen", "Hall"], [17, 27]))

    def test_building_sensors_works_with_or_without_gpio(self):
        sensors = build_motion_sensors(["Kitchen", "Hall"], [17, 27])

        try:
            self.assertEqual(["Kitchen", "Hall"], [s.room for s in sensors])
            self.assertEqual([17, 27], [s.pin for s in sensors])
        finally:
            for sensor in sensors:
                sensor.close()


class BringUpViewTest(unittest.TestCase):

    def test_counts_one_trigger_per_wave_not_per_poll(self):
        sensor = FakeMotionSensor("Kitchen")
        sensor.force(True)

        watches = check_motion([sensor], duration_s=0.25, interval_s=0.05,
                               out=io.StringIO())

        self.assertEqual(1, watches[0].triggers)

    def test_a_sensor_that_never_fires_is_called_out(self):
        quiet = FakeMotionSensor("Hall", trigger_probability=0.0)
        out = io.StringIO()

        check_motion([quiet], duration_s=0.1, interval_s=0.05, out=out)

        text = out.getvalue()
        self.assertIn("never fired", text)
        self.assertIn("Hall", text)

    def test_an_unavailable_sensor_names_the_pi_5_trap(self):
        sensor = GpioMotionSensor("Study", 22, warmup_s=0.0, pin_input=None)
        out = io.StringIO()

        check_motion([sensor], duration_s=0.1, interval_s=0.05, out=out)

        self.assertIn("Pi 5", out.getvalue())

    def test_no_sensors_says_so_rather_than_drawing_an_empty_table(self):
        out = io.StringIO()

        check_motion([], duration_s=0.1, out=out)

        self.assertIn("no motion sensors configured", out.getvalue())


if __name__ == "__main__":
    unittest.main()


class BusyPinTest(unittest.TestCase):
    def test_a_pin_held_by_another_process_is_recognised(self):
        self.assertTrue(held_elsewhere(["gpiozero: 'GPIO busy'",
                                        "RPi.GPIO: 'GPIO busy'",
                                        "lgpio: 'GPIO busy'"]))

    def test_a_missing_library_is_not_a_busy_pin(self):
        self.assertFalse(held_elsewhere(["gpiozero: No module named 'gpiozero'",
                                         "RPi.GPIO: No module named 'RPi'",
                                         "lgpio: No module named 'lgpio'"]))

    def test_a_mixture_is_not_reported_as_busy(self):
        self.assertFalse(held_elsewhere(["gpiozero: 'GPIO busy'",
                                         "lgpio: No module named 'lgpio'"]))

    def test_no_errors_is_not_busy(self):
        self.assertFalse(held_elsewhere([]))
