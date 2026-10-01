"""Tests for `scripts/check-agent-jar.py`.

**Why a synthetic jar.** The real one only exists after `./mvnw -pl core -am package`, so a
test written against it could only run on a machine that has already done the thing the
checker checks. These build a two-class jar in a temp dir instead, which is also what makes
the teeth demonstrable: each test states the incident it reproduces, and a checker that
cannot fail on any of them is not a checker.

Run:  python -m unittest discover -s scripts -p 'test_check_agent_jar.py'
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent


def _load_checker():
    """Import the checker by path, so `scripts/` need not be a package."""
    spec = importlib.util.spec_from_file_location(
        "check_agent_jar", _ROOT / "scripts" / "check-agent-jar.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


checker = _load_checker()


class CheckAgentJarTest(unittest.TestCase):
    CLASS_A = "net/marcloud/mcp/core/Fresh.class"
    CLASS_B = "net/marcloud/mcp/core/drivers/observe/ChatTools.class"

    def _fixture(self, jar_entries=None, disk=None, signed=True):
        """Build a classes dir and a jar. `jar_entries`/`disk` override either side, and
        `signed=False` builds a jar with no trust material in it at all."""
        tmp = Path(tempfile.mkdtemp())
        classes = tmp / "classes"
        jar = tmp / "core-1.8.9-all.jar"

        disk = disk if disk is not None else {
            self.CLASS_A: b"compiled-A",
            self.CLASS_B: b"compiled-B",
        }
        jar_entries = jar_entries if jar_entries is not None else dict(disk)
        if signed:
            for name in checker.SIGNED_RESOURCES:
                jar_entries.setdefault(name, b"signed")

        for name, body in disk.items():
            target = classes / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        with zipfile.ZipFile(jar, "w") as z:
            for name, body in jar_entries.items():
                z.writestr(name, body)
        return jar, classes

    # ===== the three states it must tell apart =====

    def test_a_jar_matching_the_compiled_tree_reports_nothing(self):
        jar, classes = self._fixture()
        self.assertEqual([], checker.check(jar, classes))

    def test_a_class_missing_from_the_jar_is_reported_missing(self):
        """A provider class that never got packaged: `Tool not found` on a live client."""
        jar, classes = self._fixture()
        disk = {self.CLASS_A: b"compiled-A", self.CLASS_B: b"compiled-B"}
        jar_entries = {self.CLASS_A: b"compiled-A"}  # ChatTools.class simply not in the jar
        jar, classes = self._fixture(jar_entries, disk)
        problems = checker.check(jar, classes)
        self.assertEqual([f"MISSING  {self.CLASS_B}"], problems)

    def test_a_class_whose_bytes_differ_is_reported_stale(self):
        """The 2026-09-30 incident: the jar is valid, older, and nobody can tell by looking."""
        jar, classes = self._fixture()
        jar_entries = {self.CLASS_A: b"compiled-A", self.CLASS_B: b"the-PREVIOUS-build"}
        jar, classes = self._fixture(jar_entries)
        problems = checker.check(jar, classes)
        self.assertEqual([f"STALE    {self.CLASS_B}"], problems)

    def test_missing_trust_material_is_reported(self):
        """An unsigned-resource gap does not break a call -- it silently disarms every patch."""
        jar, classes = self._fixture(signed=False)
        problems = checker.check(jar, classes)
        self.assertEqual(
            [f"MISSING  {r}  (the compat engine cannot arm any patch)"
             for r in checker.SIGNED_RESOURCES],
            problems,
        )

    # ===== the checker's own premise =====

    def test_the_launcher_path_is_read_from_the_launcher_not_assumed(self):
        """If run-mcp.bat ever points elsewhere, this must follow it rather than check a
        jar nobody runs -- and must say so if the declaration disappears entirely."""
        jar = checker.launcher_jar("CORE_JAR")
        self.assertTrue(jar.is_absolute(), f"expected an absolute path, got {jar}")
        self.assertEqual("core-1.8.9-all.jar", jar.name)
        with self.assertRaises(SystemExit):
            checker.launcher_jar("NO_SUCH_VARIABLE")

    def test_an_empty_classes_directory_is_refused_by_main_not_reported_as_success(self):
        """A clean tree must not read as success: there is nothing to have compared, and a
        checker that says "OK, 0 classes match" is the exact shape that hid the stale jar."""
        tmp = Path(tempfile.mkdtemp())
        classes = tmp / "core" / "target" / "classes"
        classes.mkdir(parents=True)
        jar = tmp / "core-1.8.9-all.jar"
        with zipfile.ZipFile(jar, "w") as z:
            for resource in checker.SIGNED_RESOURCES:
                z.writestr(resource, b"signed")

        original_launcher, original_root = checker.launcher_jar, checker.ROOT
        checker.launcher_jar = lambda name: jar
        checker.ROOT = tmp
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = checker.main()
        finally:
            checker.launcher_jar, checker.ROOT = original_launcher, original_root

        self.assertEqual(1, rc)
        self.assertIn("holds no classes", out.getvalue())


if __name__ == "__main__":
    unittest.main()