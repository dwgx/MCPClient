#!/usr/bin/env python3
"""The one command that reproduces this repository's test census.

**Why this exists.** Nine different totals for "all tests" have been quoted in this repository
(1133, 1331, 1477, 1598, 1607, 2025, 2028, 2076, 2103, 2145) and three more circulated outside
it (2543, 2547, 2548). Every one was a real reading of *something*; none named the module list,
the run, or the join rule that produced it, so none was checkable. The counting convention was
the defect, not the arithmetic.

**Two populations, never summed.** Surefire collects the `*Test` classes; failsafe collects the
`*IT` classes, behind a different plugin, a different phase and a different skip flag, all of
which default to *skip*. Adding the two produces a number that no single command ever printed and
that changes meaning depending on which flags were set. This tool censuses them separately and
prints no combined figure. See `docs/agency/test-census.md` §1.

**The module list is read out of the poms, never written down here.** `pg-engine`'s five tests
were dropped from a whole-repo total because a hand-written list missed the nested `pg/`
aggregator -- and `mvn -pl pg-engine` does not even resolve; the reactor selector is
`pg/pg-engine` or `:pg-engine`. A list in prose can be wrong silently. A list parsed out of
`pom.xml` cannot: add a module and it is counted, delete one and it stops being counted.

**The de-ghost join.** A report whose class has no source under `<module>/src/test/java` is a
*deletion*, not a test -- a probe class removed after its last run leaves its report behind, and
a naive sum of the directory counts it as loudly as a real one. Every report is joined against the
source tree; a report that fails the join is excluded *and reported*, never silently summed.

**The bijection is checked from both ends.** A report with no source is a ghost. A collectable
source with no report is a class that was never asked anything -- strictly worse, because it is
invisible to anyone summing the directory. Both fail the census.

**A skip is not a green.** Surefire counts an assumption violation as SKIPPED and the build as
SUCCESS, which is the same outcome shape as a passing test. Skipped is its own column here and is
never folded into the green figure. What each skip class is *allowed* to mean is policy, and
policy lives in `docs/agency/test-census.md` §2, not in this script.

Usage:

    python scripts/test-census.py            # census the reports already on disk
    python scripts/test-census.py --json     # same, machine-readable
    python scripts/test-census.py --run      # `clean test` the reactor first, then census
    python scripts/test-census.py --run-its  # `clean verify` with every IT flag enabled

Exit code 0 only when, for each population separately: no ghosts, no uncollected collectable
sources, no failures, no errors. Skipped does not fail the census; it is reported.

Set `MCPCLIENT_MVN` to override the wrapper name. `JAVA_HOME` must point at JDK 25 for `--run`;
this script will not pick one silently if it is unset and no known install exists.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# Surefire 3.2.5 / failsafe 3.2.5 default includes. No pom in this reactor overrides them, which
# is why these patterns -- not a policy of ours -- define what a test class *is*.
SUREFIRE_INCLUDES = re.compile(r"(?:^Test.*|.*Test|.*Tests|.*TestCase)\.java$")
FAILSAFE_INCLUDES = re.compile(r".*(?:IT|ITCase)\.java$")

REPORT_KINDS = ("surefire", "failsafe")

MVN = os.environ.get("MCPCLIENT_MVN", "mvnw.cmd")
TEST_GOAL = ["-B", "-ntp", "clean", "test"]
# Every module's IT knob, spelled the way each pom reads it. core routes through ${skipITs},
# dwm and client read a literal property, so one flag does not fit all three.
IT_GOALS = ["-B", "-ntp", "clean", "verify", "-Dcore.it.skip=false",
            "-Ddwm.live.skip=false", "-Dsmoke.skip=false"]


class Module:
    """One Maven module, identified by the path the reactor reached it through."""

    def __init__(self, rel: str, artifact: str, pom: pathlib.Path):
        self.rel = rel
        self.artifact = artifact
        self.pom = pom
        self.dir = pom.parent

    @property
    def test_src(self) -> pathlib.Path:
        return self.dir / "src" / "test" / "java"

    def report_dir(self, kind: str) -> pathlib.Path:
        return self.dir / "target" / f"{kind}-reports"

    def __repr__(self) -> str:  # pragma: no cover - diagnostics only
        return f"Module({self.rel!r}, {self.artifact!r})"


def _xml_local(tag: str) -> str:
    """Strip the namespace Maven puts on `<modules>` and `<artifactId>`."""
    return tag.rsplit("}", 1)[-1]


def _child_text(node, name: str) -> str | None:
    for child in node:
        if _xml_local(child.tag) == name:
            return (child.text or "").strip()
    return None


def discover_modules(root: pathlib.Path) -> list[Module]:
    """Walk `<modules>` recursively from the root pom.

    Recursion is the whole point. `pg/` is a `pom`-packaging aggregator whose children are real
    modules; a non-recursive read of the root pom sees `pg` and stops, and everything under it
    silently leaves the census. That is exactly how `pg-engine`'s five tests went missing.
    """
    found: list[Module] = []
    seen: set[pathlib.Path] = set()

    def walk(pom: pathlib.Path, rel: str) -> None:
        pom = pom.resolve()
        if pom in seen or not pom.is_file():
            return
        seen.add(pom)
        try:
            tree = ET.parse(pom).getroot()
        except ET.ParseError as exc:
            raise SystemExit(f"census: cannot parse {pom}: {exc}") from exc
        artifact = _child_text(tree, "artifactId") or pom.parent.name
        found.append(Module(rel or ".", artifact, pom))
        modules_node = next((c for c in tree if _xml_local(c.tag) == "modules"), None)
        if modules_node is None:
            return
        for entry in modules_node:
            if _xml_local(entry.tag) != "module":
                continue
            child = (entry.text or "").strip()
            if not child:
                continue
            child_rel = f"{rel}/{child}" if rel and rel != "." else child
            walk(pom.parent / child / "pom.xml", child_rel)

    walk(root / "pom.xml", "")
    return found


def _fqn(java_root: pathlib.Path, source: pathlib.Path) -> str:
    return ".".join(source.relative_to(java_root).with_suffix("").parts)


def _read_reports(module: Module, kind: str, report_dir: pathlib.Path) -> dict[str, dict]:
    """Parse one report directory into {className: counters}. A missing directory yields {}."""
    reports: dict[str, dict] = {}
    if not report_dir.is_dir():
        return reports
    for xml in sorted(report_dir.glob("TEST-*.xml")):
        try:
            suite = ET.parse(xml).getroot()
        except ET.ParseError as exc:
            raise SystemExit(f"census: cannot parse {xml}: {exc}") from exc
        name = suite.get("name")
        if not name:
            # failsafe writes `failsafe-summary.xml` with no `name`: a rollup, not a class.
            # Counting it would double the module.
            continue
        if name in reports:
            raise SystemExit(
                f"census: {module.artifact}: {name} has two {kind} reports -- the directory was "
                f"not cleaned. Re-run with `clean`, or delete the stale one. Summing them would "
                f"publish a number no single run produced."
            )
        reports[name] = {
            "tests": int(suite.get("tests", 0)),
            "failures": int(suite.get("failures", 0)),
            "errors": int(suite.get("errors", 0)),
            "skipped": int(suite.get("skipped", 0)),
            "skipped_methods": [
                tc.get("name") for tc in suite.iter("testcase") if tc.find("skipped") is not None
            ],
        }
    return reports


def _sources(module: Module) -> dict[str, object]:
    """Sort every test source file into disjoint buckets.

    The split that matters most is inside surefire's includes. A file named `*Test` that
    produced **no report** is one of two very different things, and treating them alike is how
    a census becomes noise nobody reads:

    - it **carries `@Test` methods** and no report exists -- a real test that was never asked
      anything. That is a loss, and it fails the census.
    - it carries **zero** `@Test` methods -- a `main()` driver that happens to be named
      `*Test`. Surefire collected the file and correctly found nothing to run. Nothing was
      lost; the name misleads. That is a naming defect, reported, and it does not fail.

    `client/ServerJoinTest` and `client/SmokeTest` are the two live instances of the second kind.
    """
    all_fqns: set[str] = set()
    collectable: set[str] = set()
    it_classes: set[str] = set()
    runnable: set[str] = set()
    misnamed: list[str] = []
    shadowed: list[str] = []
    root = module.test_src
    if not root.is_dir():
        return {"all": all_fqns, "collectable": collectable, "it": it_classes,
                "runnable": runnable, "misnamed": [], "shadowed": []}
    for path in sorted(root.rglob("*.java")):
        fqn = _fqn(root, path)
        all_fqns.add(fqn)
        has_test = "@Test" in path.read_text(encoding="utf-8", errors="replace")
        if FAILSAFE_INCLUDES.match(path.name):
            it_classes.add(fqn)
        if SUREFIRE_INCLUDES.match(path.name):
            collectable.add(fqn)
            if has_test:
                runnable.add(fqn)
            else:
                misnamed.append(fqn)
        elif has_test:
            # A file surefire will never collect that nevertheless carries @Test. Its name is
            # the only thing standing between it and a green report it never earned.
            shadowed.append(fqn)
    return {"all": all_fqns, "collectable": collectable, "it": it_classes,
            "runnable": runnable, "misnamed": sorted(misnamed), "shadowed": shadowed}


def _side(kind: str, module: Module, expected: set[str], all_fqns: set[str],
          runnable: set[str] | None = None) -> dict:
    reports = _read_reports(module, kind, module.report_dir(kind))
    live = {n: r for n, r in reports.items() if n in all_fqns}
    missing = expected - set(live)
    # With no runnable set there is nothing to tell apart, so every absence counts as a loss.
    return {
        "kind": kind,
        "expected_classes": len(expected),
        "live_reports": len(live),
        "ghost_reports": sorted(n for n in reports if n not in all_fqns),
        "uncollected_classes": sorted(missing if runnable is None else missing & runnable),
        "uncollected_not_runnable": sorted(missing if runnable is None else missing - runnable),
        "tests": sum(r["tests"] for r in live.values()),
        "failures": sum(r["failures"] for r in live.values()),
        "errors": sum(r["errors"] for r in live.values()),
        "skipped": sum(r["skipped"] for r in live.values()),
        "skipped_detail": {
            n: r["skipped_methods"] for n, r in sorted(live.items()) if r["skipped"]
        },
    }


def census_module(module: Module) -> dict:
    src = _sources(module)
    return {
        "module": module.artifact,
        "path": module.rel,
        "source_files": len(src["all"]),
        "misnamed_drivers": sorted(src["misnamed"]),
        "shadowed_tests": sorted(src["shadowed"]),
        **{
            kind: _side(kind, module, src["collectable" if kind == "surefire" else "it"],
                        src["all"], src["runnable"] if kind == "surefire" else None)
            for kind in REPORT_KINDS
        },
    }


def _sum(modules: list[dict], kind: str, key: str) -> int:
    return sum(m[kind][key] for m in modules)


def census(root: pathlib.Path) -> dict:
    modules = [census_module(m) for m in discover_modules(root)]
    modules.sort(key=lambda m: m["module"])
    report = {"root": str(root), "modules": modules}
    for kind in REPORT_KINDS:
        report[kind] = {
            "expected_classes": _sum(modules, kind, "expected_classes"),
            "live_reports": _sum(modules, kind, "live_reports"),
            "tests": _sum(modules, kind, "tests"),
            "failures": _sum(modules, kind, "failures"),
            "errors": _sum(modules, kind, "errors"),
            "skipped": _sum(modules, kind, "skipped"),
            "ghosts": sum(len(m[kind]["ghost_reports"]) for m in modules),
            "uncollected": sum(len(m[kind]["uncollected_classes"]) for m in modules),
            "uncollected_not_runnable": sum(
                len(m[kind]["uncollected_not_runnable"]) for m in modules),
        }
        side = report[kind]
        side["green"] = side["tests"] - side["failures"] - side["errors"] - side["skipped"]
    report["shadowed"] = sum(len(m["shadowed_tests"]) for m in modules)
    report["misnamed_drivers"] = sum(len(m["misnamed_drivers"]) for m in modules)
    return report


def _jdk_major(java_home: str) -> int | None:
    """The feature version of the JDK at `java_home`, or None if it cannot be read."""
    javac = pathlib.Path(java_home, "bin", "javac.exe")
    if not javac.is_file():
        javac = pathlib.Path(java_home, "bin", "javac")
    if not javac.is_file():
        return None
    try:
        out = subprocess.run([str(javac), "-version"], capture_output=True, text=True,
                             timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r"javac (\d+)", (out.stderr or "") + (out.stdout or ""))
    return int(match.group(1)) if match else None


def _resolve_jdk(env: dict) -> tuple[str | None, str]:
    """Find a JDK new enough to compile `release = 25`, and say which one and why.

    `core/pom.xml:28` sets `maven.compiler.release` to 25, so a JDK older than 25 makes
    javac fail with `release version 25 not supported` -- an error that names neither the
    cause nor the fix, twenty lines above the module that needed it. Worse, an inherited
    JAVA_HOME pointing at JDK 21 fails while looking like a source problem. The JDK is
    checked here, before the build, and the resolved path is printed either way.
    """
    configured = env.get("JAVA_HOME")
    if configured and _jdk_major(configured) and _jdk_major(configured) >= 25:
        return configured, f"JAVA_HOME={configured}"
    if configured:
        reason = f"JAVA_HOME={configured} is not a JDK 25+ (or unreadable)"
    else:
        reason = "JAVA_HOME is unset"
    for candidate in (r"D:\Software\Developer\jdk\25", r"C:\Program Files\Java\jdk-25"):
        major = _jdk_major(candidate)
        if major and major >= 25:
            return candidate, f"{reason}; using {candidate} instead"
    return None, f"{reason}, and no JDK 25+ install was found"


def _run(root: pathlib.Path, goal: list[str]) -> int:
    env = dict(os.environ)
    java_home, note = _resolve_jdk(env)
    print(f"census: {note}", file=sys.stderr)
    if java_home is None:
        print(f"census: {note}; set JAVA_HOME to a JDK 25 and re-run", file=sys.stderr)
        return 2
    env["JAVA_HOME"] = java_home
    print(f"census: running {MVN} {' '.join(goal)} in {root}", file=sys.stderr)
    return subprocess.call([MVN, *goal], cwd=str(root), env=env)


def _print_side(report: dict, kind: str, unit: str) -> None:
    head = (f"{'module':<18}{'cls':>5}{'live':>6}{'tests':>7}"
            f"{'fail':>6}{'err':>5}{'skip':>6}{'ghost':>7}{'uncoll':>8}")
    print(f"\n=== {kind} ({unit}) ===")
    print(head)
    print("-" * len(head))
    for m in report["modules"]:
        s = m[kind]
        print(f"{m['module']:<18}{s['expected_classes']:>5}{s['live_reports']:>6}{s['tests']:>7}"
              f"{s['failures']:>6}{s['errors']:>5}{s['skipped']:>6}"
              f"{len(s['ghost_reports']):>7}{len(s['uncollected_classes']):>8}")
    s = report[kind]
    print("-" * len(head))
    print(f"{'TOTAL':<18}{s['expected_classes']:>5}{s['live_reports']:>6}{s['tests']:>7}"
          f"{s['failures']:>6}{s['errors']:>5}{s['skipped']:>6}{s['ghosts']:>7}{s['uncollected']:>8}")
    print(f"green (executed, passed) = {s['green']}")
    print(f"skipped                  = {s['skipped']}   NOT green; see test-census.md section 2")
    for label, key in (("GHOST REPORT (no source; excluded)", "ghost_reports"),
                       ("UNCOLLECTED (has @Test, collectable, never asked anything)",
                        "uncollected_classes"),
                       ("MISNAMED DRIVER (collectable, carries no @Test, so nothing was lost)",
                        "uncollected_not_runnable")):
        for m in report["modules"]:
            for name in m[kind][key]:
                print(f"  {label}: {m['module']}  {name}")
    for m in report["modules"]:
        for cls, methods in m[kind]["skipped_detail"].items():
            for meth in methods:
                print(f"  SKIPPED METHOD: {m['module']}  {cls}.{meth}")


def _print_text(report: dict) -> None:
    print(f"census of {report['root']}")
    print("two populations, reported separately and never summed -- see test-census.md section 1")
    _print_side(report, "surefire", "surefire default includes: **/Test*.java **/*Test.java "
                                     "**/*Tests.java **/*TestCase.java")
    _print_side(report, "failsafe", "failsafe default includes: **/IT*.java **/*IT.java "
                                    "**/*ITCase.java")
    if report["shadowed"]:
        print("\nSHADOWED (carries @Test, but the file name is not collected by either plugin):")
        for m in report["modules"]:
            for name in m["shadowed_tests"]:
                print(f"  {m['module']}  {name}")
    if report["misnamed_drivers"]:
        print("\nMISNAMED DRIVERS (named *Test, carry no @Test; rename them to *Driver):")
        for m in report["modules"]:
            for name in m["misnamed_drivers"]:
                print(f"  {m['module']}  {name}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="census this repository's JUnit tests")
    parser.add_argument("--root", default=str(pathlib.Path(__file__).resolve().parent.parent),
                        help="repository root (default: the parent of scripts/)")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of a table")
    parser.add_argument("--run", action="store_true",
                        help=f"run `{MVN} {' '.join(TEST_GOAL)}` first, so reports are this run's")
    parser.add_argument("--run-its", action="store_true",
                        help=f"run `{MVN} {' '.join(IT_GOALS)}` first")
    parser.add_argument("--quiet", action="store_true",
                        help="print one verdict line per population instead of the tables")
    parser.add_argument(
        "--judge", choices=("surefire", "all"), default=None,
        help="which populations the exit code judges. Defaults to the ones the invocation "
             "covered: `--run` covers surefire only (`mvn test` never reaches failsafe), "
             "`--run-its` covers both, and a bare run judges surefire because that is what "
             "`mvn test` collects. Judging failsafe after a `test`-only run would report 22 "
             "uncollected classes forever and teach everyone to ignore the exit code.")
    args = parser.parse_args(argv)
    if args.judge is None:
        args.judge = "all" if args.run_its else "surefire"
    judged = REPORT_KINDS if args.judge == "all" else ("surefire",)

    root = pathlib.Path(args.root).resolve()
    if not (root / "pom.xml").is_file():
        print(f"census: {root} has no pom.xml; pass --root", file=sys.stderr)
        return 2

    if args.run or args.run_its:
        rc = _run(root, IT_GOALS if args.run_its else TEST_GOAL)
        if rc != 0:
            print(f"census: build failed (exit {rc}); not censusing a broken tree", file=sys.stderr)
            return rc

    report = census(root)
    if args.quiet:
        for kind in REPORT_KINDS:
            s = report[kind]
            print(f"{kind}: {s['green']} green / {s['tests']} tests, "
                  f"{s['failures']} failures, {s['errors']} errors, {s['skipped']} skipped, "
                  f"{s['ghosts']} ghosts, {s['uncollected']} uncollected")
    elif args.json:
        print(json.dumps(report, indent=2))
    else:
        _print_text(report)

    reasons = []
    for kind in judged:
        s = report[kind]
        for label, value in (("failures", s["failures"]), ("errors", s["errors"]),
                             ("ghost reports", s["ghosts"]),
                             ("uncollected classes", s["uncollected"])):
            if value:
                reasons.append(f"{kind}: {value} {label}")
    if reasons:
        print("\nCENSUS FAILED -- " + "; ".join(reasons), file=sys.stderr)
        return 1
    unjudged = [k for k in REPORT_KINDS if k not in judged]
    if unjudged:
        print(f"\nnot judged (this invocation did not cover it): {', '.join(unjudged)}"
              f" -- see test-census.md section 3", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())