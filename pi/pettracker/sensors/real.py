"""Real Raspberry Pi sensors.

Deliberately unfinished. Each class has the wiring, the library, and the traps
written down, but the bodies are stubs — filling them in is a job to do next to
the hardware, one sensor at a time, because the numbers involved (calibration
factor, servo angles, PIR pot positions) can only be found empirically.

Imports of Pi-only libraries happen inside the constructors, not at module
level, so this file can be imported and inspected on a laptop.

GPIO pins below are BCM numbering and are suggestions, not requirements — the
only real constraints are noted per sensor.
"""

import logging
import os
import threading
import time
from typing import List, Optional

from ..hx711 import HX711
from ..nmea import parse_gga
from .base import Camera, Feeder, LocationSensor, MotionSensor, Scale, SensorSet
from .camera import ReopeningCamera
from .gpio import open_input, open_output, open_servo

log = logging.getLogger(__name__)

#: Default for GpioMotionSensor's pin_input, meaning "go and claim the real pin".
#: A sentinel rather than None, because None is what open_input returns when no
#: backend could claim one — so it has to be passable, and has to mean "this
#: sensor has no pin". Conflating the two made a test that thought it was
#: checking an unavailable sensor quietly claim GPIO 22 on a real Pi and pass
#: for the wrong reason.
_CLAIM_THE_PIN = object()


class HX711Scale(Scale):
    """Load cell through an HX711 amplifier.

    Wiring: VCC to 5V, GND to GND, DT and SCK to any two free GPIOs. The
    protocol lives in :mod:`pettracker.hx711`; this class is the part that turns
    counts into grams, which is to say the part that needs calibrating.

    **The calibration factor cannot be guessed.** Counts per gram depend on the
    specific cell, the amplifier gain, and how the bowl sits on it. Find it once:

        1. Empty the bowl and run ``run.py --check-scale``
        2. Press t to tare
        3. Put a known mass on it — a 100 g kitchen weight, or water, where
           1 ml is 1 g
        4. The tool prints the factor to pass as --scale-factor

    Re-do it if the bowl or its mounting changes. A factor of 1.0 means
    uncalibrated, and the readings are then raw counts wearing a gram label —
    the meal detector would report a nudge of the bowl as a 40,000 g meal.
    """

    #: What the factor is when nobody has calibrated anything.
    UNCALIBRATED = 1.0

    #: How recently the amplifier must have answered to count as available. The
    #: monitor reads it several times a second while the service runs, so this
    #: only ever comes into play on a rig whose readings have stopped.
    READING_FRESHNESS_S = 10.0

    #: The least time between probe reads when nothing else is reading. Each one
    #: waits for the chip, so /health must not trigger one on every poll.
    PROBE_INTERVAL_S = 5.0

    def __init__(self, dout_pin: int = 5, sck_pin: int = 6,
                 calibration_factor: float = UNCALIBRATED, samples: int = 5,
                 gain: int = 128):
        self._calibration_factor = calibration_factor
        self._samples = samples
        self._offset = 0.0
        self._hx = None
        self._backend = "none"
        self._last_good_read = None
        self._last_probe = None

        # fast=True: this chip times out if the clock is held high for 60 µs,
        # and gpiozero can take that long to set a pin. See gpio._FAST_BACKENDS.
        dout = open_input(dout_pin, fast=True)
        sck = open_output(sck_pin, fast=True)
        if dout is None or sck is None:
            # One claimed and the other not would leave a pin held for nothing.
            if dout is not None:
                dout.close()
            if sck is not None:
                sck.close()
            log.warning("HX711 unavailable: could not claim DT=%d SCK=%d",
                        dout_pin, sck_pin)
            return

        self._hx = HX711(dout, sck, gain=gain)
        self._backend = dout.backend
        log.info("HX711 on DT=%d SCK=%d via %s", dout_pin, sck_pin, dout.backend)

        if calibration_factor == self.UNCALIBRATED:
            # Loud, because everything downstream will look like it is working:
            # readings arrive, the dashboard fills in, and every number on it is
            # counts pretending to be grams.
            log.warning("HX711 is uncalibrated — pass --scale-factor. "
                        "Until then every weight is raw counts, not grams.")

    def read_grams(self) -> Optional[float]:
        if self._hx is None:
            return None

        raw = self._hx.read_median(self._samples)
        if raw is not None:
            self._last_good_read = time.monotonic()
        if raw is None:
            # None, never 0. An amplifier that has stopped answering and a bowl
            # with nothing in it are different facts, and only one of them means
            # the pet has eaten everything.
            return None

        if not self._calibration_factor:
            return None
        return (raw - self._offset) / self._calibration_factor

    def tare(self) -> None:
        """Treats the current weight as zero.

        Averages more samples than a normal reading: this number is subtracted
        from every future one, so an outlier here is an outlier forever.
        """
        if self._hx is None:
            return

        raw = self._hx.read_median(max(self._samples * 3, 15))
        if raw is None:
            log.warning("could not tare — the HX711 is not returning readings")
            return
        self._offset = float(raw)
        log.info("tared at %d counts", raw)

    @property
    def chip(self):
        """The amplifier itself, for the bring-up tool's timing probe."""
        return self._hx

    @property
    def backend(self) -> str:
        return self._backend

    @property
    def offset(self) -> float:
        return self._offset

    @property
    def calibration_factor(self) -> float:
        return self._calibration_factor

    @property
    def calibrated(self) -> bool:
        return self._calibration_factor != self.UNCALIBRATED

    @property
    def claimed(self) -> bool:
        """Whether the two pins were opened.

        Distinct from :attr:`available`, and the distinction is the whole point:
        a dead amplifier claims its pins perfectly well. The bring-up tools want
        this one — there is nothing to diagnose if the pins never opened — while
        /health wants the other.
        """
        return self._hx is not None

    @property
    def available(self) -> bool:
        """Whether the amplifier is actually producing readings.

        It used to mean "the two GPIO pins were claimed", which stayed true
        through an amplifier killed by reversed power: /health reported a
        working scale for an afternoon while every reading was twenty-four zero
        bits. A sensor is available when it can do its job.
        """
        if self._hx is None:
            return False

        now = time.monotonic()
        if (self._last_good_read is not None
                and now - self._last_good_read <= self.READING_FRESHNESS_S):
            return True

        # Nothing has read it lately — the monitor is not running, or it has
        # stopped answering. One read settles it, rate limited because each one
        # waits on the chip and /health asks on every poll.
        if (self._last_probe is not None
                and now - self._last_probe < self.PROBE_INTERVAL_S):
            return False
        self._last_probe = now
        return self.read_raw() is not None

    def read_raw(self) -> Optional[int]:
        """Counts, before the offset and the factor. For calibration only."""
        if self._hx is None:
            return None
        raw = self._hx.read_median(self._samples)
        if raw is not None:
            self._last_good_read = time.monotonic()
        return raw

    def close(self) -> None:
        if self._hx is not None:
            self._hx.close()
            self._hx = None


class GpioMotionSensor(MotionSensor):
    """One HC-SR501 PIR, watching one room.

    Wiring: VCC to 5V, GND to GND, OUT to a GPIO. The output is 3.3V logic on
    these modules, so no divider is needed. Which GPIO does not matter; which
    library reads it does, and :mod:`pettracker.sensors.gpio` sorts that out.

    Two traps, both handled here rather than left to the reader:

    * **Warm-up.** A PIR settles for up to a minute after power-up and fires
      more or less constantly until it does. Those pulses are the sensor
      stabilising, not an animal, so :meth:`poll` reports nothing for the first
      ``warmup_s`` seconds. They are discarded rather than queued — replaying
      phantom motion once the warm-up ends would defeat the point.
    * **The two pots.** Sensitivity and hold time are set physically on the
      board, not in code. Hold time interacts with the debounce in
      :class:`~pettracker.monitor.Monitor`: turn the pot to its shortest hold
      and let the software debounce, or the two fight and one visit becomes
      either one event or fifty depending on which wins.

    ``run.py --check-motion`` reads :meth:`read_raw` in a loop and prints every
    sensor's state live, which is how you confirm that the sensor labelled
    "Kitchen" is the one that fires when you wave at the kitchen.
    """

    def __init__(self, room: str, pin: int, warmup_s: float = 60.0,
                 clock=time.monotonic, pin_input=_CLAIM_THE_PIN):
        """
        :param pin_input: omit it to claim ``pin`` for real. Pass an object with
            ``read()``/``close()`` to drive the logic from a test, or None to
            build a sensor that has no pin at all — which is what a Pi with a
            missing library or an unclaimable pin actually produces.
        """
        self._room = room
        self._pin = pin
        self._warmup_s = warmup_s
        self._clock = clock
        self._input = open_input(pin) if pin_input is _CLAIM_THE_PIN else pin_input
        self._started_at = clock()
        self._warm = warmup_s <= 0

        if self._input is None:
            log.warning("PIR '%s' unavailable on GPIO %d", room, pin)
        else:
            log.info("PIR '%s' on GPIO %d via %s", room, pin, self._input.backend)

    @property
    def room(self) -> str:
        return self._room

    @property
    def pin(self) -> int:
        return self._pin

    @property
    def backend(self) -> str:
        return self._input.backend if self._input is not None else "none"

    @property
    def warmup_remaining_s(self) -> float:
        """Seconds left before this sensor is believed. 0 once it is warm."""
        if self._warm:
            return 0.0
        return max(0.0, self._warmup_s - (self._clock() - self._started_at))

    def read_raw(self) -> bool:
        """The pin as it actually is, warm-up ignored.

        What the bring-up tool shows: during the first minute the useful
        question is "is this wire connected", not "did the cat move".
        """
        if self._input is None:
            return False
        try:
            return bool(self._input.read())
        except Exception as e:
            # A wire pulled out mid-run must not take the monitor thread down.
            log.warning("PIR '%s' read failed: %s", self._room, e)
            return False

    def poll(self) -> bool:
        high = self.read_raw()

        if not self._warm:
            if self.warmup_remaining_s > 0:
                return False
            self._warm = True
            log.info("PIR '%s' warmed up, now reporting", self._room)

        return high

    @property
    def available(self) -> bool:
        return self._input is not None

    def close(self) -> None:
        if self._input is not None:
            self._input.close()
            self._input = None


class ServoFeeder(Feeder):
    """An SG90 or MG90S driving the hopper gate.

    **Power the servo from its own 5 V supply, never from the Pi's 5 V pin.**
    An SG90 draws a few hundred milliamps moving and closer to a full amp
    stalled, which is exactly what happens when kibble jams the gate. That
    browns out the Pi, and a brown-out during an SD-card write corrupts the
    filesystem. Tie the grounds together, signal to a GPIO.

    **The three numbers here have to be measured, not guessed.** The open and
    closed angles depend on how the horn is mounted — servos only mount at 
    roughly 20-degree increments, so the useful angles are whatever the geometry
    gave you rather than a tidy 0 and 90. And the flow rate depends on the
    kibble: bigger pieces bridge the opening and fall in bursts.
    ``run.py --check-servo`` walks a servo through angles interactively so the
    first two can be read off the real gate; the third comes from opening it for
    a known time and weighing what fell.

    Approximate by nature, and that is fine. The servo controls how long the
    gate is open, not how much comes out — the load cell measures what actually
    landed, which is why the two are separate readings in the event log.
    """

    #: Sensible until measured: a quarter turn, and a flow rate that is more
    #: likely to under-dispense than to empty the hopper onto the floor.
    DEFAULT_OPEN_ANGLE = 90
    DEFAULT_CLOSED_ANGLE = 0
    DEFAULT_MS_PER_GRAM = 50.0

    #: Time for the gate itself to swing, at each end of a dispense. An SG90
    #: crosses 90 degrees in roughly 0.3 s unloaded and slower against kibble.
    TRAVEL_S = 0.5

    #: Refuse to hold the gate open longer than this in one go. A wrong
    #: milliseconds-per-gram is the likeliest mistake here, and the failure mode
    #: is the whole hopper on the floor.
    MAX_OPEN_S = 20.0

    def __init__(self, pin: int = 18,
                 open_angle: int = DEFAULT_OPEN_ANGLE,
                 closed_angle: int = DEFAULT_CLOSED_ANGLE,
                 ms_per_gram: float = DEFAULT_MS_PER_GRAM,
                 servo=_CLAIM_THE_PIN, travel_s: Optional[float] = None):
        """
        :param servo: omit it to claim ``pin`` for real. Pass an object with
            ``set_angle``/``release``/``close`` to drive the logic from a test,
            or None for a rig with no servo at all — which is what open_servo
            returns when it cannot claim the pin.
        """
        self._pin = pin
        self._travel_s = self.TRAVEL_S if travel_s is None else travel_s
        self._open_angle = open_angle
        self._closed_angle = closed_angle
        self._ms_per_gram = ms_per_gram
        # The same sentinel the PIR sensor uses, and for the same reason: None
        # is what open_servo returns when nothing could be claimed, so None has
        # to mean "no servo". Conflating the two made a test that believed it
        # was checking an absent servo claim GPIO 18 on the real Pi and move it.
        self._servo = open_servo(pin) if servo is _CLAIM_THE_PIN else servo

        if self._servo is not None:
            # Start from a known position. A servo powered up mid-travel holds
            # wherever it happened to be, which for a hopper gate may be open.
            self.move_to(closed_angle)

    def move_to(self, angle: float) -> None:
        """Sends the gate to an angle and lets go once it has arrived.

        Public because the bring-up tool drives it directly: finding the two
        angles is a matter of watching the real gate move to each one.
        """
        if self._servo is None:
            return
        self._servo.set_angle(angle)
        time.sleep(self._travel_s)
        # Released rather than held: a servo holding position against a spring
        # or a stiff hinge buzzes, warms up, and draws current all day.
        self._servo.release()

    def dispense(self, grams: int) -> None:
        """Opens the gate for as long as ``grams`` should take to fall.

        How much actually comes out is the load cell's business. This only
        opens a hole for a while, and the difference between the two is real
        rather than an error to be corrected.
        """
        if self._servo is None:
            raise RuntimeError("feeder unavailable")

        seconds = self.open_seconds(grams)
        log.info("opening the gate for %.1f s for %d g", seconds, grams)

        try:
            self._servo.set_angle(self._open_angle)
            time.sleep(self._travel_s + seconds)
        finally:
            # Always. An exception mid-dispense must not leave the hopper open,
            # which would empty it onto the floor and is the one failure here
            # with a cost beyond a missed meal.
            self.move_to(self._closed_angle)

    def open_seconds(self, grams: int) -> float:
        """How long the gate is held open for a request of ``grams``.

        Separate from :meth:`dispense` so the cap can be reasoned about — and
        tested — without anybody waiting twenty seconds for it.
        """
        return min(max(0, grams) * self._ms_per_gram / 1000.0, self.MAX_OPEN_S)

    @property
    def open_angle(self) -> int:
        return self._open_angle

    @property
    def closed_angle(self) -> int:
        return self._closed_angle

    @property
    def ms_per_gram(self) -> float:
        return self._ms_per_gram

    @property
    def pin(self) -> int:
        return self._pin

    @property
    def backend(self) -> str:
        return self._servo.backend if self._servo is not None else "none"

    @property
    def available(self) -> bool:
        # Claimed, not verified. A servo has no feedback wire: it cannot be
        # asked where it is or whether it moved, so unlike the scale there is no
        # better answer to give. Whether the gate actually opened shows up as a
        # DISPENSED reading from the load cell, or as its absence.
        return self._servo is not None

    def close(self) -> None:
        if self._servo is not None:
            self._servo.close()
            self._servo = None


class PaddleFeeder(Feeder):
    """A paddle wheel or pocketed drum, turned one pocket at a time.

    The alternative to a timed gate, and the better one for a positional servo.
    A gate meters food by how long a hole is open, which depends on how full the
    hopper is, how the kibble happens to be sitting and how much of it is
    bridging the opening. A pocket meters by volume: a pocket either passes the
    opening or it does not, and what falls is the same whether the hopper is
    full or nearly empty.

    So portioning becomes counting rather than timing. ``grams_per_cycle`` is
    measured once — cycle it ten times into a bowl, weigh, divide by ten — and
    from then on a request is rounded to a whole number of pockets, because a
    third of a pocket is not a thing this machine can do. That rounding is
    reported honestly: the load cell measures what actually landed, and the
    difference between what was asked for and what fell is real.

    Mechanically an SG90 is enough to turn a light wheel and nothing more. If a
    pocket ever catches a piece of kibble against the housing the servo will
    stall rather than shear it, which is why :attr:`SETTLE_S` pauses at the dump
    position — a moment for the pocket to empty is cheaper than a jam.
    """

    #: A wheel parks here and dumps at the other. Anything within the servo's
    #: travel works; keeping off the end stops means a jam cannot drive it into
    #: its own limit.
    DEFAULT_PARK_ANGLE = 30
    DEFAULT_DUMP_ANGLE = 150

    #: Until it is measured. Deliberately small, so a wrong value under-feeds
    #: rather than emptying the hopper.
    DEFAULT_GRAMS_PER_CYCLE = 5.0

    #: Pause at the dump position, for the pocket to actually empty. Kibble
    #: does not fall instantly and a wheel that snaps back takes some with it.
    SETTLE_S = 0.4

    #: Most pockets one request may turn. A grams_per_cycle that is wrong by an
    #: order of magnitude would otherwise run the wheel for several minutes.
    MAX_CYCLES = 20

    def __init__(self, pin: int = 18,
                 park_angle: int = DEFAULT_PARK_ANGLE,
                 dump_angle: int = DEFAULT_DUMP_ANGLE,
                 grams_per_cycle: float = DEFAULT_GRAMS_PER_CYCLE,
                 servo=_CLAIM_THE_PIN, travel_s: Optional[float] = None,
                 settle_s: Optional[float] = None):
        self._pin = pin
        self._park_angle = park_angle
        self._dump_angle = dump_angle
        self._grams_per_cycle = grams_per_cycle
        self._travel_s = ServoFeeder.TRAVEL_S if travel_s is None else travel_s
        self._settle_s = self.SETTLE_S if settle_s is None else settle_s
        self._servo = open_servo(pin) if servo is _CLAIM_THE_PIN else servo

        if self._servo is not None:
            self.move_to(park_angle)

    def move_to(self, angle: float) -> None:
        """Turns the wheel to an angle and lets go once it has arrived."""
        if self._servo is None:
            return
        self._servo.set_angle(angle)
        time.sleep(self._travel_s)
        self._servo.release()

    def cycles_for(self, grams: int) -> int:
        """Whole pockets for a request, rounded and capped.

        At least one for any positive request: asking for less than a pocket and
        getting nothing at all would look like a broken feeder, and the load cell
        will report what actually fell either way.
        """
        if grams <= 0 or self._grams_per_cycle <= 0:
            return 0
        cycles = int(round(grams / self._grams_per_cycle))
        return max(1, min(cycles, self.MAX_CYCLES))

    def dispense(self, grams: int) -> None:
        """Turns the wheel enough times to drop roughly ``grams``."""
        if self._servo is None:
            raise RuntimeError("feeder unavailable")

        cycles = self.cycles_for(grams)
        log.info("turning the paddle %d time(s) for %d g", cycles, grams)

        try:
            for _ in range(cycles):
                self._servo.set_angle(self._dump_angle)
                time.sleep(self._travel_s + self._settle_s)
                self._servo.set_angle(self._park_angle)
                time.sleep(self._travel_s)
        finally:
            # Parked whatever happened. A wheel abandoned at the dump position
            # is an open hole in the bottom of the hopper.
            self.move_to(self._park_angle)

    @property
    def grams_per_cycle(self) -> float:
        return self._grams_per_cycle

    @property
    def park_angle(self) -> int:
        return self._park_angle

    @property
    def dump_angle(self) -> int:
        return self._dump_angle

    @property
    def pin(self) -> int:
        return self._pin

    @property
    def backend(self) -> str:
        return self._servo.backend if self._servo is not None else "none"

    @property
    def available(self) -> bool:
        # Claimed, not verified, for the same reason as the gate: a hobby servo
        # has no feedback wire. What actually fell is the load cell's answer.
        return self._servo is not None

    def close(self) -> None:
        if self._servo is not None:
            self._servo.close()
            self._servo = None


class PiCamera(Camera):
    """CSI camera via picamera2.

    Raspberry Pi OS ships picamera2; it is not on PyPI in a usable form, so use
    the system package rather than pip.

    Only one process can hold the camera. If the stream is served by this
    service, nothing else may open it at the same time.
    """

    def __init__(self, width: int = 640, height: int = 480):
        self._camera = None
        try:
            from picamera2 import Picamera2  # type: ignore

            self._camera = Picamera2()
            config = self._camera.create_video_configuration(
                main={"size": (width, height)})
            self._camera.configure(config)
            self._camera.start()
            log.info("Pi camera at %dx%d", width, height)
        except Exception as e:
            log.warning("Pi camera unavailable: %s", e)

    def read_jpeg(self) -> Optional[bytes]:
        if self._camera is None:
            return None
        raise NotImplementedError(
            "Capture a frame and encode it as JPEG. picamera2's JpegEncoder "
            "with a FileOutput into a BytesIO is the usual route; capturing an "
            "array and encoding with PIL works but is much slower."
        )

    @property
    def available(self) -> bool:
        # False while read_jpeg() is a stub. The device opens perfectly well and
        # then produces nothing, which would show as a camera that is present
        # and serving an empty stream.
        # Make this `return self._camera is not None` once read_jpeg() is written.
        return False

    def close(self) -> None:
        if self._camera is not None:
            try:
                self._camera.stop()
            except Exception:
                pass
            self._camera = None


class UsbCamera(Camera):
    """USB webcam via OpenCV.

    An alternative to the CSI camera, not an addition: one MJPEG endpoint
    serves one camera. To use both, run a second instance on another port and
    give the app a second stream URL.
    """

    def __init__(self, device_index: int = 0, width: int = 640, height: int = 480):
        self._capture = None
        # The monitor thread now takes snapshots while an HTTP thread may be
        # serving /stream, and a VideoCapture is not safe for two readers: the
        # frames interleave, and the driver can return a half-decoded buffer.
        # One reader at a time; a frame takes tens of milliseconds, so nothing
        # waits long.
        self._lock = threading.Lock()
        try:
            import cv2  # type: ignore

            self._capture = cv2.VideoCapture(device_index)
            self._capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
            self._capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
            if not self._capture.isOpened():
                raise RuntimeError(f"could not open video device {device_index}")
            self._cv2 = cv2
            log.info("USB camera %d at %dx%d", device_index, width, height)
        except Exception as e:
            log.warning("USB camera unavailable: %s", e)
            self._capture = None

    def read_jpeg(self) -> Optional[bytes]:
        if self._capture is None:
            return None
        with self._lock:
            if self._capture is None:          # closed while we waited
                return None
            ok, frame = self._capture.read()
        if not ok:
            return None
        # Encoding is pure CPU on a copy of the frame, so it happens outside the
        # lock — a 10 fps stream would otherwise hold the camera most of the time
        # and starve the snapshot.
        ok, buffer = self._cv2.imencode(".jpg", frame)
        return buffer.tobytes() if ok else None

    @property
    def available(self) -> bool:
        return self._capture is not None

    def close(self) -> None:
        with self._lock:
            if self._capture is not None:
                self._capture.release()
                self._capture = None


class UbloxLocationSensor(LocationSensor):
    """A u-blox GNSS module over the Pi's serial port.

    Written against a NEO-6M and tested on a NEO-M8N; the two speak the same
    NMEA at the same default 9600 baud, so nothing here distinguishes them. The
    visible difference is that an M8N tracks several constellations at once and
    therefore talks in ``$GN`` sentences rather than ``$GP`` ones, with ``$GL``
    for the GLONASS satellites it can see. Both are parsed.

    One consequence worth knowing: an M8N at 9600 baud with every constellation
    enabled produces a great deal of traffic — GGA, GSA, GSV, GLL, RMC and VTG
    for each system, once a second — and 9600 baud is not far off the limit. If
    sentences ever start arriving truncated, that is the reason, and the fix is
    a higher baud rate rather than a faster Pi.

    Setup, in this order:

    1. ``sudo raspi-config`` -> Interface Options -> Serial Port -> login shell
       **no**, hardware serial **yes**, then reboot. Without this the kernel
       console owns the port and answers your reads with its own noise.
    2. Use ``/dev/serial0``, which points at whichever UART is right for the
       board revision. ``/dev/ttyAMA0`` is wrong on a Pi 4 by default, because
       there the good UART is on Bluetooth.
    3. Wiring is crossed: module TX to Pi RX (GPIO 15, physical pin 10), module
       RX to Pi TX (GPIO 14, physical pin 8). 3.3V power and a shared ground.

    **It will not get a fix indoors.** A cold start needs several minutes of
    open sky, and the module reports no fix until then — which is why
    :meth:`read_fix` returns None rather than zeros, and why
    :attr:`available` asks a different question: whether the module is talking
    at all. A GPS on a windowsill sending sentences and reporting no position is
    working correctly, and the two states must not look the same.

    Read with :mod:`termios` rather than pyserial: a serial port at a fixed baud
    rate is thirty lines of standard library, and the alternative is a package to
    install on every rig.

    Worth restating: this measures where the **Pi** is. For a feeder on a kitchen
    floor that is a constant. The PIR sensors are what answer "which room".
    """

    #: How recently a sentence must have arrived for the module to count as
    #: present. It sends several a second, so this is generous.
    TALKING_FRESHNESS_S = 10.0

    def __init__(self, port: str = "/dev/serial0", baudrate: int = 9600,
                 clock=time.monotonic, opener=None):
        """
        :param opener: makes the file descriptor, for tests. The default opens
            and configures the real port; a test hands over the read end of a
            pipe, which exercises the same os.read path with no hardware.
        """
        self._port_path = port
        self._fd = None
        self._buffer = b""
        self._clock = clock
        self._last_sentence_at = None
        self._last_fix = None

        try:
            self._fd = (opener or _open_serial)(port, baudrate)
            log.info("GPS on %s at %d baud", port, baudrate)
        except Exception as e:
            # The commonest cause by far is the serial console still owning the
            # port, which raises here rather than misbehaving later.
            log.warning("GPS unavailable: %s", e)

    def read_fix(self) -> Optional[dict]:
        """The most recent position, or None if the module has no fix.

        None is the normal answer indoors and for the first minutes outdoors.
        See :attr:`available` for whether the module is there at all.
        """
        for sentence in self.read_sentences():
            fix = parse_gga(sentence)
            if fix is not None:
                self._last_fix = fix
        return self._last_fix

    def read_sentences(self):
        """Whatever complete NMEA lines have arrived since the last call.

        Also what the bring-up tool prints: seeing the raw sentences is how you
        tell "no fix yet" from "nothing is connected", and the two look
        identical from :meth:`read_fix`.
        """
        if self._fd is None:
            return []

        try:
            chunk = os.read(self._fd, 4096)
        except BlockingIOError:
            chunk = b""
        except OSError as e:
            log.warning("GPS read failed: %s", e)
            return []

        if chunk:
            self._buffer += chunk
            # A module left talking to nobody would grow this without limit.
            # One second of 9600 baud is about 1 kB.
            if len(self._buffer) > 8192:
                self._buffer = self._buffer[-4096:]

        newline = b"\n"
        sentences = []
        while newline in self._buffer:
            line, _, self._buffer = self._buffer.partition(newline)
            text = line.decode("ascii", errors="replace").strip()
            if text:
                sentences.append(text)
                self._last_sentence_at = self._clock()
        return sentences

    @property
    def available(self) -> bool:
        """Whether the module is sending sentences, not whether it has a fix.

        Those are different facts and only one of them is about the hardware. A
        GPS indoors talks constantly and knows nothing, which is a working GPS.
        """
        if self._fd is None:
            return False
        if self._last_sentence_at is None:
            self.read_sentences()          # nothing has read it yet
        return (self._last_sentence_at is not None
                and self._clock() - self._last_sentence_at
                <= self.TALKING_FRESHNESS_S)

    @property
    def port(self) -> str:
        return self._port_path

    def close(self) -> None:
        if self._fd is not None:
            try:
                os.close(self._fd)
            except OSError:
                pass
            self._fd = None


def _open_serial(path: str, baudrate: int) -> int:
    """Opens a serial port for reading, configured raw at ``baudrate``.

    Non-blocking, because the service polls: a blocking read on a GPS that has
    been unplugged would hold the monitor thread and stop the motion sensors
    with it.
    """
    import termios

    speeds = {4800: termios.B4800, 9600: termios.B9600,
              19200: termios.B19200, 38400: termios.B38400,
              57600: termios.B57600, 115200: termios.B115200}
    if baudrate not in speeds:
        raise ValueError(f"unsupported baud rate {baudrate}")

    fd = os.open(path, os.O_RDONLY | os.O_NOCTTY | os.O_NONBLOCK)
    try:
        attributes = termios.tcgetattr(fd)
        # Raw: no echo, no line discipline, no character translation. The
        # defaults are meant for a terminal, and would eat and rewrite bytes.
        attributes[0] = 0                                    # iflag
        attributes[1] = 0                                    # oflag
        attributes[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
        attributes[3] = 0                                    # lflag
        attributes[4] = speeds[baudrate]                     # ispeed
        attributes[5] = speeds[baudrate]                     # ospeed
        attributes[6][termios.VMIN] = 0
        attributes[6][termios.VTIME] = 0
        termios.tcsetattr(fd, termios.TCSANOW, attributes)
    except Exception:
        os.close(fd)
        raise
    return fd


#: One PIR per room, in the order --rooms lists them. BCM numbering. These three
#: are free on every Pi header revision and avoid the pins with special jobs at
#: boot (0/1 are the ID EEPROM, 2/3 have fixed pull-ups, 14/15 are the UART).
DEFAULT_PIR_PINS = [17, 27, 22]

#: HX711 data and clock, BCM numbering. Any two free pins will do; these avoid
#: the SPI and I2C peripherals in case something else on the rig wants them.
DEFAULT_SCALE_PINS = [5, 6]


def parse_pins(spec: str) -> List[int]:
    """Turns ``"17,27,22"`` into ``[17, 27, 22]``.

    Raises ValueError with a usable message rather than letting a typo become a
    sensor that silently never fires.
    """
    pins = []
    for piece in spec.split(","):
        piece = piece.strip()
        if not piece:
            continue
        try:
            pin = int(piece)
        except ValueError:
            raise ValueError(f"'{piece}' is not a GPIO number") from None
        if not 2 <= pin <= 27:
            # BCM numbering on a 40-pin header. 0 and 1 are the ID EEPROM and
            # must not be claimed.
            raise ValueError(f"GPIO {pin} is not a usable header pin (expected 2-27)")
        pins.append(pin)
    return pins


def pin_map(rooms: List[str], pins: List[int]) -> List[tuple]:
    """Pairs room names with pins, complaining about the mistakes that matter.

    A room list and a pin list of different lengths is the easiest way to bring
    up "Hall" and quietly never read it, because :func:`zip` would drop the
    extra silently.
    """
    if len(rooms) != len(pins):
        raise ValueError(
            f"{len(rooms)} room(s) but {len(pins)} pin(s): "
            "give one --pir-pins entry per --rooms entry"
        )
    duplicates = {p for p in pins if pins.count(p) > 1}
    if duplicates:
        raise ValueError(
            "the same GPIO cannot watch two rooms: "
            + ", ".join(str(p) for p in sorted(duplicates))
        )
    return list(zip(rooms, pins))


def build_motion_sensors(rooms: Optional[List[str]] = None,
                         pins: Optional[List[int]] = None,
                         warmup_s: float = 60.0) -> List[GpioMotionSensor]:
    """The PIR sensors on their own, for the service and for bring-up."""
    if rooms is None:
        rooms = ["Kitchen", "Living room", "Hall"]
    if pins is None:
        pins = DEFAULT_PIR_PINS[:len(rooms)]

    return [
        GpioMotionSensor(room, pin, warmup_s=warmup_s)
        for room, pin in pin_map(rooms, pins)
    ]


class NoCamera(Camera):
    """A rig with no camera at all.

    Not a failure state: a Pi doing feeder and motion duty has no reason to hold
    a video device open, and "none" has to be sayable without one of the real
    camera classes logging a warning about hardware nobody asked for.
    """

    def read_jpeg(self) -> Optional[bytes]:
        return None

    @property
    def available(self) -> bool:
        return False


#: What --feeder accepts. A gate meters by time, a paddle by whole pockets.
FEEDER_KINDS = ("gate", "paddle")


#: What --camera accepts. One camera per service: the CSI and USB devices cannot
#: share a single /stream, so this is a choice rather than a list.
CAMERA_KINDS = ("csi", "usb", "none")


def build_camera(kind: str = "csi", device_index: int = 0,
                 retry_interval_s: float = 10.0) -> Camera:
    """The camera named, wrapped so it recovers on its own.

    The wrapper is the point: a device that is not there at startup — because
    the Pi beat USB enumeration to it, or because somebody was holding it — used
    to stay missing until the service was restarted by hand, taking the stream
    and every motion snapshot with it. See
    :class:`~pettracker.sensors.camera.ReopeningCamera`.

    :param kind: one of :data:`CAMERA_KINDS`
    :param device_index: which /dev/videoN, for a USB camera
    """
    if kind not in CAMERA_KINDS:
        raise ValueError(
            f"unknown camera '{kind}' (expected one of {', '.join(CAMERA_KINDS)})")

    if kind == "none":
        # Nothing to reopen, and nothing that could appear later.
        return NoCamera()

    def factory() -> Camera:
        return (UsbCamera(device_index=device_index) if kind == "usb"
                else PiCamera())

    return ReopeningCamera(factory, retry_interval_s=retry_interval_s)


def probe_scale_pins(dout_pin: int, sck_pin: int):
    """Tries the pins both ways round and returns the pair that reads.

    Swapping DT and SCK is the easiest mistake to make with this module and the
    hardest to see: the pin being read is then wired to the amplifier's clock
    input, which nothing drives, so it floats — noise with no pull, low with a
    pull-down, and twenty-four zero bits when read. That is indistinguishable
    from a dead amplifier unless something actually tries the other order.

    :returns: (dout, sck) that produced a reading, or None if neither did
    """
    for candidate in ((dout_pin, sck_pin), (sck_pin, dout_pin)):
        scale = HX711Scale(dout_pin=candidate[0], sck_pin=candidate[1])
        try:
            if scale.claimed and scale.read_raw() is not None:
                return candidate
        finally:
            scale.close()
    return None


def build_real_sensor_set(rooms: Optional[List[str]] = None,
                          pir_pins: Optional[List[int]] = None,
                          pir_warmup_s: float = 60.0,
                          camera: str = "csi",
                          camera_device: int = 0,
                          scale_pins: Optional[List[int]] = None,
                          scale_factor: float = HX711Scale.UNCALIBRATED,
                          gps_port: Optional[str] = "/dev/serial0",
                          feeder: Optional[Feeder] = None) -> SensorSet:
    """Wires up the real rig.

    Every sensor reports itself unavailable rather than raising if its hardware
    is missing, so the service still starts with one thing unplugged and
    /health says which. That is what makes it possible to bring the sensors up
    one at a time.

    :param pir_warmup_s: how long to ignore the PIRs for. The warm-up belongs to
        the *sensor's* power-up, not to this process, so a service restart on a
        rig that has been powered for hours can pass 0 and lose nothing.
    :param camera: which camera this Pi has — see :func:`build_camera`. It was
        previously always the CSI one, which meant a USB camera could be plugged
        in, working, and still reported as absent.
    """
    dout, sck = (scale_pins or DEFAULT_SCALE_PINS)
    return SensorSet(
        scale=HX711Scale(dout_pin=dout, sck_pin=sck,
                         calibration_factor=scale_factor),
        motion_sensors=build_motion_sensors(rooms, pir_pins, warmup_s=pir_warmup_s),
        feeder=(feeder if feeder is not None else ServoFeeder()),
        camera=build_camera(camera, camera_device),
        location=(UbloxLocationSensor(port=gps_port) if gps_port else None),
    )
