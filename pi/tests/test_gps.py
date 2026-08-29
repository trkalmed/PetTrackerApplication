"""NMEA parsing, and the serial port that carries it.

The parsing is where a GPS goes wrong quietly. Every mistake here produces a
number rather than an error: a receiver with no fix reports zeros, which is a
real place in the Gulf of Guinea; degrees-and-minutes read as decimal degrees
lands you in roughly the right country; and a line corrupted by noise can parse
cleanly into somewhere else entirely.

The port is exercised against a file, which runs the same os.read path as the
real thing with no hardware and no GPS.
"""

import logging
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pettracker.nmea import parse_gga, valid_checksum
from pettracker.sensors.real import Neo6mLocationSensor

#: A real sentence, from the NEO-6M datasheet's own example.
FIX = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

#: What the same receiver sends before it has found anything: quality 0, and
#: empty coordinate fields.
NO_FIX = "$GPGGA,120000.00,,,,,0,00,99.99,,,,,,*65"


def setUpModule():
    logging.disable(logging.CRITICAL)


def tearDownModule():
    logging.disable(logging.NOTSET)


def nmea(body: str) -> str:
    """Wraps a body with its $ and its XOR checksum, so tests can build lines."""
    checksum = 0
    for character in body:
        checksum ^= ord(character)
    return f"${body}*{checksum:02X}"


class ChecksumTest(unittest.TestCase):

    def test_accepts_a_good_sentence(self):
        self.assertTrue(valid_checksum(FIX))

    def test_rejects_a_corrupted_one(self):
        # One digit changed. Serial at 9600 baud beside a servo does this, and
        # the result parses perfectly into a position that is not yours.
        corrupted = FIX.replace("4807.038", "4907.038")

        self.assertFalse(valid_checksum(corrupted))

    def test_rejects_a_sentence_with_no_checksum(self):
        self.assertFalse(valid_checksum("$GPGGA,123519,4807.038,N"))

    def test_rejects_rubbish(self):
        for line in ("", "hello", "*47", "$GPGGA*zz"):
            self.assertFalse(valid_checksum(line), line)


class ParsingTest(unittest.TestCase):

    def test_reads_a_position(self):
        fix = parse_gga(FIX)

        # 4807.038 is 48 degrees 7.038 minutes. Read as decimal degrees it would
        # be 48.07 — about eight kilometres out, and plausible enough to keep.
        self.assertAlmostEqual(48.1173, fix["lat"], places=4)
        self.assertAlmostEqual(11.5167, fix["lon"], places=4)
        self.assertEqual(8, fix["satellites"])

    def test_no_fix_is_not_a_position(self):
        # The single most important line in this file. Quality 0 means the
        # coordinates are meaningless, and they are frequently zeros — which is
        # a real spot in the Atlantic, and where an unguarded parser puts you.
        self.assertIsNone(parse_gga(NO_FIX))

    def test_zero_coordinates_with_no_fix_are_refused(self):
        self.assertIsNone(parse_gga(nmea(
            "GPGGA,120000.00,0000.0000,N,00000.0000,E,0,00,99.99,0.0,M,0.0,M,,")))

    def test_southern_and_western_hemispheres_are_negative(self):
        fix = parse_gga(nmea(
            "GPGGA,123519,3352.000,S,15112.000,W,1,06,0.9,10.0,M,0.0,M,,"))

        self.assertAlmostEqual(-33.8667, fix["lat"], places=4)
        self.assertAlmostEqual(-151.2000, fix["lon"], places=4)

    def test_longitude_has_three_degree_digits_not_two(self):
        # Latitude goes to 90 and longitude to 180, so the split between degrees
        # and minutes differs between the two fields.
        fix = parse_gga(nmea(
            "GPGGA,123519,0000.000,N,17959.000,E,1,06,0.9,10.0,M,0.0,M,,"))

        self.assertAlmostEqual(179.9833, fix["lon"], places=4)

    def test_a_multi_constellation_receiver_is_understood(self):
        # Modern modules send GN rather than GP once they are using more than
        # one constellation, and can switch between the two mid-session.
        fix = parse_gga(nmea(
            "GNGGA,123519,4807.038,N,01131.000,E,1,09,0.9,545.4,M,46.9,M,,"))

        self.assertIsNotNone(fix)
        self.assertEqual(9, fix["satellites"])

    def test_other_sentences_are_ignored(self):
        for body in ("GPGSV,1,1,04,01,40,083,41", "GPRMC,123519,A,4807.038,N"):
            self.assertIsNone(parse_gga(nmea(body)))

    def test_a_corrupted_sentence_is_refused(self):
        self.assertIsNone(parse_gga(FIX.replace("4807.038", "4907.038")))

    def test_a_truncated_sentence_is_refused(self):
        self.assertIsNone(parse_gga(nmea("GPGGA,123519,4807.038,N")))

    def test_impossible_coordinates_are_refused(self):
        self.assertIsNone(parse_gga(nmea(
            "GPGGA,123519,9959.000,N,01131.000,E,1,06,0.9,10.0,M,0.0,M,,")))


class SerialPortTest(unittest.TestCase):
    """The reading half, against a file rather than a UART.

    A file rather than a pipe: os.read on a file with nothing more in it returns
    empty immediately, which is how the non-blocking serial port behaves, while
    a pipe holds a writer hostage once its buffer fills — twenty kilobytes into
    one that nothing drains simply hangs. The code path under test is the same
    os.read either way.
    """

    def setUp(self):
        handle, self.path = tempfile.mkstemp(suffix=".nmea")
        os.close(handle)
        self.read_fd = os.open(self.path, os.O_RDONLY)
        self.gps = Neo6mLocationSensor(
            port=self.path, opener=lambda path, baud: self.read_fd)

    def tearDown(self):
        self.gps.close()
        try:
            os.unlink(self.path)
        except OSError:
            pass

    def send(self, *lines):
        self.write_bytes(("\n".join(lines) + "\n").encode("ascii"))

    def write_bytes(self, payload):
        with open(self.path, "ab") as port:
            port.write(payload)

    def test_reads_the_sentences_that_arrive(self):
        self.send(NO_FIX, FIX)

        self.assertEqual([NO_FIX, FIX], self.gps.read_sentences())

    def test_a_fix_is_returned_once_one_arrives(self):
        self.send(NO_FIX)
        self.assertIsNone(self.gps.read_fix())

        self.send(FIX)
        self.assertIsNotNone(self.gps.read_fix())

    def test_the_last_known_fix_survives_a_quiet_moment(self):
        self.send(FIX)
        self.gps.read_fix()

        # Nothing new to read. A module briefly losing a satellite should not
        # blank the position on the next poll.
        self.assertIsNotNone(self.gps.read_fix())

    def test_half_a_sentence_waits_for_the_rest(self):
        self.write_bytes(b"$GPGGA,123519,4807.038,N,011")
        self.assertEqual([], self.gps.read_sentences())

        self.write_bytes(b"31.000,E,1,08,0.9,545.4,M,46.9,M,,*47\n")

        # A read lands wherever it lands in the stream; a sentence split across
        # two of them must not become two broken sentences.
        self.assertEqual([FIX], self.gps.read_sentences())

    def test_talking_means_available_even_with_no_fix(self):
        self.send(NO_FIX)
        self.gps.read_sentences()

        # The distinction the whole class rests on: a GPS indoors talks
        # constantly and knows nothing, and that is a working GPS.
        self.assertTrue(self.gps.available)
        self.assertIsNone(self.gps.read_fix())

    def test_silence_means_unavailable(self):
        self.assertFalse(self.gps.available)

    def test_a_module_that_stops_talking_stops_being_available(self):
        clock = _Clock()
        gps = Neo6mLocationSensor(port=self.path, clock=clock,
                                  opener=lambda path, baud: self.read_fd)
        self.send(NO_FIX)
        gps.read_sentences()
        self.assertTrue(gps.available)

        clock.advance(60)

        self.assertFalse(gps.available)

    def test_a_flood_of_data_does_not_grow_forever(self):
        # A module talking to nobody for a week. Nothing consumes the buffer
        # unless a newline arrives, so it needs a ceiling of its own.
        self.write_bytes(b"x" * 20000)
        self.gps.read_sentences()

        self.assertLessEqual(len(self.gps._buffer), 8192)


class NoPortTest(unittest.TestCase):

    def test_a_missing_port_is_inert_rather_than_fatal(self):
        def refuse(path, baud):
            raise OSError("No such file or directory")

        gps = Neo6mLocationSensor(port="/dev/nope", opener=refuse)

        # The serial console still owning /dev/serial0 is the usual cause, and
        # it must not stop the motion sensors from running.
        self.assertFalse(gps.available)
        self.assertIsNone(gps.read_fix())
        self.assertEqual([], gps.read_sentences())
        gps.close()


class _Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


if __name__ == "__main__":
    unittest.main()


class FrameErrorNoticeTest(unittest.TestCase):
    """A module complaining about its own receiver is not a fault here."""

    class Talking:
        port = "test"

        def __init__(self, *sentences):
            self.sentences = list(sentences)

        def read_sentences(self):
            out, self.sentences = self.sentences, []
            return out

    def test_a_frame_error_notice_is_explained(self):
        import io
        from pettracker.bringup import check_gps

        out = io.StringIO()
        check_gps(self.Talking(
            "$GNTXT,01,01,01,More than 100 frame errors, UART RX was disabled*70",
            NO_FIX), duration_s=0.05, interval_s=0.01, out=out)

        text = out.getvalue()
        # It reads like a failure and is not one: the RX side matters only for
        # configuring the module, and this service never writes to it.
        self.assertIn("disabled its RX line", text)
        self.assertIn("read-only", text)

    def test_a_quiet_module_is_not_accused_of_frame_errors(self):
        import io
        from pettracker.bringup import check_gps

        out = io.StringIO()
        check_gps(self.Talking(NO_FIX), duration_s=0.05, interval_s=0.01, out=out)

        self.assertNotIn("frame errors", out.getvalue())
