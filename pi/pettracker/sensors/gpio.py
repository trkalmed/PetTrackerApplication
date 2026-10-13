"""Digital pin access across the available Raspberry Pi GPIO libraries."""

import logging
from typing import Optional

log = logging.getLogger(__name__)


class DigitalInput:
    backend = "none"

    def read(self) -> bool:
        raise NotImplementedError

    def close(self) -> None:
        """Release the pin."""


class _GpiozeroInput(DigitalInput):
    backend = "gpiozero"

    def __init__(self, pin: int):
        from gpiozero import DigitalInputDevice  # type: ignore

        # pull_up=False pulls down, so a loose wire reads low instead of floating.
        self._device = DigitalInputDevice(pin, pull_up=False)

    def read(self) -> bool:
        return bool(self._device.value)

    def close(self) -> None:
        try:
            self._device.close()
        except Exception:
            pass


class _RpiGpioInput(DigitalInput):
    backend = "RPi.GPIO"

    def __init__(self, pin: int):
        import RPi.GPIO as GPIO  # type: ignore

        GPIO.setmode(GPIO.BCM)
        GPIO.setwarnings(False)
        GPIO.setup(pin, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
        self._gpio = GPIO
        self._pin = pin

    def read(self) -> bool:
        return bool(self._gpio.input(self._pin))

    def close(self) -> None:
        try:
            # Release per pin. GPIO.cleanup() would also reset the servo pin.
            self._gpio.cleanup(self._pin)
        except Exception:
            pass


class _LgpioInput(DigitalInput):
    backend = "lgpio"

    def __init__(self, pin: int):
        import lgpio  # type: ignore

        self._lgpio = lgpio
        self._handle = lgpio.gpiochip_open(_lgpio_chip())
        lgpio.gpio_claim_input(self._handle, pin, lgpio.SET_PULL_DOWN)
        self._pin = pin

    def read(self) -> bool:
        return bool(self._lgpio.gpio_read(self._handle, self._pin))

    def close(self) -> None:
        try:
            self._lgpio.gpio_free(self._handle, self._pin)
            self._lgpio.gpiochip_close(self._handle)
        except Exception:
            pass


def _lgpio_chip() -> int:
    return 0


class DigitalOutput:
    backend = "none"

    def write(self, high: bool) -> None:
        raise NotImplementedError

    def close(self) -> None:
        """Release the pin."""


class _GpiozeroOutput(DigitalOutput):
    backend = "gpiozero"

    def __init__(self, pin: int):
        from gpiozero import DigitalOutputDevice  # type: ignore

        self._device = DigitalOutputDevice(pin, initial_value=False)

    def write(self, high: bool) -> None:
        self._device.value = 1 if high else 0

    def close(self) -> None:
        try:
            self._device.close()
        except Exception:
            pass


class _RpiGpioOutput(DigitalOutput):
    backend = "RPi.GPIO"

    def __init__(self, pin: int):
        import RPi.GPIO as GPIO  # type: ignore

        GPIO.setmode(GPIO.BCM)
        GPIO.setwarnings(False)
        GPIO.setup(pin, GPIO.OUT, initial=GPIO.LOW)
        self._gpio = GPIO
        self._pin = pin

    def write(self, high: bool) -> None:
        self._gpio.output(self._pin, high)

    def close(self) -> None:
        try:
            self._gpio.cleanup(self._pin)
        except Exception:
            pass


class _LgpioOutput(DigitalOutput):
    backend = "lgpio"

    def __init__(self, pin: int):
        import lgpio  # type: ignore

        self._lgpio = lgpio
        self._handle = lgpio.gpiochip_open(_lgpio_chip())
        lgpio.gpio_claim_output(self._handle, pin, 0)
        self._pin = pin

    def write(self, high: bool) -> None:
        self._lgpio.gpio_write(self._handle, self._pin, 1 if high else 0)

    def close(self) -> None:
        try:
            self._lgpio.gpio_free(self._handle, self._pin)
            self._lgpio.gpiochip_close(self._handle)
        except Exception:
            pass


# Tried in order. gpiozero picks a working driver for the board.
_BACKENDS = (_GpiozeroInput, _RpiGpioInput, _LgpioInput)
_OUTPUT_BACKENDS = (_GpiozeroOutput, _RpiGpioOutput, _LgpioOutput)

# Ordered by speed: the HX711 powers down if its clock stays high for 60 us.
_FAST_BACKENDS = (_LgpioInput, _RpiGpioInput, _GpiozeroInput)
_FAST_OUTPUT_BACKENDS = (_LgpioOutput, _RpiGpioOutput, _GpiozeroOutput)


def held_elsewhere(errors) -> bool:
    return bool(errors) and all("busy" in str(e).lower() for e in errors)


def _explain_if_busy(pin: int, errors) -> None:
    if held_elsewhere(errors):
        log.warning("GPIO %d is held by another process. If the pettracker "
                    "service is running, stop it first: "
                    "sudo systemctl stop pettracker", pin)


def open_input(pin: int, fast: bool = False) -> Optional[DigitalInput]:
    errors = []
    for backend in (_FAST_BACKENDS if fast else _BACKENDS):
        try:
            claimed = backend(pin)
        except Exception as e:
            errors.append(f"{backend.backend}: {e}")
            continue
        log.info("GPIO %d claimed via %s", pin, claimed.backend)
        return claimed

    log.warning("GPIO %d unavailable (%s)", pin, "; ".join(errors))
    _explain_if_busy(pin, errors)
    return None


def open_output(pin: int, fast: bool = False) -> Optional[DigitalOutput]:
    errors = []
    for backend in (_FAST_OUTPUT_BACKENDS if fast else _OUTPUT_BACKENDS):
        try:
            claimed = backend(pin)
        except Exception as e:
            errors.append(f"{backend.backend}: {e}")
            continue
        log.info("GPIO %d claimed for output via %s", pin, claimed.backend)
        return claimed

    log.warning("GPIO %d unavailable for output (%s)", pin, "; ".join(errors))
    _explain_if_busy(pin, errors)
    return None


class ServoOutput:
    backend = "none"

    # Pulse widths at the ends of travel, in microseconds.
    MIN_PULSE_US = 500
    MAX_PULSE_US = 2400

    # Limits the drivers themselves accept.
    HARD_MIN_PULSE_US = 500
    HARD_MAX_PULSE_US = 2500

    SAFE_MIN_ANGLE = 20
    SAFE_MAX_ANGLE = 160

    def set_pulse_us(self, microseconds: int) -> None:
        raise NotImplementedError

    def set_angle(self, degrees: float) -> None:
        degrees = max(0.0, min(180.0, float(degrees)))
        span = self.MAX_PULSE_US - self.MIN_PULSE_US
        self.set_pulse_us(int(self.MIN_PULSE_US + span * degrees / 180.0))

    def release(self) -> None:
        self.set_pulse_us(0)

    def close(self) -> None:
        """Release the pin."""


class _LgpioServo(ServoOutput):
    backend = "lgpio"

    def __init__(self, pin: int):
        import lgpio  # type: ignore

        self._lgpio = lgpio
        self._handle = lgpio.gpiochip_open(_lgpio_chip())
        lgpio.gpio_claim_output(self._handle, pin, 0)
        self._pin = pin

    def set_pulse_us(self, microseconds: int) -> None:
        pulse = int(microseconds)
        if pulse:
            pulse = max(self.HARD_MIN_PULSE_US, min(self.HARD_MAX_PULSE_US, pulse))
        self._lgpio.tx_servo(self._handle, self._pin, pulse)

    def close(self) -> None:
        try:
            self.release()
            self._lgpio.gpio_free(self._handle, self._pin)
            self._lgpio.gpiochip_close(self._handle)
        except Exception:
            pass


class _GpiozeroServo(ServoOutput):
    backend = "gpiozero"

    def __init__(self, pin: int):
        from gpiozero import Servo  # type: ignore

        self._servo = Servo(
            pin,
            min_pulse_width=self.MIN_PULSE_US / 1_000_000,
            max_pulse_width=self.MAX_PULSE_US / 1_000_000)

    def set_pulse_us(self, microseconds: int) -> None:
        if not microseconds:
            self._servo.detach()
            return
        span = self.MAX_PULSE_US - self.MIN_PULSE_US
        fraction = (microseconds - self.MIN_PULSE_US) / span
        self._servo.value = max(-1.0, min(1.0, fraction * 2 - 1))

    def close(self) -> None:
        try:
            self._servo.close()
        except Exception:
            pass


class _RpiGpioServo(ServoOutput):
    backend = "RPi.GPIO"

    def __init__(self, pin: int):
        import RPi.GPIO as GPIO  # type: ignore

        GPIO.setmode(GPIO.BCM)
        GPIO.setwarnings(False)
        GPIO.setup(pin, GPIO.OUT)
        self._gpio = GPIO
        self._pin = pin
        self._pwm = GPIO.PWM(pin, 50)
        self._pwm.start(0)

    def set_pulse_us(self, microseconds: int) -> None:
        self._pwm.ChangeDutyCycle(microseconds / 20_000.0 * 100.0 if microseconds
                                  else 0)

    def close(self) -> None:
        try:
            self._pwm.stop()
            self._gpio.cleanup(self._pin)
        except Exception:
            pass


# lgpio first: its pulses are timed by the library, so they do not jitter.
_SERVO_BACKENDS = (_LgpioServo, _GpiozeroServo, _RpiGpioServo)


def open_servo(pin: int,
               min_pulse_us: Optional[int] = None,
               max_pulse_us: Optional[int] = None) -> Optional[ServoOutput]:
    for name, value in (("--servo-pulse-min", min_pulse_us),
                        ("--servo-pulse-max", max_pulse_us)):
        if value is None:
            continue
        if not (ServoOutput.HARD_MIN_PULSE_US <= int(value)
                <= ServoOutput.HARD_MAX_PULSE_US):
            raise ValueError(
                f"{name}: {int(value)} us is outside what the driver accepts "
                f"({ServoOutput.HARD_MIN_PULSE_US}-"
                f"{ServoOutput.HARD_MAX_PULSE_US}). That ceiling is lgpio's, "
                f"and it is close to where servos stop responding anyway — a "
                f"servo that still falls short at "
                f"{ServoOutput.HARD_MAX_PULSE_US} us has reached its own "
                f"mechanical limit, not a software one.")

    errors = []
    for backend in _SERVO_BACKENDS:
        try:
            claimed = backend(pin)
        except Exception as e:
            errors.append(f"{backend.backend}: {e}")
            continue

        if min_pulse_us is not None:
            claimed.MIN_PULSE_US = int(min_pulse_us)
        if max_pulse_us is not None:
            claimed.MAX_PULSE_US = int(max_pulse_us)

        log.info("servo on GPIO %d via %s (%d-%d us)", pin, claimed.backend,
                 claimed.MIN_PULSE_US, claimed.MAX_PULSE_US)
        return claimed

    log.warning("servo on GPIO %d unavailable (%s)", pin, "; ".join(errors))
    _explain_if_busy(pin, errors)
    return None
