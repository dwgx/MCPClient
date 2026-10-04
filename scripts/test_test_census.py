"""Tests for `scripts/test-census.py` — the tool that answers "how many tests, and are they green".

**Why a synthetic tree rather than the real repository.** Every property here is a property of
the *tool's* discrimination, not of this repository's current contents. Running the tool against
the real tree proves only that it runs; it cannot prove the tool would *notice* a ghost, because
the real tree happens to have none. So each test builds a miniature module with a known answer and
asserts the tool reaches it.

**The four failures this exists to catch, and why a naive tool misses all four.**

1. *A module dropped from the count.* A module list written down by hand goes stale silently.
   The tool parses `<modules>` recursively, so a module two levels down is counted even though
   nothing in this file's fixture list mentions it. A non-recursive parse finds the aggregator
   and stops — which is how `pg-engine`'s five tests left a whole-repo total.

2. *A ghost inflating the total.* A report whose class has no source is a deleted probe class.
   Summing the directory counts it as loudly as a real test; this repository has been bitten
   five separate times, most visibly `guarantees.md` §0 (three ghosts, total wrong by exactly 3).

3. *A source that was never asked anything.* The mirror image, and strictly worse: it contributes
   nothing to a naive sum, so it is invisible to anyone who sums. Only a
   collectable-minus-live-reports set difference finds it.

4. *Two populations added together.* Surefire and failsafe reports live in different directories,
   are collected by different plugins, and sit behind different skip flags. A tool that sums both
   publishes a number no single command ever printed — the error this repository's whole counting
   history is made of, so it gets its own assertion.

**And the arithmetic rule that makes "green" mean something.** A skipped method is collected but
never executed; surefire reports SKIPPED and the build reports SUCCESS, which is the same outcome
shape as a passing test. Green therefore excludes skipped, and the skip is named.

Run:  python -m unittest discover -s scripts -p 'test_test_census.py'
"""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

_TOOL = pathlib.Path(__file__).resolve().parent / "test-census.py"
_spec = importlib.util.spec_from_file_location("test_census_under_test", _TOOL)
census = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(census)

# A class surefire would collect if it ever ran, and a `main()` driver wearing the same name.
NEVER_RAN = ('import org.junit.Test;\npublic class %s {\n'
             '  @Test public void neverAskedAnything() {}\n}')
DRIVER = "public class %s { public static void main(String[] a) {} }"

POM = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
    "  <modelVersion>4.0.0</modelVersion>\n"
    "  <groupId>t</groupId>\n"
    "  <artifactId>{artifact}</artifactId>\n"
    "  <version>1</version>\n"
    "{modules}</project>\n"
)


def write_pom(directory: pathlib.Path, artifact: str, modules: list[str] | None = None) -> pathlib.Path:
    """A minimal pom. Only `<modules>` matters to the tool; the rest is Maven-shape filler."""
    entries = "".join(f"  <module>{m}</module>\n" for m in (modules or []))
    modules_xml = f"  <modules>\n{entries}  </modules>\n" if modules else ""
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "pom.xml").write_text(
        POM.format(artifact=artifact, modules=modules_xml), encoding="utf-8"
    )
    return directory / "pom.xml"


def write_test(module: pathlib.Path, fqn: str, body: str = "public class C {}") -> pathlib.Path:
    """Write `src/test/java/<fqn>.java` with `body` as the class body."""
    path = module / "src" / "test" / "java" / pathlib.Path(*fqn.split(".")).with_suffix(".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"package a;\n{body}\n", encoding="utf-8")
    return path


def write_report(module: pathlib.Path, kind: str, classname: str, tests: int,
                 failures: int = 0, errors: int = 0, skipped: int = 0,
                 skipped_methods: tuple[str, ...] = (), filename: str | None = None) -> pathlib.Path:
    """Write a `TEST-<classname>.xml` the way surefire writes one."""
    directory = module / "target" / f"{kind}-reports"
    directory.mkdir(parents=True, exist_ok=True)
    cases = []
    for i in range(tests):
        name = f"method{i}"
        if name in skipped_methods:
            cases.append(f'    <testcase name="{name}" classname="{classname}">'
                         f'<skipped message="assumed"/></testcase>')
        else:
            cases.append(f'    <testcase name="{name}" classname="{classname}"/>')
    path = directory / (filename or f"TEST-{classname}.xml")
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{classname}" tests="{tests}" errors="{errors}" '
        f'skipped="{skipped}" failures="{failures}">\n'
        + "\n".join(cases)
        + "\n</testsuite>\n",
        encoding="utf-8",
    )
    return path


class Fixture:
    """A throwaway reactor root that is wired the way a real one is.

    `mod(name)` makes a child module AND registers it in its parent's `<modules>`, so a test
    that adds a module does what adding one to `pom.xml` does. Getting this wrong is how the
    first draft of this file asserted a tool bug that was a fixture bug: a module directory
    existed, and nothing ever pointed the reactor at it.
    """

    def __init__(self, root: pathlib.Path, root_artifact: str = "root"):
        self.root = root
        self.root_pom = write_pom(root, root_artifact, [])

    def mod(self, name: str, modules: list[str] | None = None,
            parent: pathlib.Path | None = None) -> pathlib.Path:
        parent = parent or self.root
        directory = parent / name
        write_pom(directory, name, modules)
        self.register(parent, name)
        return directory

    def register(self, parent: pathlib.Path, name: str) -> None:
        """Add `name` to `parent`'s `<modules>`, rewriting that pom."""
        tree = ET.parse(parent / "pom.xml").getroot()
        modules_node = next((c for c in tree if c.tag.rsplit("}", 1)[-1] == "modules"), None)
        if modules_node is None:
            modules_node = ET.SubElement(tree, "modules")
        entry = ET.SubElement(modules_node, "module")
        entry.text = name
        ET.ElementTree(tree).write(parent / "pom.xml", encoding="utf-8", xml_declaration=True)


class ToolTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = pathlib.Path(tmp.name)
        self.fixture = Fixture(self.root)

    def side(self, module_path: pathlib.Path, kind: str = "surefire") -> dict:
        module = next(m for m in census.discover_modules(self.root) if m.dir == module_path)
        return census.census_module(module)[kind]

    def simple_module(self, artifact: str = "mod", classes: int = 2, tests_each: int = 3):
        module = self.fixture.mod(artifact)
        for i in range(classes):
            write_test(module, f"a.C{i}Test", f"public class C{i}Test {{}}")
            write_report(module, "surefire", f"a.C{i}Test", tests_each)
        return module


class ModuleDiscoveryTest(ToolTestCase):
    """The module list is parsed out of the poms, recursively, because that is the fix."""

    def test_a_nested_module_two_levels_down_is_counted(self):
        """`pg/pg-engine` is the case that lost five tests from a whole-repo total.

        A non-recursive read of the root pom sees the aggregator and stops, so the leaf
        contributes zero and nothing fails. Only recursion counts it.
        """
        self.fixture.mod("pg_mid", modules=["pg_leaf"])
        leaf = self.fixture.mod("pg_leaf", parent=self.root / "pg_mid")
        write_test(leaf, "a.LeafTest", "public class LeafTest {}")
        write_report(leaf, "surefire", "a.LeafTest", 5)

        found = {m.artifact for m in census.discover_modules(self.root)}

        self.assertIn("pg_leaf", found, "the nested module was dropped from the census")
        self.assertIn("pg_mid", found, "the aggregator itself must be reported as a module")

    def test_the_nested_module_tests_reach_the_total(self):
        self.fixture.mod("pg_mid", modules=["pg_leaf"])
        leaf = self.fixture.mod("pg_leaf", parent=self.root / "pg_mid")
        write_test(leaf, "a.LeafTest", "public class LeafTest {}")
        write_report(leaf, "surefire", "a.LeafTest", 5)

        report = census.census(self.root)

        self.assertEqual(report["surefire"]["tests"], 5)

    def test_a_module_added_to_the_pom_appears_without_editing_the_tool(self):
        """Add a module, do not touch the script, and the total moves. This is the whole point."""
        alpha = self.fixture.mod("alpha")
        write_test(alpha, "a.T", "public class T {}")
        write_report(alpha, "surefire", "a.T", 4)
        before = census.census(self.root)["surefire"]["tests"]

        beta = self.fixture.mod("beta")
        write_test(beta, "a.T", "public class T {}")
        write_report(beta, "surefire", "a.T", 6)
        after = census.census(self.root)["surefire"]["tests"]

        self.assertEqual(before, 4)
        self.assertEqual(after, 10, "adding a module to pom.xml must move the census")

    def test_a_root_without_a_pom_is_refused_rather_than_reported_as_zero(self):
        """A missing root is an error, not a repository with no tests."""
        self.assertEqual(census.main(["--root", str(self.root / "nowhere"), "--quiet"]), 2)

    def test_an_unparseable_pom_is_refused_with_the_path_in_the_message(self):
        (self.root / "pom.xml").write_text("<project>", encoding="utf-8")
        with self.assertRaises(SystemExit) as ctx:
            census.census(self.root)
        self.assertIn("cannot parse", str(ctx.exception))


class GhostAndUncollectedTest(ToolTestCase):
    """The join against the source tree, checked from both ends."""

    def test_a_report_whose_class_has_no_source_is_excluded_and_named(self):
        """The defect `guarantees.md` §0 documents: the total was wrong by exactly the ghosts."""
        module = self.simple_module(classes=2, tests_each=3)
        write_report(module, "surefire", "a.DeletedProbeTest", 4)

        side = self.side(module)

        self.assertEqual(side["ghost_reports"], ["a.DeletedProbeTest"])
        self.assertEqual(side["tests"], 6, "the ghost's 4 tests must not be in the total")

    def test_a_collectable_class_with_no_report_is_named(self):
        """Worse than a ghost: it contributes nothing to a naive sum, so nothing notices."""
        module = self.simple_module(classes=1, tests_each=2)
        write_test(module, "a.NeverRunTest", NEVER_RAN)

        side = self.side(module)

        self.assertEqual(side["uncollected_classes"], ["a.NeverRunTest"])
        self.assertEqual(side["tests"], 2, "the uncollected class must not be counted as run")

    def test_a_named_driver_with_no_at_test_is_a_naming_defect_not_a_loss(self):
        """`client/ServerJoinTest` and `client/SmokeTest` are the two live instances.

        Surefire collected the file and correctly found no runnable test in it. Calling that a
        lost test would make the census permanently red over a rename, and a permanently red
        census is one people stop reading. It is reported under its own label instead.
        """
        module = self.simple_module(classes=1, tests_each=2)
        write_test(module, "a.SmokeTest", DRIVER)

        side = self.side(module)

        self.assertEqual(side["uncollected_classes"], [])
        self.assertEqual(side["uncollected_not_runnable"], ["a.SmokeTest"])
        self.assertEqual(side["tests"], 2)

    def test_two_reports_for_one_class_are_refused_rather_than_summed(self):
        """Their sum is a number no single run produced, which is this file's entire subject."""
        module = self.simple_module(classes=1, tests_each=2)
        write_report(module, "surefire", "a.C0Test", 5, filename="TEST-stale.xml")

        with self.assertRaises(SystemExit) as ctx:
            self.side(module)
        self.assertIn("not cleaned", str(ctx.exception))

    def test_a_helper_file_is_not_a_ghost_even_though_it_lives_in_the_test_tree(self):
        """`SimWorld`, `FakeActuator`, `BodySim` sit under src/test and are not tests."""
        module = self.simple_module(classes=1, tests_each=2)
        write_test(module, "a.SimWorld", "public class SimWorld {}")

        side = self.side(module)

        self.assertEqual(side["ghost_reports"], [])
        self.assertEqual(side["tests"], 2)

    def test_a_failsafe_rollup_file_is_not_counted_as_a_class(self):
        """`failsafe-summary.xml` carries no `name`; counting it would double the module."""
        module = self.simple_module(classes=1, tests_each=2)
        (module / "target" / "surefire-reports" / "failsafe-summary.xml").write_text(
            '<testsuite tests="2" errors="0" skipped="0" failures="0"/>\n', encoding="utf-8"
        )

        side = self.side(module)

        self.assertEqual(side["live_reports"], 1)
        self.assertEqual(side["tests"], 2)


class TwoPopulationsTest(ToolTestCase):
    """Surefire and failsafe are separate numbers and the tool never adds them."""

    def test_surefire_and_failsafe_totals_are_reported_separately(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.UnitTest", "public class UnitTest {}")
        write_report(module, "surefire", "a.UnitTest", 7)
        write_test(module, "a.LiveIT", "public class LiveIT {}")
        write_report(module, "failsafe", "a.LiveIT", 3)

        report = census.census(self.root)

        self.assertEqual(report["surefire"]["tests"], 7)
        self.assertEqual(report["failsafe"]["tests"], 3)

    def test_no_combined_figure_is_published_anywhere_in_the_report(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.UnitTest", "public class UnitTest {}")
        write_report(module, "surefire", "a.UnitTest", 7)
        write_test(module, "a.LiveIT", "public class LiveIT {}")
        write_report(module, "failsafe", "a.LiveIT", 3)

        report = census.census(self.root)

        self.assertNotIn("combined", report,
                         "the tool must not publish a figure that sums two populations")
        self.assertNotIn("total", report,
                         "the top level must not carry one number for two populations")

    def test_an_it_class_is_not_in_the_surefire_expected_set(self):
        """`*IT.java` is outside surefire's includes; calling it uncollected would be a lie."""
        module = self.fixture.mod("mod")
        write_test(module, "a.LiveIT", "public class LiveIT {}")

        side = self.side(module)

        self.assertEqual(side["expected_classes"], 0)
        self.assertEqual(side["uncollected_classes"], [])

    def test_a_failsafe_class_is_expected_when_the_it_flag_was_used(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.LiveIT", "public class LiveIT {}")
        write_report(module, "failsafe", "a.LiveIT", 3)

        side = self.side(module, "failsafe")

        self.assertEqual(side["expected_classes"], 1)
        self.assertEqual(side["live_reports"], 1)
        self.assertEqual(side["uncollected_classes"], [])

    def test_a_shadowed_test_is_reported_but_not_counted_as_run(self):
        """A file carrying `@Test` whose name neither plugin collects: visible, not counted."""
        module = self.fixture.mod("mod")
        write_test(module, "a.Probe", "import org.junit.Test;\npublic class Probe {\n"
                                     "  @Test public void x() {}\n}")

        module_census = census.census_module(
            next(m for m in census.discover_modules(self.root) if m.dir == module))

        self.assertEqual(module_census["shadowed_tests"], ["a.Probe"])
        self.assertEqual(module_census["surefire"]["expected_classes"], 0)


class GreenArithmeticTest(ToolTestCase):
    """A skip is reported, never folded into green."""

    def test_green_excludes_failures_errors_and_skips(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 10, failures=2, errors=1, skipped=3)

        side = census.census(self.root)["surefire"]

        self.assertEqual(side["tests"], 10)
        self.assertEqual(side["skipped"], 3)
        self.assertEqual(side["green"], 4, "10 - 2 failures - 1 error - 3 skipped")

    def test_the_skipped_method_is_named(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3, skipped=1, skipped_methods=("method1",))

        side = self.side(module)

        self.assertEqual(side["skipped_detail"], {"a.T": ["method1"]})

    def test_skipped_alone_does_not_fail_the_census(self):
        """A skip is a judgement call; the tool reports it and leaves the judgement to a human."""
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3, skipped=1, skipped_methods=("method0",))

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 0)

    def test_a_failure_fails_the_census(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3, failures=1)

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 1)

    def test_an_error_fails_the_census(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3, errors=1)

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 1)

    def test_a_ghost_fails_the_census(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3)
        write_report(module, "surefire", "a.Ghost", 2)

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 1)

    def test_an_uncollected_test_class_fails_the_census(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3)
        write_test(module, "a.NeverRunTest", NEVER_RAN)

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 1)

    def test_a_misnamed_driver_does_not_fail_the_census(self):
        module = self.fixture.mod("mod")
        write_test(module, "a.T", "public class T {}")
        write_report(module, "surefire", "a.T", 3)
        write_test(module, "a.SmokeTest", DRIVER)

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 0)

    def test_a_clean_green_tree_passes(self):
        self.simple_module(classes=2, tests_each=3)

        self.assertEqual(census.main(["--root", str(self.root), "--quiet"]), 0)


class JdkResolutionTest(unittest.TestCase):
    """`core` compiles at `release = 25`, so a JDK 21 fails twenty lines above the real cause."""

    def test_a_java_home_pointing_at_an_old_jdk_is_reported_not_silently_used(self):
        old = pathlib.Path(r"C:\Program Files\Java\jdk-21")
        if not (old / "bin" / "javac.exe").is_file():
            self.skipTest("no JDK 21 at the conventional path to point at")
        home, note = census._resolve_jdk({"JAVA_HOME": str(old)})
        self.assertNotEqual(home, str(old), "an old JDK must not be handed to javac")
        self.assertIn("not a JDK 25+", note)

    def test_a_good_java_home_is_used_and_said_out_loud(self):
        jdk25 = pathlib.Path(r"D:\Software\Developer\jdk\25")
        if not (jdk25 / "bin" / "javac.exe").is_file():
            self.skipTest("no JDK 25 on this machine")
        home, note = census._resolve_jdk({"JAVA_HOME": str(jdk25)})
        self.assertEqual(home, str(jdk25))
        self.assertIn("JAVA_HOME", note)

    def test_an_unreadable_java_home_is_refused_with_a_reason(self):
        home, note = census._resolve_jdk({"JAVA_HOME": r"C:\nope\not-a-jdk"})
        self.assertNotEqual(home, r"C:\nope\not-a-jdk", "an unreadable JDK must not be used")
        self.assertIn("not a JDK 25+", note)

    def test_an_unset_java_home_with_no_fallback_says_so(self):
        home, note = census._resolve_jdk({"JAVA_HOME": ""}, )
        if home is None:
            self.assertIn("no JDK 25+ install was found", note)


if __name__ == "__main__":
    unittest.main()