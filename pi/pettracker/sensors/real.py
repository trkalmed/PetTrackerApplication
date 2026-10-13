"""Real Raspberry Pi sensors."""

import io
from collections import OrderedDict
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

_CLAIM_THE_PIN = object()


class HX711Scale(Scale):
    UNCALIBRATED = 1.0

    READING_FRESHNESS_S = 10.0

    PROBE_INTERVAL_S = 5.0

    def __init__(self, dout_pin: int = 5, sck_pin: int = 6,
                 calibration_factor: float = UNCALIBRATED, samples: int = 5,
                 gain: int = 128, tare_path: Optional[str] = None):
        self._calibration_factor = calibration_factor
        self._samples = samples
        self._tare_path = tare_path
        self._offset = self._load_offset()
        self._hx = None
        self._backend = "none"
        self._last_good_read = None
        self._last_probe = None

        # fast=True: the HX711 powers down if the clock stays high for 60 us.
        dout = open_input(dout_pin, fast=True)
        sck = open_output(sck_pin, fast=True)
        if dout is None or sck is None:
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
            log.warning("HX711 is uncalibrated — pass --scale-factor. "
                        "Until then every weight is raw counts, not grams.")

    def read_grams(self) -> Optional[float]:
        if self._hx is None:
            return None

        raw = self._hx.read_median(self._samples)
        if raw is not None:
            self._last_good_read = time.monotonic()
        if raw is None:
            # None, never 0: a silent amplifier is not an empty bowl.
            return None

        if not self._calibration_factor:
            return None
        return (raw - self._offset) / self._calibration_factor

    def tare(self) -> None:
        if self._hx is None:
            return

        raw = self._hx.read_median(max(self._samples * 3, 15))
        if raw is None:
            log.warning("could not tare — the HX711 is not returning readings")
            return
        self._offset = float(raw)
        self._save_offset(self._offset)
        log.info("tared at %d counts", raw)

    def _load_offset(self) -> float:
        if not self._tare_path:
            return 0.0
        try:
            with open(self._tare_path) as handle:
                return float(handle.read().strip())
        except (OSError, ValueError):
            return 0.0

    def _save_offset(self, offset: float) -> None:
        if not self._tare_path:
            return
        temporary = self._tare_path + ".part"
        try:
            with open(temporary, "w") as handle:
                handle.write(repr(float(offset)))
            os.replace(temporary, self._tare_path)
        except OSError as e:
            log.warning("could not save the tare offset: %s", e)

    @property
    def chip(self):
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
        return self._hx is not None

    @property
    def available(self) -> bool:
        if self._hx is None:
            return False

        now = time.monotonic()
        if (self._last_good_read is not None
                and now - self._last_good_read <= self.READING_FRESHNESS_S):
            return True

        if (self._last_probe is not None
                and now - self._last_probe < self.PROBE_INTERVAL_S):
            return False
        self._last_probe = now
        return self.read_raw() is not None

    def read_raw(self) -> Optional[int]:
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
    def __init__(self, room: str, pin: int, warmup_s: float = 60.0,
                 clock=time.monotonic, pin_input=_CLAIM_THE_PIN):
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
        if self._warm:
            return 0.0
        return max(0.0, self._warmup_s - (self._clock() - self._started_at))

    def read_raw(self) -> bool:
        if self._input is None:
            return False
        try:
            return bool(self._input.read())
        except Exception as e:
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
    DEFAULT_OPEN_ANGLE = 90
    DEFAULT_CLOSED_ANGLE = 20
    DEFAULT_MS_PER_GRAM = 50.0

    TRAVEL_S = 0.5

    MAX_OPEN_S = 20.0

    def __init__(self, pin: int = 18,
                 open_angle: int = DEFAULT_OPEN_ANGLE,
                 closed_angle: int = DEFAULT_CLOSED_ANGLE,
                 ms_per_gram: float = DEFAULT_MS_PER_GRAM,
                 servo=_CLAIM_THE_PIN, travel_s: Optional[float] = None):
        self._pin = pin
        self._travel_s = self.TRAVEL_S if travel_s is None else travel_s
        self._open_angle = open_angle
        self._closed_angle = closed_angle
        self._ms_per_gram = ms_per_gram
        self._servo = open_servo(pin) if servo is _CLAIM_THE_PIN else servo

        if self._servo is not None:
            # Start closed: a servo powered up mid travel may hold the gate open.
            self.move_to(closed_angle)

    def move_to(self, angle: float) -> None:
        if self._servo is None:
            return
        self._servo.set_angle(angle)
        time.sleep(self._travel_s)
        self._servo.release()

    def dispense(self, grams: int) -> None:
        if self._servo is None:
            raise RuntimeError("feeder unavailable")

        seconds = self.open_seconds(grams)
        log.info("opening the gate for %.1f s for %d g", seconds, grams)

        try:
            self._servo.set_angle(self._open_angle)
            time.sleep(self._travel_s + seconds)
        finally:
            # Always close, even if dispensing raised.
            self.move_to(self._closed_angle)

    kind = "gate"

    def open_seconds(self, grams: int) -> float:
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
        # Claimed, not verified: the servo has no feedback. The load cell measures what fell.
        return self._servo is not None

    def close(self) -> None:
        if self._servo is not None:
            self._servo.close()
            self._servo = None


class PaddleFeeder(Feeder):
    DEFAULT_PARK_ANGLE = 30
    DEFAULT_DUMP_ANGLE = 150

    # Measured on this rig: a pocket drops 10 to 15 g.
    DEFAULT_GRAMS_PER_CYCLE = 12.5

    SETTLE_S = 0.4

    # Upper bound on pockets per request.
    MAX_CYCLES = 20

    def __init__(self, pin: int = 18,
                 park_angle: int = DEFAULT_PARK_ANGLE,
                 dump_angle: int = DEFAULT_DUMP_ANGLE,
                 grams_per_cycle: float = DEFAULT_GRAMS_PER_CYCLE,
                 servo=_CLAIM_THE_PIN, travel_s: Optional[float] = None,
                 settle_s: Optional[float] = None,
                 sweep: Optional[List[float]] = None,
                 min_pulse_us: Optional[int] = None,
                 max_pulse_us: Optional[int] = None):
        self._pin = pin
        self._park_angle = park_angle
        self._dump_angle = dump_angle
        self._grams_per_cycle = grams_per_cycle
        self._travel_s = ServoFeeder.TRAVEL_S if travel_s is None else travel_s
        self._settle_s = self.SETTLE_S if settle_s is None else settle_s
        self._sweep = list(sweep) if sweep else None
        self._rest_angle = self._sweep[-1] if self._sweep else park_angle
        self._servo = (open_servo(pin, min_pulse_us, max_pulse_us)
                       if servo is _CLAIM_THE_PIN else servo)

        if self._servo is not None:
            self.move_to(self._rest_angle)

    def move_to(self, angle: float) -> None:
        if self._servo is None:
            return
        self._servo.set_angle(angle)
        time.sleep(self._travel_s)
        self._servo.release()

    def cycles_for(self, grams: int) -> int:
        if grams <= 0 or self._grams_per_cycle <= 0:
            return 0
        cycles = int(round(grams / self._grams_per_cycle))
        return max(1, min(cycles, self.MAX_CYCLES))

    def dispense(self, grams: int) -> None:
        if self._servo is None:
            raise RuntimeError("feeder unavailable")

        cycles = self.cycles_for(grams)
        log.info("turning the paddle %d time(s) for %d g", cycles, grams)

        sweep = self._sweep or [self._dump_angle, self._park_angle]
        try:
            for _ in range(cycles):
                for index, angle in enumerate(sweep):
                    self._servo.set_angle(angle)
                    dwell = self._travel_s
                    if angle == max(sweep):
                        dwell += self._settle_s
                    time.sleep(dwell)
        finally:
            # Always park, so the wheel never stops open.
            self.move_to(self._rest_angle)

    kind = "paddle"

    @property
    def grams_per_cycle(self) -> float:
        return self._grams_per_cycle

    @property
    def park_angle(self) -> int:
        return self._park_angle

    @property
    def rest_angle(self) -> float:
        return self._rest_angle

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
        # Claimed, not verified: the servo has no feedback. The load cell measures what fell.
        return self._servo is not None

    def close(self) -> None:
        if self._servo is not None:
            self._servo.close()
            self._servo = None


class PiCamera(Camera):
    def __init__(self, width: int = 640, height: int = 480):
        self._camera = None
        self._lock = threading.Lock()
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

        buffer = io.BytesIO()
        try:
            with self._lock:
                if self._camera is None:
                    return None
                self._camera.capture_file(buffer, format="jpeg")
        except Exception as e:
            log.warning("Pi camera read failed: %s", e)
            return None

        frame = buffer.getvalue()
        return frame if frame else None

    @property
    def available(self) -> bool:
        return self._camera is not None

    def close(self) -> None:
        with self._lock:
            if self._camera is not None:
                try:
                    self._camera.stop()
                except Exception:
                    pass
                self._camera = None


class UsbCamera(Camera):
    def __init__(self, device_index: int = 0, width: int = 640, height: int = 480):
        self._capture = None
        # A VideoCapture is not safe for two readers, so reads are locked.
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
        with self._lock:
            if self._capture is not None:
                self._capture.release()
                self._capture = None


class UbloxLocationSensor(LocationSensor):
    TALKING_FRESHNESS_S = 10.0

    def __init__(self, port: str = "/dev/serial0", baudrate: int = 9600,
                 clock=time.monotonic, opener=None):
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
            # Usually the serial console still owns the port.
            log.warning("GPS unavailable: %s", e)

    def read_fix(self) -> Optional[dict]:
        for sentence in self.read_sentences():
            fix = parse_gga(sentence)
            if fix is not None:
                self._last_fix = fix
        return self._last_fix

    def read_sentences(self):
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
        if self._fd is None:
            return False
        if self._last_sentence_at is None:
            self.read_sentences()
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
    import termios

    speeds = {4800: termios.B4800, 9600: termios.B9600,
              19200: termios.B19200, 38400: termios.B38400,
              57600: termios.B57600, 115200: termios.B115200}
    if baudrate not in speeds:
        raise ValueError(f"unsupported baud rate {baudrate}")

    fd = os.open(path, os.O_RDONLY | os.O_NOCTTY | os.O_NONBLOCK)
    try:
        attributes = termios.tcgetattr(fd)
        # Raw mode: no echo and no byte translation.
        attributes[0] = 0
        attributes[1] = 0
        attributes[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
        attributes[3] = 0
        attributes[4] = speeds[baudrate]
        attributes[5] = speeds[baudrate]
        attributes[6][termios.VMIN] = 0
        attributes[6][termios.VTIME] = 0
        termios.tcsetattr(fd, termios.TCSANOW, attributes)
    except Exception:
        os.close(fd)
        raise
    return fd


# One PIR per room, in the order the rooms are listed. BCM numbering.
DEFAULT_PIR_PINS = [17, 27, 22]

DEFAULT_SCALE_PINS = [5, 6]


def parse_pins(spec: str) -> List[int]:
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
            # Pins 0 and 1 are the ID EEPROM and must not be claimed.
            raise ValueError(f"GPIO {pin} is not a usable header pin (expected 2-27)")
        pins.append(pin)
    return pins


def pin_map(rooms: List[str], pins: List[int]) -> List[tuple]:
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
    if rooms is None:
        rooms = ["Kitchen", "Living room", "Hall"]
    if pins is None:
        pins = DEFAULT_PIR_PINS[:len(rooms)]

    return [
        GpioMotionSensor(room, pin, warmup_s=warmup_s)
        for room, pin in pin_map(rooms, pins)
    ]


class NoCamera(Camera):
    def read_jpeg(self) -> Optional[bytes]:
        return None

    @property
    def available(self) -> bool:
        return False


class HoldFeeder(Feeder):
    AUTO_CLOSE_S = 120.0

    def __init__(self, pin: int = 18,
                 open_angle: int = 180,
                 closed_angle: int = 10,
                 servo=_CLAIM_THE_PIN, travel_s: Optional[float] = None,
                 auto_close_s: Optional[float] = AUTO_CLOSE_S,
                 clock=time.monotonic,
                 min_pulse_us: Optional[int] = None,
                 max_pulse_us: Optional[int] = None):
        self._pin = pin
        self._open_angle = open_angle
        self._closed_angle = closed_angle
        self._travel_s = ServoFeeder.TRAVEL_S if travel_s is None else travel_s
        self._auto_close_s = auto_close_s
        self._clock = clock
        self._opened_at: Optional[float] = None
        self._servo = (open_servo(pin, min_pulse_us, max_pulse_us)
                       if servo is _CLAIM_THE_PIN else servo)

        if self._servo is not None:
            self.move_to(closed_angle)

    def move_to(self, angle: float, hold: bool = False) -> None:
        if self._servo is None:
            return
        self._servo.set_angle(angle)
        time.sleep(self._travel_s)
        if not hold:
            self._servo.release()

    def dispense(self, grams: int = 0) -> None:
        if self._servo is None:
            raise RuntimeError("feeder unavailable")

        self.move_to(self._open_angle, hold=True)
        self._opened_at = self._clock()
        log.info("hopper open at %d degrees — waiting to be told to close",
                 self._open_angle)

    def close_gate(self) -> None:
        if self._servo is None:
            raise RuntimeError("feeder unavailable")

        self.move_to(self._closed_angle)
        self._opened_at = None
        log.info("hopper closed at %d degrees", self._closed_angle)

    def tick(self) -> None:
        if self._auto_close_s is None or self._opened_at is None:
            return
        if self._clock() - self._opened_at < self._auto_close_s:
            return

        log.warning("hopper was open for %.0f s with no close — closing it",
                    self._auto_close_s)
        try:
            self.close_gate()
        except RuntimeError:
            self._opened_at = None

    kind = "hold"

    @property
    def is_open(self) -> bool:
        return self._opened_at is not None

    @property
    def pin(self) -> int:
        return self._pin

    @property
    def backend(self) -> str:
        return self._servo.backend if self._servo is not None else "none"

    @property
    def available(self) -> bool:
        return self._servo is not None

    def close(self) -> None:
        if self._servo is not None:
            self._servo.close()
            self._servo = None


FEEDER_KINDS = ("gate", "paddle", "hold")


CAMERA_KINDS = ("csi", "usb", "none")


def build_camera(kind: str = "csi", device_index: int = 0,
                 retry_interval_s: float = 10.0) -> Camera:
    if kind not in CAMERA_KINDS:
        raise ValueError(
            f"unknown camera '{kind}' (expected one of {', '.join(CAMERA_KINDS)})")

    if kind == "none":
        return NoCamera()

    def factory() -> Camera:
        return (UsbCamera(device_index=device_index) if kind == "usb"
                else PiCamera())

    return ReopeningCamera(factory, retry_interval_s=retry_interval_s)


def build_cameras(spec: str = "csi", usb_device_index: int = 0,
                  retry_interval_s: float = 10.0):
    kinds = [k.strip() for k in (spec or "").split(",") if k.strip()]
    if not kinds:
        raise ValueError("--camera needs at least one kind")

    seen = []
    for kind in kinds:
        if kind not in CAMERA_KINDS:
            raise ValueError(
                f"unknown camera '{kind}' (expected one of "
                f"{', '.join(CAMERA_KINDS)})")
        if kind in seen:
            raise ValueError(f"camera '{kind}' named twice")
        seen.append(kind)

    if "none" in seen and len(seen) > 1:
        raise ValueError("'none' cannot be combined with a real camera")

    return OrderedDict(
        (kind, build_camera(kind, usb_device_index, retry_interval_s))
        for kind in seen)


def probe_scale_pins(dout_pin: int, sck_pin: int):
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
                          tare_path: Optional[str] = "scale-tare",
                          gps_port: Optional[str] = "/dev/serial0",
                          feeder: Optional[Feeder] = None) -> SensorSet:
    dout, sck = (scale_pins or DEFAULT_SCALE_PINS)
    cameras = build_cameras(camera, camera_device)
    return SensorSet(
        scale=HX711Scale(dout_pin=dout, sck_pin=sck,
                         calibration_factor=scale_factor,
                         tare_path=tare_path),
        motion_sensors=build_motion_sensors(rooms, pir_pins, warmup_s=pir_warmup_s),
        feeder=(feeder if feeder is not None else ServoFeeder()),
        camera=next(iter(cameras.values())),
        cameras=cameras,
        location=(UbloxLocationSensor(port=gps_port) if gps_port else None),
    )
