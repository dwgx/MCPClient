#!/usr/bin/env python3
"""Regression guard for the probes' JSON-RPC reply framing.

    python3 -m unittest discover -s scripts -p 'test_*.py' -v

The probes read a line-delimited JSON-RPC reply off a socket. The bug this pins: breaking the
read loop as soon as b'"id":2' appears anywhere in the buffer, which truncates any reply larger
than one recv. nav-astar-probe.py hit it for real -- world_view at radius 16 is ~180KB over 4
chunks and came back "unparseable reply: Unterminated string". live-dwm-probe.py carried the same
shape for longer, hidden only by smaller payloads.

The framing must therefore treat "the id=2 line is present" and "the id=2 line is complete" as
different questions. These tests fail against the old logic.

The contract now has ONE home: scripts/mcp_probe.py. This file used to run it twice, once per
copy, because there were two copies -- that duplication is precisely how the fixed probe and the
broken one coexisted for a session. So the framing tests below address mcp_probe.Mcp directly, and
a separate test pins that every probe still gets its client from there rather than growing a
third copy.

Not only the framing lives here any more, because the same shape turned up again and again: a
check that cannot fail, and a check that cannot run. The waking rules the shared module owns are
pinned here with it -- that `is_ticking` compares the world clock instead of reading a flag and
calling it a measurement (H14), that a JSON-RPC error is an error rather than an empty result
(H15), that a guard which measured nothing ends the run as EXIT_SETUP rather than as a FAIL (M5),
and that every live probe both imports and CALLS the guard. mutate.py is here for the same reason
one level up: it is the tool that decides whether the others have teeth, and on Windows it answered
SURVIVED for mutations it never compiled (H16).
"""

import contextlib
import io
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import types
import unittest

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPTS)

import importlib.util  # noqa: E402 - after the sys.path line, same as the probes do it
import mcp_probe  # noqa: E402

RECV = mcp_probe.RECV_SIZE  # chunk boundaries are what the old logic tripped over


def load(script):
    """Import a probe by path -- the filenames are hyphenated, so plain import will not do."""
    path = os.path.join(SCRIPTS, script)
    spec = importlib.util.spec_from_file_location(script.replace("-", "_")[:-3], path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def encode(obj):
    """Compact, the way the server emits it. With spaces the b'"id":2' probe never matches."""
    return json.dumps(obj, separators=(",", ":")).encode()


def reply(text):
    return encode({"jsonrpc": "2.0", "id": 2, "result": {"content": [{"text": text}]}})


class SharedClientTest(unittest.TestCase):
    """Every probe must take its socket client from mcp_probe, not carry its own."""

    PROBES = ("nav-astar-probe.py", "live-dwm-probe.py", "live-hold-probe.py",
              "live-nav-probe.py", "live-look-probe.py", "live-route-probe.py",
              "live-act-plan-probe.py")

    # The probes that talk to a live client, and therefore measure a world that has to be moving.
    # Spelled out rather than aliased to PROBES above: the two lists coincide today, and the point
    # of this one is that a probe is either on it or is a probe that needs no world at all -- which
    # is a claim to make deliberately, not to inherit from a neighbouring list.
    PROBES_NEEDING_A_LIVE_WORLD = (
        "nav-astar-probe.py", "live-dwm-probe.py", "live-hold-probe.py", "live-nav-probe.py",
        "live-look-probe.py", "live-route-probe.py", "live-act-plan-probe.py",
    )

    def test_no_probe_defines_its_own_socket_client(self):
        """A second copy of the read loop would be a second chance to reintroduce the truncation.

        Asserting on identity, not on behaviour: a copy that happens to be correct today still
        drifts, and this file's whole reason for existing is that exactly that happened once.

        assertIs works here only because the probes now `import mcp_probe` by name, so every one
        of them resolves to the same sys.modules entry. It could NOT be written while the shared
        parts lived in nav-astar-probe.py: load() execs a fresh module per call, and the hold
        probe's own by-path load of the nav probe produced a second class object with the same
        name, so an identity check failed with "X is not X" and said nothing about the property.
        The weaker check available then compared Mcp.__module__ as a string -- which two copies
        in one file could also satisfy.
        """
        for script in self.PROBES:
            with self.subTest(probe=script):
                probe = load(script)
                self.assertIs(probe.Mcp, mcp_probe.Mcp,
                              f"{script} must use mcp_probe.Mcp rather than defining its own; "
                              "another copy of the read loop is another chance to reintroduce "
                              "the truncation this file exists to pin")

    def test_the_derived_probes_reuse_the_guards_too(self):
        """The guards are the other half of what a copy loses.

        Writing a bare call past the ticking guard is the mistake that produced a false bug
        report in this repo before, so a derived probe must reach for the shared guard rather than
        reimplement it.

        SEVEN probes, not the five this used to name. The two it left out are the two that needed
        it most: nav-astar-probe.py is the one probe with no top-level `return EXIT_SETUP` in front
        of report(), and live-dwm-probe.py drove a real game for a whole session without ever
        asking whether the world was moving -- the ticking guard and the pauseOnLostFocus hatch
        were exactly the two things its copy of the harness had never grown.
        """
        for script in self.PROBES_NEEDING_A_LIVE_WORLD:
            derived = load(script)
            for helper in ("require_ticking", "allow_unfocused", "record"):
                with self.subTest(probe=script, helper=helper):
                    self.assertIs(getattr(derived, helper, None), getattr(mcp_probe, helper),
                                  f"{script} must reuse mcp_probe.{helper} rather than "
                                  "reimplementing the guard")

    def test_every_probe_that_needs_a_live_world_actually_calls_the_ticking_guard(self):
        """Importing the guard is not the same contract as running it.

        The identity check above is satisfied by an import nobody uses, and the failure it would
        hide is the expensive one: a probe that measures a frozen world and reports the result as
        a defect in the code. This asks the compiled code instead of the text -- whether
        `require_ticking` is LOADED inside one of the probe's functions, which a mention in a
        docstring or a comment cannot fake.
        """
        for script in self.PROBES_NEEDING_A_LIVE_WORLD:
            with self.subTest(probe=script):
                self.assertIn("require_ticking", globals_loaded(load(script)),
                              f"{script} depends on the world advancing but never calls "
                              "require_ticking()")


def globals_loaded(module):
    """Every global name the module's own FUNCTIONS load, nested functions included.

    Not the module's co_names: `from mcp_probe import require_ticking` stores that name at module
    level, so an import with no call site would satisfy it. A name LOADED from inside a function
    body is a use, and reading it off the compiled code means a comment or a docstring that happens
    to spell the name cannot fake it.
    """
    names = set()
    stack = [v.__code__ for v in vars(module).values() if isinstance(v, types.FunctionType)]
    while stack:
        code = stack.pop()
        names.update(code.co_names)
        stack.extend(c for c in code.co_consts if isinstance(c, types.CodeType))
    return names


def java_string_blob(path):
    """Every string literal in a Java file, concatenated into one searchable blob.

    Java splits a long message across `+`-joined literals on separate lines, so a phrase the probe
    matches on ("tracked for N ticks, aim held on ...") does not appear contiguously in the source.
    Joining the literals in order reconstitutes what the code actually emits, minus the runtime
    values -- which is exactly the part a probe matches on.

    Crude on purpose: it does not parse Java. It only needs to answer "does this phrase survive in
    the source", and a false PASS would require the phrase to appear in some unrelated literal,
    which the phrases below are far too specific for.
    """
    with open(path, encoding="utf-8") as f:
        src = f.read()
    out = []
    i = 0
    while True:
        start = src.find('"', i)
        if start < 0:
            break
        j = start + 1
        while j < len(src):
            if src[j] == "\\":
                j += 2
                continue
            if src[j] == '"':
                break
            j += 1
        out.append(src[start + 1:j])
        i = j + 1
    return "".join(out)


class ProbeMessageLiteralsMatchProductionTest(unittest.TestCase):
    """The phrases live-look-probe.py matches on must exist in the code that emits them.

    WHY THIS IS NOT PARANOIA. The probe's verdicts key on message text, and its self-check feeds
    them fixtures TYPED BY HAND from reading the Java. So the self-check can pass 27/27 while every
    live assertion fails, because both halves agree with each other and neither agrees with
    production. That is the same shape as a description assertion that pins prose nobody emits --
    the defect family this repo keeps finding in itself.

    Reworded messages are the expected failure here, and the fix is to update both the probe and
    this list together. A phrase deleted outright is the more interesting failure: it means the
    probe is asserting on an ending the code no longer has.
    """

    LOOK_CONTROLLER = os.path.join(
        SCRIPTS, os.pardir, "core", "src", "main", "java", "net", "marcloud", "mcp", "core",
        "drivers", "act", "LookController.java")

    # Every phrase a verdict in live-look-probe.py keys on, with the verdict that needs it.
    PHRASES = (
        ("holding aim on yaw=", "landed_verdict / following_verdict"),
        ("slewing toward yaw=", "following_verdict"),
        ("look cancelled after ", "cancel_verdict"),
        ("tracked for ", "bounded_verdict / never_aimed_verdict"),
        ("aim held on yaw=", "bounded_verdict"),
        ("never reached the target", "never_aimed_verdict"),
        ("degrees of yaw out", "never_aimed_verdict"),
        ("is gone", "gone_verdict"),
        ("ticks of tracking", "gone_verdict (the KEEP suffix)"),
        ("not in world", "the world-gone ending"),
    )

    def test_every_phrase_the_look_probe_matches_on_exists_in_production(self):
        blob = java_string_blob(self.LOOK_CONTROLLER)
        self.assertGreater(len(blob), 200, "the literal extractor found almost nothing; it is "
                                           "broken and every assertion below would be vacuous")
        for phrase, used_by in self.PHRASES:
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, blob,
                              f"live-look-probe.py's {used_by} matches on {phrase!r}, but "
                              "LookController emits no such text. Either the message was reworded "
                              "(update both) or the ending was removed (the probe is asserting on "
                              "something the code no longer does)")

    def test_the_extractor_would_notice_a_missing_phrase(self):
        """Guards the check above from going hollow: prove a phrase that is NOT there fails.

        Without this, a broken extractor returning a huge irrelevant blob could satisfy every
        assertion above, and the length check alone would not catch it.
        """
        blob = java_string_blob(self.LOOK_CONTROLLER)
        self.assertNotIn("holding aim on pitch-only", blob)
        self.assertNotIn("tracked forever", blob)

    def test_the_look_probe_verdicts_accept_productions_own_wording(self):
        """End to end: build the message the way the Java does and feed it to the real verdict.

        The strongest of the three, because it does not trust the phrase list either -- it takes
        the fixtures out of the probe's own self-check and requires them to be recognised, which
        only holds while the fixtures still look like what production emits.
        """
        probe = load("live-look-probe.py")
        blob = java_string_blob(self.LOOK_CONTROLLER)
        # Each fixture is the probe's, and each prefix must be production's.
        for fixture, prefix, verdict, want in (
            ("holding aim on yaw=-90.0 pitch=-0.0 (tick 12)", "holding aim on yaw=",
             lambda m: probe.landed_verdict("ACTIVE", m), True),
            ("look cancelled after 37 ticks", "look cancelled after ",
             lambda m: probe.cancel_verdict("CANCELLED", m), True),
            ("tracked for 40 ticks, aim held on yaw=-90.0 pitch=-0.0", "aim held on yaw=",
             lambda m: probe.bounded_verdict("COMPLETE", m, 40), True),
        ):
            with self.subTest(fixture=fixture):
                self.assertIn(prefix, blob, "the fixture's wording is not production's")
                self.assertEqual(want, verdict(fixture))


class RoutePhraseMatchesProductionTest(unittest.TestCase):
    """live-route-probe.py keys on RouteExecutor's terminal wording.

    COMPLETE alone is hollow (empty plan also completes). The distinctive string is
    'route complete:'. If production rewords it, the live probe goes green on the
    wrong ending until this fails.
    """

    ROUTE_EXECUTOR = os.path.join(
        SCRIPTS, os.pardir, "core", "src", "main", "java", "net", "marcloud", "mcp", "core",
        "drivers", "plan", "RouteExecutor.java")

    def test_route_complete_wording_exists_in_production(self):
        blob = java_string_blob(self.ROUTE_EXECUTOR)
        self.assertGreater(len(blob), 200)
        self.assertIn("route complete:", blob)
        self.assertIn("nothing to do: the plan was empty", blob)

    def test_the_route_probe_rejects_the_empty_plan_complete(self):
        probe = load("live-route-probe.py")
        self.assertFalse(probe.route_complete_verdict(
            "COMPLETE",
            "nothing to do: the plan was empty, so the player is already where it asked to be",
            0.05, 0.04, 1))
        self.assertTrue(probe.route_complete_verdict(
            "COMPLETE",
            "route complete: 8 move(s), 0 block(s) spent, ending (8.5, 64.0, 0.5)",
            8.0, 0.32, 47))


class ActPlanPhraseMatchesProductionTest(unittest.TestCase):
    """live-act-plan-probe.py keys on ActPlanInterpreter's terminal wording."""

    INTERPRETER = os.path.join(
        SCRIPTS, os.pardir, "core", "src", "main", "java", "net", "marcloud", "mcp", "core",
        "drivers", "act", "ActPlanInterpreter.java")

    def test_plan_complete_wording_exists_in_production(self):
        blob = java_string_blob(self.INTERPRETER)
        self.assertGreater(len(blob), 100)
        self.assertIn("plan complete", blob)

    def test_the_act_plan_probe_rejects_running_and_unchanged_hotbar(self):
        probe = load("live-act-plan-probe.py")
        self.assertFalse(probe.plan_complete_verdict(
            "RUNNING", "running step 0/2", 0, 0, 3, 10.0, 90.0, 90.0))
        self.assertFalse(probe.plan_complete_verdict(
            "COMPLETE", "plan complete", 3, 3, 3, 10.0, 90.0, 90.0))
        self.assertTrue(probe.plan_complete_verdict(
            "COMPLETE", "plan complete", 0, 3, 3, 10.0, 90.0, 90.0))


class ReplyFramingTest(unittest.TestCase):
    """The contract itself, against the one implementation every probe shares."""

    def setUp(self):
        self.mcp = mcp_probe.Mcp

    def test_should_reject_a_reply_that_is_still_arriving(self):
        big = reply("X" * 200_000)
        self.assertGreater(len(big) // RECV, 2, "fixture must span several recv calls")
        for cut in (100, RECV, 2 * RECV, len(big) - 1):
            with self.subTest(received=cut):
                self.assertIsNone(
                    self.mcp._complete_reply(big[:cut]),
                    "a partial reply was accepted, so the read loop stops early and truncates",
                )

    def test_should_accept_the_reply_once_it_is_whole(self):
        big = reply("X" * 200_000)
        self.assertEqual(self.mcp._complete_reply(big), big)

    def test_should_ignore_the_initialize_reply(self):
        init = encode({"jsonrpc": "2.0", "id": 1, "result": {}}) + b"\n"
        self.assertIsNone(self.mcp._complete_reply(init))

    def test_should_not_mistake_a_finished_id1_reply_for_the_id2_one(self):
        """The real wire order: initialize completes first, then the big reply streams in."""
        init = encode({"jsonrpc": "2.0", "id": 1, "result": {}}) + b"\n"
        big = reply("X" * 200_000)
        self.assertIsNone(self.mcp._complete_reply(init + big[:5000]))
        self.assertEqual(self.mcp._complete_reply(init + big), big)

    def test_should_read_a_small_reply_in_one_chunk(self):
        """The case that let the bug hide in live-dwm-probe.py: payload smaller than one recv."""
        small = reply("ok")
        self.assertLess(len(small), RECV)
        self.assertEqual(self.mcp._complete_reply(small), small)


class CaptureOverlayIsQml4jTest(unittest.TestCase):
    """gl/imgui/skiko launchers were demolished; this script must not teach them."""

    SCRIPT = os.path.join(SCRIPTS, "capture-overlay.sh")

    def test_source_does_not_name_demolished_backends(self):
        with open(self.SCRIPT, encoding="utf-8") as f:
            text = f.read()
        for needle in ("dwm-gl", "dwm-imgui", "dwm-skiko", "overlay.backend"):
            self.assertNotIn(needle, text, f"{needle} is a demolished overlay path")
        self.assertIn("qml4j", text)


class FrozenWorldTest(unittest.TestCase):
    """H14: "is the world advancing" is decided by the CLOCK, not by the pause flag.

    A world whose isGamePaused() is false and whose clock stands still is not hypothetical -- the
    game thread suspended by this repo's own debug_suspend_thread, an integrated-server stall, a
    resource reload. The old is_ticking returned True for all of them (its only decision was
    `"paused=false" in out`, over a line that also carried the world time, unread), and every probe
    below the guard then measured a still world and reported what it saw as a defect in the code.
    """

    class FakeMcp:
        """The one snippet is_ticking uses, answered with scripted clock readings."""

        def __init__(self, times, paused="false", active="true"):
            self.times = list(times)
            self.paused = paused
            self.active = active
            self.samples = 0

        def java(self, class_name, body):
            t = self.times[min(self.samples, len(self.times) - 1)]
            self.samples += 1
            return f"paused={self.paused} active={self.active} time={t}"

        def call(self, tool, args):
            raise AssertionError("the ticking guard is one snippet; it must not need a tool call")

    def test_a_frozen_clock_is_not_ticking(self):
        # Called with NO timing arguments on purpose: this is the one test that has to fail on the
        # old body for the right reason. The old one answered "ticking" for `"paused=false" in out`,
        # so a keyword the old signature does not accept would turn this into a TypeError -- red,
        # and saying nothing about the defect.
        mcp = self.FakeMcp([1000] * 8)
        ok, detail = mcp_probe.is_ticking(mcp)
        self.assertFalse(ok, "the clock did not move, so nothing under this guard measured "
                             "anything: " + detail)
        self.assertGreaterEqual(mcp.samples, 2,
                                "one sample cannot show whether a clock is moving -- the guard has "
                                "to look twice before it calls a world ticking")

    def test_a_moving_clock_is_ticking(self):
        ok, _ = mcp_probe.is_ticking(self.FakeMcp([1000, 1010]), settle_s=0.0)
        self.assertTrue(ok)

    def test_a_paused_world_is_not_ticking_and_says_why(self):
        ok, detail = mcp_probe.is_ticking(self.FakeMcp([1000, 1010], paused="true"), settle_s=0.0)
        self.assertFalse(ok, "a paused world advances nothing, however it got that way")
        self.assertIn("paused=true", detail)

    def test_an_unreadable_sample_is_not_a_pass(self):
        """PROBE-ERROR (a dead port, a Java throw) must not read as "ticking" either."""

        class Broken(self.FakeMcp):
            def java(self, class_name, body):
                return "PROBE-ERROR connect failed"

        ok, detail = mcp_probe.is_ticking(Broken([0]), settle_s=0.0)
        self.assertFalse(ok)
        self.assertIn("PROBE-ERROR", detail)


class SkippedWorldExitsSetupTest(unittest.TestCase):
    """M5: a check that measured NOTHING must end the run as EXIT_SETUP, not as a FAIL.

    The module's own exit codes reserve 3 for it ("the port answers but the world/client is not in
    a state that can be probed"). Five probes hid the old FAIL by returning 3 before report() ran --
    nav-astar-probe.py has no such guard and ends in a bare report(), so there a frozen world
    printed "FAIL: n/m checks" naming code that was never exercised.
    """

    def setUp(self):
        self.saved_results = mcp_probe.results[:]
        self.saved_skips = mcp_probe.skips[:]
        del mcp_probe.results[:]
        del mcp_probe.skips[:]

    def tearDown(self):
        mcp_probe.results[:] = self.saved_results
        mcp_probe.skips[:] = self.saved_skips

    @staticmethod
    def guard_then_report(mcp, what):
        """Run the guard and the tally with their output captured: the code is the contract."""
        with contextlib.redirect_stdout(io.StringIO()) as buf:
            mcp_probe.require_ticking(mcp, what)
            return mcp_probe.report(), buf.getvalue()

    def test_a_skipped_check_ends_in_exit_setup_and_not_a_fail(self):
        rc, out = self.guard_then_report(FrozenWorldTest.FakeMcp([1000, 1000]),
                                         "a thing only a live world can show")
        self.assertEqual(mcp_probe.EXIT_SETUP, rc,
                         "a frozen world is not a failure of the code under test")
        self.assertIn("a thing only a live world can show", out)
        self.assertNotIn("FAIL", out)
        self.assertEqual([], mcp_probe.results, "a skip is not a check that failed")

    def test_a_real_failure_still_outranks_a_skip(self):
        with contextlib.redirect_stdout(io.StringIO()):
            mcp_probe.record("a real failure", False, "this one IS about the code")
        rc, out = self.guard_then_report(FrozenWorldTest.FakeMcp([1000, 1000]), "unmeasured")
        self.assertEqual(mcp_probe.EXIT_FAIL, rc)
        self.assertIn("a real failure", out)


class UseCheckNeedsAReadSlotTest(unittest.TestCase):
    """H15, probe side: "the slot does not say rejected" is evidence only if a slot was READ.

    nav-astar-probe.py asserts the ABSENCE of "use rejected in air" in act_status's interact slot.
    Searching an empty string for a string that is not in it always succeeds, so the check used to
    PASS on a transport error, on a reply with no text, and on a status carrying no interact slot
    at all -- and then printed "reported as started", which names the opposite of what it knew.
    """

    class FakeMcp:
        """The two snippets and the one tool the check uses, scripted."""

        def __init__(self, status):
            self.status = status

        def java(self, class_name, body):
            if class_name == "UseSetup":
                return "canEat=true held=bread"
            return "useCount=32 isUsing=true"

        def call(self, tool, args):
            return self.status if tool == "act_status" else {}

    def verdict_for(self, status):
        """Run the real check against that reply and return its recorded verdict."""
        probe = load("nav-astar-probe.py")
        fake = self.FakeMcp(status)
        saved = mcp_probe.results[:]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                with patched(probe, require_ticking=lambda mcp, what: True):
                    probe.probe_use_reports_started(fake)
            _, ok, detail = mcp_probe.results[-1]
        finally:
            mcp_probe.results[:] = saved
        return ok, detail

    @staticmethod
    def status_with(slot):
        return {"text": json.dumps({"slots": [slot]})}

    def test_a_transport_error_is_not_a_pass(self):
        ok, detail = self.verdict_for({"error": "connect failed: [Errno 61] refused"})
        self.assertFalse(ok, "a reply that never arrived cannot show anything about the code")
        self.assertIn("PREMISE FAILED", detail)

    def test_a_status_without_an_interact_slot_is_not_a_pass(self):
        ok, _ = self.verdict_for(self.status_with({"slot": "look", "phase": "ACTIVE"}))
        self.assertFalse(ok, "no slot was read, so no absence was observed")

    def test_a_status_with_no_text_at_all_is_not_a_pass(self):
        ok, _ = self.verdict_for({})
        self.assertFalse(ok)

    def test_a_read_slot_without_the_rejection_still_passes(self):
        ok, detail = self.verdict_for(self.status_with({"slot": "interact", "phase": "ACTIVE"}))
        self.assertTrue(ok, detail)

    def test_a_read_slot_reporting_the_rejection_fails(self):
        ok, _ = self.verdict_for(self.status_with(
            {"slot": "interact", "phase": "FAILED", "message": "use rejected in air"}))
        self.assertFalse(ok)


class OneReplyServer:
    """Answers exactly one Mcp.call with a canned line, over a real socket.

    Mcp.call opens its own connection per call, so one accept is enough. A real socket rather than
    a stubbed transport, because what is pinned here is the classification of a reply that came off
    the wire -- the read loop above it is already pinned by ReplyFramingTest.
    """

    def __init__(self, payload):
        self.payload = payload
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(1)
        self.port = self.sock.getsockname()[1]
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self):
        try:
            conn, _ = self.sock.accept()
            with conn:
                # Waited for by METHOD, not by `"id":2`: the client emits spaced json
                # (`"id": 2`) and only the reply fixture above is compact. Waiting for the
                # compact form here hangs the fixture until Mcp.call's own timeout.
                buf = b""
                while b'"tools/call"' not in buf:
                    chunk = conn.recv(RECV)
                    if not chunk:
                        break
                    buf += chunk
                conn.sendall(self.payload)
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.thread.join(10)
        self.sock.close()

    def call(self, tool="act_status"):
        return mcp_probe.Mcp(self.port, timeout=10).call(tool, {})


class ErrorReplyTest(unittest.TestCase):
    """H15: a JSON-RPC error is an ERROR, and a reply that cannot be read is not a success.

    Before this, both arrived as {"text": "", "isError": False} -- indistinguishable from a tool
    that legitimately returned nothing -- so every caller's `if "error" in reply` guard was blind
    to them. nav-astar-probe.py's use check asserts the ABSENCE of a string in act_status's slot,
    and an absence is trivially satisfied by a reply that carries no slot at all: it printed PASS
    for a transport failure.
    """

    def reply_from(self, payload):
        with OneReplyServer(payload) as server:
            return server.call()

    def test_a_jsonrpc_error_is_an_error_and_keeps_its_message(self):
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2, "error": {
            "code": -32601, "message": "no such tool: act_status"}}))
        self.assertIn("error", got)
        self.assertNotIn("text", got, "an error must not be reported as an empty result")
        self.assertIn("no such tool: act_status", got["error"])

    def test_a_reply_with_neither_result_nor_error_is_not_a_success(self):
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2}))
        self.assertIn("error", got)
        self.assertNotIn("text", got)

    def test_a_result_without_a_content_list_is_not_a_success(self):
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2, "result": {"isError": False}}))
        self.assertIn("error", got)

    def test_a_result_without_text_is_not_a_success(self):
        """Nothing a probe reads is text-free; "" there is the flattening this exists to end."""
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2, "result": {"content": [
            {"type": "image", "data": "AAAA"}]}}))
        self.assertIn("error", got)

    def test_an_image_result_does_not_hide_the_text_beside_it(self):
        """gui_snapshot(includeImage) sends text AND an image. content[0] may be either."""
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2, "result": {"content": [
            {"type": "image", "data": "AAAA"}, {"type": "text", "text": "snapshot"}]}}))
        self.assertEqual(got, {"text": "snapshot", "isError": False})

    def test_a_tool_level_error_is_still_a_tool_result(self):
        """The guard against over-correcting: isError is how a tool refuses, and it has text."""
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2, "result": {"isError": True,
                                  "content": [{"type": "text", "text": "refused"}]}}))
        self.assertEqual(got, {"text": "refused", "isError": True})

    def test_a_legitimately_empty_tool_result_is_still_empty(self):
        """content: [] is a tool that returned nothing -- a case, not an error."""
        got = self.reply_from(encode({"jsonrpc": "2.0", "id": 2, "result": {"content": []}}))
        self.assertEqual(got, {"text": "", "isError": False})


class MutateHarnessTest(unittest.TestCase):
    """H16: mutate.py decides whether every OTHER regression test has teeth, so its own failure
    modes are pinned here.

    It reported SURVIVED -- "the assertions do not cover this behaviour" -- for mutations it never
    compiled: a hardcoded macOS JAVA_HOME over a correct one, the POSIX ./mvnw on Windows, and an
    uncaught FileNotFoundError, which exits Python with status 1, which this tool documents as
    SURVIVED. The tool that exists to detect no-op assertions was producing one.
    """

    def setUp(self):
        self.mutate = load("mutate.py")

    def make_fake_jdk(self, directory, version):
        os.makedirs(os.path.join(directory, "bin"), exist_ok=True)
        with open(os.path.join(directory, "bin", "javac.exe" if os.name == "nt" else "javac"),
                  "w", encoding="utf-8"):
            pass
        with open(os.path.join(directory, "release"), "w", encoding="utf-8") as f:
            f.write(f'JAVA_VERSION="{version}"\n')

    def test_the_jdk_is_discovered_and_the_newest_one_wins(self):
        jdk = os.path.join(tempfile.gettempdir(), "test-probe-framing-jdk")
        shutil.rmtree(jdk, ignore_errors=True)
        try:
            self.make_fake_jdk(jdk, "99.0.1")
            self.assertEqual(jdk, self.mutate.find_java_home({"JAVA_HOME": jdk}),
                             "a JDK that exists must be used, and newer must beat older")
            self.assertEqual(99, self.mutate.jdk_major(jdk))
        finally:
            shutil.rmtree(jdk, ignore_errors=True)

    def test_a_path_that_does_not_exist_is_never_returned(self):
        missing = os.path.join(tempfile.gettempdir(), "test-probe-framing-not-a-jdk")
        got = self.mutate.find_java_home({"JAVA_HOME": missing})
        self.assertNotEqual(missing, got)
        self.assertTrue(got is None or self.mutate.is_jdk(got),
                        f"returned a path that cannot build: {got}")

    def test_the_wrapper_is_the_one_that_runs_on_this_platform(self):
        wrapper = self.mutate.find_maven_wrapper()
        self.assertIsNotNone(wrapper, "mvnw and mvnw.cmd are both in the checkout root")
        self.assertTrue(os.path.isfile(wrapper))
        self.assertTrue(wrapper.endswith("mvnw.cmd" if os.name == "nt" else "mvnw"),
                        f"{wrapper} is not the wrapper this platform can execute")

    def test_an_unrunnable_harness_is_invalid_and_leaves_the_source_alone(self):
        """The exact defect: status 1 is SURVIVED, and this must never reach it."""
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "Something.java")
            with open(src, "w", encoding="utf-8") as f:
                f.write("class Something { int x = 1; }\n")
            saved = (self.mutate.find_java_home, self.mutate.find_maven_wrapper, sys.argv)
            self.mutate.find_java_home = lambda env=None: None
            self.mutate.find_maven_wrapper = lambda: None
            sys.argv = ["mutate.py", src, "int x = 1;", "int x = 2;", "-Dtest=Nothing"]
            try:
                with contextlib.redirect_stdout(io.StringIO()) as buf:
                    rc = self.mutate.main()
            finally:
                self.mutate.find_java_home, self.mutate.find_maven_wrapper, sys.argv = saved
            self.assertEqual(3, rc, "an unrunnable harness verified nothing; 1 would be SURVIVED")
            lines = buf.getvalue().splitlines()
            # The verdict lines start in column 0; the explanation below them NAMES both verdicts
            # ("neither CAUGHT nor SURVIVED"), so the check is on the line, not on the word.
            self.assertTrue(any(ln.startswith("INVALID") for ln in lines), buf.getvalue())
            self.assertFalse([ln for ln in lines if ln.startswith("SURVIVED")], buf.getvalue())
            with open(src, encoding="utf-8") as f:
                self.assertIn("int x = 1;", f.read(), "nothing was run, so nothing should be left "
                                                     "mutated on disk")


@contextlib.contextmanager
def patched(module, **attrs):
    """Set attributes on a module (loaded by path or not) and put the originals back after.

    The probes are driven through their OWN module globals, so both the functions they define and
    the ones they imported can be replaced by name. A name that was not there before is removed
    again rather than replaced with a placeholder.
    """
    missing = object()
    saved = {k: getattr(module, k, missing) for k in attrs}
    for k, v in attrs.items():
        setattr(module, k, v)
    try:
        yield module
    finally:
        for k, v in saved.items():
            if v is missing:
                delattr(module, k)
            else:
                setattr(module, k, v)


class StagedTargetCleanupTest(unittest.TestCase):
    """M6: the entity live-look-probe.py stages must not outlive the probe on ANY path.

    The probe left the killing to its LAST probe, and that one kills the stand only on its own
    success path -- three of its branches return before the kill, and an exception returns not at
    all. A leftover is not untidiness: spawn_stand's scan takes the FIRST entity wearing the name,
    so the run AFTER a failed one silently tracks the stale stand and reports on it. This repo has
    already paid for that shape once ("a probe left standing poisons the NEXT run").
    """

    class FakeMcp:
        """Enough of a client for the staging helpers: records the java classes it was asked for."""

        def __init__(self, leftovers=0):
            self.classes = []
            self.leftovers = leftovers

        def java(self, class_name, body):
            self.classes.append(class_name)
            if class_name == "LookSweep":
                return "SWEPT 0"
            if class_name == "LookSpawn":
                return "SPAWNED serverId=1 at=1.0,64.0,1.0"
            if class_name == "LookFind":
                return "FOUND id=7 at=1.0,64.0,1.0"
            if class_name == "LookKill":
                return "KILLED"
            if class_name == "LookStands":
                left, self.leftovers = self.leftovers, 0
                return f"STANDS {left} " + ("[9]" if left else "[]")
            return ""

        def call(self, tool, args):
            return {}

    class ProbeBlewUp(Exception):
        """Stands in for any failure between staging and the end of the run."""

    def test_the_staged_stand_is_removed_when_a_probe_raises(self):
        probe = load("live-look-probe.py")
        fake = self.FakeMcp()
        no_op = lambda *a, **k: None  # noqa: E731 - a stand-in, not a definition
        stand_ins = dict(
            Mcp=lambda *a, **k: fake,
            probe_in_world=lambda mcp: True,
            allow_unfocused=lambda mcp: "pauseOnLostFocus=false",
            require_ticking=lambda mcp, what: True,
            require_act_ticking=lambda mcp: True,
            probe_rotation_seam_writes_and_reads_back=no_op,
            probe_durationticks_without_track_is_rejected=no_op,
            spawn_stand=lambda mcp, dx, dz: (7, "SPAWNED id=7"),
            probe_default_aim_stops_correcting=no_op,
            probe_track_follows_a_moving_target=no_op,
            probe_unbounded_track_survives_many_real_ticks=no_op,
            probe_track_reasserts_against_an_outside_write=no_op,
            probe_cancel_frees_the_slot=no_op,
            probe_bounded_track_completes_at_its_bound=no_op,
            probe_a_slew_too_slow_fails_rather_than_claiming_it_aimed=no_op,
            probe_losing_the_target_fails_honestly=lambda mcp, eid: (
                _ for _ in ()).throw(self.ProbeBlewUp("a probe raised")),
        )
        saved_results = mcp_probe.results[:]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                with patched(probe, **stand_ins), patched(sys, argv=["live-look-probe.py"]):
                    with self.assertRaises(self.ProbeBlewUp):
                        probe.main()
        finally:
            mcp_probe.results[:] = saved_results
        self.assertIn("LookKill", fake.classes,
                      "the stage it spawned is still in the world after a probe raised, and the "
                      "NEXT run's scan will adopt it")
        self.assertIn("LookStands", fake.classes,
                      "the run must also assert that nothing is left behind")

    def test_an_unreadable_client_list_is_a_skip_and_not_a_leftover(self):
        """The check must not claim an entity is still there when the read itself failed."""
        probe = load("live-look-probe.py")
        fake = self.FakeMcp()
        fake.java = lambda class_name, body: (
            "KILLED" if class_name == "LookKill" else "PROBE-ERROR connect failed")
        saved_results = mcp_probe.results[:]
        saved_skips = mcp_probe.skips[:]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                probe.remove_staged_stand(fake, 7, max_wait_s=0.0)
            self.assertEqual([], mcp_probe.results, "an unreadable check is not a FAIL")
            self.assertEqual(1, len(mcp_probe.skips), "it measured nothing, and that is a skip")
        finally:
            mcp_probe.results[:] = saved_results
            mcp_probe.skips[:] = saved_skips


class DwmLivenessTest(unittest.TestCase):
    """H18: live-dwm-probe.py's alive() must run HERE.

    It shelled out to `pgrep -f net.minecraft.client.main.Main`, which Windows does not have, so
    the first step of the first run raised FileNotFoundError out of a probe whose whole point is to
    notice a client that died -- on the only platform this project runs on.
    """

    def test_alive_answers_on_this_platform_instead_of_raising(self):
        probe = load("live-dwm-probe.py")
        self.assertIsInstance(probe.alive(), bool)
        self.assertIsInstance(probe.alive(), bool, "and again, from the cached pid path")


if __name__ == "__main__":
    unittest.main(verbosity=2)
