#!/usr/bin/env python3
"""Acceptance tests for mcp_stdio_tcp_bridge.py, without launching Minecraft.

**Why a fake server and not the game.** The whole point of the relay is a hop
that has never been exercised. Testing it against the real kernel would require
starting the game, which is the one thing that must not happen here, and a
failure would be ambiguous: was the wire wrong, or was the game not ready? So
the upstream is a socket on 127.0.0.1:0 that speaks the same newline-delimited
JSON-RPC the kernel speaks. Everything the relay is responsible for - framing,
UTF-8 transparency, both directions live at once, refusing to hang on a dead
port - is upstream-independent.

**What each test is actually proving**, because a test that passes for the
wrong reason is worse than no test:

- ``test_request_response_roundtrip`` proves the client->server direction and
  byte-for-byte fidelity: the exact bytes written to stdin are the exact bytes
  the fake server read, including a non-ASCII payload and non-canonical JSON
  whitespace that a normalising implementation would eat.
- ``test_server_push_reaches_stdout`` proves the server->client direction on its
  own, with NOTHING written to stdin. This is the direction that a single-
  threaded relay silently loses, because it is blocked reading a stdin that
  will never speak.
- ``test_both_directions_live_simultaneously`` is the anti-starvation test: a
  server blast and a client blast overlap in time, and both must complete. A
  relay that alternated the two directions in one thread passes tests 1 and 2
  and fails this one.
- ``test_refuses_when_nothing_listening`` pins the diagnosability requirement:
  a closed port must produce an immediate, named failure - not a process that
  waits forever looking healthy.
- ``test_large_line_is_not_truncated`` pins the framing requirement: 4 KB+ on a
  single line, in BOTH directions, because a reader that assumes one read per
  message passes the small tests and loses the large ones.
- ``test_stdout_carries_only_protocol_bytes`` pins the stderr decision: not one
  diagnostic byte may reach the protocol channel.

Run: python scripts/test_mcp_stdio_tcp_bridge.py
"""

from __future__ import annotations

import json
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

BRIDGE = Path(__file__).with_name("mcp_stdio_tcp_bridge.py")
TIMEOUT = 15.0


class FakeMcpServer:
    """A one-connection TCP server that speaks newline-delimited JSON-RPC.

    `on_line(line: bytes)` may return bytes to be sent back, or None. Whatever
    the handler appends is written verbatim, so a test can assert on the exact
    framing rather than on a parsed shape.
    """

    def __init__(self, on_line=None, on_connect=None):
        self.on_line = on_line
        self.on_connect = on_connect
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self.received: list[bytes] = []
        self.conn: socket.socket | None = None
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self):
        try:
            conn, _ = self._sock.accept()
        except OSError:
            return
        self.conn = conn
        if self.on_connect:
            try:
                self.on_connect(conn)
            except OSError:
                return
        if self.on_line is None:
            return
        buf = bytearray()
        try:
            while True:
                chunk = conn.recv(65536)
                if not chunk:
                    return
                buf += chunk
                while True:
                    nl = buf.find(b"\n")
                    if nl < 0:
                        break
                    line = bytes(buf[:nl])
                    del buf[:nl + 1]
                    self.received.append(line)
                    reply = self.on_line(line)
                    if reply is not None:
                        conn.sendall(reply)
        except OSError:
            return
        finally:
            # A real server closes when the client half-closes; without this the
            # relay's bounded drain would sit out its whole timeout every test.
            try:
                conn.close()
            except OSError:
                pass

    def close(self):
        for s in (self.conn, self._sock):
            try:
                if s:
                    s.close()
            except OSError:
                pass


class Bridge:
    """The relay under test, driven exactly the way omp would drive it."""

    def __init__(self, port, host="127.0.0.1", extra=()):
        self.proc = subprocess.Popen(
            [sys.executable, "-u", str(BRIDGE), "--host", host,
             "--port", str(port), *extra],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE)
        self._buf = bytearray()
        self._lock = threading.Lock()

    def send(self, data: bytes):
        # A relay that already exited closes the pipe; for the fail-fast tests
        # that is a legitimate outcome, not a harness error.
        try:
            self.proc.stdin.write(data)
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError, OSError):
            pass

    def send_line(self, obj):
        payload = obj if isinstance(obj, bytes) else json.dumps(obj).encode()
        self.send(payload + b"\n")

    def read_line(self, timeout=TIMEOUT) -> bytes:
        """One newline-terminated line from stdout, or raise.

        The leftover buffer and the lock are the whole point. A read can return
        three lines at once, and a reader that keeps them only in a local
        variable loses two of them - then waits forever for a line the relay
        already sent, and the test hangs instead of failing. The lock is
        because the starvation test reads stdout from two threads at once.
        """
        deadline = time.monotonic() + timeout
        with self._lock:
            while True:
                nl = self._buf.find(b"\n")
                if nl >= 0:
                    line = bytes(self._buf[:nl])
                    del self._buf[:nl + 1]
                    return line
                if time.monotonic() >= deadline:
                    raise AssertionError(
                        f"no line within {timeout}s (partial {bytes(self._buf)!r})")
                chunk = self.proc.stdout.read1(65536)
                if not chunk:
                    raise AssertionError(
                        f"bridge closed stdout early; stderr="
                        f"{self.proc.stderr.read().decode(errors='replace')}")
                self._buf += chunk

    def drain_stdout(self) -> bytes:
        """Everything the relay has written, for the purity assertions."""
        with self._lock:
            self._buf += self.proc.stdout.read() or b""
            return bytes(self._buf)

    def close_stdin(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass

    def wait(self, timeout=TIMEOUT) -> int:
        return self.proc.wait(timeout=timeout)

    def kill(self):
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait(timeout=5)
        for stream in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.kill()


def _closed_port() -> int:
    """A port that is guaranteed to have no listener."""
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# --------------------------------------------------------------------------
# 1. client -> server -> client, byte for byte
# --------------------------------------------------------------------------

def test_request_response_roundtrip():
    """An `initialize` goes out verbatim; the reply comes back verbatim.

    The request deliberately uses non-canonical JSON spacing, a non-ASCII
    payload and an escaped forward slash. Any layer that reformats the document
    - json.loads then json.dumps - changes one of them.
    """
    request = (b'{"jsonrpc": "2.0", "id": 1, "method": "initialize",'
               b' "params": {"clientInfo": {"name": "caf\xc3\xa9-\xe4\xb8\xad\xe6\x96\x87",'
               b' "tags": ["a/b", "1e3"]}, "x": 1.50}}')
    reply = (b'{"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2024-11-05",'
            b' "serverInfo": {"name": "mcp-core", "version": "1.0.0"},'
            b' "capabilities": {"tools": {}}}}')

    server = FakeMcpServer(on_line=lambda line: reply + b"\n")
    try:
        with Bridge(server.port) as b:
            b.send(request + b"\n")
            got = b.read_line()
            assert got == reply, f"relay altered the reply:\n  in  {reply!r}\n  out {got!r}"
            b.close_stdin()
            b.wait()
        assert server.received == [request], (
            f"relay altered the request:\n  sent     {request!r}\n"
            f"  received {server.received!r}")
    finally:
        server.close()
    print(f"  server saw {len(server.received[0])} bytes, client saw "
          f"{len(got)} bytes, both byte-identical")


# --------------------------------------------------------------------------
# 2. server -> client with the client silent
# --------------------------------------------------------------------------

def test_server_push_reaches_stdout():
    """An unsolicited server line arrives while stdin is completely idle.

    Nothing is written to stdin here, so a relay that only ever reads stdin
    before it reads the socket produces nothing at all and this test times out.
    """
    notifications = [
        json.dumps({"jsonrpc": "2.0", "method": "notifications/message",
                    "params": {"level": "info", "data": f"tick {i}"}}).encode()
        for i in range(5)
    ]

    def on_connect(conn):
        for n in notifications:
            conn.sendall(n + b"\n")

    server = FakeMcpServer(on_line=lambda line: None, on_connect=on_connect)
    try:
        with Bridge(server.port) as b:
            for i, expected in enumerate(notifications):
                got = b.read_line()
                assert got == expected, (
                    f"notification {i} altered:\n  expected {expected!r}\n"
                    f"  got      {got!r}")
            b.close_stdin()
            b.wait()
    finally:
        server.close()
    print(f"  {len(notifications)} unsolicited lines relayed with stdin idle")


# --------------------------------------------------------------------------
# 3. both directions at the same time
# --------------------------------------------------------------------------

def test_both_directions_live_simultaneously():
    """Overlapping traffic in both directions must not starve.

    The server pushes 400 lines without reading anything; the client writes 50
    requests and expects 50 replies. The server only starts replying after it
    has finished pushing, so a relay that cannot read and write concurrently
    wedges: it is blocked writing to stdout while the game is blocked writing
    to the socket, and neither side ever reads again.

    ONE thread reads stdout, because stdout is a single FIFO - two readers
    split the stream between them at whatever boundary the OS picks, and the
    assertions would then be testing that race instead of the relay. Replies
    are told apart from pushes by their JSON-RPC shape, which is exactly what
    the relay is supposed to be preserving.
    """
    n_push, n_req = 400, 50
    pushed = {json.dumps({"jsonrpc": "2.0", "method": "notifications/progress",
                          "params": {"p": i}}).encode() for i in range(n_push)}

    def on_connect(conn):
        for line in sorted(pushed, key=lambda x: json.loads(x)["params"]["p"]):
            conn.sendall(line + b"\n")

    def on_line(line):
        return json.dumps({"jsonrpc": "2.0", "id": json.loads(line)["id"],
                           "result": "ok"}).encode() + b"\n"

    server = FakeMcpServer(on_line=on_line, on_connect=on_connect)
    seen = []
    try:
        with Bridge(server.port) as b:
            def reader():
                while len(seen) < n_push + n_req:
                    seen.append(b.read_line())

            t = threading.Thread(target=reader, daemon=True)
            t.start()
            for i in range(n_req):
                b.send_line({"jsonrpc": "2.0", "id": i, "method": "ping"})
            t.join(timeout=TIMEOUT)
            assert not t.is_alive(), (
                f"only {len(seen)}/{n_push + n_req} lines came back within "
                f"{TIMEOUT}s: a direction is starved")
            b.close_stdin()
            b.wait()
    finally:
        server.close()

    replies = [json.loads(line) for line in seen if b'"id"' in line]
    pushes = [line for line in seen if b'"id"' not in line]
    assert len(replies) == n_req, f"{len(replies)}/{n_req} replies came back"
    assert len(pushes) == n_push, f"{len(pushes)}/{n_push} pushes came back"
    assert set(pushes) == pushed, "a pushed line was altered"
    ids = [r["id"] for r in replies]
    assert ids == list(range(n_req)), f"replies out of order/lost: {ids}"
    print(f"  {n_push} server->client and {n_req} client->server lines, "
          f"both complete, ids 0..{n_req - 1} in order")



# --------------------------------------------------------------------------
# 4. dead port fails fast and says so
# --------------------------------------------------------------------------


def test_refuses_when_nothing_listening():
    """A closed port must exit immediately with a named diagnostic.

    Three assertions, because they are three different requirements: it exits
    (so the client learns the server is gone); its stderr names the target (so
    a human can tell "the game is not running" from "the relay is broken"); and
    it exits *without waiting for --connect-timeout*. The last is the real
    anti-hang proof, so the timeout is deliberately 30 s: a relay that retried
    or blocked instead of failing on the first refusal would take 30 s and fail.

    The wall-clock bound is measured against a refusal baseline, not against a
    constant. On this machine `socket.create_connection` to a closed loopback
    port takes ~2.0 s before Windows reports ECONNREFUSED, which has nothing to
    do with the relay; an assertion written against a fixed 1 s budget would
    have blamed the relay for the OS. What must be true is that the relay adds
    no waiting of its own on top of the refusal the OS already took.
    """
    port = _closed_port()
    baseline = time.monotonic()
    try:
        socket.create_connection(("127.0.0.1", port), timeout=30)
        raise AssertionError(f"port {port} unexpectedly accepted a connection")
    except OSError:
        refusal = time.monotonic() - baseline

    started = time.monotonic()
    with Bridge(port, extra=("--connect-timeout", "30")) as b:
        b.send_line({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        code = b.wait(timeout=TIMEOUT)
        elapsed = time.monotonic() - started
        stderr = b.proc.stderr.read().decode(errors="replace")
        stdout = b.drain_stdout()

    assert code == 3, f"expected exit 3 (cannot connect), got {code}"
    assert elapsed < refusal + 2.0, (
        f"took {elapsed:.2f}s against a {refusal:.2f}s OS refusal baseline: "
        f"the relay is waiting on something instead of failing at once")
    assert f"127.0.0.1:{port}" in stderr, f"stderr does not name the target: {stderr}"
    assert "cannot connect" in stderr, f"stderr does not say what failed: {stderr}"
    assert stdout == b"", f"a failed connect wrote {stdout!r} to the protocol channel"
    print(f"  exit {code} after {elapsed:.3f}s (OS refusal baseline "
          f"{refusal:.3f}s, --connect-timeout 30s); stdout empty")
    for line in stderr.strip().splitlines():
        print(f"  stderr| {line}")

# --------------------------------------------------------------------------
# 5. a 4 KB+ single line survives in both directions
# --------------------------------------------------------------------------

def test_large_line_is_not_truncated():
    """A single line of 64 KB must arrive whole, in both directions.

    The size is far past any socket read buffer, so it cannot accidentally fit
    in one `recv`. A relay that forwards whatever one read returned loses the
    tail silently - the request looks delivered and the reply is garbage.
    """
    request_obj = {"jsonrpc": "2.0", "id": 7, "method": "tools/list",
                   "params": {"cursor": None}}
    # Non-canonical on purpose: a normalising layer would re-space this.
    request = (b'{"jsonrpc": "2.0",  "id": 7 ,   "method": "tools/list",'
               b' "params": {"cursor": null}, "filler": "'
               + b"x" * 65536 + b'"}')
    reply_obj = {"jsonrpc": "2.0", "id": 7, "result": {"tools": []}}
    reply = (b'{"jsonrpc": "2.0", "id": 7, "result": {"tools": [], "blob": "'
             + b"y" * 131072 + b'"}}')

    assert request_obj["method"] == "tools/list" and reply_obj["id"] == 7
    server = FakeMcpServer(on_line=lambda line: reply + b"\n")
    try:
        with Bridge(server.port) as b:
            b.send(request + b"\n")
            got = b.read_line()
            assert got == reply, (
                f"reply truncated or altered: expected {len(reply)} bytes, "
                f"got {len(got)}")
            b.close_stdin()
            b.wait()
        assert server.received == [request], (
            f"request truncated or altered: expected {len(request)} bytes, "
            f"got {[len(r) for r in server.received]}")
    finally:
        server.close()
    print(f"  request {len(request)} bytes and reply {len(reply)} bytes, "
          f"both byte-identical")


# --------------------------------------------------------------------------
# 6. the protocol channel stays clean
# --------------------------------------------------------------------------

def test_stdout_carries_only_protocol_bytes():
    """Not one diagnostic byte may reach stdout - while working AND while dying.

    Diagnostics go to stderr because omp parses stdout as JSON-RPC; a single
    log line there is a corrupt frame, and it is the kind of corruption that
    shows up as an intermittent parse error on the client side rather than as
    an obvious relay bug. Both states matter: a relay that stays quiet while
    relaying can still print freely once it is on its way out, where a stray
    write to a half-dead client is easy to add.
    """
    req = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize"}).encode()
    rep = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {}}).encode()
    server = FakeMcpServer(on_line=lambda line: rep + b"\n")
    try:
        with Bridge(server.port, extra=("--verbose",)) as b:
            b.send(req + b"\n")
            got = b.read_line()
            b.close_stdin()
            b.wait()
            stderr = b.proc.stderr.read().decode(errors="replace")
            # read_line already pulled this out of the shared buffer, so the
            # purity check must account for what was consumed too.
            stdout = got + b"\n" + b.drain_stdout()
        assert got == rep, f"the live verbose session corrupted a reply: {got!r}"
        assert stdout == rep + b"\n", (
            f"verbose mode wrote {len(stdout)} bytes to stdout for a "
            f"{len(rep) + 1}-byte protocol exchange")
    finally:
        server.close()
    assert "[mcp-stdio-tcp-bridge]" in stderr, "diagnostics lost"
    live_diag = len(stderr.splitlines())

    port = _closed_port()
    with Bridge(port, extra=("--verbose",)) as b:
        code = b.wait(timeout=TIMEOUT)
        dead_stderr = b.proc.stderr.read().decode(errors="replace")
        dead_stdout = b.drain_stdout()
    assert code == 3
    assert dead_stdout == b"", f"a failing relay polluted stdout: {dead_stdout!r}"
    assert "cannot connect" in dead_stderr, "failure diagnostic missing"
    print(f"  live session: stdout {len(stdout)} bytes == the protocol exchange "
          f"exactly, {live_diag} diagnostic lines on stderr")
    print(f"  failing run: stdout {len(dead_stdout)} bytes, "
          f"{len(dead_stderr.splitlines())} diagnostic lines on stderr")


TESTS = [
    test_request_response_roundtrip,
    test_server_push_reaches_stdout,
    test_both_directions_live_simultaneously,
    test_refuses_when_nothing_listening,
    test_large_line_is_not_truncated,
    test_stdout_carries_only_protocol_bytes,
]


def main() -> int:
    failures = 0
    for test in TESTS:
        print(f"{test.__name__}")
        try:
            test()
        except Exception as exc:
            failures += 1
            print(f"  FAIL {type(exc).__name__}: {exc}")
    print()
    print(f"{len(TESTS) - failures}/{len(TESTS)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())