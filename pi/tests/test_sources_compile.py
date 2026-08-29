"""Every source file parses, and the entry point can build its arguments.

This exists because it did not, and the gap it leaves is invisible: run.py is
the one file no other test imports, so a syntax error in it passed a full green
suite and was committed, pushed, and only found by running the service by hand.
Nothing else in the project can break this way — the modules are all imported by
something — but the file a user actually types is exactly the file worth
protecting.

Cheap, and it also catches the sort of damage an automated edit can do to a file
nobody happens to open afterwards.
"""

import os
import sys
import unittest

PI_ROOT = os.path.join(os.path.dirname(__file__), "..")
sys.path.insert(0, PI_ROOT)


def python_files():
    for directory, _, names in os.walk(PI_ROOT):
        if "__pycache__" in directory or ".git" in directory:
            continue
        for name in names:
            if name.endswith(".py"):
                yield os.path.join(directory, name)


class SourcesCompileTest(unittest.TestCase):

    def test_every_source_file_parses(self):
        for path in python_files():
            with self.subTest(path=os.path.relpath(path, PI_ROOT)):
                with open(path, encoding="utf-8") as source:
                    text = source.read()
                # compile() rather than py_compile: it writes nothing, needs no
                # temporary directory, and behaves the same on every platform.
                try:
                    compile(text, path, "exec")
                except SyntaxError as e:
                    self.fail(f"{os.path.relpath(path, PI_ROOT)}: {e}")

    def test_the_entry_point_imports(self):
        # Importing run.py is safe: everything it does is behind main() and the
        # __main__ guard.
        import run

        self.assertTrue(hasattr(run, "main"))

    def test_the_entry_point_builds_its_arguments(self):
        import subprocess

        result = subprocess.run(
            [sys.executable, os.path.join(PI_ROOT, "run.py"), "--help"],
            capture_output=True, text=True, timeout=60)

        # --help exercises the whole argparse setup, which is where a typo in a
        # flag would otherwise sit until somebody used that flag.
        self.assertEqual(0, result.returncode, result.stderr)
        for flag in ("--fake", "--rooms", "--pir-pins", "--camera",
                     "--scale-pins", "--check-motion", "--check-scale",
                     "--check-gps"):
            self.assertIn(flag, result.stdout, flag)


if __name__ == "__main__":
    unittest.main()
