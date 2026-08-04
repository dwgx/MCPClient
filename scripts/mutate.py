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
    ran_tests = "Tests run:" in out
    compile_broke = "COMPILATION ERROR" in out or not ran_tests

    # A mutation that does not COMPILE proves nothing, and reporting it as CAUGHT is the failure
    # mode this tool exists to prevent -- it looks exactly like a passing verification. Two of the
    # mutations written against this repo did precisely that (a `for` header replaced by `if (false)`
    # leaves the loop variable undefined), and the run said CAUGHT with no failing test named.
    if compile_broke:
        errs = [ln.strip() for ln in out.splitlines() if "ERROR" in ln and ".java:" in ln]
        print(f"INVALID   {label}")
        print("    the mutant did not compile, so nothing was tested -- rewrite it as a change")
        print("    that builds. This is NOT a caught mutation.")
        for ln in errs[:6]:
            print(f"    {ln}")
        return 2

    caught = proc.returncode != 0
    failing = [ln.strip() for ln in out.splitlines()
               if ln.strip().startswith(("[ERROR]   ", "[ERROR] Tests run"))]
    print(f"{'CAUGHT ' if caught else 'SURVIVED'}  {label}")
    for ln in failing[:12]:
        # Truncated: several assertions in this repo embed the whole ~3KB tool description in
        # their failure message, and two of those buries the one line that identifies the test.
        print(f"    {ln[:300] + ' ...[truncated]' if len(ln) > 300 else ln}")
    if not caught:
        print("    ^ nothing went red: the assertions do not cover this behaviour")
    return 0 if caught else 1

if __name__ == "__main__":
    sys.exit(main())
