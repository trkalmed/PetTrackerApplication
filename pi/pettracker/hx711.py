"""Talking to an HX711 load-cell amplifier, over two wires and nothing else.

There are several HX711 packages on PyPI and they are all built on RPi.GPIO,
which does not work on a Pi 5 — the same wall the PIR sensors hit. Rather than
depend on one and inherit that, the protocol is here. It is small: the chip has
no registers, no addresses and no commands. It pulls a data line low when a
reading is ready, and you clock 24 bits out of it.

    DOUT high   busy, converting
    DOUT low    a sample is waiting
    24 clock pulses, MSB first, reading DOUT after each rising edge
    1 to 3 more pulses, which choose the gain of the *next* reading

The subtleties that cost real time:

**The clock line must not stay high for more than 60 microseconds.** Hold it
high longer and the chip decides you meant to power it down, and the reading
that comes back is garbage. Python cannot promise anything about 60 µs — a
garbage collection or a busy CPU is enough — so bad samples are a fact of life
here, not a bug to be fixed. They are dealt with by reading several and taking
the median, which is why :meth:`HX711.read_median` exists and why the caller
should use it rather than :meth:`HX711.read_raw`.

**Two saturated values mean "no cell", not "no weight".** A load cell that is
disconnected or wired wrongly rails the amplifier, and it reports the extremes
of the 24-bit range. Those are rejected rather than returned, because an empty
bowl and a broken amplifier must not look the same — a silent zero would show a
pet that ate nothing as a pet whose feeder is working perfectly.

**Readings are counts, not grams.** What the chip returns depends on the
particular cell, the amplifier gain and how the bowl sits on it, so it means
nothing until it is divided by a calibration factor measured on the real rig.
:class:`~pettracker.sensors.real.HX711Scale` does that part; this class stays
honestly in counts.
"""

import logging
import statistics
import threading
import time
from typing import List, Optional

log = logging.getLogger(__name__)

#: Clock pulses after the 24 data bits, which select the gain of the *next*
#: conversion. 128 is channel A at its most sensitive, which is what a bowl on a
#: single cell wants; 32 is channel B, for a second cell nobody here has.
GAIN_PULSES = {128: 1, 64: 3, 32: 2}

#: The full-scale extremes. A cell that is disconnected, miswired or crushed
#: rails to one of these, and neither is a weight.
_SATURATED = (0x7FFFFF, -0x800000)

_SIGN_BIT = 0x800000
_RANGE = 0x1000000


class HX711:
    """One amplifier, on a data pin and a clock pin."""

    def __init__(self, dout, sck, gain: int = 128,
                 ready_timeout_s: float = 0.5, clock=time.monotonic):
        """
        :param dout: a claimed input pin — see :func:`~pettracker.sensors.gpio.open_input`
        :param sck: a claimed output pin
        :param gain: 128, 64 or 32; anything else is a wiring misunderstanding
        :param ready_timeout_s: how long to wait for the chip to offer a sample.
            It converts at 10 Hz by default, so half a second is five missed
            conversions — long past "slow" and into "not answering".
        """
        if gain not in GAIN_PULSES:
            raise ValueError(f"gain must be one of {sorted(GAIN_PULSES)}, not {gain}")

        self._dout = dout
        self._sck = sck
        self._gain_pulses = GAIN_PULSES[gain]
        self._ready_timeout_s = ready_timeout_s
        self._clock = clock
        # The monitor thread reads the scale; a bring-up tool or a tare can ask
        # at the same time. Two readers interleaving their clock pulses would
        # produce two corrupt samples rather than one good one.
        self._lock = threading.Lock()

        self._sck.write(False)

    def is_ready(self) -> bool:
        """True when a conversion is waiting to be clocked out."""
        return not self._dout.read()

    def read_raw(self) -> Optional[int]:
        """One sample in counts, or None if the chip did not cooperate.

        None covers both "no sample arrived in time" and "the value was
        saturated". Neither is a weight, and the difference matters only to
        whoever is holding a screwdriver — the log says which.
        """
        with self._lock:
            if not self._wait_until_ready():
                log.debug("HX711 did not become ready")
                return None

            value = 0
            for _ in range(24):
                self._sck.write(True)
                bit = self._dout.read()
                self._sck.write(False)
                value = (value << 1) | (1 if bit else 0)

            # These extra pulses do not produce data; they tell the chip which
            # channel and gain to use for the conversion it starts next.
            for _ in range(self._gain_pulses):
                self._sck.write(True)
                self._sck.write(False)

        if value & _SIGN_BIT:
            value -= _RANGE

        if value in _SATURATED:
            log.debug("HX711 returned a saturated reading (%d) — check the cell wiring",
                      value)
            return None
        return value

    def read_median(self, samples: int = 5) -> Optional[int]:
        """The median of several samples, or None if too few came back.

        The median rather than the mean, because the failure being defended
        against is not noise — it is the occasional completely wrong sample from
        a clock pulse that got stretched by the scheduler. A mean lets one of
        those drag the answer; a median ignores it entirely.
        """
        readings: List[int] = []
        for _ in range(max(1, samples)):
            value = self.read_raw()
            if value is not None:
                readings.append(value)

        if not readings:
            return None
        # A single good reading out of five is not a measurement, it is a hint.
        # Anything the meal detector acts on should have agreement behind it.
        if len(readings) * 2 < samples:
            log.warning("HX711 gave only %d usable readings out of %d",
                        len(readings), samples)
            return None
        return int(statistics.median(readings))

    def power_down(self) -> None:
        """Puts the chip to sleep — clock held high for more than 60 µs."""
        with self._lock:
            self._sck.write(False)
            self._sck.write(True)
            time.sleep(0.0001)

    def power_up(self) -> None:
        """Wakes it, and resets the gain to channel A on the next reading."""
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
            # Yield rather than spin: at 10 Hz there are around 100 ms to wait,
            # and a tight loop on a Pi is a core spent doing nothing.
            time.sleep(0.001)
        return True
