#!/usr/bin/env python3
"""Apply one source mutation, run a test selection, restore the file, report red/green.

Verification scaffold, not a build tool. An assertion that stays GREEN with the production
side broken is proving nothing, and this repo has caught seven such assertions in itself --
several written by the same hand that wrote the code. So each claim below is checked by
breaking the thing it claims to guard.

Usage:
  mutate.py <file> <old> <new> <-Dtest selection> [label]

Exits 0 if the mutation was CAUGHT (tests went red), 1 if it SURVIVED.
"""
import subprocess
import sys
import os

def main():
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    path, old, new, tests = sys.argv[1:5]
    label = sys.argv[5] if len(sys.argv) > 5 else old[:60]

    with open(path, encoding="utf-8") as f:
        original = f.read()
    if original.count(old) != 1:
        print(f"ANCHOR NOT UNIQUE ({original.count(old)} matches) in {path}: {old[:80]!r}")
        return 2

    env = dict(os.environ)
    env["JAVA_HOME"] = os.path.expanduser("~/.jdks/jdk-25.0.3+9/Contents/Home")
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(original.replace(old, new))
        proc = subprocess.run(
            ["./mvnw", "-B", "-ntp", "-pl", "core", "test", f"-Dtest={tests}"],
            capture_output=True, text=True, env=env,
        )
    finally:
        with open(path, "w", encoding="utf-8") as f:
            f.write(original)

    out = proc.stdout
    caught = proc.returncode != 0
    failing = [ln.strip() for ln in out.splitlines()
               if ln.strip().startswith(("[ERROR]   ", "[ERROR] Tests run"))]
    print(f"{'CAUGHT ' if caught else 'SURVIVED'}  {label}")
    for ln in failing[:12]:
        print(f"    {ln}")
    if not caught:
        print("    ^ nothing went red: the assertions do not cover this behaviour")
    return 0 if caught else 1

if __name__ == "__main__":
    sys.exit(main())
