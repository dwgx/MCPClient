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
"""

import json
import os
import sys
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

    def test_the_hold_probe_reuses_the_guards_too(self):
        """The guards are the other half of what a copy loses.

        Writing a bare call past the ticking guard is the mistake that produced a false bug
        report in this repo before, so the hold probe must reach for the shared guard rather than
        reimplement it.
        """
        # Both derived probes, not only the hold one. live-nav-probe.py arrived from a change
        # written in parallel with the extraction and wired itself through nav-astar-probe.py,
        # where these helpers used to live; the merge repointed it. Naming both here is what stops
        # the next arrival taking the same indirect route back.
        for script in ("live-hold-probe.py", "live-nav-probe.py", "live-look-probe.py",
                       "live-route-probe.py", "live-act-plan-probe.py"):
            derived = load(script)
            for helper in ("require_ticking", "allow_unfocused", "record"):
                with self.subTest(probe=script, helper=helper):
                    self.assertIs(getattr(derived, helper, None), getattr(mcp_probe, helper),
                                  f"{script} must reuse mcp_probe.{helper} rather than "
                                  "reimplementing the guard")


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
