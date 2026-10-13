"""Turns a noisy stream of bowl weights into meals."""

from collections import deque
from typing import Deque, List, NamedTuple, Optional, Tuple


class MealEvent(NamedTuple):
    timestamp_ms: int
    grams: int
    kind: str


EATEN = "EATEN"
DISPENSED = "DISPENSED"


class MealDetector:
    def __init__(
        self,
        min_change_grams: float = 5.0,
        settle_ms: int = 4000,
        noise_grams: float = 2.0,
        drift_settle_ms: int = 30000,
    ):
        self._min_change = min_change_grams
        self._settle_ms = settle_ms
        self._noise = noise_grams
        self._settle_band = noise_grams * 2
        self._drift_settle_ms = drift_settle_ms

        self._baseline: Optional[float] = None
        self._window: Deque[Tuple[int, float]] = deque()
        self._settled_since_ms: Optional[int] = None

    @property
    def baseline_grams(self) -> Optional[float]:
        return self._baseline

    def update(self, timestamp_ms: int, grams: Optional[float]) -> Optional[MealEvent]:
        if grams is None:
            return None

        if self._baseline is None:
            self._baseline = grams

        self._window.append((timestamp_ms, grams))
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
            # Too small to be food. Only slow drift may move the baseline.
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
        if len(self._window) < 2:
            return False
        if self._window[-1][0] - self._window[0][0] < self._settle_ms:
            return False

        weights = [g for _, g in self._window]
        return max(weights) - min(weights) <= self._settle_band

    def reset(self, grams: Optional[float] = None) -> None:
        self._baseline = grams
        self._window.clear()
        self._settled_since_ms = None


def replay(readings: List[tuple], **kwargs) -> List[MealEvent]:
    detector = MealDetector(**kwargs)
    events = []
    for timestamp_ms, grams in readings:
        event = detector.update(timestamp_ms, grams)
        if event is not None:
            events.append(event)
    return events
