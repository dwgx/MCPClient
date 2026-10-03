#!/usr/bin/env python3
"""stdio <-> TCP relay for the game's MCP server, for omp's stdio transport.

**The gap this fills.** The kernel's MCP server listens on a bare TCP socket
(`SocketTransportServer.DEFAULT_PORT = 25599`, newline-delimited JSON-RPC, and
`McpCore` says why: "not stdio - the game owns the console"). omp's `mcpServers`
schema has exactly three transports - stdio, http, sse - and **no raw TCP
socket**. So this process is the missing wire: it is spawned by omp as a stdio
server, and it relays one line at a time to 127.0.0.1:25599.

**It is deliberately not a protocol implementation.** Both sides of this hop
already speak newline-delimited JSON-RPC (the kernel reuses the SDK's
`StdioServerTransportProvider` codec; omp's stdio transport wants the same
thing). So the relay moves bytes and nothing else: no framing change, no
re-encoding, no request ids of its own, no queue. That is what keeps it from
drifting when either side's protocol changes.

**Two threads, one direction each.** A single thread cannot pump two blocking
streams: if it sits in `read(stdin)` while the game pushes output, the game
blocks on a full socket buffer, and the client waits for output that the relay
is not reading - everyone waits, forever. Each direction therefore gets its own
thread and its own exclusive endpoint (stdin->socket owns `send`, socket->stdout
owns `recv`), so neither can starve the other. See `pump()` and the note there.

**Diagnostics never touch stdout.** stdout IS the protocol channel to omp; one
log line there is a corrupt JSON-RPC message. Everything diagnostic goes to
stderr, and on the happy path there is nothing to say at all unless --verbose.

Usage (this is exactly the shape omp's mcp.json `stdio` transport wants):

    {"mcpServers": {"mcpclient": {
      "command": "python",
      "args": ["D:/Project/MCPClient/scripts/mcp_stdio_tcp_bridge.py",
               "--host", "127.0.0.1", "--port", "25599"]}}}

Exit codes: 0 clean, 2 usage, 3 cannot connect, 4 I/O error mid-stream.
"""

from __future__ import annotations

import argparse
import os
import socket
import sys
import threading

#: The kernel's socket transport default (SocketTransportServer.DEFAULT_PORT).
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25599

#: Refuse to sit in an unanswerable connect(). A refused local port returns
#: instantly; this only bounds the case where something is silently swallowing
#: SYNs, where "hang with no diagnosis" is the failure mode we are avoiding.
DEFAULT_CONNECT_TIMEOUT = 5.0

#: How long to keep draining socket->stdout after stdin reaches EOF, so a reply
#: already in flight is not thrown away. Bounded on purpose: if the game never
#: closes, the relay still exits instead of lingering as an orphan.
DEFAULT_DRAIN_TIMEOUT = 5.0

#: Read granularity. Only a throughput knob; lines are accumulated until their
#: own newline, so this is NOT a line-length limit (see `pump`).
CHUNK = 65536

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_CONNECT = 3
EXIT_IO = 4


def _diag(msg: str) -> None:
    """One diagnostic line, on stderr only.

    stdout is the JSON-RPC channel. omp parses every byte of it. A "helpful"
    log line written there is a protocol error, so there is no code path in this
    file that prints anything but relayed bytes to stdout.
    """
    sys.stderr.write(f"[mcp-stdio-tcp-bridge] {msg}\n")
    sys.stderr.flush()


def pump(read_chunk, write_chunk, done: threading.Event, label: str,
         verbose: bool = False) -> None:
    """Move whole lines from one blocking byte source to the other.

    `read_chunk()` returns b"" at end of stream. `write_chunk(bytes)` takes one
    line **without** its newline and appends the newline itself, so the bytes on
    the wire are byte-identical to the bytes that arrived - including a trailing
    \\r of a CRLF client, trailing whitespace, and non-ASCII UTF-8, none of
    which are ever decoded.

    No line-length limit: the buffer grows until the line's own newline shows
    up, because a `tools/list` reply on a real game session is far larger than
    one read and truncation here would be a silent protocol failure. The buffer
    is a bytearray that is drained from the front, so a large line costs one
    pass, not one copy per chunk.

    `done` is set when this direction ends, for any reason.
    """
    buf = bytearray()
    lines = 0
    try:
        while True:
            chunk = read_chunk()
            if not chunk:
                break
            buf += chunk
            start = 0
            while True:
                nl = buf.find(b"\n", start)
                if nl < 0:
                    break
                write_chunk(bytes(buf[start:nl]))
                start = nl + 1
                lines += 1
            if start:
                del buf[:start]
            if verbose and lines:
                _diag(f"{label}: {lines} line(s) so far")
                lines = 0
        if buf:
            # Final line without a trailing newline: still forwarded, still
            # undecoded. Dropping it would be a silent loss of one message.
            write_chunk(bytes(buf))
            lines += 1
        if verbose:
            _diag(f"{label}: end of stream, {lines} line(s) total")
    except OSError as exc:
        _diag(f"{label}: stream failed: {exc}")
        done.set()
        raise
    finally:
        done.set()


def _stdin_reader():
    """Raw bytes from stdin, never decoded.

    `read1` returns as soon as anything is available. A bare `read()` would
    block until EOF - which for a pipe that stays open for the whole session is
    forever, and would deadlock the very first message.
    """
    buf = sys.stdin.buffer
    if buf is None:  # stdin replaced by something without a binary layer
        return lambda: os.read(0, CHUNK)
    return lambda: buf.read1(CHUNK)


def _stdout_writer():
    out = sys.stdout.buffer
    return out.write, out.flush


def parse_args(argv):
    ap = argparse.ArgumentParser(
        prog="mcp_stdio_tcp_bridge.py",
        description="Relay newline-delimited JSON-RPC between stdio (omp) and "
                    "a TCP port (the game's MCP server).")
    ap.add_argument("--host", default=DEFAULT_HOST,
                    help=f"upstream host (default {DEFAULT_HOST})")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT,
                    help=f"upstream port (default {DEFAULT_PORT})")
    ap.add_argument("--connect-timeout", type=float,
                    default=DEFAULT_CONNECT_TIMEOUT,
                    help="seconds to wait for the upstream port "
                         f"(default {DEFAULT_CONNECT_TIMEOUT})")
    ap.add_argument("--drain-timeout", type=float, default=DEFAULT_DRAIN_TIMEOUT,
                    help="seconds to keep draining upstream output after stdin "
                         f"closes (default {DEFAULT_DRAIN_TIMEOUT})")
    ap.add_argument("--verbose", "-v", action="store_true",
                    help="log every line crossing the relay to stderr")
    return ap.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    target = f"{args.host}:{args.port}"

    try:
        sock = socket.create_connection((args.host, args.port),
                                        timeout=args.connect_timeout)
    except OSError as exc:
        # The whole point: nothing listening must be an immediate, named
        # failure, not a process that sits there forever looking healthy.
        _diag(f"cannot connect to {target}: {exc}")
        _diag(f"is the game running? (scripts/run-mcp.bat starts it); "
              f"nothing is listening on {target}")
        return EXIT_CONNECT
    # Blocking reads from here on: the connect timeout must not turn into a
    # per-read timeout that would cut a session off mid-message.
    sock.settimeout(None)

    if args.verbose:
        _diag(f"connected to {target}")

    sock_done = threading.Event()   # upstream direction ended
    stdin_done = threading.Event()  # client direction ended
    exit_now = threading.Event()    # main thread's cue
    failed = threading.Event()      # either direction broke (not a clean EOF)

    def to_socket(line: bytes) -> None:
        sock.sendall(line + b"\n")

    def from_socket() -> bytes:
        # A bufsize is mandatory: `sock.recv` bound bare raises TypeError the
        # moment the reader thread calls it, and a thread that dies on its
        # first statement takes the whole direction down silently.
        return sock.recv(CHUNK)

    write, flush = _stdout_writer()

    def to_stdout(line: bytes) -> None:
        write(line + b"\n")
        flush()

    def socket_thread() -> None:
        try:
            pump(from_socket, to_stdout, sock_done, "socket->stdout",
                 verbose=args.verbose)
            _diag(f"upstream {target} closed the connection")
        except OSError:
            _diag(f"upstream {target} is gone; relaying stopped")
            failed.set()
        except Exception as exc:
            # A worker thread that dies on an unexpected error would otherwise
            # take its direction down silently, and the client would see a
            # healthy-looking relay that has stopped relaying. Name it.
            _diag(f"socket->stdout relay failed: {type(exc).__name__}: {exc}")
            failed.set()
        finally:
            exit_now.set()

    def stdin_thread() -> None:
        try:
            pump(_stdin_reader(), to_socket, stdin_done, "stdin->socket",
                 verbose=args.verbose)
        except OSError as exc:
            _diag(f"stdin stream failed: {exc}")
            failed.set()
        except Exception as exc:
            _diag(f"stdin->socket relay failed: {type(exc).__name__}: {exc}")
            failed.set()
        finally:
            # Half-close: we will write no more, but must still be able to read
            # whatever the game already had in flight.
            try:
                sock.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            # Bounded drain, then exit anyway. A game that never closes must
            # not leave an orphan relay behind.
            if not sock_done.wait(args.drain_timeout):
                _diag(f"upstream still open {args.drain_timeout}s after stdin "
                      f"closed; exiting without draining")
            exit_now.set()

    t_sock = threading.Thread(target=socket_thread, name="socket->stdout",
                              daemon=True)
    t_stdin = threading.Thread(target=stdin_thread, name="stdin->socket",
                               daemon=True)
    t_sock.start()
    t_stdin.start()

    exit_now.wait()
    # Read the outcome BEFORE closing the socket: closing it under the still-
    # running reader would manufacture the very OSError we are reporting on.
    broken = failed.is_set()
    try:
        sock.close()
    except OSError:
        pass

    # A clean upstream EOF is an ordinary end of session (the game quit).
    # An OSError on either stream is a broken pipe, and is reported as one:
    # a transport fault must not look like a normal shutdown.
    return EXIT_IO if broken else EXIT_OK


if __name__ == "__main__":
    sys.exit(main())