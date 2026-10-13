import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.meals import DISPENSED, EATEN, MealDetector, replay


def trace(*segments):
    readings = []
    now = 0
    for duration_s, grams in segments:
        for _ in range(int(duration_s * 5)):
            readings.append((now, grams))
            now += 200
    return readings


def eating(start_grams, end_grams, duration_s, start_ms=0):
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
        self.assertEqual([], replay(trace((30, 120.0))))

    def test_one_meal_is_recorded_once(self):
        events = replay(trace((10, 120.0), (10, 80.0), (20, 80.0)))

        self.assertEqual(1, len(events))
        self.assertEqual(EATEN, events[0].kind)
        self.assertEqual(40, events[0].grams)

    def test_a_pause_mid_meal_does_not_split_it(self):
        readings = trace((10, 120.0))
        now = readings[-1][0] + 200

        readings += eating(120.0, 95.0, 3, start_ms=now)
        now = readings[-1][0] + 200

        readings += [(now + i * 200, 95.0) for i in range(10)]
        now = readings[-1][0] + 200

        readings += eating(95.0, 60.0, 3, start_ms=now)
        now = readings[-1][0] + 200

        readings += [(now + i * 200, 60.0) for i in range(100)]

        events = replay(readings)

        self.assertEqual(1, len(events))
        self.assertEqual(60, events[0].grams)

    def test_a_long_gap_does_split_into_two_meals(self):
        events = replay(trace(
            (10, 120.0),
            (3, 95.0),
            (15, 95.0),
            (3, 60.0),
            (15, 60.0),
        ))

        self.assertEqual(2, len(events))
        self.assertEqual([25, 35], [e.grams for e in events])
        self.assertTrue(all(e.kind == EATEN for e in events))

    def test_electrical_noise_is_not_a_meal(self):
        readings = []
        now = 0
        for i in range(300):
            readings.append((now, 120.0 + (1.5 if i % 2 else -1.5)))
            now += 200

        self.assertEqual([], replay(readings))

    def test_a_nudged_bowl_is_not_a_meal(self):
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
            (15, 160.0),
            (5, 110.0),
            (15, 110.0),
        ))

        self.assertEqual(2, len(events))
        self.assertEqual(DISPENSED, events[0].kind)
        self.assertEqual(150, events[0].grams)
        self.assertEqual(EATEN, events[1].kind)
        self.assertEqual(50, events[1].grams)

    def test_a_failed_reading_is_skipped_not_treated_as_empty(self):
        detector = MealDetector()
        detector.update(0, 120.0)
        for i in range(1, 20):
            self.assertIsNone(detector.update(i * 200, None))

        self.assertEqual(120.0, detector.baseline_grams)

    def test_an_unsettled_meal_is_not_reported_early(self):
        events = replay(trace((10, 120.0), (2, 100.0)))

        self.assertEqual([], events)

    def test_slow_drift_moves_the_baseline_without_creating_meals(self):
        readings = []
        now = 0
        weight = 100.0
        for _ in range(400):
            readings.append((now, weight))
            weight -= 0.01
            now += 200

        self.assertEqual([], replay(readings))

    def test_reset_after_tare_does_not_emit_a_meal(self):
        detector = MealDetector()
        detector.update(0, 120.0)
        detector.reset(0.0)

        for i in range(1, 40):
            self.assertIsNone(detector.update(i * 200, 0.0))

    def test_grams_are_whole_numbers(self):
        events = replay(trace((10, 120.4), (5, 79.6), (20, 79.6)))

        self.assertEqual(1, len(events))
        self.assertIsInstance(events[0].grams, int)


if __name__ == "__main__":
    unittest.main()
