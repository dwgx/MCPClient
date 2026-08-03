#!/usr/bin/env python3
"""Regression guard for the probes' JSON-RPC reply framing.

    python3 -m unittest discover -s scripts -p 'test_*.py' -v

Both probes read a line-delimited JSON-RPC reply off a socket. The bug this pins: breaking the
read loop as soon as b'"id":2' appears anywhere in the buffer, which truncates any reply larger
than one recv. nav-astar-probe.py hit it for real -- world_view at radius 16 is ~180KB over 4
chunks and came back "unparseable reply: Unterminated string". live-dwm-probe.py carried the same
shape for longer, hidden only by smaller payloads.

Both probes must therefore treat "the id=2 line is present" and "the id=2 line is complete" as
different questions. These tests fail against the old logic.
"""

import importlib.util
import json
import os
import unittest

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
RECV = 65536  # the probes' recv size; chunk boundaries are what the old logic tripped over


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


class ReplyFramingTest(unittest.TestCase):
    """Run the same contract against both probes, so neither drifts back."""

    def setUp(self):
        self.probes = {
            "nav-astar-probe.py": load("nav-astar-probe.py").Mcp,
            "live-dwm-probe.py": load("live-dwm-probe.py").Mcp,
        }

    def test_should_reject_a_reply_that_is_still_arriving(self):
        big = reply("X" * 200_000)
        self.assertGreater(len(big) // RECV, 2, "fixture must span several recv calls")
        for name, mcp in self.probes.items():
            for cut in (100, RECV, 2 * RECV, len(big) - 1):
                with self.subTest(probe=name, received=cut):
                    self.assertIsNone(
                        mcp._complete_reply(big[:cut]),
                        "a partial reply was accepted, so the read loop stops early and truncates",
                    )

    def test_should_accept_the_reply_once_it_is_whole(self):
        big = reply("X" * 200_000)
        for name, mcp in self.probes.items():
            with self.subTest(probe=name):
                self.assertEqual(mcp._complete_reply(big), big)

    def test_should_ignore_the_initialize_reply(self):
        init = encode({"jsonrpc": "2.0", "id": 1, "result": {}}) + b"\n"
        for name, mcp in self.probes.items():
            with self.subTest(probe=name):
                self.assertIsNone(mcp._complete_reply(init))

    def test_should_not_mistake_a_finished_id1_reply_for_the_id2_one(self):
        """The real wire order: initialize completes first, then the big reply streams in."""
        init = encode({"jsonrpc": "2.0", "id": 1, "result": {}}) + b"\n"
        big = reply("X" * 200_000)
        for name, mcp in self.probes.items():
            with self.subTest(probe=name):
                self.assertIsNone(mcp._complete_reply(init + big[:5000]))
                self.assertEqual(mcp._complete_reply(init + big), big)

    def test_should_read_a_small_reply_in_one_chunk(self):
        """The case that let the bug hide in live-dwm-probe.py: payload smaller than one recv."""
        small = reply("ok")
        self.assertLess(len(small), RECV)
        for name, mcp in self.probes.items():
            with self.subTest(probe=name):
                self.assertEqual(mcp._complete_reply(small), small)


if __name__ == "__main__":
    unittest.main(verbosity=2)
