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
from typing import List, Optional

from .base import Camera, Feeder, LocationSensor, MotionSensor, Scale, SensorSet

log = logging.getLogger(__name__)


class HX711Scale(Scale):
    """Load cell through an HX711 amplifier.

    Wiring: VCC to 5V, GND to GND, DT and SCK to any two free GPIOs.

    The number that matters is the **calibration factor**, and it cannot be
    guessed: raw counts per gram depend on the specific cell, the amplifier
    gain, and how the bowl sits on it. Find it once with a known weight —

        1. Empty the bowl, call tare()
        2. Put a known mass on it (a 100 g kitchen weight, or water: 1 ml = 1 g)
        3. factor = raw_reading / known_grams

    Re-do it if the bowl or mounting changes. Store it in the config rather than
    hard-coding it here.

    The HX711 also needs settling time after power-up, and its readings are
    noisy enough that a median of several samples beats a single read.
    """

    def __init__(self, dout_pin: int = 5, sck_pin: int = 6,
                 calibration_factor: float = 1.0, samples: int = 5):
        self._calibration_factor = calibration_factor
        self._samples = samples
        self._offset = 0.0
        self._hx = None
        try:
            # pip install hx711 (or vendor one of the several small drivers)
            from hx711 import HX711  # type: ignore

            self._hx = HX711(dout_pin=dout_pin, pd_sck_pin=sck_pin)
            log.info("HX711 on DT=%d SCK=%d", dout_pin, sck_pin)
        except Exception as e:
            log.warning("HX711 unavailable: %s", e)

    def read_grams(self) -> Optional[float]:
        if self._hx is None:
            return None
        raise NotImplementedError(
            "Read `samples` raw values, take the median, subtract the offset, "
            "divide by the calibration factor. Return None if the read fails "
            "rather than 0 — an empty bowl and a broken amplifier must not "
            "look the same."
        )

    def tare(self) -> None:
        if self._hx is None:
            return
        raise NotImplementedError("Average several raw reads into self._offset.")

    @property
    def available(self) -> bool:
        return self._hx is not None


class GpioMotionSensor(MotionSensor):
    """One HC-SR501 PIR.

    Wiring: VCC to 5V, GND to GND, OUT to a GPIO. The output is 3.3V logic on
    these modules, so no divider is needed.

    Two traps:

    * **Warm-up.** They settle for up to 60 s after power-up and fire
      constantly until they do. Ignore everything for the first minute after
      boot or the log fills with phantom motion.
    * **The two pots.** Sensitivity and hold time are set physically on the
      board, not in code. Hold time in particular interacts with the debounce in
      Monitor — set the pot short and let the software do the debouncing, or the
      two fight each other.
    """

    def __init__(self, room: str, pin: int):
        self._room = room
        self._pin = pin
        self._gpio = None
        try:
            import RPi.GPIO as GPIO  # type: ignore

            GPIO.setmode(GPIO.BCM)
            GPIO.setup(pin, GPIO.IN)
            self._gpio = GPIO
            log.info("PIR '%s' on GPIO %d", room, pin)
        except Exception as e:
            log.warning("PIR '%s' unavailable: %s", room, e)

    @property
    def room(self) -> str:
        return self._room

    def poll(self) -> bool:
        if self._gpio is None:
            return False
        return bool(self._gpio.input(self._pin))

    @property
    def available(self) -> bool:
        return self._gpio is not None


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
        ok, frame = self._capture.read()
        if not ok:
            return None
        ok, buffer = self._cv2.imencode(".jpg", frame)
        return buffer.tobytes() if ok else None

    @property
    def available(self) -> bool:
        return self._capture is not None

    def close(self) -> None:
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


def build_real_sensor_set(rooms: Optional[List[str]] = None,
                          pir_pins: Optional[List[int]] = None) -> SensorSet:
    """Wires up the real rig.

    Every sensor reports itself unavailable rather than raising if its hardware
    is missing, so the service still starts with one thing unplugged and
    /health says which. That is what makes it possible to bring the sensors up
    one at a time.
    """
    if rooms is None:
        rooms = ["Kitchen", "Living room", "Hall"]
    if pir_pins is None:
        pir_pins = [17, 27, 22]

    return SensorSet(
        scale=HX711Scale(),
        motion_sensors=[
            GpioMotionSensor(room, pin) for room, pin in zip(rooms, pir_pins)
        ],
        feeder=ServoFeeder(),
        camera=PiCamera(),
        location=Neo6mLocationSensor(),
    )
