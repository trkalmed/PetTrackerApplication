import logging
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.sensors.gpio import ServoOutput
from pettracker.sensors.real import PaddleFeeder, ServoFeeder


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class RecordingServo(ServoOutput):
    backend = "test"

    def __init__(self):
        self.pulses = []
        self.angles = []
        self.closed = False
        self.raises_on_angle = None

    def set_angle(self, degrees):
        if self.raises_on_angle is not None and degrees == self.raises_on_angle:
            raise OSError("servo wire fell out")
        self.angles.append(degrees)

    def set_pulse_us(self, microseconds):
        self.pulses.append(microseconds)

    def release(self):
        self.angles.append("released")

    def close(self):
        self.closed = True


class FeederTest(unittest.TestCase):

    def setUp(self):
        self.servo = RecordingServo()
        self.feeder = ServoFeeder(open_angle=95, closed_angle=10,
                                  ms_per_gram=1.0, servo=self.servo, travel_s=0.0)
        self.servo.angles.clear()

    def test_it_starts_from_the_closed_position(self):
        servo = RecordingServo()
        ServoFeeder(open_angle=95, closed_angle=10, servo=servo, travel_s=0.0)

        self.assertEqual(10, servo.angles[0])

    def test_dispensing_opens_then_closes(self):
        self.feeder.dispense(5)

        self.assertEqual([95, 10, "released"], self.servo.angles)

    def test_the_gate_is_closed_even_when_opening_fails(self):
        self.servo.raises_on_angle = 95

        with self.assertRaises(OSError):
            self.feeder.dispense(5)

        self.assertIn(10, self.servo.angles)

    def test_the_servo_is_released_rather_than_held(self):
        self.feeder.move_to(45)

        self.assertEqual([45, "released"], self.servo.angles)

    def test_a_wrong_flow_rate_cannot_empty_the_hopper(self):
        greedy = ServoFeeder(ms_per_gram=100_000.0, servo=RecordingServo(),
                             travel_s=0.0)

        self.assertEqual(ServoFeeder.MAX_OPEN_S, greedy.open_seconds(50))

    def test_a_measured_flow_rate_is_used_as_given(self):
        feeder = ServoFeeder(ms_per_gram=42.0, servo=RecordingServo(), travel_s=0.0)

        self.assertAlmostEqual(0.42, feeder.open_seconds(10))

    def test_no_servo_means_a_refusal_rather_than_silence(self):
        feeder = ServoFeeder(servo=None)

        self.assertFalse(feeder.available)
        with self.assertRaises(RuntimeError):
            feeder.dispense(10)

    def test_closing_releases_the_pin(self):
        self.feeder.close()

        self.assertTrue(self.servo.closed)
        self.assertFalse(self.feeder.available)


class AngleMappingTest(unittest.TestCase):
    def test_the_ends_of_travel_map_to_the_pulse_limits(self):
        servo = RecordingServo()

        servo.set_angle(0)
        servo.set_angle(180)

        self.assertEqual([], servo.pulses)

    def test_the_base_class_converts_degrees_to_microseconds(self):
        class Probe(ServoOutput):
            def __init__(self):
                self.pulses = []

            def set_pulse_us(self, microseconds):
                self.pulses.append(microseconds)

        probe = Probe()
        probe.set_angle(0)
        probe.set_angle(180)
        probe.set_angle(90)

        self.assertEqual(ServoOutput.MIN_PULSE_US, probe.pulses[0])
        self.assertEqual(ServoOutput.MAX_PULSE_US, probe.pulses[1])
        self.assertAlmostEqual(
            (ServoOutput.MIN_PULSE_US + ServoOutput.MAX_PULSE_US) / 2,
            probe.pulses[2], delta=1)

    def test_angles_past_the_stops_are_clamped(self):
        class Probe(ServoOutput):
            def __init__(self):
                self.pulses = []

            def set_pulse_us(self, microseconds):
                self.pulses.append(microseconds)

        probe = Probe()
        probe.set_angle(-30)
        probe.set_angle(250)

        self.assertEqual([ServoOutput.MIN_PULSE_US, ServoOutput.MAX_PULSE_US],
                         probe.pulses)


class ProberTest(unittest.TestCase):
    def prober(self, *answers):
        import io
        from pettracker.bringup import check_servo

        servo = RecordingServo()
        feeder = ServoFeeder(servo=servo, travel_s=0.0)
        servo.angles.clear()

        remaining = iter(answers)
        out = io.StringIO()
        check_servo(feeder, out=out, ask=lambda prompt: next(remaining, ""))
        return servo, out.getvalue()

    def test_a_typed_angle_moves_the_servo(self):
        servo, _ = self.prober("40", "120", "")

        self.assertEqual([40, "released", 120, "released"], servo.angles)

    def test_sweep_walks_the_range(self):
        servo, _ = self.prober("sweep", "")

        self.assertIn(0, servo.angles)
        self.assertIn(180, servo.angles)

    def test_an_angle_outside_the_travel_is_refused(self):
        servo, text = self.prober("200", "")

        self.assertEqual([], servo.angles)
        self.assertIn("own stop", text)

    def test_nonsense_is_survived(self):
        servo, text = self.prober("banana", "")

        self.assertEqual([], servo.angles)
        self.assertIn("A number from 0 to 180", text)

    def test_it_says_what_to_do_with_the_angles(self):
        _, text = self.prober("30", "100", "")

        self.assertIn("--servo-closed", text)
        self.assertIn("PETTRACKER_SERVO_OPEN", text)

    def test_the_power_warning_is_impossible_to_miss(self):
        _, text = self.prober("")

        self.assertIn("own 5V supply", text)


if __name__ == "__main__":
    unittest.main()


class UndervoltageTest(unittest.TestCase):
    class Result:
        def __init__(self, stdout):
            self.stdout = stdout

    def prober(self, before, after):
        import io
        from pettracker.bringup import check_servo

        feeder = ServoFeeder(servo=RecordingServo(), travel_s=0.0)
        readings = iter([before, after])
        out = io.StringIO()
        check_servo(feeder, out=out, ask=lambda prompt: "",
                    throttled=lambda: next(readings, after))
        return out.getvalue()

    def test_a_dip_during_the_session_is_reported(self):
        text = self.prober(0x0, 0x10000)

        self.assertIn("under-voltage dip while the servo moved", text)
        self.assertIn("its own supply", text)

    def test_a_steady_supply_is_stated_rather_than_implied(self):
        text = self.prober(0x0, 0x0)

        self.assertIn("no dip in its 5V rail", text)
        self.assertIn("nothing attached", text)

    def test_a_pi_that_was_already_struggling_is_flagged_up_front(self):
        text = self.prober(0x10000, 0x10000)

        self.assertIn("already seen an under-voltage dip", text)
        self.assertNotIn("more than the 5V pin can spare", text)

    def test_a_machine_with_no_vcgencmd_says_it_could_not_check(self):
        text = self.prober(None, None)

        self.assertIn("Could not ask this machine", text)
        self.assertNotIn("no dip in its 5V rail", text)


class ThrottledParsingTest(unittest.TestCase):

    def parse(self, stdout):
        from pettracker.bringup import read_throttled

        class Result:
            pass

        result = Result()
        result.stdout = stdout
        return read_throttled(runner=lambda: result)

    def test_reads_the_hex_word(self):
        self.assertEqual(0x50005, self.parse("throttled=0x50005\n"))

    def test_a_healthy_pi_reads_zero(self):
        self.assertEqual(0, self.parse("throttled=0x0\n"))

    def test_anything_else_is_no_answer_rather_than_a_wrong_one(self):
        for stdout in ("", "command not found", "throttled=banana"):
            self.assertIsNone(self.parse(stdout), stdout)

    def test_a_missing_vcgencmd_is_no_answer(self):
        from pettracker.bringup import read_throttled

        def explode():
            raise FileNotFoundError("vcgencmd")

        self.assertIsNone(read_throttled(runner=explode))


class PaddleTest(unittest.TestCase):
    def setUp(self):
        from pettracker.sensors.real import PaddleFeeder

        self.servo = RecordingServo()
        self.feeder = PaddleFeeder(park_angle=30, dump_angle=150,
                                   grams_per_cycle=5.0, servo=self.servo,
                                   travel_s=0.0, settle_s=0.0)
        self.servo.angles.clear()

    def test_it_parks_on_startup(self):
        from pettracker.sensors.real import PaddleFeeder

        servo = RecordingServo()
        PaddleFeeder(park_angle=30, dump_angle=150, servo=servo,
                     travel_s=0.0, settle_s=0.0)

        self.assertEqual(30, servo.angles[0])

    def test_a_request_becomes_whole_pockets(self):
        self.assertEqual(2, self.feeder.cycles_for(12))
        self.assertEqual(3, self.feeder.cycles_for(14))

    def test_less_than_one_pocket_still_gives_one(self):
        self.assertEqual(1, self.feeder.cycles_for(2))

    def test_nothing_is_nothing(self):
        self.assertEqual(0, self.feeder.cycles_for(0))
        self.assertEqual(0, self.feeder.cycles_for(-5))

    def test_an_absurd_request_is_capped(self):
        from pettracker.sensors.real import PaddleFeeder

        self.assertEqual(PaddleFeeder.MAX_CYCLES, self.feeder.cycles_for(5000))

    def test_each_cycle_dumps_and_returns(self):
        self.feeder.dispense(10)

        self.assertEqual([150, 30, 150, 30, 30, "released"], self.servo.angles)

    def test_it_parks_even_when_a_turn_fails(self):
        self.servo.raises_on_angle = 150

        with self.assertRaises(OSError):
            self.feeder.dispense(10)

        self.assertEqual(30, self.servo.angles[-2])

    def test_no_servo_means_a_refusal(self):
        from pettracker.sensors.real import PaddleFeeder

        feeder = PaddleFeeder(servo=None)

        self.assertFalse(feeder.available)
        with self.assertRaises(RuntimeError):
            feeder.dispense(10)


class RestingAnglesStayOffTheEndStops(unittest.TestCase):
    def test_gate_defaults_are_reachable(self):
        self.assertGreaterEqual(ServoFeeder.DEFAULT_CLOSED_ANGLE,
                                ServoOutput.SAFE_MIN_ANGLE)
        self.assertLessEqual(ServoFeeder.DEFAULT_OPEN_ANGLE,
                             ServoOutput.SAFE_MAX_ANGLE)

    def test_paddle_defaults_are_reachable(self):
        self.assertGreaterEqual(PaddleFeeder.DEFAULT_PARK_ANGLE,
                                ServoOutput.SAFE_MIN_ANGLE)
        self.assertLessEqual(PaddleFeeder.DEFAULT_DUMP_ANGLE,
                             ServoOutput.SAFE_MAX_ANGLE)

    def test_the_band_is_inside_the_pulse_range(self):
        self.assertGreater(ServoOutput.SAFE_MIN_ANGLE, 0)
        self.assertLess(ServoOutput.SAFE_MAX_ANGLE, 180)


class SweepingThePaddle(unittest.TestCase):
    def setUp(self):
        self.servo = RecordingServo()

    def _feeder(self, sweep):
        return PaddleFeeder(park_angle=30, dump_angle=150, grams_per_cycle=10,
                            servo=self.servo, travel_s=0.0, settle_s=0.0,
                            sweep=sweep)

    def test_a_cycle_visits_every_angle_in_order(self):
        feeder = self._feeder([50, 180, 50, 0])
        self.servo.angles.clear()

        feeder.dispense(10)

        self.assertEqual([50, 180, 50, 0, 0, "released"], self.servo.angles)

    def test_each_pocket_repeats_the_whole_sweep(self):
        feeder = self._feeder([50, 180, 50, 0])
        self.servo.angles.clear()

        feeder.dispense(20)

        self.assertEqual([50, 180, 50, 0, 50, 180, 50, 0, 0, "released"],
                         self.servo.angles)

    def test_the_wheel_rests_at_the_last_angle(self):
        feeder = self._feeder([50, 180, 50, 0])

        self.assertEqual(0, feeder.rest_angle)

    def test_it_parks_there_on_construction_too(self):
        self._feeder([50, 180, 50, 0])

        self.assertEqual([0, "released"], self.servo.angles)

    def test_no_sweep_keeps_the_original_motion(self):
        feeder = PaddleFeeder(park_angle=30, dump_angle=150, grams_per_cycle=10,
                              servo=self.servo, travel_s=0.0, settle_s=0.0)
        self.servo.angles.clear()

        feeder.dispense(10)

        self.assertEqual([150, 30, 30, "released"], self.servo.angles)

    def test_a_wrong_sweep_is_refused_rather_than_ignored(self):
        import run
        for bad in ("50,banana", "50,200", "50", "0"):
            with self.assertRaises(ValueError, msg=bad):
                run.parse_sweep(bad)

    def test_an_empty_setting_means_no_sweep(self):
        import run
        self.assertIsNone(run.parse_sweep(""))
        self.assertIsNone(run.parse_sweep(None))
        self.assertIsNone(run.parse_sweep("   "))

    def test_a_sweep_parses_to_angles(self):
        import run
        self.assertEqual([50.0, 180.0, 50.0, 0.0],
                         run.parse_sweep("50,180,50,0"))
        self.assertEqual([50.0, 180.0], run.parse_sweep(" 50 , 180 "))


class HoldingTheHopperOpen(unittest.TestCase):
    def setUp(self):
        from pettracker.sensors.real import HoldFeeder
        self.servo = RecordingServo()
        self.now = 0.0
        self.feeder = HoldFeeder(open_angle=180, closed_angle=10,
                                 servo=self.servo, travel_s=0.0,
                                 auto_close_s=120.0, clock=lambda: self.now)
        self.servo.angles.clear()

    def test_it_starts_closed(self):
        servo = RecordingServo()
        from pettracker.sensors.real import HoldFeeder
        HoldFeeder(open_angle=180, closed_angle=10, servo=servo, travel_s=0.0)

        self.assertEqual(10, servo.angles[0])

    def test_dispensing_opens_and_stays_open(self):
        self.feeder.dispense(40)

        self.assertEqual([180], self.servo.angles)
        self.assertTrue(self.feeder.is_open)

    def test_closing_lets_go_again(self):
        self.feeder.dispense(40)
        self.servo.angles.clear()

        self.feeder.close_gate()

        self.assertEqual([10, "released"], self.servo.angles)

    def test_closing_returns_to_the_closed_angle(self):
        self.feeder.dispense(40)
        self.servo.angles.clear()

        self.feeder.close_gate()

        self.assertEqual([10, "released"], self.servo.angles)
        self.assertFalse(self.feeder.is_open)

    def test_the_grams_are_ignored(self):
        self.feeder.dispense(5)
        first = list(self.servo.angles)
        self.feeder.close_gate()
        self.servo.angles.clear()

        self.feeder.dispense(500)

        self.assertEqual(first, self.servo.angles)

    def test_it_stays_open_while_the_limit_has_not_passed(self):
        self.feeder.dispense(40)
        self.servo.angles.clear()

        self.now = 119.0
        self.feeder.tick()

        self.assertEqual([], self.servo.angles)
        self.assertTrue(self.feeder.is_open)

    def test_it_closes_itself_if_the_close_never_arrives(self):
        self.feeder.dispense(40)
        self.servo.angles.clear()

        self.now = 121.0
        self.feeder.tick()

        self.assertEqual([10, "released"], self.servo.angles)
        self.assertFalse(self.feeder.is_open)

    def test_a_closed_hopper_is_not_closed_again(self):
        self.now = 500.0
        self.feeder.tick()

        self.assertEqual([], self.servo.angles)

    def test_the_backstop_can_be_turned_off(self):
        from pettracker.sensors.real import HoldFeeder
        servo = RecordingServo()
        feeder = HoldFeeder(open_angle=180, closed_angle=10, servo=servo,
                            travel_s=0.0, auto_close_s=None,
                            clock=lambda: self.now)
        feeder.dispense(40)
        servo.angles.clear()

        self.now = 10_000.0
        feeder.tick()

        self.assertEqual([], servo.angles)
        self.assertTrue(feeder.is_open)

    def test_no_servo_means_a_refusal_rather_than_silence(self):
        from pettracker.sensors.real import HoldFeeder
        feeder = HoldFeeder(servo=None)

        self.assertFalse(feeder.available)
        with self.assertRaises(RuntimeError):
            feeder.dispense(40)
        with self.assertRaises(RuntimeError):
            feeder.close_gate()


class WideningTheServosTravel(unittest.TestCase):
    class Probe(ServoOutput):
        def __init__(self):
            self.pulses = []

        def set_pulse_us(self, microseconds):
            self.pulses.append(microseconds)

    def test_the_default_range_is_unchanged(self):
        probe = self.Probe()
        probe.set_angle(180)

        self.assertEqual(ServoOutput.MAX_PULSE_US, probe.pulses[0])

    def test_a_wider_range_sends_a_longer_pulse_for_the_same_angle(self):
        probe = self.Probe()
        probe.MAX_PULSE_US = 2600

        probe.set_angle(180)

        self.assertEqual(2600, probe.pulses[0])

    def test_the_range_is_per_instance(self):
        widened = self.Probe()
        widened.MAX_PULSE_US = 2600
        ordinary = self.Probe()

        widened.set_angle(180)
        ordinary.set_angle(180)

        self.assertEqual(2600, widened.pulses[0])
        self.assertEqual(ServoOutput.MAX_PULSE_US, ordinary.pulses[0])

    def test_angles_still_map_across_whatever_range_is_set(self):
        probe = self.Probe()
        probe.MIN_PULSE_US = 600
        probe.MAX_PULSE_US = 2600

        probe.set_angle(0)
        probe.set_angle(90)
        probe.set_angle(180)

        self.assertEqual(600, probe.pulses[0])
        self.assertEqual(1600, probe.pulses[1])
        self.assertEqual(2600, probe.pulses[2])


class PulseWidthsTheDriverWillAccept(unittest.TestCase):
    def test_a_range_inside_the_limits_is_accepted(self):
        from pettracker.sensors.gpio import ServoOutput, open_servo

        open_servo(18, ServoOutput.HARD_MIN_PULSE_US, ServoOutput.HARD_MAX_PULSE_US)

    def test_too_wide_a_range_is_refused_with_an_explanation(self):
        from pettracker.sensors.gpio import open_servo

        with self.assertRaises(ValueError) as caught:
            open_servo(18, 500, 2600)

        self.assertIn("2500", str(caught.exception))

    def test_too_narrow_a_minimum_is_refused(self):
        from pettracker.sensors.gpio import open_servo

        with self.assertRaises(ValueError):
            open_servo(18, 400, 2400)

    def test_the_driver_clamps_as_a_last_resort(self):
        from pettracker.sensors.gpio import ServoOutput

        sent = []

        class Probe(ServoOutput):
            def set_pulse_us(inner, microseconds):
                pulse = int(microseconds)
                if pulse:
                    pulse = max(inner.HARD_MIN_PULSE_US,
                                min(inner.HARD_MAX_PULSE_US, pulse))
                sent.append(pulse)

        probe = Probe()
        probe.MAX_PULSE_US = 3000
        probe.set_angle(180)

        self.assertEqual(ServoOutput.HARD_MAX_PULSE_US, sent[0])
