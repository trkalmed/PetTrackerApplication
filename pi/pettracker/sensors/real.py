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
import threading
import time
from typing import List, Optional

from ..hx711 import HX711
from .base import Camera, Feeder, LocationSensor, MotionSensor, Scale, SensorSet
from .gpio import open_input, open_output

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

    def __init__(self, dout_pin: int = 5, sck_pin: int = 6,
                 calibration_factor: float = UNCALIBRATED, samples: int = 5,
                 gain: int = 128):
        self._calibration_factor = calibration_factor
        self._samples = samples
        self._offset = 0.0
        self._hx = None
        self._backend = "none"

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

    def read_raw(self) -> Optional[int]:
        """Counts, before the offset and the factor. For calibration only."""
        return self._hx.read_median(self._samples) if self._hx else None

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
    def available(self) -> bool:
        return self._hx is not None

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
    """SG90 or MG90S driving the hopper gate.

    **Power the servo from its own 5V supply, never from the Pi's 5V pin.**
    Stall and start-up current spikes brown out the Pi, and a brown-out during
    an SD-card write corrupts the filesystem. Tie the grounds together; signal
    goes to a GPIO. This is the single most likely cause of a Pi that "randomly
    reboots" in this build.

    Software PWM from RPi.GPIO jitters, which makes cheap servos buzz and creep.
    pigpio's hardware-timed PWM is steadier and worth the extra daemon.

    Open angle, closed angle and the milliseconds-per-gram rate all have to be
    measured with the real hopper and the real food — kibble size changes the
    flow rate.
    """

    def __init__(self, pin: int = 18, open_angle: int = 90, closed_angle: int = 0,
                 ms_per_gram: float = 50.0):
        self._pin = pin
        self._open_angle = open_angle
        self._closed_angle = closed_angle
        self._ms_per_gram = ms_per_gram
        self._pwm = None
        try:
            import RPi.GPIO as GPIO  # type: ignore

            GPIO.setmode(GPIO.BCM)
            GPIO.setup(pin, GPIO.OUT)
            self._pwm = GPIO.PWM(pin, 50)      # 50 Hz, standard for hobby servos
            self._pwm.start(0)
            log.info("servo on GPIO %d", pin)
        except Exception as e:
            log.warning("servo unavailable: %s", e)

    def dispense(self, grams: int) -> None:
        if self._pwm is None:
            raise RuntimeError("feeder unavailable")
        raise NotImplementedError(
            "Open the gate, hold for grams * ms_per_gram, close it. Always "
            "close in a finally block: an exception mid-dispense must not leave "
            "the hopper open. Detach the PWM signal when idle so the servo is "
            "not held under load, which is what makes them buzz and overheat."
        )

    @property
    def available(self) -> bool:
        return self._pwm is not None


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
        return self._camera is not None

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


class Neo6mLocationSensor(LocationSensor):
    """NEO-6M GPS over the Pi's serial port.

    Setup, in this order:

    1. ``sudo raspi-config`` → Interface Options → Serial Port → login shell
       **no**, hardware serial **yes**. Without this the kernel console owns the
       port and the module's data is unreadable.
    2. Use ``/dev/serial0``, which points at whichever UART is right for the
       board revision. ``/dev/ttyAMA0`` is wrong on a Pi 4 by default.
    3. Wiring is crossed: module TX to Pi RX, module RX to Pi TX. 3.3V power.

    It will not get a fix indoors. Cold start outdoors takes minutes, and the
    module reports no fix until then — which is why read_fix returns None
    rather than zeros.

    Worth restating: this measures where the *Pi* is. For a feeder on a kitchen
    floor that is a constant. It is the PIR sensors that answer "which room is
    the cat in".
    """

    def __init__(self, port: str = "/dev/serial0", baudrate: int = 9600):
        self._serial = None
        try:
            import serial  # type: ignore

            self._serial = serial.Serial(port, baudrate, timeout=1)
            log.info("GPS on %s", port)
        except Exception as e:
            log.warning("GPS unavailable: %s", e)

    def read_fix(self) -> Optional[dict]:
        if self._serial is None:
            return None
        raise NotImplementedError(
            "Read NMEA lines and parse $GPGGA (or $GNGGA). Field 6 is the fix "
            "quality: 0 means no fix, and the coordinates in that case are "
            "zeros that must not be reported as a position off West Africa. "
            "Field 7 is the satellite count."
        )

    @property
    def available(self) -> bool:
        return self._serial is not None


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


#: What --camera accepts. One camera per service: the CSI and USB devices cannot
#: share a single /stream, so this is a choice rather than a list.
CAMERA_KINDS = ("csi", "usb", "none")


def build_camera(kind: str = "csi", device_index: int = 0) -> Camera:
    """The camera named, or a stub if the rig has none.

    :param kind: one of :data:`CAMERA_KINDS`
    :param device_index: which /dev/videoN, for a USB camera
    """
    if kind not in CAMERA_KINDS:
        raise ValueError(
            f"unknown camera '{kind}' (expected one of {', '.join(CAMERA_KINDS)})")

    if kind == "usb":
        return UsbCamera(device_index=device_index)
    if kind == "none":
        return NoCamera()
    return PiCamera()


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
            if scale.available and scale.read_raw() is not None:
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
                          scale_factor: float = HX711Scale.UNCALIBRATED) -> SensorSet:
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
        feeder=ServoFeeder(),
        camera=build_camera(camera, camera_device),
        location=Neo6mLocationSensor(),
    )
