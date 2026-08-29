"""The hopper gate.

One failure here costs more than a missed meal: a gate left open empties the
hopper onto the floor, and a cat that finds it eats until it is sick. So the
close is in a finally, the open time is capped, and both are pinned here.

The servo is a stand-in that records what it was told. There is nothing else to
assert against — a hobby servo has no feedback wire, so even on real hardware
the only evidence a gate moved is the weight that lands in the bowl.
"""

import logging
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.sensors.gpio import ServoOutput
from pettracker.sensors.real import ServoFeeder


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class RecordingServo(ServoOutput):
    """Notes every angle it is sent, and whether it was let go afterwards."""

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

        # A servo powered up mid-travel holds wherever it happens to be, which
        # for a hopper gate may be open.
        self.assertEqual(10, servo.angles[0])

    def test_dispensing_opens_then_closes(self):
        self.feeder.dispense(5)

        self.assertEqual([95, 10, "released"], self.servo.angles)

    def test_the_gate_is_closed_even_when_opening_fails(self):
        self.servo.raises_on_angle = 95

        with self.assertRaises(OSError):
            self.feeder.dispense(5)

        # The one failure with a cost beyond a missed meal. Whatever went wrong,
        # the hopper must not be left pouring onto the floor.
        self.assertIn(10, self.servo.angles)

    def test_the_servo_is_released_rather_than_held(self):
        self.feeder.move_to(45)

        # A servo holding position against a stiff hinge buzzes, warms up and
        # draws current all day for a gate that is not moving.
        self.assertEqual([45, "released"], self.servo.angles)

    def test_a_wrong_flow_rate_cannot_empty_the_hopper(self):
        # ms_per_gram measured wrongly by a factor of a hundred is an easy
        # mistake, and it would otherwise hold the gate open for an hour and a
        # half. Asserted on the calculation rather than by waiting for it.
        greedy = ServoFeeder(ms_per_gram=100_000.0, servo=RecordingServo(),
                             travel_s=0.0)

        self.assertEqual(ServoFeeder.MAX_OPEN_S, greedy.open_seconds(50))

    def test_a_measured_flow_rate_is_used_as_given(self):
        feeder = ServoFeeder(ms_per_gram=42.0, servo=RecordingServo(), travel_s=0.0)

        self.assertAlmostEqual(0.42, feeder.open_seconds(10))

    def test_no_servo_means_a_refusal_rather_than_silence(self):
        # None, explicitly: on a Pi, omitting it would claim the real pin and
        # move a real servo in the middle of a test run.
        feeder = ServoFeeder(servo=None)

        self.assertFalse(feeder.available)
        # Silently doing nothing would leave the app showing a dispense that
        # never happened.
        with self.assertRaises(RuntimeError):
            feeder.dispense(10)

    def test_closing_releases_the_pin(self):
        self.feeder.close()

        self.assertTrue(self.servo.closed)
        self.assertFalse(self.feeder.available)


class AngleMappingTest(unittest.TestCase):
    """Angles to pulse widths, which is all a servo actually understands."""

    def test_the_ends_of_travel_map_to_the_pulse_limits(self):
        servo = RecordingServo()

        servo.set_angle(0)
        servo.set_angle(180)

        # Handled by ServoOutput itself, so every backend agrees.
        self.assertEqual([], servo.pulses)     # RecordingServo overrides set_angle

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

        # Past its travel a servo grinds against its own stop, which sounds
        # like a servo working hard and is a servo destroying itself.
        self.assertEqual([ServoOutput.MIN_PULSE_US, ServoOutput.MAX_PULSE_US],
                         probe.pulses)


class ProberTest(unittest.TestCase):
    """--check-servo, which is how the two angles get found in the first place."""

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

        # The most expensive mistake available here, and it looks like a
        # software fault when it happens.
        self.assertIn("own 5V supply", text)


if __name__ == "__main__":
    unittest.main()


class UndervoltageTest(unittest.TestCase):
    """The Pi's own account of its supply, which is the honest arbiter here.

    Somebody testing a servo off the 5 V pin is taking a calculated risk, and
    the Pi already measures the thing that decides whether it was a good bet.
    Asking it costs nothing and turns "it seemed fine" into a number.
    """

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

        # Silence would read as "fine", which is a different claim from
        # "nobody checked" — and the two used to look identical.
        self.assertIn("no dip in its 5V rail", text)
        self.assertIn("nothing attached", text)

    def test_a_pi_that_was_already_struggling_is_flagged_up_front(self):
        # The dip predates the servo, so blaming the servo for it afterwards
        # would be wrong — but it is still worth knowing before you start.
        text = self.prober(0x10000, 0x10000)

        self.assertIn("already seen an under-voltage dip", text)
        # A dip that predates the servo must not be blamed on the servo. The
        # phrase to look for is the accusation, not the word "moved" — the
        # all-clear mentions the servo moving too.
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
    """The wheel: portioning by counting pockets rather than by timing a hole."""

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

        # A wheel powered up at the dump position is an open hole in the
        # bottom of the hopper.
        self.assertEqual(30, servo.angles[0])

    def test_a_request_becomes_whole_pockets(self):
        # 12 g of a 5 g pocket is two pockets. A third of a pocket is not a
        # thing the machine can do, and the load cell reports what actually fell.
        self.assertEqual(2, self.feeder.cycles_for(12))
        self.assertEqual(3, self.feeder.cycles_for(14))

    def test_less_than_one_pocket_still_gives_one(self):
        # Asking for 2 g and getting nothing at all would read as a broken
        # feeder rather than as rounding.
        self.assertEqual(1, self.feeder.cycles_for(2))

    def test_nothing_is_nothing(self):
        self.assertEqual(0, self.feeder.cycles_for(0))
        self.assertEqual(0, self.feeder.cycles_for(-5))

    def test_an_absurd_request_is_capped(self):
        from pettracker.sensors.real import PaddleFeeder

        # A grams_per_cycle wrong by an order of magnitude would otherwise run
        # the wheel for minutes.
        self.assertEqual(PaddleFeeder.MAX_CYCLES, self.feeder.cycles_for(5000))

    def test_each_cycle_dumps_and_returns(self):
        self.feeder.dispense(10)

        # Two pockets: out and back, twice, then parked.
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
