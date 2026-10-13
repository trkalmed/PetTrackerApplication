"""HX711 load cell amplifier driver over two GPIO pins."""

import logging
import statistics
import threading
import time
from typing import List, Optional

log = logging.getLogger(__name__)

# Clock pulses after the 24 data bits select the gain of the next conversion.
GAIN_PULSES = {128: 1, 64: 3, 32: 2}

_SATURATED = (0x7FFFFF, -0x800000)

# Longest the clock may stay high before the chip powers down, in seconds (datasheet).
POWER_DOWN_THRESHOLD_S = 60e-6

_SIGN_BIT = 0x800000
_RANGE = 0x1000000


class HX711:
    def __init__(self, dout, sck, gain: int = 128,
                 ready_timeout_s: float = 0.5, clock=time.monotonic):
        if gain not in GAIN_PULSES:
            raise ValueError(f"gain must be one of {sorted(GAIN_PULSES)}, not {gain}")

        self._dout = dout
        self._sck = sck
        self._gain_pulses = GAIN_PULSES[gain]
        self._ready_timeout_s = ready_timeout_s
        self._clock = clock
        # Two readers interleaving clock pulses would corrupt both readings.
        self._lock = threading.Lock()

        self._sck.write(False)

    def is_ready(self) -> bool:
        return not self._dout.read()

    def read_raw(self) -> Optional[int]:
        value, _ = self._read_once()
        return value

    def diagnose(self) -> str:
        value, reason = self._read_once()
        if value is not None:
            return f"reading {value} counts"
        return reason

    def _read_once(self):
        with self._lock:
            if not self._wait_until_ready():
                return None, ("the data line never went low, so the chip never "
                              "offered a sample. Usually it has no power, or its "
                              "GND is not shared with the Pi.")

            value = 0
            for _ in range(24):
                self._sck.write(True)
                bit = self._dout.read()
                self._sck.write(False)
                value = (value << 1) | (1 if bit else 0)

            for _ in range(self._gain_pulses):
                self._sck.write(True)
                self._sck.write(False)

        if value & _SIGN_BIT:
            value -= _RANGE

        if value in _SATURATED:
            return None, ("the reading was full scale, which means the amplifier "
                          "is railed. Usually the load cell is not connected to "
                          "E+/E-/A+/A-, or one of its wires is broken.")

        if value == 0:
            # Twenty four zero bits means the data line was held low, not a weight of zero.
            return None, ("the data line stayed low for the whole read, so all "
                          "24 bits came back as zero. A real cell never reads "
                          "exactly zero. Usually DT is not on the pin you named.")

        return value, "ok"

    def read_median(self, samples: int = 5) -> Optional[int]:
        readings: List[int] = []
        for _ in range(max(1, samples)):
            value = self.read_raw()
            if value is not None:
                readings.append(value)

        if not readings:
            return None
        # Require agreement across reads before trusting a value.
        if len(readings) * 2 < samples:
            log.warning("HX711 gave only %d usable readings out of %d",
                        len(readings), samples)
            return None
        return int(statistics.median(readings))

    def clock_high_seconds(self, pulses: int = 200) -> float:
        total = 0.0
        with self._lock:
            for _ in range(pulses):
                start = time.perf_counter()
                self._sck.write(True)
                total += time.perf_counter() - start
                self._sck.write(False)
        return total / pulses

    def power_down(self) -> None:
        with self._lock:
            self._sck.write(False)
            self._sck.write(True)
            time.sleep(0.0001)

    def power_up(self) -> None:
        with self._lock:
            self._sck.write(False)
            time.sleep(0.0001)

    def close(self) -> None:
        self._sck.write(False)
        self._dout.close()
        self._sck.close()

    def _wait_until_ready(self) -> bool:
        deadline = self._clock() + self._ready_timeout_s
        while self._dout.read():
            if self._clock() >= deadline:
                return False
            time.sleep(0.001)
        return True
