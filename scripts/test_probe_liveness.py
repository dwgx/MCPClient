"""The liveness check must degrade, not raise, when the tool it needs is missing.

`live-dwm-probe.py` re-checks the client's liveness after every step, because two of the three
bugs that probe exists for killed the JVM outright: a dead process is otherwise
indistinguishable from a hung call.

That check had two shapes. The FIRST resolution step, `_client_pids()`, was already guarded --
it returns None when neither PowerShell nor pgrep is available, and `alive()` turns that into
"assume alive, say so once". The SECOND shape, the cached-pid re-check, was not: it called
`_pid_alive()` directly, and on the POSIX branch that reached `subprocess.run(["tasklist", ...])`
with no guard. On a machine where tasklist is absent, the FileNotFoundError escaped `alive()`
and killed the probe -- the exact inversion of the note it prints when the check cannot run.

These tests drive that path directly. They set `_CLIENT_PID` by hand, which is what
`test_probe_framing.py` structurally cannot do: with no client running, `_CLIENT_PID` stays
empty and the cached branch is never entered.
"""

import importlib.util
import os
import subprocess
import sys
import unittest

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
if SCRIPTS not in sys.path:
    sys.path.insert(0, SCRIPTS)


def load(script):
    """Import a probe by path -- the filenames are hyphenated, so plain import will not do."""
    path = os.path.join(SCRIPTS, script)
    spec = importlib.util.spec_from_file_location(script.replace("-", "_")[:-3], path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CachedPidLivenessTest(unittest.TestCase):
    """`alive()` on the cached-pid path must answer, whatever the machine is missing."""

    def setUp(self):
        self.probe = load("live-dwm-probe.py")
        # Every test starts from a clean cache and a clean "already warned" latch, because both
        # are module-level and the note is deliberately printed once.
        self.probe._CLIENT_PID.clear()
        self.probe._LIVENESS_WARNED.clear()
        self.addCleanup(self.probe._CLIENT_PID.clear)
        self.addCleanup(self.probe._LIVENESS_WARNED.clear)

    def test_a_missing_tasklist_degrades_to_assumed_alive_and_never_raises(self):
        """The regression. Pre-fix this raised FileNotFoundError out of alive()."""
        self.probe._CLIENT_PID.append(4321)

        def missing(*_args, **_kwargs):
            raise FileNotFoundError("tasklist")

        real_run, self.probe.subprocess.run = self.probe.subprocess.run, missing
        try:
            # The contract is not the return value alone -- an exception here IS the failure.
            answer = self.probe.alive()
        finally:
            self.probe.subprocess.run = real_run

        self.assertIs(answer, True,
                      "a machine that cannot answer is not evidence of a dead client, and "
                      "reporting one would be a crash this probe never observed")

    def test_the_degraded_path_explains_itself_once(self):
        """A silent degradation is how a skipped check becomes a trusted PASS."""
        self.probe._CLIENT_PID.append(4321)
        self.probe._pid_alive = lambda pid: None

        import io as _io
        import contextlib
        buf = _io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.probe.alive()
            self.probe.alive()
            self.probe.alive()

        note = buf.getvalue()
        self.assertIn("liveness check cannot run", note,
                      "the probe must say the check is not running")
        self.assertEqual(1, note.count("liveness check cannot run"),
                         "printed once, not on every step")

    def test_a_tasklist_that_fails_is_not_evidence_of_a_dead_client(self):
        """Returncode 0 with no match is a real answer; a non-zero code is the tool failing."""
        self.probe._CLIENT_PID.append(4321)

        class Out:
            returncode = 128
            stdout = ""
            stderr = "boom"

        real_run = self.probe.subprocess.run
        self.probe.subprocess.run = lambda *a, **k: Out()
        try:
            self.assertIsNone(self.probe._pid_alive(4321),
                              "a tasklist that could not run is the absence of evidence")
        finally:
            self.probe.subprocess.run = real_run

    def test_permission_denied_on_a_signal_zero_means_the_process_exists(self):
        """EPERM is not ESRCH: the pid is live and owned by someone else.

        Reading it as "dead" would have the probe report a crash on a client that is still
        running, which is the failure the check exists to prevent.
        """
        import errno

        def denied(_pid, _sig):
            raise PermissionError(errno.EPERM, "denied")

        real_kill, os.kill = os.kill, denied
        real_name, os.name = os.name, "posix"
        try:
            self.assertIs(self.probe._pid_alive(4321), True,
                          "refused for permission means the process exists")
        finally:
            os.kill, os.name = real_kill, real_name

    def test_a_dead_pid_is_still_reported_dead(self):
        """The guard must not have been written so wide that it swallows a real death.

        Without this, a fix that made every failure return True would pass the other four tests.
        """
        import errno

        def gone(_pid, _sig):
            raise ProcessLookupError(errno.ESRCH, "gone")

        real_kill, os.kill = os.kill, gone
        real_name, os.name = os.name, "posix"
        try:
            self.assertIs(self.probe._pid_alive(4321), False)
        finally:
            os.kill, os.name = real_kill, real_name


if __name__ == "__main__":
    unittest.main()
