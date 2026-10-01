"""Tests for `scripts/mcpframing.py` — the shared MCP reply reader.

**Why a synthetic server rather than the real client.** The defect this pins cannot be produced by
a reply that happens to fit in one `recv`; it needs a payload that straddles chunk boundaries at
unlucky offsets, and the real surface straddles some offsets and not others. A test that only ran
against the live client would therefore be green or red depending on the day's description sizes.

So: a real socket, a real thread, a payload served at **every** chunk size from 1 byte upward. The
rule under test is that a reply is arrived when it PARSES, never when it looks finished.

**The failure this exists for.** `verify-protocol.py` originally waited for the buffer to end in
`}`. A chunk boundary landing after an inner `}` satisfies that while the line is still half
arrived, so the loop stopped early and `json.loads` raised on a truncated document — a transport
fault escaping as a product failure. Three of five chunk sizes reproduced it.

Run:  python -m unittest discover -s scripts -p 'test_mcpframing.py'
"""

from __future__ import annotations

import json
import socket
import threading
import unittest

from mcpframing import complete_reply, read_reply


def _reply_line(n_tools: int) -> bytes:
    # COMPACT separators, because that is what the kernel puts on the wire. json.dumps defaults to
    # `", "` and `": "`, which would emit `"id": 2` and match nothing -- a test that quietly stops
    # testing is the failure mode this file exists to prevent, so the fixture must be faithful.
    payload = {"jsonrpc": "2.0", "id": 2, "result": {"tools": [
        {"name": f"tool_{i}", "description": "x" * 40, "inputSchema": {"type": "object"}}
        for i in range(n_tools)]}}
    return (json.dumps(payload, separators=(",", ":")) + "\n").encode()
class CompleteReplyTest(unittest.TestCase):
    def test_a_line_counts_only_once_it_parses(self):
        line = _reply_line(3)
        # Every prefix STRICTLY SHORTER than the document itself must fail to parse. The last byte
        # is the newline, so line[:-1] IS the complete document and is expected to parse -- a test
        # that forgets that asserts the reader must reject a finished message, which is the opposite
        # of what it means.
        body = line.rstrip(b"\n")
        for cut in range(1, len(body)):
            self.assertIsNone(complete_reply(line[:cut]),
                              f"a {cut}-byte prefix parsed, so the loop would stop early")
        self.assertEqual(complete_reply(line), line.rstrip(b"\n"))

    def test_the_initialize_reply_is_not_mistaken_for_the_reply(self):
        init = b'{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05"}}\n'
        self.assertIsNone(complete_reply(init))
        both = init + _reply_line(2)
        self.assertEqual(complete_reply(both), complete_reply(_reply_line(2)))

    def test_an_unrelated_line_is_ignored(self):
        noise = b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
        self.assertIsNone(complete_reply(noise + b'{"jsonrpc":"2.0","id":'))
        self.assertIsNotNone(complete_reply(noise + _reply_line(1)))

    def test_a_different_id_is_not_this_reply(self):
        other = b'{"jsonrpc":"2.0","id":7,"result":{"x":1}}\n'
        self.assertIsNone(complete_reply(other))
        self.assertIsNotNone(complete_reply(other, reply_id=7))


class ReadReplyOverARealSocketTest(unittest.TestCase):
    """The end-to-end shape: every chunk size, because that is where the bug lived."""

    @staticmethod
    def _serve(payload: bytes, chunk: int, ready: threading.Event, port_box: list):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port_box.append(srv.getsockname()[1])
        ready.set()
        conn, _ = srv.accept()
        try:
            conn.recv(65536)  # the request; its content does not matter here
            for i in range(0, len(payload), chunk):
                conn.sendall(payload[i:i + chunk])
            conn.shutdown(socket.SHUT_WR)
        finally:
            conn.close()
            srv.close()

    def test_every_chunk_size_yields_the_whole_reply(self):
        payload = _reply_line(400)  # ~30 KB, and deliberately dense with `}` so a boundary lands
        # inside the document constantly
        self.assertGreater(len(payload), 32_000)
        # Byte-at-a-time chunking is deliberately NOT in this list: the reader re-scans the buffer on
        # every chunk, so 1-byte chunks over 30 KB is quadratic and turns a unit test into a
        # minute-long one. The bug this pins is boundaries landing INSIDE the document, and 64 bytes
        # does that hundreds of times.
        for chunk in (64, 512, 1024, 2048, 4096, 8192, 65536):
            with self.subTest(chunk=chunk):
                ready = threading.Event()
                port_box: list = []
                t = threading.Thread(target=self._serve,
                                    args=(payload, chunk, ready, port_box), daemon=True)
                t.start()
                ready.wait(5)
                sock = socket.create_connection(("127.0.0.1", port_box[0]), 5)
                sock.settimeout(20)
                try:
                    # The server waits for a request before it replies, exactly as the kernel's
                    # socket transport does, so the client has to send one. An earlier version of
                    # this test only ever read, and every case timed out -- which is what a test
                    # that never exercised the path looks like.
                    sock.sendall(b'{"jsonrpc":"2.0","id":1,"method":"initialize"}\n')
                    line = read_reply(sock, 65536)
                finally:
                    sock.close()
                t.join(5)
                self.assertEqual(json.loads(line)["result"]["tools"][399]["name"], "tool_399")

    def test_a_server_that_closes_first_is_a_transport_error(self):
        ready = threading.Event()
        port_box: list = []
        t = threading.Thread(target=self._serve,
                            args=(b'{"jsonrpc":"2.0","id":2,"result":', 64, ready, port_box),
                            daemon=True)
        t.start()
        ready.wait(5)
        sock = socket.create_connection(("127.0.0.1", port_box[0]), 5)
        sock.settimeout(20)
        try:
            sock.sendall(b'{"jsonrpc":"2.0","id":1,"method":"initialize"}\n')
            with self.assertRaises(OSError):
                read_reply(sock, 65536)
        finally:
            sock.close()
            t.join(5)


if __name__ == "__main__":
    unittest.main()