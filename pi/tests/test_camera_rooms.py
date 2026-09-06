"""Which camera answers for which room.

A camera is bolted to one place. The rig has the ribbon camera in the kitchen,
and every PIR in the house was being photographed with it — so a sighting in the
bathroom reached the phone captioned "BedRoom/Bathroom" with a picture of the
kitchen attached. The event was true and the picture was of somewhere else, and
nothing on the card said so.

The rule these tests hold to is the same one the rest of the project runs on: an
absent reading is shown as absent. A room no camera can see gets a motion event
with no picture, which the app already renders as a card without a thumbnail.
"""

import logging
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from collections import OrderedDict

from pettracker.monitor import Monitor
from pettracker.sensors.base import SensorSet
from pettracker.sensors.fake import FakeCamera, FakeFeeder, FakeMotionSensor, FakeScale
from pettracker.snapshots import SnapshotStore
from pettracker.store import KIND_MOTION, EventStore
from run import build_parser, parse_camera_rooms


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class ParseCameraRoomsTest(unittest.TestCase):

    def test_pairs_are_stored_by_room(self):
        # Written camera-first, because that is how the rig reads. Asked
        # room-first, because that is the question a firing PIR poses.
        self.assertEqual({"Kitchen": "csi", "Hall": "usb"},
                         parse_camera_rooms("csi=Kitchen,usb=Hall"))

    def test_a_room_name_may_contain_spaces_and_punctuation(self):
        # "Living Room / Hall" is a real room name on this rig. Only the comma
        # is reserved, and room names cannot contain one for the same reason
        # --rooms cannot.
        self.assertEqual({"Living Room / Hall": "usb"},
                         parse_camera_rooms("usb=Living Room / Hall"))

    def test_two_rooms_may_share_one_camera(self):
        self.assertEqual({"Kitchen": "csi", "Hall": "csi"},
                         parse_camera_rooms("csi=Kitchen,csi=Hall"))

    def test_empty_means_no_mapping(self):
        # Not an error: it is what every rig that has never configured this
        # passes, and it must keep meaning "say nothing about where cameras
        # point" rather than "no room gets a picture".
        self.assertIsNone(parse_camera_rooms(""))
        self.assertIsNone(parse_camera_rooms("   "))
        self.assertIsNone(parse_camera_rooms(None))

    def test_a_pair_without_an_equals_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_rooms("csi")

    def test_a_half_written_pair_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_rooms("csi=")
        with self.assertRaises(ValueError):
            parse_camera_rooms("=Kitchen")

    def test_one_room_cannot_have_two_cameras(self):
        # Silently keeping the last would be a coin toss decided by argument
        # order, and the wrong side of it looks exactly like the bug this whole
        # mapping exists to fix.
        with self.assertRaises(ValueError):
            parse_camera_rooms("csi=Kitchen,usb=Kitchen")

    def test_the_flag_reaches_the_parser(self):
        args = build_parser().parse_args(["--camera-rooms", "csi=Kitchen"])
        self.assertEqual("csi=Kitchen", args.camera_rooms)


class CameraForRoomTest(unittest.TestCase):

    def setUp(self):
        self.csi = FakeCamera()
        self.usb = FakeCamera()
        self.sensors = SensorSet(
            scale=FakeScale(),
            motion_sensors=[FakeMotionSensor("Kitchen"),
                            FakeMotionSensor("BedRoom/Bathroom")],
            feeder=FakeFeeder(),
            camera=self.csi,
            cameras=OrderedDict([("csi", self.csi), ("usb", self.usb)]),
        )

    def test_without_a_mapping_the_primary_answers_for_every_room(self):
        self.assertIs(self.csi, self.sensors.camera_for_room("Kitchen"))
        self.assertIs(self.csi, self.sensors.camera_for_room("BedRoom/Bathroom"))

    def test_a_mapped_room_gets_its_own_camera(self):
        self.sensors.set_camera_rooms({"Kitchen": "csi",
                                       "BedRoom/Bathroom": "usb"})

        self.assertIs(self.csi, self.sensors.camera_for_room("Kitchen"))
        self.assertIs(self.usb, self.sensors.camera_for_room("BedRoom/Bathroom"))

    def test_a_room_outside_the_mapping_gets_no_camera(self):
        self.sensors.set_camera_rooms({"Kitchen": "csi"})

        self.assertIsNone(self.sensors.camera_for_room("BedRoom/Bathroom"))

    def test_a_camera_this_rig_does_not_have_is_rejected(self):
        with self.assertRaises(ValueError):
            self.sensors.set_camera_rooms({"Kitchen": "doorbell"})

    def test_a_room_no_sensor_watches_is_rejected(self):
        # A typo here fails silently in the worst direction — the room simply
        # stops producing pictures, and the only symptom is a sighting that
        # never has one.
        with self.assertRaises(ValueError):
            self.sensors.set_camera_rooms({"Ktichen": "csi"})

    def test_a_rejected_mapping_leaves_the_previous_one_alone(self):
        self.sensors.set_camera_rooms({"Kitchen": "csi"})

        with self.assertRaises(ValueError):
            self.sensors.set_camera_rooms({"Kitchen": "csi",
                                           "BedRoom/Bathroom": "doorbell"})

        self.assertEqual({"Kitchen": "csi"}, self.sensors.camera_rooms)

    def test_health_reports_where_the_cameras_point(self):
        self.sensors.set_camera_rooms({"Kitchen": "csi"})

        # So the mapping can be checked from the phone during a demo, without
        # an SSH session to read the unit file.
        self.assertEqual({"Kitchen": "csi"}, self.sensors.health()["camera_rooms"])


class MotionSnapshotRoutingTest(unittest.TestCase):
    """The bug itself, at the level the phone sees it."""

    def setUp(self):
        handle, self.db = tempfile.mkstemp(suffix=".db")
        os.close(handle)
        os.unlink(self.db)
        self.directory = tempfile.mkdtemp()

        self.store = EventStore(self.db)
        self.kitchen = FakeMotionSensor("Kitchen", trigger_probability=0.0)
        self.bathroom = FakeMotionSensor("BedRoom/Bathroom",
                                         trigger_probability=0.0)
        scale = FakeScale()
        scale.available_flag = False

        self.csi = FakeCamera()
        self.sensors = SensorSet(
            scale=scale,
            motion_sensors=[self.kitchen, self.bathroom],
            feeder=FakeFeeder(scale=scale),
            camera=self.csi,
            cameras=OrderedDict([("csi", self.csi)]),
        )
        self.monitor = Monitor(self.sensors, self.store,
                               snapshots=SnapshotStore(self.directory))

    def tearDown(self):
        self.store.close()
        os.unlink(self.db)
        shutil.rmtree(self.directory, ignore_errors=True)

    def motion_events(self):
        return [e for e in self.store.since(0) if e["kind"] == KIND_MOTION]

    def by_room(self):
        return {e["room"]: e for e in self.motion_events()}

    def test_only_the_room_the_camera_watches_gets_a_picture(self):
        self.sensors.set_camera_rooms({"Kitchen": "csi"})
        self.kitchen.force(True)
        self.bathroom.force(True)

        self.monitor.poll_once(now=1000.0)

        events = self.by_room()
        self.assertIn("snapshot", events["Kitchen"])
        self.assertNotIn("snapshot", events["BedRoom/Bathroom"])

    def test_the_uncovered_room_still_records_the_sighting(self):
        # The picture is the part that was wrong. The reading is not, and losing
        # it would trade one fault for a worse one.
        self.sensors.set_camera_rooms({"Kitchen": "csi"})
        self.bathroom.force(True)

        self.monitor.poll_once(now=1000.0)

        self.assertEqual(["BedRoom/Bathroom"],
                         [e["room"] for e in self.motion_events()])

    def test_only_one_file_is_written(self):
        # The frame for the uncovered room must not be taken and then discarded:
        # a snapshot per PIR fills the card at three times the rate, and on this
        # rig the CSI camera would be read for a room it cannot see.
        self.sensors.set_camera_rooms({"Kitchen": "csi"})
        self.kitchen.force(True)
        self.bathroom.force(True)

        self.monitor.poll_once(now=1000.0)

        self.assertEqual(1, len(os.listdir(self.directory)))

    def test_an_unmapped_rig_photographs_every_room_as_before(self):
        self.kitchen.force(True)
        self.bathroom.force(True)

        self.monitor.poll_once(now=1000.0)

        for event in self.motion_events():
            self.assertIn("snapshot", event)


if __name__ == "__main__":
    unittest.main()
