"""The HX711 protocol, and grams.

Everything here runs against a fake pair of pins that play back a chosen 24-bit
pattern, so the bit-level protocol is testable without an amplifier — which
matters, because the failure modes are all silent. A sign bit read the wrong way
round, a gain pulse missed, a saturated reading passed through as a weight: none
of them raise anything. They produce a number, the dashboard draws it, and the
pet appears to have eaten.
"""

import io
import logging
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.bringup import calibrate_scale
from pettracker.hx711 import GAIN_PULSES, HX711


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class FakeAmplifier:
    """A pin pair that clocks out whatever 24-bit values it was given.

    Models the one thing the protocol depends on: DOUT is high while the chip is
    busy, goes low when a sample is ready, and yields one bit per rising clock
    edge, most significant first.
    """

    def __init__(self, *values, ready=True):
        self.pending = list(values)
        self.ready = ready
        self.bits = []
        self.extra_pulses = 0
        self.closed = False
        self._load_next()

    def _load_next(self):
        if self.pending:
            value = self.pending.pop(0) & 0xFFFFFF
            self.bits = [(value >> i) & 1 for i in range(23, -1, -1)]
        else:
            self.bits = []

    # ── the two pins ────────────────────────────────────────────────────────

    @property
    def dout(self):
        return self

    @property
    def sck(self):
        return self

    def read(self) -> bool:
        if not self.ready:
            return True                     # busy, forever
        if self._reading:
            return bool(self._current_bit)
        return False                        # low: a sample is waiting

    def write(self, high: bool) -> None:
        if not high:
            return
        # Rising edge: hand over the next bit, or count a gain pulse.
        if self.bits:
            self._current_bit = self.bits.pop(0)
            self._reading = True
        else:
            self.extra_pulses += 1
            self._reading = False
            if self.extra_pulses in GAIN_PULSES.values():
                self._load_next()

    def close(self) -> None:
        self.closed = True

    _reading = False
    _current_bit = 0


class ProtocolTest(unittest.TestCase):

    def read(self, *values, **kwargs):
        pins = FakeAmplifier(*values, **kwargs)
        return HX711(pins.dout, pins.sck), pins

    def test_reads_a_positive_value_most_significant_bit_first(self):
        chip, _ = self.read(0x0186A0)        # 100,000

        self.assertEqual(100_000, chip.read_raw())

    def test_reads_a_negative_value_as_twos_complement(self):
        # Bit 23 set means negative. Read as unsigned this is 16,677,216-ish —
        # a bowl that weighs as much as a car, and no exception anywhere.
        chip, _ = self.read(0xFFFFFF)

        self.assertEqual(-1, chip.read_raw())

    def test_reads_zero(self):
        chip, _ = self.read(0x000000)

        self.assertEqual(0, chip.read_raw())

    def test_clocks_the_gain_pulses_after_the_data(self):
        chip, pins = self.read(0x000001)
        chip.read_raw()

        # These pulses carry no data; they choose the channel and gain for the
        # *next* conversion. Miss them and every second reading is from the
        # wrong channel at the wrong gain.
        self.assertEqual(GAIN_PULSES[128], pins.extra_pulses)

    def test_a_different_gain_clocks_a_different_number_of_pulses(self):
        pins = FakeAmplifier(0x000001)
        chip = HX711(pins.dout, pins.sck, gain=64)

        chip.read_raw()

        self.assertEqual(GAIN_PULSES[64], pins.extra_pulses)

    def test_an_impossible_gain_is_refused_at_construction(self):
        pins = FakeAmplifier(0x000001)

        # 100 is not a gain the chip has. Better to say so than to clock some
        # arbitrary number of pulses and read from an unknown channel.
        with self.assertRaises(ValueError):
            HX711(pins.dout, pins.sck, gain=100)

    def test_a_chip_that_never_becomes_ready_times_out(self):
        clock = iter([0.0, 0.1, 0.2, 10.0, 20.0, 30.0])
        pins = FakeAmplifier(0x000001, ready=False)
        chip = HX711(pins.dout, pins.sck, ready_timeout_s=0.5,
                     clock=lambda: next(clock, 99.0))

        # A disconnected DT line sits high forever. Waiting for it would stop
        # the monitor thread and take the motion sensors down with it.
        self.assertIsNone(chip.read_raw())

    def test_saturated_readings_are_not_weights(self):
        # A cell that is disconnected or miswired rails the amplifier. Returned
        # as a number, full scale would read as an enormous meal.
        for saturated in (0x7FFFFF, 0x800000):
            chip, _ = self.read(saturated)
            self.assertIsNone(chip.read_raw(), hex(saturated))


class MedianTest(unittest.TestCase):

    def test_takes_the_median_of_several_samples(self):
        pins = FakeAmplifier(1000, 1002, 1001)
        chip = HX711(pins.dout, pins.sck)

        self.assertEqual(1001, chip.read_median(3))

    def test_one_wild_sample_does_not_move_the_answer(self):
        # The defence this exists for. A clock pulse stretched by the scheduler
        # produces a completely wrong sample, not a slightly wrong one, and a
        # mean would let it drag the reading.
        pins = FakeAmplifier(1000, 900_000, 1001, 1002, 999)
        chip = HX711(pins.dout, pins.sck)

        self.assertEqual(1001, chip.read_median(5))

    def test_mostly_failed_samples_are_no_reading_at_all(self):
        pins = FakeAmplifier(0x7FFFFF, 0x7FFFFF, 0x7FFFFF, 1000, 1001)
        chip = HX711(pins.dout, pins.sck)

        # Two usable readings out of five is a hint, not a measurement, and the
        # meal detector should not act on it.
        self.assertIsNone(chip.read_median(5))


class GramsTest(unittest.TestCase):
    """Counts to grams — the part that needs a number off a real bench."""

    class Scale:
        """The arithmetic from HX711Scale, without claiming any pins."""

        def __init__(self, chip, factor):
            self.chip, self.factor, self.offset = chip, factor, 0.0

        def read_grams(self):
            raw = self.chip.read_median(1)
            if raw is None or not self.factor:
                return None
            return (raw - self.offset) / self.factor

        def tare(self):
            self.offset = float(self.chip.read_median(1))

    def scale(self, *values, factor=428.7):
        pins = FakeAmplifier(*values)
        return self.Scale(HX711(pins.dout, pins.sck), factor)

    def test_counts_become_grams_through_the_factor(self):
        scale = self.scale(42_870)

        self.assertAlmostEqual(100.0, scale.read_grams(), places=1)

    def test_tare_moves_the_zero(self):
        scale = self.scale(1000, 43_870)
        scale.tare()

        # The bowl itself weighs something, and it is not food.
        self.assertAlmostEqual(100.0, scale.read_grams(), places=1)

    def test_a_failed_reading_is_none_not_zero(self):
        scale = self.scale(0x7FFFFF)

        # An amplifier that has stopped answering and an empty bowl are
        # different facts. Only one of them means the pet ate everything.
        self.assertIsNone(scale.read_grams())

    def test_a_zero_factor_does_not_divide_by_zero(self):
        scale = self.scale(1000, factor=0)

        self.assertIsNone(scale.read_grams())


def answering(*answers):
    """An `ask` that ignores the prompt and returns the next canned answer."""
    remaining = iter(answers)

    def ask(_prompt):
        return next(remaining, "")

    return ask


class CalibrationTest(unittest.TestCase):

    class Scale:
        available = True
        calibrated = False

        def __init__(self, zero, loaded):
            self.readings = [zero, loaded]

        def tare(self):
            pass

        def read_raw(self):
            return self.readings.pop(0) if len(self.readings) > 1 \
                else self.readings[0]

        def read_grams(self):
            return None

    def test_computes_counts_per_gram_from_a_known_weight(self):
        scale = self.Scale(zero=1000, loaded=43_870)
        out = io.StringIO()

        factor = calibrate_scale(scale, out=out, ask=answering("", "100"))

        self.assertAlmostEqual(428.7, factor, places=1)
        self.assertIn("PETTRACKER_SCALE_FACTOR", out.getvalue())

    def test_a_weight_that_changes_nothing_is_reported_as_such(self):
        scale = self.Scale(zero=1000, loaded=1000)
        out = io.StringIO()

        factor = calibrate_scale(scale, out=out, ask=answering("", "100"))

        # The bowl is resting on something other than the cell — the commonest
        # mechanical mistake, and one that produces a perfectly steady reading.
        self.assertIsNone(factor)
        self.assertIn("not taking the load", out.getvalue())

    def test_a_negative_factor_is_explained_rather_than_refused(self):
        scale = self.Scale(zero=43_870, loaded=1000)
        out = io.StringIO()

        factor = calibrate_scale(scale, out=out, ask=answering("", "100"))

        # E+/E- swapped. It works exactly as well; the reading just falls as
        # weight is added, and the factor comes out negative.
        self.assertLess(factor, 0)
        self.assertIn("wiring swap", out.getvalue())

    def test_a_nonsense_weight_is_refused(self):
        for answer in ("", "heavy", "0", "-100"):
            scale = self.Scale(zero=1000, loaded=43_870)
            self.assertIsNone(
                calibrate_scale(scale, out=io.StringIO(),
                                ask=answering("", answer)), answer)


if __name__ == "__main__":
    unittest.main()
