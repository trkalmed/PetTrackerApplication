"""A camera that comes back on its own.

Every case here has happened on the real rig: the Pi beating USB enumeration at
boot, a device held by a process that was still exiting, and a webcam knocked
out of its socket. All three left the service serving no stream and, worse,
silently saving no motion snapshots — the events kept arriving, so nothing
looked broken until somebody went looking for the pictures.
"""

import logging
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.sensors.base import Camera, SensorSet
from pettracker.sensors.fake import FakeFeeder, FakeScale
from pettracker.sensors.camera import ReopeningCamera

FRAME = b"\xff\xd8 frame \xff\xd9"


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


class FakeDevice(Camera):
    """A camera that is present, or not, as the test decides."""

    def __init__(self, present=True, frame=FRAME):
        self.present = present
        self.frame = frame
        self.closed = False
        self.reads = 0

    def read_jpeg(self):
        self.reads += 1
        return self.frame if self.present else None

    @property
    def available(self):
        return self.present and not self.closed

    def close(self):
        self.closed = True


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class Factory:
    """Hands out the queued devices, then repeats the last one."""

    def __init__(self, *devices):
        self.queue = list(devices)
        self.made = []

    def __call__(self):
        device = self.queue.pop(0) if len(self.queue) > 1 else self.queue[0]
        self.made.append(device)
        return device


class OpeningTest(unittest.TestCase):

    def test_a_camera_that_is_there_is_used(self):
        camera = ReopeningCamera(Factory(FakeDevice()), clock=Clock())

        self.assertTrue(camera.available)
        self.assertEqual(FRAME, camera.read_jpeg())

    def test_a_camera_that_is_missing_is_not_fatal(self):
        camera = ReopeningCamera(Factory(FakeDevice(present=False)), clock=Clock())

        self.assertFalse(camera.available)
        self.assertIsNone(camera.read_jpeg())

    def test_a_camera_that_appears_later_is_picked_up(self):
        clock = Clock()
        factory = Factory(FakeDevice(present=False), FakeDevice())
        camera = ReopeningCamera(factory, retry_interval_s=10.0, clock=clock)

        self.assertFalse(camera.available)

        # The Pi beat USB enumeration at boot. Ten seconds later the webcam is
        # there, and nobody should have to restart the service for it.
        clock.advance(11)
        self.assertTrue(camera.available)
        self.assertEqual(FRAME, camera.read_jpeg())

    def test_attempts_are_rate_limited(self):
        clock = Clock()
        factory = Factory(FakeDevice(present=False))
        camera = ReopeningCamera(factory, retry_interval_s=10.0, clock=clock)

        for _ in range(20):
            camera.available

        # /health asks this on every poll, and opening a device that is not
        # there costs about a second of a request thread.
        self.assertEqual(1, len(factory.made))

    def test_one_attempt_per_interval(self):
        clock = Clock()
        factory = Factory(FakeDevice(present=False))
        camera = ReopeningCamera(factory, retry_interval_s=10.0, clock=clock)

        for _ in range(3):
            clock.advance(11)
            camera.available

        self.assertEqual(4, len(factory.made))     # the first, plus three retries

    def test_a_failed_attempt_releases_the_device(self):
        broken = FakeDevice(present=False)
        ReopeningCamera(Factory(broken), clock=Clock())

        # Half-open devices are how /dev/video0 ends up busy for the next try.
        self.assertTrue(broken.closed)


class RecoveryTest(unittest.TestCase):
    """A camera that is still open but has stopped producing."""

    def test_repeated_empty_reads_reopen_the_device(self):
        clock = Clock()
        wedged = FakeDevice()
        wedged.frame = None                      # open, and returning nothing
        replacement = FakeDevice()
        factory = Factory(wedged, replacement)
        camera = ReopeningCamera(factory, retry_interval_s=0.0, clock=clock)

        for _ in range(ReopeningCamera.FAILURES_BEFORE_REOPEN):
            camera.read_jpeg()

        # A webcam pulled mid-run keeps its file handle, so "available" stays
        # true and every frame is empty. Reporting that forever is useless.
        self.assertTrue(wedged.closed)
        self.assertEqual(FRAME, camera.read_jpeg())

    def test_an_occasional_dropped_frame_is_not_a_reopen(self):
        clock = Clock()
        device = FakeDevice()
        camera = ReopeningCamera(Factory(device), retry_interval_s=0.0, clock=clock)

        device.frame = None
        camera.read_jpeg()
        device.frame = FRAME
        camera.read_jpeg()
        device.frame = None
        camera.read_jpeg()

        # Real cameras drop the odd frame. Tearing the device down for that
        # would cost more frames than it saved.
        self.assertFalse(device.closed)

    def test_a_read_that_raises_is_treated_as_an_empty_frame(self):
        class Exploding(FakeDevice):
            def read_jpeg(self):
                raise OSError("device disconnected")

        camera = ReopeningCamera(Factory(Exploding()), retry_interval_s=0.0,
                                 clock=Clock())

        # Some drivers raise instead of returning nothing when the device goes.
        # Either way it is not a frame, and neither should reach the caller.
        self.assertIsNone(camera.read_jpeg())


class ClosingTest(unittest.TestCase):

    def test_close_releases_the_device(self):
        device = FakeDevice()
        camera = ReopeningCamera(Factory(device), clock=Clock())

        camera.close()

        self.assertTrue(device.closed)
        self.assertFalse(camera.available)

    def test_a_closed_camera_stays_closed(self):
        clock = Clock()
        factory = Factory(FakeDevice())
        camera = ReopeningCamera(factory, retry_interval_s=0.0, clock=clock)

        camera.close()
        clock.advance(60)

        # This is the service shutting down, not a camera falling out. Reopening
        # the device on the way out would leave it held after exit.
        self.assertFalse(camera.available)
        self.assertIsNone(camera.read_jpeg())

    def test_closing_twice_is_safe(self):
        camera = ReopeningCamera(Factory(FakeDevice()), clock=Clock())

        camera.close()
        camera.close()


if __name__ == "__main__":
    unittest.main()


class CheckCameraTest(unittest.TestCase):
    """The bring-up view, which asks the questions /health cannot.

    /health says the device opened. A camera can open, hold the device so
    nothing else can have it, and return nothing — or return frames that are not
    JPEGs, which the app's strict parser shows as a broken stream rather than a
    broken camera.
    """

    class Fixed(Camera):
        def __init__(self, *frames, present=True):
            self.frames = list(frames)
            self.index = 0
            self.present = present

        def read_jpeg(self):
            if not self.frames:
                return None
            frame = self.frames[self.index % len(self.frames)]
            self.index += 1
            return frame

        @property
        def available(self):
            return self.present

        def close(self):
            pass

    def run_check(self, camera, **kwargs):
        import io as stdio
        from pettracker.bringup import check_camera

        out = stdio.StringIO()
        check_camera(camera, frames=6, out=out, **kwargs)
        return out.getvalue()

    def jpeg(self, size):
        return b"\xff\xd8" + b"x" * size + b"\xff\xd9"

    def test_a_camera_that_did_not_open_says_what_to_check(self):
        text = self.run_check(self.Fixed(present=False))

        self.assertIn("ribbon", text)
        self.assertIn("/dev/video", text)

    def test_frames_are_counted_and_measured(self):
        text = self.run_check(self.Fixed(self.jpeg(20000), self.jpeg(21000)))

        self.assertIn("6 of 6 frames arrived", text)

    def test_an_open_camera_returning_nothing_is_called_out(self):
        text = self.run_check(self.Fixed())

        # The state a USB camera lands in when another process holds it.
        self.assertIn("returned no frames at all", text)

    def test_frames_that_are_not_jpegs_are_flagged(self):
        text = self.run_check(self.Fixed(b"not a jpeg at all"))

        # The app decodes with a strict parser, so this shows up there as a
        # broken stream and gets blamed on the network.
        self.assertIn("not valid JPEGs", text)

    def test_identical_frame_sizes_suggest_a_test_pattern(self):
        text = self.run_check(self.Fixed(self.jpeg(20000)))

        # A real scene never produces byte-identical frames; sensor noise alone
        # changes the compressed size.
        self.assertIn("test pattern", text)

    def test_the_last_frame_can_be_saved_for_looking_at(self):
        import tempfile

        handle, path = tempfile.mkstemp(suffix=".jpg")
        os.close(handle)
        try:
            frame = self.jpeg(1000)
            text = self.run_check(self.Fixed(frame, self.jpeg(1100)),
                                  save_to=path)

            with open(path, "rb") as saved:
                self.assertTrue(saved.read().startswith(b"\xff\xd8"))
            # Because a camera can work perfectly and point at the ceiling.
            self.assertIn("pointing at", text)
        finally:
            os.unlink(path)


class TwoCameras(unittest.TestCase):
    """A rig with a CSI camera and a USB webcam, and one client choosing.

    The two buses are independent, which is what makes this worth supporting:
    a CSI camera and a webcam cost each other nothing, while two webcams
    compete for one USB controller and neither gets a usable frame rate.
    """

    def _set(self, names):
        from collections import OrderedDict
        cameras = OrderedDict((n, FakeDevice()) for n in names)
        return SensorSet(
            scale=FakeScale(), motion_sensors=[], feeder=FakeFeeder(),
            camera=next(iter(cameras.values())), cameras=cameras)

    def test_health_names_every_camera(self):
        health = self._set(["usb", "csi"]).health()

        self.assertEqual({"usb": True, "csi": True}, health["cameras"])

    def test_health_keeps_the_old_flag_for_older_clients(self):
        # A phone built before the second camera reads sensors.camera and must
        # keep working. True when any camera is up.
        self.assertTrue(self._set(["usb", "csi"]).health()["camera"])

    def test_the_first_named_is_the_primary(self):
        sensors = self._set(["usb", "csi"])

        # What /stream serves with no camera named, and where motion snapshots
        # come from.
        self.assertIs(sensors.cameras["usb"], sensors.camera)
        self.assertIs(sensors.cameras["usb"], sensors.camera_named(None))
        self.assertIs(sensors.cameras["usb"], sensors.camera_named(""))

    def test_a_camera_can_be_chosen_by_name(self):
        sensors = self._set(["usb", "csi"])

        self.assertIs(sensors.cameras["csi"], sensors.camera_named("csi"))

    def test_an_unknown_name_is_not_the_primary(self):
        # None, so the server can answer 404. Falling back to the primary would
        # show the hall to a phone that asked for the kitchen, and nothing in
        # the picture would say so.
        self.assertIsNone(self._set(["usb", "csi"]).camera_named("garden"))

    def test_one_camera_still_behaves_as_it_always_did(self):
        sensors = SensorSet(scale=FakeScale(), motion_sensors=[],
                            feeder=FakeFeeder(), camera=FakeDevice())

        self.assertIs(sensors.camera, sensors.camera_named(None))
        self.assertTrue(sensors.health()["camera"])
        self.assertEqual(1, len(sensors.health()["cameras"]))

    def test_closing_releases_every_camera(self):
        sensors = self._set(["usb", "csi"])
        sensors.close()

        for camera in sensors.cameras.values():
            self.assertTrue(camera.closed)


class BuildingCameras(unittest.TestCase):
    """Parsing the --camera setting, which is now a list."""

    def _build(self, spec):
        from pettracker.sensors.real import build_cameras
        return build_cameras(spec)

    def test_one_kind(self):
        self.assertEqual(["usb"], list(self._build("usb")))

    def test_two_kinds_keep_their_order(self):
        # The order decides which is primary, so it is not incidental.
        self.assertEqual(["usb", "csi"], list(self._build("usb,csi")))
        self.assertEqual(["csi", "usb"], list(self._build("csi,usb")))

    def test_whitespace_is_forgiven(self):
        self.assertEqual(["usb", "csi"], list(self._build(" usb , csi ")))

    def test_an_unknown_kind_is_refused(self):
        with self.assertRaises(ValueError):
            self._build("usb,thermal")

    def test_the_same_kind_twice_is_refused(self):
        # A Pi has one CSI connector, and two webcams on one controller is a
        # rig that does not work rather than one that is unsupported.
        with self.assertRaises(ValueError):
            self._build("usb,usb")

    def test_none_cannot_be_mixed_with_a_real_camera(self):
        # It would add a camera that can never produce a frame, and /health
        # would report it as a camera that is simply unavailable.
        with self.assertRaises(ValueError):
            self._build("usb,none")

    def test_none_alone_is_a_rig_with_no_camera(self):
        self.assertEqual(["none"], list(self._build("none")))

    def test_nothing_at_all_is_refused(self):
        with self.assertRaises(ValueError):
            self._build("")
