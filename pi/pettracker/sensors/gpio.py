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


#: Tried in order. gpiozero first because it picks a working low-level driver
#: for the board it is running on, which is the whole problem being solved here.
_BACKENDS = (_GpiozeroInput, _RpiGpioInput, _LgpioInput)


def open_input(pin: int) -> Optional[DigitalInput]:
    """Claims ``pin`` as an input, or returns None if no backend can.

    None rather than an exception: a Pi with one sensor unplugged, or a laptop
    with no GPIO at all, must still start the service. The caller reports itself
    unavailable and /health says so.
    """
    errors = []
    for backend in _BACKENDS:
        try:
            claimed = backend(pin)
        except Exception as e:                      # ImportError, or the Pi 5 setup failure
            errors.append(f"{backend.backend}: {e}")
            continue
        log.info("GPIO %d claimed via %s", pin, claimed.backend)
        return claimed

    log.warning("GPIO %d unavailable (%s)", pin, "; ".join(errors))
    return None
