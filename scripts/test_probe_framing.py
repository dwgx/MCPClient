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
              "live-nav-probe.py")

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
        for script in ("live-hold-probe.py", "live-nav-probe.py"):
            derived = load(script)
            for helper in ("require_ticking", "allow_unfocused", "record"):
                with self.subTest(probe=script, helper=helper):
                    self.assertIs(getattr(derived, helper, None), getattr(mcp_probe, helper),
                                  f"{script} must reuse mcp_probe.{helper} rather than "
                                  "reimplementing the guard")


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
