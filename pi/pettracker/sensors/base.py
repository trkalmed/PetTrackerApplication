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
from collections import OrderedDict
from typing import Dict, List, Optional


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
        cameras: Optional["OrderedDict[str, Camera]"] = None,
        camera_rooms: Optional[Dict[str, str]] = None,
    ):
        """
        :param camera: the primary camera. Motion snapshots come from this one,
            and it is what ``/stream`` serves when no camera is named.
        :param cameras: every camera by name, in the order they were configured.
            A rig with one camera passes nothing here and behaves as it always
            did; the name is only needed once there is a choice to make.
        :param camera_rooms: which camera watches which room, as room -> camera
            name. See :meth:`set_camera_rooms`.
        """
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
        """Records which camera is pointed at which room.

        A camera is bolted to one place. The rig has one in the kitchen, and
        every PIR in the house was getting a picture from it — so a sighting in
        the bathroom arrived at the phone with a photograph of the kitchen
        attached, timestamped and captioned with the bathroom's name. That is
        worse than no photograph: an empty card says "the sensor fired and there
        is no picture", while that one says "here is what happened in the
        bathroom" and shows a different room entirely.

        Both names are checked against what this rig actually has, because the
        failure of a typo is silent in the direction that matters — a room that
        matches nothing simply stops producing pictures, and nobody notices
        until they go looking for a sighting that has none.

        :param mapping: room name -> camera name. Rooms left out get no
            snapshot. Several rooms may name the same camera; one room cannot
            have two.
        """
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
        """The camera that can see ``room``, or None if none can.

        With no mapping configured this is the primary camera, which is the
        behaviour every single-camera rig has always had: one camera, one room
        worth photographing, nothing to choose between. The moment a mapping
        exists the rig has said where its cameras point, and a room it does not
        mention is a room nothing is watching.
        """
        if not self.camera_rooms:
            return self.camera
        name = self.camera_rooms.get(room)
        if name is None:
            return None
        return self.cameras.get(name)

    def camera_named(self, name: Optional[str]) -> Optional[Camera]:
        """The camera by name, or the primary one when nothing is named.

        Returns None for a name that does not exist, which the server answers
        with a 404 — deliberately not "fall back to the primary". A phone asking
        for the kitchen camera and quietly being shown the hall is worse than an
        error, because nothing about the picture says it is the wrong room.
        """
        if not name:
            return self.camera
        return self.cameras.get(name)

    def health(self) -> dict:
        return {
            "scale": self.scale.available,
            "feeder": self.feeder.available,
            # Kept for older clients: true when *any* camera is up. The named
            # map below is what a client with a choice to offer should read.
            "camera": any(c.available for c in self.cameras.values()),
            "cameras": {name: c.available for name, c in self.cameras.items()},
            # Where those cameras point, so the mapping can be checked from the
            # phone. Empty means none was configured and the primary camera
            # answers for every room.
            "camera_rooms": dict(self.camera_rooms),
            "location": self.location.available if self.location else False,
            "motion": {s.room: s.available for s in self.motion_sensors},
        }

    def close(self) -> None:
        """Releases every device that holds one.

        Motion sensors are included because a claimed GPIO stays claimed:
        restarting the service without freeing the pins leaves the next run
        unable to open them, which looks exactly like a wiring fault.
        """
        for camera in self.cameras.values():
            camera.close()
        for sensor in self.motion_sensors:
            sensor.close()
