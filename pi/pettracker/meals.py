"""Turning a noisy stream of bowl weights into meals.

This is the part of the feeder that is genuinely hard, and none of it needs
hardware to get right — which is why it lives here, on its own, with tests.

A load cell does not report "the cat ate 40 g". It reports a weight, several
times a second, that wanders by a gram or two from electrical noise, dips when
the bowl is nudged, and drops in fits and starts while an animal eats. Reading
meals out of that means answering three questions:

* **Is this a real change or noise?** Anything under a few grams is ignored.
* **Has it settled?** A drop is only counted once the weight stops moving,
  otherwise one meal is recorded as five as the cat pauses and returns.
* **Down or up?** Down is eaten. Up is a refill, which resets the baseline
  rather than counting as negative eating.

The whole thing is a small state machine over (timestamp, grams) pairs, with no
I/O and no clock of its own — time is passed in. That makes it testable in
milliseconds instead of real minutes.
"""

from collections import deque
from typing import Deque, List, NamedTuple, Optional, Tuple


class MealEvent(NamedTuple):
    """A settled change in bowl weight."""

    timestamp_ms: int
    grams: int
    kind: str          # EATEN or DISPENSED


EATEN = "EATEN"
DISPENSED = "DISPENSED"


class MealDetector:
    """Weights in, meals out.

    Feed every reading to :meth:`update`; it returns an event on the reading
    that completes one, and None otherwise.
    """

    def __init__(
        self,
        min_change_grams: float = 5.0,
        settle_ms: int = 4000,
        noise_grams: float = 2.0,
        drift_settle_ms: int = 30000,
    ):
        """
        :param min_change_grams: smallest change worth calling a meal. Below
            this it is a nudged bowl or drift, not food.
        :param settle_ms: how long the weight must hold steady before the change
            is committed. This is what stops one meal becoming five.
        :param noise_grams: band within which two readings count as "the same
            weight". Should sit above the load cell's jitter.
        :param drift_settle_ms: how long the weight must hold steady before the
            baseline follows it. Much longer than settle_ms on purpose: only
            something far slower than any meal is treated as drift.
        """
        self._min_change = min_change_grams
        self._settle_ms = settle_ms
        self._noise = noise_grams
        # Peak-to-peak allowance across the window. Twice the noise figure,
        # because jitter of +/-n spans 2n from trough to crest.
        self._settle_band = noise_grams * 2
        self._drift_settle_ms = drift_settle_ms

        self._baseline: Optional[float] = None
        self._window: Deque[Tuple[int, float]] = deque()
        # When the current run of steady readings began, or None while moving.
        self._settled_since_ms: Optional[int] = None

    @property
    def baseline_grams(self) -> Optional[float]:
        """The settled weight the next change is measured against."""
        return self._baseline

    def update(self, timestamp_ms: int, grams: Optional[float]) -> Optional[MealEvent]:
        """Feeds in one reading.

        ``grams`` may be None when the amplifier fails to read; that is skipped
        rather than treated as an empty bowl, which would otherwise look like
        the largest meal of the day.
        """
        if grams is None:
            return None

        if self._baseline is None:
            self._baseline = grams

        self._window.append((timestamp_ms, grams))
        # Keep the shortest window that still spans settle_ms, so "settled"
        # always means "steady for the full period" rather than "the last two
        # readings happened to agree".
        while len(self._window) > 2 and \
                self._window[-1][0] - self._window[1][0] >= self._settle_ms:
            self._window.popleft()

        if not self._settled():
            self._settled_since_ms = None
            return None

        if self._settled_since_ms is None:
            self._settled_since_ms = timestamp_ms

        delta = grams - self._baseline

        if abs(delta) < self._min_change:
            # Too small to be food. It is either jitter, a nudged bowl, or
            # thermal drift — and only drift should move the baseline.
            #
            # Magnitude alone cannot tell them apart, because the opening
            # seconds of a slow meal are also small. Timescale can: drift is
            # grams per hour, eating is grams per second. So the baseline only
            # follows a weight that has held steady far longer than any meal
            # takes. Adopting on magnitude alone made this detector report a
            # 60 g meal as 57 g, having quietly absorbed the start of it.
            if self._settled_since_ms is not None and \
                    timestamp_ms - self._settled_since_ms >= self._drift_settle_ms:
                self._baseline = grams
            return None

        self._baseline = grams
        self._settled_since_ms = None

        if delta < 0:
            return MealEvent(timestamp_ms, int(round(-delta)), EATEN)
        return MealEvent(timestamp_ms, int(round(delta)), DISPENSED)

    def _settled(self) -> bool:
        """True when the weight has held steady for the whole settle window.

        Judged across the window rather than between consecutive readings. That
        distinction is the point: an animal eating produces a gradual decline
        whose step-to-step change is smaller than the noise floor, so comparing
        neighbours reports "not moving" throughout a real meal.
        """
        if len(self._window) < 2:
            return False
        if self._window[-1][0] - self._window[0][0] < self._settle_ms:
            return False

        weights = [g for _, g in self._window]
        return max(weights) - min(weights) <= self._settle_band

    def reset(self, grams: Optional[float] = None) -> None:
        """Forgets history. Used after a tare, where the scale jumps by design."""
        self._baseline = grams
        self._window.clear()
        self._settled_since_ms = None


def replay(readings: List[tuple], **kwargs) -> List[MealEvent]:
    """Runs a whole series of ``(timestamp_ms, grams)`` through a detector.

    Convenient for tests and for replaying a captured trace from the real load
    cell once one exists.
    """
    detector = MealDetector(**kwargs)
    events = []
    for timestamp_ms, grams in readings:
        event = detector.update(timestamp_ms, grams)
        if event is not None:
            events.append(event)
    return events
