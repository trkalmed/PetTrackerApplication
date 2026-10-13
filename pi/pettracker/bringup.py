"""Live sensor checks used while wiring the rig."""

import sys
import time
from typing import List, Optional, Sequence

from .hx711 import POWER_DOWN_THRESHOLD_S
from .nmea import parse_gga, valid_checksum
from .sensors.base import MotionSensor


class _Watch:
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
        reader = getattr(self.sensor, "read_raw", self.sensor.poll)
        high = bool(reader())

        if high and not self.high:
            self.triggers += 1
            self.last_trigger_at = self._clock()
        self.high = high

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
    on, off = ("●", "·") if _unicode_ok(out) else ("#", ".")
    started = clock()

    try:
        while duration_s is None or clock() - started < duration_s:
            for watch in watches:
                watch.tick()

            if interactive:
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
    if not seen:
        return

    if all(value == 0 for value in seen):
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
                        best_satellites = max(best_satellites,
                                              _satellites_in(sentence))
                print(f"  {sentence[:78]}", file=out)
            time.sleep(interval_s)
    except KeyboardInterrupt:
        pass

    print("\nSummary:", file=out)
    if frame_errors:
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
    fields = sentence.split("*")[0].split(",")
    try:
        return int(fields[7]) if len(fields) > 7 and fields[7] else 0
    except ValueError:
        return 0


UNDERVOLTAGE_NOW = 0x1
UNDERVOLTAGE_SINCE_BOOT = 0x10000


def read_throttled(runner=None) -> Optional[int]:
    import subprocess

    runner = runner or (lambda: subprocess.run(
        ["vcgencmd", "get_throttled"], capture_output=True, text=True, timeout=5))
    try:
        result = runner()
    except Exception:
        return None

    text = (getattr(result, "stdout", "") or "").strip()
    if "throttled=" not in text:
        return None
    try:
        return int(text.split("throttled=")[1].strip(), 16)
    except ValueError:
        return None


def check_servo(feeder, out=None, ask=input, throttled=read_throttled) -> None:
    out = out or sys.stdout

    if not feeder.available:
        print("\nNo servo. Check the signal wire is on the pin you passed to",
              file=out)
        print("--servo-pin, and that the servo has power.", file=out)
        return

    pin = getattr(feeder, "pin", "?")
    backend = getattr(feeder, "backend", "?")
    print(f"\nServo on GPIO {pin} via {backend}.", file=out)
    print(file=out)
    print("  Power it from its own 5V supply, not the Pi. An SG90 stalled",
          file=out)
    print("  against jammed kibble draws close to an amp, and a brown-out",
          file=out)
    print("  during an SD-card write is how a Raspberry Pi loses its", file=out)
    print("  filesystem. Grounds tied together, signal to the GPIO.", file=out)
    print(file=out)
    print("  Type an angle from 0 to 180 and press Enter to move there.", file=out)
    print("  'sweep' walks the whole range. Blank line or 'q' to finish.", file=out)
    print(file=out)

    before = throttled()
    if before is not None and before & UNDERVOLTAGE_SINCE_BOOT:
        print("  Note: this Pi has already seen an under-voltage dip since it", file=out)
        print("  booted, before any servo moved. Its supply is marginal to start", file=out)
        print("  with, which makes everything below more likely.", file=out)
        print(file=out)

    visited = []
    while True:
        try:
            answer = ask("  angle> ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            break

        if answer in ("", "q", "quit", "exit"):
            break

        if answer == "sweep":
            for angle in (0, 45, 90, 135, 180, 90, 0):
                print(f"    {angle}", file=out)
                feeder.move_to(angle)
            continue

        try:
            angle = float(answer)
        except ValueError:
            print("    A number from 0 to 180, or 'sweep', or blank to finish.",
                  file=out)
            continue

        if not 0 <= angle <= 180:
            print("    Outside 0-180. The servo would push against its own stop.",
                  file=out)
            continue

        feeder.move_to(angle)
        visited.append(angle)

    print(file=out)
    if visited:
        print("Angles tried: " + ", ".join(str(a) for a in visited), file=out)
    print("Once the gate is built, pass the two that matter:", file=out)
    print("  --servo-closed <angle> --servo-open <angle>", file=out)
    print("and put them in service.env as PETTRACKER_SERVO_CLOSED and", file=out)
    print("PETTRACKER_SERVO_OPEN so they survive a restart.", file=out)
    print(file=out)
    print("The third number, milliseconds per gram, comes from the bowl: open",
          file=out)
    print("the gate for a known time, weigh what fell, divide. It only has to",
          file=out)
    print("be close — the load cell measures what actually landed.", file=out)

    after = throttled()
    print(file=out)
    if after is None or before is None:
        print("Could not ask this machine about its supply — vcgencmd is a", file=out)
        print("Raspberry Pi tool and is not answering here, so nothing above", file=out)
        print("says anything about the 5V rail.", file=out)
    else:
        appeared = after & ~before & (UNDERVOLTAGE_NOW | UNDERVOLTAGE_SINCE_BOOT)
        if not appeared:
            print("The Pi reported no dip in its 5V rail while the servo moved.",
                  file=out)
            print("That holds for a servo with nothing attached to it; a gate to", file=out)
            print("push against changes the arithmetic.", file=out)
        if appeared:
            print(file=out)
            print("The Pi reported an under-voltage dip while the servo moved.",
                  file=out)
            print("It is drawing more than the 5V pin can spare. Unloaded that is",
                  file=out)
            print("a warning; with a gate on it, it is how an SD card gets", file=out)
            print("corrupted mid-write. Give the servo its own supply before", file=out)
            print("attaching anything to the horn.", file=out)


JPEG_START = b"\xff\xd8"
JPEG_END = b"\xff\xd9"


def check_camera(camera, frames: int = 20, out=None, save_to=None,
                 clock=time.monotonic) -> None:
    out = out or sys.stdout

    if not camera.available:
        print("\nThe camera did not open.", file=out)
        print("  A CSI camera: check the ribbon is seated at both ends, blue", file=out)
        print("  side towards the ethernet port on the Pi, and that no other", file=out)
        print("  copy of this service is running — only one process may hold", file=out)
        print("  it.", file=out)
        print("  A USB camera: check it appears in ls /dev/video*, and try", file=out)
        print("  --camera-device 1 if it is not video0.", file=out)
        return

    print(f"\nGrabbing {frames} frames.\n", file=out)

    sizes = []
    malformed = 0
    last = None
    started = clock()

    for _ in range(frames):
        frame = camera.read_jpeg()
        if frame is None:
            continue
        last = frame
        sizes.append(len(frame))
        if not (frame.startswith(JPEG_START) and frame.endswith(JPEG_END)):
            malformed += 1

    elapsed = clock() - started

    print("Summary:", file=out)
    if not sizes:
        print("  The camera is open and returned no frames at all. For a USB", file=out)
        print("  camera that usually means another process has it; for a CSI", file=out)
        print("  one, a ribbon that is seated enough to detect and not enough", file=out)
        print("  to read.", file=out)
        return

    average = sum(sizes) // len(sizes)
    rate = len(sizes) / elapsed if elapsed > 0 else 0.0
    print(f"  {len(sizes)} of {frames} frames arrived, {average // 1024} KB "
          f"on average, {rate:.1f} per second.", file=out)

    if malformed:
        print(f"  {malformed} were not valid JPEGs. The app decodes these with", file=out)
        print("  a strict parser and will show a broken stream.", file=out)

    if rate < 5:
        print("  Under five frames a second the live view will look like a", file=out)
        print("  slideshow. Lower the resolution if that matters; snapshots on", file=out)
        print("  motion do not care.", file=out)

    if len(set(sizes)) == 1:
        print("  Every frame was exactly the same size, which a real scene does", file=out)
        print("  not do. The camera may be returning a test pattern — check the", file=out)
        print("  lens cap and the ribbon.", file=out)

    if save_to and last:
        try:
            with open(save_to, "wb") as image:
                image.write(last)
            print(f"\n  Last frame written to {save_to}", file=out)
            print("  Look at it. A camera can work perfectly and be pointing at", file=out)
            print("  the ceiling, and nothing above would tell you.", file=out)
        except OSError as e:
            print(f"  Could not write {save_to}: {e}", file=out)
