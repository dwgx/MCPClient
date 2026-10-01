#!/usr/bin/env python3
"""Does the jar the launcher will actually load contain the code that was just compiled?

**Why this exists.** On 2026-09-30 a session reported "1331 tests green, four tools
verified" and then the first live call answered `Tool not found`. The cause was not a
missing class: `run-mcp.bat` loads `core/target/core-1.8.9-all.jar`, and that session had
only ever run `mvn test`. It had never run `package`. The jar on disk was from an older
build and was perfectly valid -- just not the one under test. Every test was true and the
shipped artifact was stale, and nothing in the repository could see it.

A jar like that is worse than no jar: it starts, it serves `tools/list`, and it answers
`Tool not found` for a tool that the test suite says exists.

**What it checks.** For every class the module just compiled into `core/target/classes`,
the jar must carry a byte-identical entry, and the signed compat resources must be in the
jar too -- a stale jar also means un-armed patches, and that fails as one stderr line per
patch rather than as an error. Byte equality rather than timestamps: shade is configured
with no relocation, so project classes are copied verbatim, and a timestamp comparison
would be satisfied by a jar that was touched rather than rebuilt.

**How to use it.**

    ./mvnw -pl core -am package -DskipTests && python scripts/check-agent-jar.py

Exits 0 when the jar matches, 1 with a list of what differs otherwise. It never starts the
game -- that is the point. It is a check on the artifact, not a smoke test.

Run its own tests with:

    python -m unittest discover -s scripts -p 'test_check_agent_jar.py'
"""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Resources whose absence from the jar changes behaviour rather than merely breaking a call:
# the trust material the compat engine verifies every patch signature against.
SIGNED_RESOURCES = (
    "net/marcloud/mcp/core/compat/kernel-ed25519.pub",
    "net/marcloud/mcp/core/compat/root-ed25519.pub",
    "net/marcloud/mcp/core/compat/root-metadata.json",
    "net/marcloud/mcp/core/compat/root-metadata.sig",
)


def launcher_jar(name: str) -> Path:
    """Resolve a jar path the way `run-mcp.bat` declares it, not the way we assume it.

    A checker that hardcodes its own idea of the path checks the wrong file the moment the
    launcher changes, and then reports green about an artifact nobody runs.
    """
    bat = (ROOT / "scripts" / "run-mcp.bat").read_text(encoding="utf-8", errors="replace")
    match = re.search(rf'set\s+"{re.escape(name)}=([^"]+)"', bat, re.IGNORECASE)
    if not match:
        raise SystemExit(f"could not find {name} in scripts/run-mcp.bat -- the launcher changed shape")
    return Path(match.group(1).replace("%ROOT%", str(ROOT)))


def check(jar: Path, classes: Path) -> list[str]:
    """Compare a jar against a classes directory. Returns one line per difference.

    Split out from `main` so a test can drive it against a synthetic jar: the real one only
    exists after a package, and a check that can only be exercised on a machine that has
    already done the thing it checks is a check nobody runs.
    """
    problems: list[str] = []
    with zipfile.ZipFile(jar) as z:
        entries = {i.filename: z.read(i.filename) for i in z.infolist()}

        for path in sorted(classes.rglob("*.class")):
            entry = path.relative_to(classes).as_posix()
            if entry not in entries:
                problems.append(f"MISSING  {entry}")
            elif entries[entry] != path.read_bytes():
                problems.append(f"STALE    {entry}")

        for resource in SIGNED_RESOURCES:
            if resource not in entries:
                problems.append(f"MISSING  {resource}  (the compat engine cannot arm any patch)")
    return problems


def main() -> int:
    jar = launcher_jar("CORE_JAR")
    classes = ROOT / "core" / "target" / "classes"
    if not jar.is_file():
        print(f"FAIL {jar} does not exist. Run:  ./mvnw -pl core -am package -DskipTests")
        return 1
    if not classes.is_dir():
        print(f"FAIL {classes} does not exist -- nothing has been compiled to compare against.")
        return 1

    on_disk = sorted(classes.rglob("*.class"))
    if not on_disk:
        print(f"FAIL {classes} holds no classes; a clean tree would prove nothing.")
        return 1

    problems = check(jar, classes)

    print(f"jar     {jar}")
    print(f"classes {len(on_disk)} compiled, {len(zipfile.ZipFile(jar).namelist())} entries in the jar")
    if problems:
        print(f"\n{len(problems)} difference(s):")
        for line in problems[:40]:
            print("  " + line)
        if len(problems) > 40:
            print(f"  ... and {len(problems) - 40} more")
        print("\nThe jar does not match what was compiled. A live run would answer "
              "'Tool not found' for tools the tests say exist.")
        return 1

    print("\nOK  every compiled class is present byte-for-byte, and the trust material is in the jar.")
    return 0


if __name__ == "__main__":
    sys.exit(main())