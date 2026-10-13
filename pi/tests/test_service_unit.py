import os
import re
import shlex
import sys
import unittest

PI_ROOT = os.path.join(os.path.dirname(__file__), "..")
sys.path.insert(0, PI_ROOT)

UNIT_PATH = os.path.join(PI_ROOT, "pettracker.service")

ENVIRONMENT = re.compile(r'^Environment="([A-Za-z_][A-Za-z0-9_]*)=(.*)"$')


def read_unit():
    with open(UNIT_PATH, encoding="utf-8") as handle:
        return handle.read()


def unit_environment(text):
    values = {}
    for line in text.splitlines():
        match = ENVIRONMENT.match(line.strip())
        if match:
            values[match.group(1)] = match.group(2)
    return values


def unit_exec_start(text):
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
    def expand(word):
        return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}",
                      lambda m: environment.get(m.group(1), "\x00" + m.group(1)),
                      word)

    return [expand(word) for word in shlex.split(command)]


def unit_arguments(environment=None):
    text = read_unit()
    parts = substitute(unit_exec_start(text),
                       environment if environment is not None
                       else unit_environment(text))
    return parts[parts.index("run.py") + 1:]


class UnitFileSyntax(unittest.TestCase):

    def test_every_environment_value_is_quoted(self):
        for line in read_unit().splitlines():
            stripped = line.strip()
            if stripped.startswith("Environment=") and "EnvironmentFile" not in stripped:
                self.assertRegex(stripped, ENVIRONMENT,
                                 "Environment= value is not quoted: " + stripped)

    def test_exec_start_uses_braced_variables(self):
        command = unit_exec_start(read_unit())
        self.assertNotRegex(command, r"\$[A-Za-z_]",
                            "ExecStart uses $VAR; it must use ${VAR}")

    def test_every_variable_used_is_declared(self):
        arguments = " ".join(unit_arguments())
        self.assertNotIn("\x00", arguments,
                         "ExecStart uses a variable the unit never declares")


class UnitStartsTheService(unittest.TestCase):
    def setUp(self):
        import run
        self.args = run.build_parser().parse_args(unit_arguments())

    def test_the_unit_command_line_parses(self):
        self.assertIsNotNone(self.args)

    def test_the_feeder_can_be_selected(self):
        from pettracker.sensors.real import FEEDER_KINDS
        self.assertIn(self.args.feeder, FEEDER_KINDS)

    def test_the_paddle_is_reachable_by_changing_one_variable(self):
        environment = unit_environment(read_unit())
        environment["PETTRACKER_FEEDER"] = "paddle"

        import run
        args = run.build_parser().parse_args(unit_arguments(environment))
        self.assertEqual(args.feeder, "paddle")
        self.assertIsNotNone(args.paddle_grams)

    def test_a_room_name_with_a_space_survives(self):
        import run
        args = run.build_parser().parse_args(unit_arguments())
        rooms = [r.strip() for r in args.rooms.split(",") if r.strip()]
        pins = [p for p in args.pir_pins.split(",") if p.strip()]
        self.assertEqual(len(rooms), len(pins),
                         "the unit declares %d room(s) and %d pin(s)"
                         % (len(rooms), len(pins)))


class UnitDefaultsMatchCodeDefaults(unittest.TestCase):
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
        import run
        self.assertEqual(self.args.gps_port,
                         run.build_parser().get_default("gps_port"))

    def test_camera_device(self):
        import run
        self.assertEqual(self.args.camera_device,
                         run.build_parser().get_default("camera_device"))

    def test_scale_pins(self):
        from pettracker.sensors.real import DEFAULT_SCALE_PINS, parse_pins
        self.assertEqual(parse_pins(self.args.scale_pins), DEFAULT_SCALE_PINS)


if __name__ == "__main__":
    unittest.main()


class TheHoldFeederIsReachable(unittest.TestCase):
    def test_hold_is_reachable_by_changing_one_variable(self):
        environment = unit_environment(read_unit())
        environment["PETTRACKER_FEEDER"] = "hold"

        import run
        args = run.build_parser().parse_args(unit_arguments(environment))

        self.assertEqual("hold", args.feeder)
        from pettracker.sensors.real import FEEDER_KINDS
        self.assertIn("hold", FEEDER_KINDS)

    def test_the_travel_time_reaches_the_program(self):
        import run
        args = run.build_parser().parse_args(unit_arguments())

        self.assertEqual(args.servo_travel,
                         run.build_parser().get_default("servo_travel"))
        self.assertGreater(args.servo_travel, 0)
