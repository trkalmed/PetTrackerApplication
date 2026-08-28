"""Watching a sensor with your own eyes, before trusting the event log.

/health answers "did the library claim the pin". That is not the same question
as "is the sensor wired the right way round and pointing at the right room", and
only the second one is worth anything once the app starts drawing charts from
it. A PIR whose OUT pin is on the wrong GPIO is indistinguishable, in the event
log, from a cat that never goes in the hall.

So: a live view. Wave at one sensor, watch exactly one line light up, and read
its room name. If the wrong name lights up, the pins are swapped; if none does,
the wiring or the pot is wrong. Two minutes here saves an evening of reading
`/events` and guessing.

No dependencies, and it runs against the fakes too (``--fake --check-motion``),
which is a fair way to see what a working rig is supposed to look like.
"""

import sys
import time
from typing import List, Optional, Sequence

from .sensors.base import MotionSensor


class _Watch:
    """One sensor's live state, plus the statistics that make wiring obvious."""

    def __init__(self, sensor: MotionSensor, clock=time.monotonic):
        self.sensor = sensor
        self._clock = clock
        self.high = False
        self.triggers = 0
        self.last_trigger_at: Optional[float] = None

    @property
    def room(self) -> str:
        return self.sensor.room

    @property
    def pin(self) -> str:
        pin = getattr(self.sensor, "pin", None)
        return str(pin) if pin is not None else "-"

    @property
    def backend(self) -> str:
        return getattr(self.sensor, "backend", "fake")

    @property
    def warmup_remaining_s(self) -> float:
        return float(getattr(self.sensor, "warmup_remaining_s", 0.0))

    def tick(self) -> None:
        # read_raw shows the pin during warm-up, when "is this wire connected"
        # is the only question that matters. Fakes have no such thing.
        reader = getattr(self.sensor, "read_raw", self.sensor.poll)
        high = bool(reader())

        # Count rising edges, not polls: at 5 Hz a three-second PIR hold is
        # fifteen polls and one wave of a hand. The edge count is the one that
        # matches what you just did.
        if high and not self.high:
            self.triggers += 1
            self.last_trigger_at = self._clock()
        self.high = high

        # Let the sensor advance its own warm-up state alongside.
        if reader is not self.sensor.poll:
            self.sensor.poll()

    def last_column(self) -> str:
        if not self.sensor.available:
            return "unavailable"
        remaining = self.warmup_remaining_s
        if remaining > 0:
            return f"warming up {remaining:.0f}s"
        if self.last_trigger_at is None:
            return "never fired"
        return f"{self._clock() - self.last_trigger_at:.0f}s ago"


_HEADER = f"  {'room':<16}{'pin':>4}  {'backend':<10}{'state':^7}{'fired':>6}   last"


def _unicode_ok(out) -> bool:
    encoding = getattr(out, "encoding", None) or ""
    try:
        "●".encode(encoding or "ascii")
        return True
    except (LookupError, UnicodeEncodeError):
        return False


def check_motion(sensors: Sequence[MotionSensor],
                 duration_s: Optional[float] = None,
                 interval_s: float = 0.2,
                 out=None,
                 clock=time.monotonic) -> List[_Watch]:
    """Prints every motion sensor's state until interrupted, then summarises.

    :param duration_s: stop after this long. None means run until Ctrl-C, which
        is the normal way to use it while standing at the rig.
    :returns: the watches, so a test (or a caller) can assert on what fired.
    """
    out = out or sys.stdout
    watches = [_Watch(s, clock=clock) for s in sensors]

    if not watches:
        print("no motion sensors configured — check --rooms", file=out)
        return watches

    print("\nPIR bring-up. Wave at one sensor and watch which room lights up.", file=out)
    print("Ctrl-C to stop.\n", file=out)
    print(_HEADER, file=out)
    for _ in watches:
        print("", file=out)

    interactive = getattr(out, "isatty", lambda: False)()
    # A terminal that cannot encode ● would crash the tool on its first repaint,
    # and SSH-ing in from a Windows console is exactly when someone runs this.
    on, off = ("●", "·") if _unicode_ok(out) else ("#", ".")
    started = clock()

    try:
        while duration_s is None or clock() - started < duration_s:
            for watch in watches:
                watch.tick()

            if interactive:
                # Repaint in place: cursor up N lines, then rewrite them. Plain
                # ANSI, so it works over SSH, which is where this gets used.
                out.write(f"\033[{len(watches)}A")
            for watch in watches:
                state = f"  {on if watch.high else off}  "
                line = (f"  {watch.room[:15]:<16}{watch.pin:>4}  "
                        f"{watch.backend:<10}{state:^7}{watch.triggers:>6}   "
                        f"{watch.last_column()}")
                out.write(f"\r\033[K{line}\n" if interactive else f"{line}\n")
            out.flush()

            time.sleep(interval_s)
    except KeyboardInterrupt:
        pass

    _summarise(watches, out)
    return watches


def _summarise(watches: Sequence[_Watch], out) -> None:
    print("\nSummary:", file=out)
    for watch in watches:
        if not watch.sensor.available:
            print(f"  {watch.room}: no GPIO backend could claim pin {watch.pin}. "
                  f"On a Pi 5, RPi.GPIO cannot — install gpiozero or lgpio.", file=out)
        elif watch.triggers == 0:
            print(f"  {watch.room}: never fired. Check OUT is on GPIO {watch.pin}, "
                  f"that VCC is 5V, and that the sensitivity pot is not at minimum.",
                  file=out)
        else:
            where = f"GPIO {watch.pin} via {watch.backend}" if watch.pin != "-" else watch.backend
            print(f"  {watch.room}: fired {watch.triggers}x on {where}.", file=out)

    fired = [w for w in watches if w.triggers]
    if len(fired) > 1:
        print("\n  More than one sensor fired. If you only waved at one, they are "
              "either pointing at the same space or one pin is misidentified — "
              "the rooms in --rooms are in the same order as --pir-pins.", file=out)
