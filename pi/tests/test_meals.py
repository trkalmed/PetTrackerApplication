"""Meal detection against realistic weight traces.

Each test is a trace a real load cell could plausibly produce. The point is to
pin the behaviour that is easy to get wrong and impossible to eyeball: one meal
counted once rather than five, noise ignored, refills not counted as negative
eating.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.meals import DISPENSED, EATEN, MealDetector, replay


def trace(*segments):
    """Builds (timestamp_ms, grams) readings from (duration_s, grams) segments.

    Readings every 200 ms, which is roughly what an HX711 delivers at 10 SPS
    with a little oversampling. Each segment holds a constant weight, so this
    models "the bowl is sitting at X" rather than "the cat is eating".
    """
    readings = []
    now = 0
    for duration_s, grams in segments:
        for _ in range(int(duration_s * 5)):
            readings.append((now, grams))
            now += 200
    return readings


def eating(start_grams, end_grams, duration_s, start_ms=0):
    """Readings for food actually being eaten: a gradual decline.

    Distinct from a :func:`trace` segment, which holds a constant. A real animal
    produces a falling weight, and the detector only treats weight as settled
    once it stops moving — so a decline and a plateau are not interchangeable.
    """
    count = int(duration_s * 5)
    readings = []
    for i in range(count):
        fraction = (i + 1) / count
        grams = start_grams + (end_grams - start_grams) * fraction
        readings.append((start_ms + i * 200, grams))
    return readings


class MealDetectorTest(unittest.TestCase):

    def test_a_steady_empty_bowl_produces_nothing(self):
        self.assertEqual([], replay(trace((30, 0.0))))

    def test_a_steady_full_bowl_produces_nothing(self):
        # Food sitting untouched is not a meal.
        self.assertEqual([], replay(trace((30, 120.0))))

    def test_one_meal_is_recorded_once(self):
        # 120 g, cat eats down to 80 g, then the bowl sits still.
        events = replay(trace((10, 120.0), (10, 80.0), (20, 80.0)))

        self.assertEqual(1, len(events))
        self.assertEqual(EATEN, events[0].kind)
        self.assertEqual(40, events[0].grams)

    def test_a_pause_mid_meal_does_not_split_it(self):
        # Eats 120 -> 95, pauses two seconds, eats 95 -> 60. The pause is
        # shorter than the settle window, so this is one meal of 60 g, not two.
        readings = trace((10, 120.0))
        now = readings[-1][0] + 200

        readings += eating(120.0, 95.0, 3, start_ms=now)
        now = readings[-1][0] + 200

        readings += [(now + i * 200, 95.0) for i in range(10)]      # 2 s pause
        now = readings[-1][0] + 200

        readings += eating(95.0, 60.0, 3, start_ms=now)
        now = readings[-1][0] + 200

        readings += [(now + i * 200, 60.0) for i in range(100)]     # settles

        events = replay(readings)

        self.assertEqual(1, len(events))
        self.assertEqual(60, events[0].grams)

    def test_a_long_gap_does_split_into_two_meals(self):
        # Same food, but the cat leaves for long enough that these really are
        # two visits.
        events = replay(trace(
            (10, 120.0),
            (3, 95.0),
            (15, 95.0),    # settles: first meal commits
            (3, 60.0),
            (15, 60.0),    # settles: second meal commits
        ))

        self.assertEqual(2, len(events))
        self.assertEqual([25, 35], [e.grams for e in events])
        self.assertTrue(all(e.kind == EATEN for e in events))

    def test_electrical_noise_is_not_a_meal(self):
        # +/- 1.5 g jitter around a full bowl, forever.
        readings = []
        now = 0
        for i in range(300):
            readings.append((now, 120.0 + (1.5 if i % 2 else -1.5)))
            now += 200

        self.assertEqual([], replay(readings))

    def test_a_nudged_bowl_is_not_a_meal(self):
        # A brief 3 g dip that returns: under min_change_grams, so ignored.
        events = replay(trace((10, 120.0), (1, 117.0), (20, 120.0)))

        self.assertEqual([], events)

    def test_a_refill_is_dispensed_not_negative_eating(self):
        events = replay(trace((10, 20.0), (2, 150.0), (20, 150.0)))

        self.assertEqual(1, len(events))
        self.assertEqual(DISPENSED, events[0].kind)
        self.assertEqual(130, events[0].grams)

    def test_refill_then_eat_gives_two_events_in_order(self):
        events = replay(trace(
            (10, 10.0),
            (2, 160.0),
            (15, 160.0),   # refill settles
            (5, 110.0),
            (15, 110.0),   # meal settles
        ))

        self.assertEqual(2, len(events))
        self.assertEqual(DISPENSED, events[0].kind)
        self.assertEqual(150, events[0].grams)
        self.assertEqual(EATEN, events[1].kind)
        self.assertEqual(50, events[1].grams)

    def test_a_failed_reading_is_skipped_not_treated_as_empty(self):
        # None means the amplifier did not read. Treating it as 0 g would look
        # like the largest meal of the day.
        detector = MealDetector()
        detector.update(0, 120.0)
        for i in range(1, 20):
            self.assertIsNone(detector.update(i * 200, None))

        self.assertEqual(120.0, detector.baseline_grams)

    def test_an_unsettled_meal_is_not_reported_early(self):
        # Still eating when the trace ends: nothing is committed, because the
        # amount is not known yet.
        events = replay(trace((10, 120.0), (2, 100.0)))

        self.assertEqual([], events)

    def test_slow_drift_moves_the_baseline_without_creating_meals(self):
        # Load cells drift with temperature over hours. That must not silently
        # accumulate into a phantom meal.
        readings = []
        now = 0
        weight = 100.0
        for _ in range(400):
            readings.append((now, weight))
            weight -= 0.01          # 4 g over the whole trace, gradually
            now += 200

        self.assertEqual([], replay(readings))

    def test_reset_after_tare_does_not_emit_a_meal(self):
        # Taring makes the reading jump by design; that is not the cat eating.
        detector = MealDetector()
        detector.update(0, 120.0)
        detector.reset(0.0)

        for i in range(1, 40):
            self.assertIsNone(detector.update(i * 200, 0.0))

    def test_grams_are_whole_numbers(self):
        # The app's schema stores an int; a fractional gram would be truncated
        # somewhere less obvious.
        events = replay(trace((10, 120.4), (5, 79.6), (20, 79.6)))

        self.assertEqual(1, len(events))
        self.assertIsInstance(events[0].grams, int)


if __name__ == "__main__":
    unittest.main()
