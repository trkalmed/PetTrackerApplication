"""Hardware interfaces the service depends on."""

from abc import ABC, abstractmethod
from collections import OrderedDict
from typing import Dict, List, Optional


class Scale(ABC):
    @abstractmethod
    def read_grams(self) -> Optional[float]:
        """Bowl weight in grams, or None if the reading failed."""

    @abstractmethod
    def tare(self) -> None:
        """Treat the current weight as zero."""

    @property
    @abstractmethod
    def available(self) -> bool: ...


class MotionSensor(ABC):
    @property
    @abstractmethod
    def room(self) -> str: ...

    @abstractmethod
    def poll(self) -> bool:
        """True while motion is detected."""

    @property
    @abstractmethod
    def available(self) -> bool: ...

    def close(self) -> None:
        """Release the pin."""


class Feeder(ABC):
    @abstractmethod
    def dispense(self, grams: int) -> None:
        """Run the feeder for roughly the given grams. The load cell measures what fell."""

    @property
    @abstractmethod
    def available(self) -> bool: ...


class Camera(ABC):
    @abstractmethod
    def read_jpeg(self) -> Optional[bytes]:
        """One JPEG frame, or None if the camera is not producing."""

    @property
    @abstractmethod
    def available(self) -> bool: ...

    def close(self) -> None:
        """Release the device."""


class LocationSensor(ABC):
    @abstractmethod
    def read_fix(self) -> Optional[dict]:
        """Latest fix as lat, lon and satellites, or None without a fix."""

    @property
    @abstractmethod
    def available(self) -> bool: ...


class SensorSet:
    def __init__(
        self,
        scale: Scale,
        motion_sensors: List[MotionSensor],
        feeder: Feeder,
        camera: Camera,
        location: Optional[LocationSensor] = None,
        cameras: Optional["OrderedDict[str, Camera]"] = None,
        camera_rooms: Optional[Dict[str, str]] = None,
    ):
        self.scale = scale
        self.motion_sensors = motion_sensors
        self.feeder = feeder
        self.camera = camera
        self.location = location

        if cameras:
            self.cameras = OrderedDict(cameras)
        else:
            self.cameras = OrderedDict()
            if camera is not None:
                self.cameras["camera"] = camera

        self.camera_rooms: Dict[str, str] = {}
        if camera_rooms:
            self.set_camera_rooms(camera_rooms)

    def set_camera_rooms(self, mapping: Dict[str, str]) -> None:
        for room, name in mapping.items():
            if name not in self.cameras:
                raise ValueError(
                    f"camera '{name}' is not configured for room '{room}' "
                    f"(this rig has: {', '.join(self.cameras) or 'none'})")
        known = {s.room for s in self.motion_sensors}
        for room in mapping:
            if known and room not in known:
                raise ValueError(
                    f"no motion sensor watches '{room}' "
                    f"(this rig has: {', '.join(sorted(known))})")
        self.camera_rooms = dict(mapping)

    def camera_for_room(self, room: str) -> Optional[Camera]:
        if not self.camera_rooms:
            return self.camera
        name = self.camera_rooms.get(room)
        if name is None:
            return None
        return self.cameras.get(name)

    def camera_named(self, name: Optional[str]) -> Optional[Camera]:
        if not name:
            return self.camera
        return self.cameras.get(name)

    def health(self) -> dict:
        return {
            "scale": self.scale.available,
            "feeder": self.feeder.available,
            # Kept for older clients: true when any camera is up.
            "camera": any(c.available for c in self.cameras.values()),
            "cameras": {name: c.available for name, c in self.cameras.items()},
            "camera_rooms": dict(self.camera_rooms),
            "location": self.location.available if self.location else False,
            "motion": {s.room: s.available for s in self.motion_sensors},
        }

    def close(self) -> None:
        for camera in self.cameras.values():
            camera.close()
        for sensor in self.motion_sensors:
            sensor.close()
