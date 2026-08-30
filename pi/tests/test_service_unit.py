"""The systemd unit and the program it starts agree with each other.

The unit file is the one piece of configuration that is never exercised by
running anything on a laptop. It is read by systemd, on the Pi, at boot, and
when it is wrong the only evidence is a service that exits 2 into the journal
and an app that looks broken. Every bug this file has had was of that shape:

  * an unquoted Environment= value split on a space, so "Kitchen,Living room,
    Hall" became two rooms and the startup error talked about pin counts;
  * a feeder the unit could not select at all, because the ExecStart line
    passed no --feeder, so a rig built as a paddle wheel would have run the
    gate code and metered food by a time that means nothing to it.

So the tests here do what systemd does — read the Environment= defaults,
substitute them into ExecStart — and then hand the result to the real parser
from run.py. A flag the unit passes and the program does not have fails here,
on a laptop, in a second, instead of on the Pi at boot.

The defaults are deliberately duplicated between the unit and the code: the
unit is meant to be readable on its own, and someone editing service.env should
be able to see what they are overriding. Duplication is only safe while
something checks it, which is the second half of this file.
"""

import os
import re
import shlex
import sys
import unittest

PI_ROOT = os.path.join(os.path.dirname(__file__), "..")
sys.path.insert(0, PI_ROOT)

UNIT_PATH = os.path.join(PI_ROOT, "pettracker.service")

#: Environment="NAME=value" — the quotes are part of the syntax, not decoration,
#: and a value containing a space without them is the bug described above.
ENVIRONMENT = re.compile(r'^Environment="([A-Za-z_][A-Za-z0-9_]*)=(.*)"$')


def read_unit():
    with open(UNIT_PATH, encoding="utf-8") as handle:
        return handle.read()


def unit_environment(text):
    """The defaults the unit declares, as systemd would hold them."""
    values = {}
    for line in text.splitlines():
        match = ENVIRONMENT.match(line.strip())
        if match:
            values[match.group(1)] = match.group(2)
    return values


def unit_exec_start(text):
    """The ExecStart line, with its backslash continuations joined up."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if not line.startswith("ExecStart="):
            continue
        collected = [line[len("ExecStart="):]]
        while collected[-1].rstrip().endswith("\\"):
            collected[-1] = collected[-1].rstrip()[:-1]
            index += 1
            collected.append(lines[index])
        return " ".join(part.strip() for part in collected)
    raise AssertionError("the unit has no ExecStart line")


def substitute(command, environment):
    """The argv systemd builds: split into words first, expand ${VAR} after.

    The order is the whole point. Substituting into the string and splitting
    afterwards would turn --rooms "Kitchen,Living room,Hall" into two arguments
    and reproduce, inside the test, the exact bug the ${VAR} form exists to
    prevent — the test would then agree with a broken unit. systemd expands
    ${VAR} to precisely one argument however many spaces the value holds, so
    the expansion has to happen per word.

    Deliberately not os.path.expandvars, which also expands $VAR. Anything left
    unsubstituted is marked, so a variable the unit uses but never declares is
    visible rather than silently empty.
    """
    def expand(word):
        return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}",
                      lambda m: environment.get(m.group(1), "\x00" + m.group(1)),
                      word)

    return [expand(word) for word in shlex.split(command)]


def unit_arguments(environment=None):
    """The argv the service is started with, using only the unit's defaults."""
    text = read_unit()
    parts = substitute(unit_exec_start(text),
                       environment if environment is not None
                       else unit_environment(text))
    # ExecStart is "/usr/bin/python3 run.py --rooms ..."; the parser sees what
    # follows the script name.
    return parts[parts.index("run.py") + 1:]


class UnitFileSyntax(unittest.TestCase):

    def test_every_environment_value_is_quoted(self):
        # An unquoted value is only a bug when it contains a space, but the
        # rule is easier to keep than the exception, and the failure it causes
        # is a startup error that points at the wrong setting entirely.
        for line in read_unit().splitlines():
            stripped = line.strip()
            if stripped.startswith("Environment=") and "EnvironmentFile" not in stripped:
                self.assertRegex(stripped, ENVIRONMENT,
                                 "Environment= value is not quoted: " + stripped)

    def test_exec_start_uses_braced_variables(self):
        # $VAR splits on whitespace and would quietly drop two of three rooms.
        command = unit_exec_start(read_unit())
        self.assertNotRegex(command, r"\$[A-Za-z_]",
                            "ExecStart uses $VAR; it must use ${VAR}")

    def test_every_variable_used_is_declared(self):
        arguments = " ".join(unit_arguments())
        self.assertNotIn("\x00", arguments,
                         "ExecStart uses a variable the unit never declares")


class UnitStartsTheService(unittest.TestCase):
    """The unit's own command line, through the program's own parser."""

    def setUp(self):
        import run
        self.args = run.build_parser().parse_args(unit_arguments())

    def test_the_unit_command_line_parses(self):
        # setUp does the work: argparse exits the process on an unknown flag,
        # so reaching here at all is the assertion.
        self.assertIsNotNone(self.args)

    def test_the_feeder_can_be_selected(self):
        # The gap this whole file was written for. Before the unit passed
        # --feeder, a rig built as a paddle wheel ran the gate code no matter
        # what service.env said, and metered food by a duration the paddle has
        # no use for.
        from pettracker.sensors.real import FEEDER_KINDS
        self.assertIn(self.args.feeder, FEEDER_KINDS)

    def test_the_paddle_is_reachable_by_changing_one_variable(self):
        # What someone will actually do to service.env once the wheel is built.
        environment = unit_environment(read_unit())
        environment["PETTRACKER_FEEDER"] = "paddle"

        import run
        args = run.build_parser().parse_args(unit_arguments(environment))
        self.assertEqual(args.feeder, "paddle")
        self.assertIsNotNone(args.paddle_grams)

    def test_a_room_name_with_a_space_survives(self):
        # "Living room" is one room. It has been two before now.
        import run
        args = run.build_parser().parse_args(unit_arguments())
        rooms = [r.strip() for r in args.rooms.split(",") if r.strip()]
        pins = [p for p in args.pir_pins.split(",") if p.strip()]
        self.assertEqual(len(rooms), len(pins),
                         "the unit declares %d room(s) and %d pin(s)"
                         % (len(rooms), len(pins)))


class UnitDefaultsMatchCodeDefaults(unittest.TestCase):
    """The duplication is allowed because this class exists.

    Someone reading service.env should be able to see the numbers they are
    overriding without reading Python. That is worth a copy, but only while a
    test notices when the copy stops being true — a unit that says 30 degrees
    and code that means 45 is a paddle that parks in the wrong place with
    nothing anywhere reporting a disagreement.
    """

    def setUp(self):
        import run
        self.args = run.build_parser().parse_args(unit_arguments())

    def test_gate_angles(self):
        from pettracker.sensors.real import ServoFeeder
        self.assertEqual(self.args.servo_open, ServoFeeder.DEFAULT_OPEN_ANGLE)
        self.assertEqual(self.args.servo_closed, ServoFeeder.DEFAULT_CLOSED_ANGLE)
        self.assertEqual(self.args.servo_ms_per_gram, ServoFeeder.DEFAULT_MS_PER_GRAM)

    def test_paddle_angles(self):
        from pettracker.sensors.real import PaddleFeeder
        self.assertEqual(self.args.paddle_park, PaddleFeeder.DEFAULT_PARK_ANGLE)
        self.assertEqual(self.args.paddle_dump, PaddleFeeder.DEFAULT_DUMP_ANGLE)
        self.assertEqual(self.args.paddle_grams, PaddleFeeder.DEFAULT_GRAMS_PER_CYCLE)

    def test_gps_port(self):
        # The service reads this port; --check-gps defaults to the same one, and
        # a bring-up that proves a port the service never opens proves nothing.
        import run
        self.assertEqual(self.args.gps_port,
                         run.build_parser().get_default("gps_port"))

    def test_scale_pins(self):
        from pettracker.sensors.real import DEFAULT_SCALE_PINS, parse_pins
        self.assertEqual(parse_pins(self.args.scale_pins), DEFAULT_SCALE_PINS)


if __name__ == "__main__":
    unittest.main()
