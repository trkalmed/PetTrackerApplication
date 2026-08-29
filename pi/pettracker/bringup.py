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

from .hx711 import POWER_DOWN_THRESHOLD_S
from .nmea import parse_gga, valid_checksum
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


#: Enough sentences that "no satellites yet" stops being about patience. At
#: roughly six a second, this is a couple of minutes of listening.
MANY_SENTENCES = 500

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
                  f"If the log says 'busy', the service already has it — "
                  f"sudo systemctl stop pettracker. Otherwise, on a Pi 5 "
                  f"RPi.GPIO cannot claim pins: install gpiozero or lgpio.",
                  file=out)
        elif watch.triggers == 0:
            print(f"  {watch.room}: never fired. Check OUT is on GPIO {watch.pin}, "
                  f"that the sensor has 5V, and that the sensitivity pot is not "
                  f"at minimum.", file=out)
        else:
            where = f"GPIO {watch.pin} via {watch.backend}" if watch.pin != "-" else watch.backend
            print(f"  {watch.room}: fired {watch.triggers}x on {where}.", file=out)

    fired = [w for w in watches if w.triggers]
    if len(fired) > 1:
        print("\n  More than one sensor fired. If you only waved at one, they are "
              "either pointing at the same space or one pin is misidentified — "
              "the rooms in --rooms are in the same order as --pir-pins.", file=out)


def check_scale(scale, duration_s: Optional[float] = None, interval_s: float = 0.5,
                out=None, clock=time.monotonic) -> None:
    """Prints what the load cell is reading, live.

    The counterpart to :func:`check_motion` for the bowl. A load cell gives you
    no sign of life at all until something interprets it — no click, no LED — so
    the first question is simply whether the numbers move when you press on it.

    Raw counts are shown alongside grams because they are the honest reading:
    grams are counts divided by a factor somebody measured, and if that factor
    is wrong the grams are wrong in a way only the counts reveal.
    """
    out = out or sys.stdout

    if not scale.available:
        print("\nThe HX711 could not be opened.", file=out)
        print("  If the log above says the pins are busy, the service already has",
              file=out)
        print("  them: sudo systemctl stop pettracker", file=out)
        print("  Otherwise check DT and SCK are the pins you passed to "
              "--scale-pins,", file=out)
        print("  and that the amplifier has power (3.3V on a Pi).", file=out)
        return

    _report_clock_speed(scale, out)

    print("\nLoad cell. Press on the bowl and watch the numbers move.", file=out)
    print("Ctrl-C to stop.\n", file=out)
    if not getattr(scale, "calibrated", True):
        print("  Uncalibrated: 'grams' below is really raw counts.", file=out)
        print("  Run --calibrate-scale to find the factor.\n", file=out)

    started = clock()
    interactive = getattr(out, "isatty", lambda: False)()
    seen = []
    explained = None
    try:
        while duration_s is None or clock() - started < duration_s:
            raw = scale.read_raw() if hasattr(scale, "read_raw") else None
            grams = scale.read_grams()
            if raw is not None:
                seen.append(raw)

            if raw is None and grams is None:
                chip = getattr(scale, "chip", None)
                why = chip.diagnose() if hasattr(chip, "diagnose") else "no reading"
                # Said once, not twice a second. It is a paragraph, and the
                # in-place repaint smears anything wider than the terminal.
                if why != explained:
                    explained = why
                    if interactive:
                        out.write("\r\033[K")
                    print("  no reading — " + why, file=out)
                line = "  no reading"
            else:
                line = (f"  raw {raw if raw is not None else '—':>10}"
                        f"   {grams:>10.1f} g" if grams is not None
                        else f"  raw {raw:>10}   (no grams)")

            if interactive:
                out.write("\r\033[K" + line)
            else:
                out.write(line + "\n")
            out.flush()
            time.sleep(interval_s)
    except KeyboardInterrupt:
        pass
    print(file=out)
    _explain_readings(seen, out)


def _report_clock_speed(scale, out) -> None:
    """Says how fast this Pi can actually clock the amplifier.

    The HX711 powers itself down if the clock line stays high for more than
    60 microseconds, and a Python GPIO library can spend that long setting a
    pin. When it does, every reading comes back as zeros and looks exactly like
    a disconnected sensor — so the number is worth showing before the readings
    rather than after a long hunt for a loose wire.
    """
    chip = getattr(scale, "chip", None)
    if chip is None or not hasattr(chip, "clock_high_seconds"):
        return

    try:
        held = chip.clock_high_seconds()
    except Exception:
        return

    micros = held * 1e6
    limit = POWER_DOWN_THRESHOLD_S * 1e6
    backend = getattr(getattr(scale, "backend", None), "__str__", lambda: "")()

    print(file=out)
    via = f" via {backend}" if backend else ""
    print(f"  Clock held high for {micros:.1f} µs per pulse"
          f" (the chip powers down past {limit:.0f} µs){via}", file=out)
    if micros >= limit:
        print("  That is too slow — the chip will power itself down mid-read and",
              file=out)
        print("  every reading will be zeros. This is a GPIO backend problem, not",
              file=out)
        print("  a wiring one: install lgpio (sudo apt install python3-lgpio),",
              file=out)
        print("  which is a thin C wrapper, rather than relying on gpiozero.",
              file=out)


def _explain_readings(seen, out) -> None:
    """Says what a suspicious set of readings usually means.

    A load cell has no way of telling you it is unhappy, so every fault arrives
    as a number. These three cover nearly all of them, and each sends you
    somewhere different — which is the whole value of saying it rather than
    printing the number and leaving you to it.
    """
    if not seen:
        return

    if all(value == 0 for value in seen):
        # An HX711 with no power, or a DT line that is not connected, leaves the
        # input low. Low reads as ready, and then as twenty-four zero bits. It is
        # not a weight of zero; it is no amplifier at all.
        print("Every reading was exactly 0, which a working amplifier does not do —",
              file=out)
        print("even an empty bowl wanders by a few counts. The data line is stuck",
              file=out)
        print("low: check DT is on the pin you passed, and that the HX711 has power",
              file=out)
        print("(3.3V is the right supply on a Pi — 5V drives 5V logic into a 3.3V",
              file=out)
        print("input).", file=out)
        return

    if len(set(seen)) == 1:
        print(f"Every reading was exactly {seen[0]}, which means the amplifier is",
              file=out)
        print("talking but nothing is changing its input. Usually the cell is not",
              file=out)
        print("wired to E+/E-/A+/A-, or the bowl is resting on something as well as",
              file=out)
        print("on the cell.", file=out)
        return

    spread = max(seen) - min(seen)
    if spread < 50:
        print(f"Readings moved by only {spread} counts. That is noise, not weight —",
              file=out)
        print("press on the bowl while this runs and expect thousands.", file=out)


def calibrate_scale(scale, out=None, ask=input) -> Optional[float]:
    """Walks through finding the calibration factor, and returns it.

    Counts per gram cannot be derived, looked up or guessed — they depend on the
    cell, the amplifier and how the bowl sits on it. The only way is to weigh
    something whose weight you already know, which is what this does.

    :returns: the factor, or None if it could not be measured
    """
    out = out or sys.stdout

    if not getattr(scale, "claimed", scale.available):
        print("The HX711 could not be opened — nothing to calibrate.", file=out)
        return None

    print("\nCalibrating the load cell.\n", file=out)
    print("1. Empty the bowl and leave it still.", file=out)
    ask("   Press Enter when it is settled... ")

    scale.tare()
    zero = scale.read_raw()
    if zero is None:
        print("   No reading. Check the wiring and try again.", file=out)
        return None
    print(f"   Zero is {zero} counts.\n", file=out)

    print("2. Put a known weight in the bowl — a kitchen weight, or water,", file=out)
    print("   where 1 ml is 1 g. Heavier is better: the error in your", file=out)
    print("   knowledge of the weight divides through into the factor.", file=out)
    answer = ask("   How many grams? ")

    try:
        known = float(answer)
    except (TypeError, ValueError):
        print("   That is not a number of grams.", file=out)
        return None
    if known <= 0:
        print("   The weight has to be more than nothing.", file=out)
        return None

    loaded = scale.read_raw()
    if loaded is None:
        print("   No reading with the weight on. Check the wiring.", file=out)
        return None

    factor = (loaded - zero) / known
    if factor == 0:
        print("   The reading did not change when you added weight. The cell is", file=out)
        print("   probably not taking the load — check that the bowl rests on it", file=out)
        print("   and nothing else.", file=out)
        return None

    print(f"\n   Loaded reading: {loaded} counts", file=out)
    print(f"   Factor: {factor:.2f} counts per gram\n", file=out)
    print("Pass this to the service, and put it in service.env so it survives", file=out)
    print("a restart:\n", file=out)
    print(f"   PETTRACKER_SCALE_FACTOR={factor:.2f}\n", file=out)
    if factor < 0:
        print("The factor is negative, which means the reading falls as weight is", file=out)
        print("added. That is a wiring swap, not a problem — E+ and E- or A+ and", file=out)
        print("A- are the other way round. It works exactly as well like this.\n",
              file=out)
    return factor


def check_gps(location, duration_s: Optional[float] = None, interval_s: float = 1.0,
              out=None, clock=time.monotonic) -> None:
    """Prints what the GPS is saying, sentence by sentence.

    The reason this exists rather than "watch /health": a module with no fix and
    a module with no wiring produce the same silence from read_fix(). The
    sentences tell them apart instantly — a GPS indoors talks constantly and
    knows nothing, which is a working GPS, and the only thing it needs is sky.

    Satellite counts are worth watching for the same reason. Going from 0 to 3
    to 7 over a few minutes is a cold start doing exactly what it should.
    """
    out = out or sys.stdout

    if not hasattr(location, "read_sentences"):
        print("\nNo GPS configured.", file=out)
        return

    port = getattr(location, "port", "the serial port")
    print(f"\nGPS on {port}. Sentences appear below as they arrive.", file=out)
    print("Ctrl-C to stop.\n", file=out)
    print("  No fix indoors is normal — a cold start needs minutes of open sky.",
          file=out)
    print("  What matters here is whether anything arrives at all.\n", file=out)

    started = clock()
    seen = 0
    best_satellites = 0
    fix = None
    frame_errors = False

    try:
        while duration_s is None or clock() - started < duration_s:
            for sentence in location.read_sentences():
                seen += 1
                if "frame errors" in sentence:
                    frame_errors = True
                if valid_checksum(sentence):
                    position = parse_gga(sentence)
                    if position is not None:
                        fix = position
                        best_satellites = max(best_satellites,
                                              position.get("satellites", 0))
                    elif sentence.startswith(("$GPGGA", "$GNGGA")):
                        # A GGA with no fix still carries the satellite count in
                        # the same place, and watching it climb is the whole
                        # story of a cold start.
                        best_satellites = max(best_satellites,
                                              _satellites_in(sentence))
                # Truncated: some sentences are long and the point is the flow.
                print(f"  {sentence[:78]}", file=out)
            time.sleep(interval_s)
    except KeyboardInterrupt:
        pass

    print("\nSummary:", file=out)
    if frame_errors:
        # The module saying its own receiver is unhappy, which reads like a
        # fault and is not one for this service.
        print("  The module reported frame errors and disabled its RX line.",
              file=out)
        print("  That is its *receive* side, which matters only for sending it",
              file=out)
        print("  configuration commands — this service opens the port read-only",
              file=out)
        print("  and never writes. Usually the Pi TX line is carrying console",
              file=out)
        print("  output at another baud rate. To silence it, disconnect Pi TX",
              file=out)
        print("  from the module RX: three wires are enough — VCC, GND, and",
              file=out)
        print("  module TX to Pi RX.", file=out)
        print(file=out)
    if seen == 0:
        print("  Nothing arrived at all. In order of likelihood: the serial", file=out)
        print("  console still owns the port (raspi-config -> Interface Options", file=out)
        print("  -> Serial Port -> login shell no, hardware serial yes, then", file=out)
        print("  reboot); TX and RX are not crossed — module TX goes to Pi RX;", file=out)
        print("  or the module has no power.", file=out)
    elif fix is not None:
        print(f"  Fix: {fix['lat']:.5f}, {fix['lon']:.5f} "
              f"from {fix['satellites']} satellites.", file=out)
        print("  The module works. Remember this is where the *Pi* is.", file=out)
    else:
        print(f"  {seen} sentences arrived, so the module and the wiring are", file=out)
        print(f"  fine — but no fix yet ({best_satellites} satellites seen).", file=out)

        if seen >= MANY_SENTENCES and best_satellites == 0:
            # Not a sky problem. A working antenna under a roof still shows
            # satellites at low signal; a flat zero across thousands of
            # sentences means nothing is reaching the receiver at all.
            print("  Zero satellites across that many sentences is not a poor", file=out)
            print("  view of the sky — a bad view still shows satellites weakly.",
                  file=out)
            print("  Suspect the antenna. It must be a GNSS antenna for", file=out)
            print("  1575.42 MHz: a 2.4 GHz Wi-Fi whip screws onto the same SMA", file=out)
            print("  and hears nothing. Check it is seated, and that you are", file=out)
            print("  using the connector it is plugged into — a board with both", file=out)
            print("  SMA and IPEX uses one or the other.", file=out)
        else:
            print("  Put it by a window, or better outside, and give it a few", file=out)
            print("  minutes. A cold start is slow and indoors it may never finish.",
                  file=out)


def _satellites_in(sentence: str) -> int:
    """The satellite count from a GGA that has no fix. 0 if it cannot be read."""
    fields = sentence.split("*")[0].split(",")
    try:
        return int(fields[7]) if len(fields) > 7 and fields[7] else 0
    except ValueError:
        return 0
