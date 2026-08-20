#!/usr/bin/env python3
"""Apply one source mutation, run a test selection, restore the file, report red/green.

Verification scaffold, not a build tool. An assertion that stays GREEN with the production
side broken is proving nothing, and this repo keeps finding such assertions in itself --
several written by the same hand that wrote the code. So each claim below is checked by
breaking the thing it claims to guard. (The running count is not pinned here on purpose: it
has been wrong every time it was written down. Count CAUGHT/SURVIVED from this tool's own
exit codes, not from a document.)

Usage:
  mutate.py <file> <old> <new> <-Dtest selection> [label] [module]

Module is inferred from the file path (core/board/dwm/...) and defaults to core.

Exit codes -- only 0 means "this assertion has teeth":
  0  CAUGHT     tests went red under the mutation
  1  SURVIVED   tests stayed green: the assertions do not cover this behaviour
  2  refused    bad usage, non-unique anchor, or a mutant that did not COMPILE
  3  TIMEOUT    the run exceeded its bound, so NO verdict was reached
Treat 2 and 3 as "nothing was verified", NOT as survivors and NOT as catches. A caller that
buckets every non-zero exit as SURVIVED will report coverage gaps that were never measured.
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
    # Module defaults to core because that is where the kernel lives, but -pl was
    # hardcoded until a board candidate needed it: running a board mutation under
    # -pl core compiles the mutated file and then runs NO board test, which prints
    # SURVIVED. A survivor that was never tested is the one result this tool must
    # never produce. Derive it from the path so a caller cannot silently mismatch.
    module = sys.argv[6] if len(sys.argv) > 6 else (
        path.split("/")[0] if path.split("/")[0] in ("core", "board", "dwm", "client",
                                                     "lwjgl2-shim") else "core")

    with open(path, encoding="utf-8") as f:
        original = f.read()
    if original.count(old) != 1:
        print(f"ANCHOR NOT UNIQUE ({original.count(old)} matches) in {path}: {old[:80]!r}")
        return 2

    env = dict(os.environ)
    env["JAVA_HOME"] = os.path.expanduser("~/.jdks/jdk-25.0.3+9/Contents/Home")
    timed_out = False
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(original.replace(old, new))
        try:
            # Bounded, because a mutant can make a test WAIT rather than fail: dropping
            # CraftController.SETTLE_TICKS from 4 to 2 confirms before the round trip can
            # land, and the run sat for 15 minutes with no output. Unbounded, that costs
            # more than the campaign it is part of -- and it is worse than slow, because
            # a run killed by hand does not reach the restore below, so the mutation
            # stays on disk. That is the same leak as handoff-2026-08-04 section 4(4),
            # arriving from the other direction. Measured: the slowest healthy mutation
            # in this repo is well under two minutes, so 8 is generous, not tight.
            proc = subprocess.run(
                ["./mvnw", "-B", "-ntp", "-pl", module, "test", f"-Dtest={tests}"],
                capture_output=True, text=True, env=env, timeout=480,
            )
        except subprocess.TimeoutExpired:
            timed_out = True
            proc = None
    finally:
        with open(path, "w", encoding="utf-8") as f:
            f.write(original)
        # Restoring the SOURCE is not enough: target/classes still holds the mutated
        # bytecode until something recompiles, and the next thing to read it may not
        # be a test. `package` would put a mutated class in a jar; codegraph builds
        # its index from target/classes and would map mutated code. This is the
        # bytecode twin of the unreverted-mutation-in-a-commit incident
        # (handoff-2026-08-04 section 4(4)), and it is silent in exactly the same way.
        subprocess.run(["./mvnw", "-B", "-ntp", "-q", "-pl", module,
                        "-DskipTests", "compile"],
                       capture_output=True, text=True, env=env)

    # A timeout is NOT a caught mutation, and must never be reported as one: the tests
    # never returned a verdict, so nothing was verified. It is also not a survivor. Say
    # what happened and let the caller decide -- a hang is usually real information about
    # the mutant (this one made the controller wait for a round trip that cannot arrive).
    if timed_out:
        print(f"TIMEOUT   {label}")
        print("    the run exceeded its bound, so NO verdict was reached -- this is neither")
        print("    CAUGHT nor SURVIVED. The source and bytecode have been restored. A mutant")
        print("    that hangs rather than fails usually means it removed a deadline the test")
        print("    depends on; drive it with a bounded fake instead of the real wait.")
        return 3

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
