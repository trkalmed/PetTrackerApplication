"""What the service needs from hardware, stated without any hardware.

Every sensor is behind one of these. The service talks only to these types, so
swapping a fake for a real device changes one line in the wiring at startup and
nothing else. That is what lets the whole app-to-Pi path be finished and tested
before a single wire is stripped.

Each also reports ``available``: a Pi with the load cell unplugged should keep
serving motion and camera rather than refusing to start, and /health should say
which parts are actually live.
"""

from abc import ABC, abstractmethod
from typing import List, Optional


class Scale(ABC):
    """Load cell + HX711: the weight of the bowl."""

    @abstractmethod
    def read_grams(self) -> Optional[float]:
        """Current bowl weight in grams, or None if the reading failed.

        None matters: a flaky HX711 returning nonsense is different from a bowl
        that is genuinely empty, and only one of those is a meal.
        """

    @abstractmethod
    def tare(self) -> None:
        """Treats the current weight as zero."""

    @property
    @abstractmethod
    def available(self) -> bool: ...


class MotionSensor(ABC):
    """One PIR sensor, watching one room."""

    @property
    @abstractmethod
    def room(self) -> str: ...

    @abstractmethod
    def poll(self) -> bool:
        """True if motion is being detected right now.

        "Right now" is the honest description: a PIR reports movement, not
        presence. Turning a sequence of these into "the cat is in the kitchen"
        is the app's job, not this one's.
        """

    @property
    @abstractmethod
    def available(self) -> bool: ...

    def close(self) -> None:
        """Releases the pin. Safe to call more than once."""


class Feeder(ABC):
    """The servo that opens the hopper."""

    @abstractmethod
    def dispense(self, grams: int) -> None:
        """Runs the servo long enough to release roughly ``grams``.

        Approximate by nature — the servo controls time open, not weight. The
        load cell is what actually measures the result, which is why the two are
        separate readings in the event log.
        """

    @property
    @abstractmethod
    def available(self) -> bool: ...


class Camera(ABC):
    """Something that produces JPEG frames."""

    @abstractmethod
    def read_jpeg(self) -> Optional[bytes]:
        """One encoded JPEG frame, or None if the camera is not producing."""

    @property
    @abstractmethod
    def available(self) -> bool: ...

    def close(self) -> None:
        """Releases the device. Safe to call more than once."""


class LocationSensor(ABC):
    """NEO-6M GPS.

    Worth remembering what this measures: where the *Pi* is. For a feeder
    sitting in a kitchen that is a fixed point, not pet tracking. It earns its
    place only if the Pi itself moves.
    """

    @abstractmethod
    def read_fix(self) -> Optional[dict]:
        """``{"lat": float, "lon": float, "satellites": int}`` or None with no fix.

        None is the normal state indoors — these modules need sky view.
        """

    @property
    @abstractmethod
    def available(self) -> bool: ...


class SensorSet:
    """Everything the service was given, fake or real."""

    def __init__(
        self,
        scale: Scale,
        motion_sensors: List[MotionSensor],
        feeder: Feeder,
        camera: Camera,
        location: Optional[LocationSensor] = None,
    ):
        self.scale = scale
        self.motion_sensors = motion_sensors
        self.feeder = feeder
        self.camera = camera
        self.location = location

    def health(self) -> dict:
        return {
            "scale": self.scale.available,
            "feeder": self.feeder.available,
            "camera": self.camera.available,
            "location": self.location.available if self.location else False,
            "motion": {s.room: s.available for s in self.motion_sensors},
        }

    def close(self) -> None:
        """Releases every device that holds one.

        Motion sensors are included because a claimed GPIO stays claimed:
        restarting the service without freeing the pins leaves the next run
        unable to open them, which looks exactly like a wiring fault.
        """
        self.camera.close()
        for sensor in self.motion_sensors:
            sensor.close()
