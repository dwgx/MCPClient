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
  mutate.py --check     report the JDK and the wrapper it would use, and exit; mutates nothing

Module is inferred from the file path (core/board/dwm/...) and defaults to core.

Exit codes -- only 0 means "this assertion has teeth":
  0  CAUGHT     tests went red under the mutation
  1  SURVIVED   tests stayed green: the assertions do not cover this behaviour
  2  refused    bad usage, non-unique anchor, or a mutant that did not COMPILE
  3  INVALID    NOTHING was verified: the harness could not run here, the run reached no test,
                or the run exceeded its bound
Treat 2 and 3 as "nothing was verified", NOT as survivors and NOT as catches. A caller that
buckets every non-zero exit as SURVIVED will report coverage gaps that were never measured --
which is what this tool did on this workstation: `./mvnw` does not run on Windows, the
FileNotFoundError was uncaught, and an uncaught exception exits Python with status 1 = SURVIVED.
"""
import glob
import os
import shutil
import subprocess
import sys


def jdk_major(home):
    """The major version, from the JDK's own release file, or None if it does not say."""
    try:
        with open(os.path.join(home, "release"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("JAVA_VERSION="):
                    v = line.split("=", 1)[1].strip().strip('"')
                    return int(v.split(".")[0]) if v.split(".")[0].isdigit() else None
    except OSError:
        return None
    return None


def is_jdk(home):
    """A directory that can BUILD, not just run: bin/javac is what separates it from a JRE."""
    return bool(home) and os.path.isfile(
        os.path.join(home, "bin", "javac.exe" if os.name == "nt" else "javac"))


def find_java_home(env=None):
    """The JDK to build with: the newest one this machine actually has.

    Everything the old body did was wrong in the same direction. It set JAVA_HOME unconditionally
    to ~/.jdks/jdk-25.0.3+9/Contents/Home -- a macOS JetBrains path -- so on Windows it pointed the
    build at a directory that does not exist AND clobbered the JAVA_HOME the machine had already
    exported. The VERSION matters as much as existence: core compiles at release 25 (core/pom.xml),
    so a JDK 21 -- which is what this workstation's JAVA_HOME happens to point at -- fails every
    mutation to compile, for a reason that has nothing to do with the mutant. Hence: every JDK the
    machine advertises, newest first, and the caller prints which one it got.
    """
    env = os.environ if env is None else env
    homes = [v for k, v in env.items() if k == "JAVA_HOME" or k.startswith("JAVA_HOME_")]
    # IntelliJ's JDK store, where the path this used to hardcode came from. On macOS the home is
    # one level down, so both shapes are offered.
    for store in glob.glob(os.path.expanduser("~/.jdks/*")):
        homes += [store, os.path.join(store, "Contents", "Home")]
    javac = shutil.which("javac")
    if javac:
        homes.append(os.path.dirname(os.path.dirname(javac)))   # <home>/bin/javac
    found = [(jdk_major(h) or -1, h) for h in dict.fromkeys(homes) if is_jdk(h)]
    return max(found)[1] if found else None


def find_maven_wrapper():
    """The wrapper that exists HERE, as an absolute path.

    The old command was the POSIX ./mvnw, which does not run on Windows at all -- the checkout
    ships mvnw.cmd beside it -- so the run raised FileNotFoundError, Python exited 1, and 1 is this
    tool's SURVIVED. The cwd is tried first so the invocation stays the one callers are used to;
    the repo root (scripts/..) is the fallback for a caller that runs from a subdirectory.
    """
    names = ("mvnw.cmd", "mvnw") if os.name == "nt" else ("mvnw", "mvnw.cmd")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for name in names:
        for base in (os.getcwd(), root):
            candidate = os.path.join(base, name)
            if os.path.isfile(candidate):
                return candidate
    return None


def check_harness():
    """Report the JDK and the wrapper a mutation would use. Mutates nothing, builds nothing.

    The harness is discovered rather than hardcoded now, so "which JDK did it pick" is the first
    question when a run comes back INVALID -- and this is the only way to ask it without paying for
    a build.
    """
    jdk = find_java_home()
    wrapper = find_maven_wrapper()
    seen = sorted(k for k in os.environ if k == "JAVA_HOME" or k.startswith("JAVA_HOME_"))
    print("JAVA_HOME* seen: " + (", ".join(f"{k}={os.environ[k]}" for k in seen) or "none"))
    print(f"JDK:      {jdk or 'NOT FOUND'}"
          + (f"  (release {jdk_major(jdk)}; core compiles at release 25)" if jdk else ""))
    print(f"wrapper:  {wrapper or 'NOT FOUND'}")
    if jdk is None or wrapper is None:
        print("INVALID: the harness cannot run here, so no mutation would be verified.")
        return 3
    print("ok: a mutation would run with these.")
    return 0


def main():
    # --help exits 0 like everyone else's, and --check answers "can this machine verify a mutation
    # at all" without compiling anything.
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        return check_harness()
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
    # os.sep-aware, because this tool's whole failure mode is a WRONG module: a mutant compiled
    # into one module and tested by another, which compiles the mutated file, runs no matching
    # test and prints SURVIVED -- a survivor that was never tested. "board\\src\\main\\..."
    # split on "/" yields the whole path, misses the module list and silently fell back to
    # "core" on this machine, which is the default this comment is complaining about.
    head = path.replace("\\", "/").split("/")[0]
    module = sys.argv[6] if len(sys.argv) > 6 else (
        head if head in ("core", "board", "dwm", "client", "lwjgl2-shim") else "core")
    if len(sys.argv) <= 6 and head not in ("core", "board", "dwm", "client", "lwjgl2-shim"):
        print(f"WARNING: cannot tell which module {path} belongs to; defaulting to core. "
              f"If that is wrong the mutant may be compiled and never tested. Pass the module "
              f"as argument 7 to be sure.")

    with open(path, encoding="utf-8") as f:
        original = f.read()
    if original.count(old) != 1:
        print(f"ANCHOR NOT UNIQUE ({original.count(old)} matches) in {path}: {old[:80]!r}")
        return 2

    # Resolved BEFORE the file is touched. A missing JDK or wrapper is not a mutation that
    # survived -- nothing was even run -- and finding out here means there is no mutant on disk and
    # no mutated bytecode in target/classes to undo. The old code discovered this the hard way: it
    # forced a macOS JAVA_HOME and then called the POSIX ./mvnw, so on Windows the FileNotFoundError
    # escaped as exit 1, which this tool documents as SURVIVED.
    jdk = find_java_home()
    wrapper = find_maven_wrapper()
    if jdk is None or wrapper is None:
        print(f"INVALID   {label}")
        print("    the harness cannot run here, so NOTHING was verified -- this is neither")
        print("    CAUGHT nor SURVIVED, and counting it as a survivor invents a coverage gap.")
        print(f"    JDK:     {jdk or 'none found (JAVA_HOME, JAVA_HOME_*, ~/.jdks, javac on PATH)'}")
        print(f"    wrapper: {wrapper or 'none found (mvnw.cmd / mvnw, in the cwd or the repo root)'}")
        return 3

    env = dict(os.environ)
    env["JAVA_HOME"] = jdk
    timed_out = False
    run_failed = None
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
                [wrapper, "-B", "-ntp", "-pl", module, "test", f"-Dtest={tests}"],
                capture_output=True, text=True, env=env, timeout=480,
            )
        except subprocess.TimeoutExpired:
            timed_out = True
            proc = None
        except OSError as e:
            # The wrapper or the JDK in it could not be started at all. Never a verdict.
            run_failed = e
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
        #
        # This is the second call the old code died on: ./mvnw again, so on Windows the restore
        # raised FileNotFoundError a second time and the mutant bytecode stayed in target/classes.
        # Hence the discovered wrapper, and hence the catch -- a cleanup that raises is a cleanup
        # that did not happen, and silence about that is the leak itself.
        try:
            rec = subprocess.run([wrapper, "-B", "-ntp", "-q", "-pl", module,
                                  "-DskipTests", "compile"],
                                 capture_output=True, text=True, env=env, timeout=480)
        except (OSError, subprocess.SubprocessError) as e:
            rec = None
            print(f"WARNING: the restore compile could not run ({e}); {module}/target/classes may")
            print("         still hold the mutant until something recompiles it.")
        if rec is not None and rec.returncode != 0:
            print(f"WARNING: the restore compile failed; {module}/target/classes may still hold")
            print("         the mutant until something recompiles it.")

    if run_failed is not None:
        print(f"INVALID   {label}")
        print(f"    the test run could not start: {run_failed}")
        print("    NOTHING was verified -- this is neither CAUGHT nor SURVIVED. The source and")
        print("    the bytecode have been restored.")
        return 3

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
    # Tests that ran, not merely a "Tests run:" line: surefire prints that summary with a total of
    # 0 when the selection matches nothing, and a build that then fails for THAT reason is not a
    # caught mutation -- it is a red result that proves nothing, which is the one direction this
    # tool must never report.
    totals = [ln.split("Tests run:", 1)[1].split(",", 1)[0].strip()
              for ln in out.splitlines() if "Tests run:" in ln]
    ran_tests = any(t.isdigit() and int(t) > 0 for t in totals)

    # A mutation that does not COMPILE proves nothing, and reporting it as CAUGHT is the failure
    # mode this tool exists to prevent -- it looks exactly like a passing verification. Two of the
    # mutations written against this repo did precisely that (a `for` header replaced by `if (false)`
    # leaves the loop variable undefined), and the run said CAUGHT with no failing test named.
    if not ran_tests and ("COMPILATION ERROR" in out or "Compilation failure" in out):
        errs = [ln.strip() for ln in out.splitlines() if "ERROR" in ln and ".java:" in ln]
        print(f"INVALID   {label}")
        print("    the mutant did not compile, so nothing was tested -- rewrite it as a change")
        print("    that builds. This is NOT a caught mutation.")
        for ln in errs[:6]:
            print(f"    {ln}")
        return 2

    # Maven or the selection failed without a test ever running: a dependency that could not be
    # fetched, a lock on target/, a JDK that cannot compile this tree, a -Dtest that matches
    # nothing. The old code folded all of these into "the mutant did not compile", which is a
    # claim it cannot support, and exit 2 is right only when the mutant is the thing that broke.
    if not ran_tests:
        print(f"INVALID   {label}")
        print("    the run reached NO test (Tests run: 0 or none at all), so nothing was verified")
        print("    -- this is NOT a caught mutation and NOT a survivor. maven said:")
        for ln in [ln.strip() for ln in out.splitlines()
                   if ln.strip().startswith("[ERROR]")][:6]:
            print(f"    {ln[:300]}")
        return 3

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
