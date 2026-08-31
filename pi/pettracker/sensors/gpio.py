"""Reading a digital input pin, on whichever Raspberry Pi you happen to have.

There is no single GPIO library that works everywhere any more, and picking the
wrong one is the most likely reason a correctly wired sensor reads nothing:

* **RPi.GPIO** is the classic, and every tutorial online uses it. On a Pi 5 —
  and on Bookworm images generally — it imports fine and then fails at
  ``setup()`` with "Cannot determine SOC peripheral base address", because the
  Pi 5 moved GPIO behind a new chip (RP1) that the library never learned about.
* **gpiozero** is installed by default on Raspberry Pi OS and picks a working
  low-level backend for the board it is on, so it is the first thing to try.
* **lgpio** is what Bookworm ships underneath and works on a Pi 5 directly, so
  it is the last resort when gpiozero itself is missing.

So: try each in turn, use the first that actually claims the pin, and say in the
log which one won. During bring-up, "which backend" is the first question worth
answering when a sensor reads nothing.

Everything here is imported lazily inside the attempts, so this module imports
cleanly on a laptop and simply finds no backend.
"""

import logging
from typing import Optional

log = logging.getLogger(__name__)


class DigitalInput:
    """One input pin, already claimed and ready to read.

    Deliberately tiny: ``read()`` and ``close()`` are all any sensor here needs,
    and keeping the surface that small is what lets three very different
    libraries hide behind it.
    """

    #: Name of the library backing this pin, for logs and bring-up output.
    backend = "none"

    def read(self) -> bool:
        """True while the pin is high."""
        raise NotImplementedError

    def close(self) -> None:
        """Releases the pin. Safe to call more than once."""


class _GpiozeroInput(DigitalInput):
    backend = "gpiozero"

    def __init__(self, pin: int):
        from gpiozero import DigitalInputDevice  # type: ignore

        # pull_up=False is a pull-*down*: with the signal wire off, the input
        # floats and reads as random noise, which looks exactly like a PIR
        # firing. Held low, a disconnected sensor reads as "no motion", which is
        # the truthful answer.
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
        # This is the call that raises on a Pi 5, not the import.
        GPIO.setup(pin, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
        self._gpio = GPIO
        self._pin = pin

    def read(self) -> bool:
        return bool(self._gpio.input(self._pin))

    def close(self) -> None:
        try:
            # Per pin, never GPIO.cleanup(): a bare cleanup resets every pin on
            # the board, including the servo's, which would drop the hopper gate.
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
    """Which gpiochip carries the header pins.

    0 on a Pi 5 (the RP1), 0 on a Pi 4 under Bookworm's kernel too — but older
    kernels put the header on chip 0 and newer ones briefly used 4. Try 0 and
    let the caller fall over to the next backend if the claim fails.
    """
    return 0


class DigitalOutput:
    """One output pin, claimed and ready to drive.

    The HX711 needs a clock line, which is the first thing here that has to
    *write* a pin rather than read one. Same three backends, same reason.
    """

    backend = "none"

    def write(self, high: bool) -> None:
        raise NotImplementedError

    def close(self) -> None:
        """Releases the pin. Safe to call more than once."""


class _GpiozeroOutput(DigitalOutput):
    backend = "gpiozero"

    def __init__(self, pin: int):
        from gpiozero import DigitalOutputDevice  # type: ignore

        self._device = DigitalOutputDevice(pin, initial_value=False)

    def write(self, high: bool) -> None:
        # .value rather than .on()/.off(): one attribute write instead of a
        # method dispatch, and this runs 24 times per weight reading inside a
        # window the chip times out of.
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


#: Tried in order. gpiozero first because it picks a working low-level driver
#: for the board it is running on, which is the whole problem being solved here.
_BACKENDS = (_GpiozeroInput, _RpiGpioInput, _LgpioInput)
_OUTPUT_BACKENDS = (_GpiozeroOutput, _RpiGpioOutput, _LgpioOutput)

#: The same three, ordered by speed instead of by convenience.
#:
#: gpiozero is a pleasant abstraction over a pin factory, and every read or
#: write goes through several Python frames to reach the hardware. For a PIR
#: polled five times a second that is invisible. For an HX711 it is fatal: the
#: chip powers itself down if the clock line stays high longer than 60
#: microseconds, and gpiozero can spend that much getting there. The reading
#: then comes back as twenty-four zero bits, which looks exactly like a
#: disconnected sensor.
#:
#: lgpio and RPi.GPIO are thin wrappers over C, so they are first here.
_FAST_BACKENDS = (_LgpioInput, _RpiGpioInput, _GpiozeroInput)
_FAST_OUTPUT_BACKENDS = (_LgpioOutput, _RpiGpioOutput, _GpiozeroOutput)


def held_elsewhere(errors) -> bool:
    """Whether every backend failed because something else has the pin.

    Worth separating from "no library could be loaded", because the two need
    opposite responses: one is a missing package, the other is the service
    already running. A pin can only be claimed once, so the bring-up tools and
    the service cannot both have it — and the failure looks identical to a
    wiring fault unless somebody says otherwise.
    """
    return bool(errors) and all("busy" in str(e).lower() for e in errors)


def _explain_if_busy(pin: int, errors) -> None:
    if held_elsewhere(errors):
        log.warning("GPIO %d is held by another process. If the pettracker "
                    "service is running, stop it first: "
                    "sudo systemctl stop pettracker", pin)


def open_input(pin: int, fast: bool = False) -> Optional[DigitalInput]:
    """Claims ``pin`` as an input, or returns None if no backend can.

    None rather than an exception: a Pi with one sensor unplugged, or a laptop
    with no GPIO at all, must still start the service. The caller reports itself
    unavailable and /health says so.

    :param fast: prefer the backends with the least overhead per call, for a
        device with timing requirements. See :data:`_FAST_BACKENDS`.
    """
    errors = []
    for backend in (_FAST_BACKENDS if fast else _BACKENDS):
        try:
            claimed = backend(pin)
        except Exception as e:                      # ImportError, or the Pi 5 setup failure
            errors.append(f"{backend.backend}: {e}")
            continue
        log.info("GPIO %d claimed via %s", pin, claimed.backend)
        return claimed

    log.warning("GPIO %d unavailable (%s)", pin, "; ".join(errors))
    _explain_if_busy(pin, errors)
    return None


def open_output(pin: int, fast: bool = False) -> Optional[DigitalOutput]:
    """Claims ``pin`` as an output, or returns None if no backend can.

    None rather than an exception, for the same reason as :func:`open_input`: a
    missing amplifier must leave the service running the sensors it does have.

    :param fast: prefer the backends with the least overhead per call. A clock
        line almost always wants this.
    """
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
    """One hobby servo, positioned by pulse width.

    A servo is told where to go by the width of a pulse repeated at 50 Hz:
    roughly 500 µs at one end of its travel, 2400 µs at the other. The number
    that matters is therefore microseconds, not degrees — degrees are a
    convenience this class provides on top, and the mapping is approximate on
    a cheap servo.

    ``release()`` is not an afterthought. A servo held at an angle keeps
    correcting itself against whatever is pushing back, which on a hopper gate
    means buzzing, heat, and current drawn all day for a gate that is not
    moving. Stop the pulses and it goes limp and quiet.
    """

    backend = "none"

    #: Pulse widths at the ends of travel. The datasheet for an SG90 says
    #: 1000-2000, and almost every real one does 500-2400; going past what a
    #: particular servo can reach makes it grind against its own stop.
    MIN_PULSE_US = 500
    MAX_PULSE_US = 2400

    #: The range a *resting* angle should stay inside.
    #:
    #: Not enforced — a bring-up session needs to be able to ask for anything,
    #: because finding a particular servo's real limits is the point of it. This
    #: is advice for choosing defaults and configured positions.
    #:
    #: 0 degrees is exactly MIN_PULSE_US and 180 exactly MAX_PULSE_US. Datasheet
    #: travel for an SG90 is 1000-2000 us; the wider range here is what most
    #: real ones manage, but "most" is not "this one", and a servo asked for a
    #: pulse it cannot reach stalls against an internal stop and draws stall
    #: current for as long as it is asked. A feeder parks on construction and
    #: the service restarts on failure, so a resting position at the limit is
    #: not a momentary mistake — it is a standing one, repeated at every start.
    #:
    #: Angles outside this band are for finding limits, not for living at.
    SAFE_MIN_ANGLE = 20
    SAFE_MAX_ANGLE = 160

    def set_pulse_us(self, microseconds: int) -> None:
        raise NotImplementedError

    def set_angle(self, degrees: float) -> None:
        """Positions by angle, 0 to 180, mapped linearly onto the pulse range."""
        degrees = max(0.0, min(180.0, float(degrees)))
        span = self.MAX_PULSE_US - self.MIN_PULSE_US
        self.set_pulse_us(int(self.MIN_PULSE_US + span * degrees / 180.0))

    def release(self) -> None:
        """Stops the pulses, so the servo stops holding position."""
        self.set_pulse_us(0)

    def close(self) -> None:
        """Releases the pin. Safe to call more than once."""


class _LgpioServo(ServoOutput):
    backend = "lgpio"

    def __init__(self, pin: int):
        import lgpio  # type: ignore

        self._lgpio = lgpio
        self._handle = lgpio.gpiochip_open(_lgpio_chip())
        lgpio.gpio_claim_output(self._handle, pin, 0)
        self._pin = pin

    def set_pulse_us(self, microseconds: int) -> None:
        # tx_servo is timed in the library rather than by Python, so the pulse
        # does not jitter when the interpreter is busy. Software PWM does, and
        # a jittering pulse is what makes cheap servos buzz and creep.
        self._lgpio.tx_servo(self._handle, self._pin, int(microseconds))

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
        self._pwm = GPIO.PWM(pin, 50)          # 50 Hz, standard for hobby servos
        self._pwm.start(0)

    def set_pulse_us(self, microseconds: int) -> None:
        # Duty cycle as a percentage of the 20 ms frame.
        self._pwm.ChangeDutyCycle(microseconds / 20_000.0 * 100.0 if microseconds
                                  else 0)

    def close(self) -> None:
        try:
            self._pwm.stop()
            self._gpio.cleanup(self._pin)
        except Exception:
            pass


#: lgpio first: its pulses are timed by the library rather than by Python, and
#: a jittering pulse is the difference between a gate that moves and one that
#: buzzes at the angle it was already at.
_SERVO_BACKENDS = (_LgpioServo, _GpiozeroServo, _RpiGpioServo)


def open_servo(pin: int) -> Optional[ServoOutput]:
    """Claims ``pin`` for a servo, or returns None if no backend can."""
    errors = []
    for backend in _SERVO_BACKENDS:
        try:
            claimed = backend(pin)
        except Exception as e:
            errors.append(f"{backend.backend}: {e}")
            continue
        log.info("servo on GPIO %d via %s", pin, claimed.backend)
        return claimed

    log.warning("servo on GPIO %d unavailable (%s)", pin, "; ".join(errors))
    _explain_if_busy(pin, errors)
    return None
