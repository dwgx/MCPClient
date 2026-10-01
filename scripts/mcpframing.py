#!/usr/bin/env python3
"""The one correct way to read an MCP reply off a socket, shared by every probe.

**Why this module exists.** On 2026-10-02 a review proved that `verify-protocol.py` truncated any
`tools/list` reply larger than one `recv`: its loop waited for `b'"id":2'` to appear AND for the
buffer to end in `}`, and a chunk boundary landing after an inner `}` satisfies both while the line
is still half-arrived. Against a synthetic server serving a 122 KB payload, three of five chunk
sizes raised `JSONDecodeError` — and because the exception escaped `main()`, a transport fault was
reported as a product fault. `mcp_probe.py` already had the correct loop and had it fixed once
already; the copy in `verify-protocol.py` was the version that had NOT been fixed, which is the worst
of both: duplicated, and behind.

**The rule.** Presence of the marker is not arrival of the message. A reply can span several `recv`
calls, so a line counts as arrived when it PARSES, not when it looks finished. Buffer length, trailing
braces and marker position are all heuristics that break at exactly the boundary where correctness
matters most.

**Cost is one import, not one more copy.** A second hand-rolled loop is a second thing to get wrong,
and the failure is silent until a reply happens to straddle a chunk.
"""

from __future__ import annotations

import json

#: The id the kernel's socket transport answers the call on.
REPLY_ID = 2


def complete_reply(buf: bytes, reply_id: int = REPLY_ID) -> bytes | None:
    """The complete `id=<reply_id>` line, or None while it is still arriving.

    Returns the line WITH its trailing newline stripped, so callers can decode it without re-splitting.
    A line that contains the marker but does not parse is treated as still-arriving, which is the
    whole point: `{"id":2,"result":{"tools":[{"name":"a"}` is a valid prefix of a longer reply and an
    invalid JSON document, and only one of those two facts is a transport bug.
    """
    for line in buf.split(b"\n"):
        if f'"id":{reply_id}'.encode() not in line:
            continue
        try:
            json.loads(line)
        except ValueError:
            return None
        return line
    return None


def read_reply(sock, recv_size: int = 65536, reply_id: int = REPLY_ID) -> bytes:
    """Read from `sock` until the reply line parses, and return it.

    Raises OSError if the peer closes first — which is a transport fact, and must be reported as one
    rather than as whatever the caller was trying to measure.

    Each newline-terminated line is attempted AT MOST ONCE, and only once it has been terminated.
    The obvious loop — re-run the whole scan over the whole buffer after every recv — is correct and
    quadratic: at 64-byte chunks over a 30 KB reply it re-parses the same growing partial document
    hundreds of times, and the test that proves correctness then takes a minute instead of a
    millisecond. Correctness here is "a line counts when it parses", not "a line counts when it
    looks finished", so the scan position is all the state this needs.
    """
    buf = b""
    attempted = 0  # bytes of buf already tried, and not the answer
    while True:
        nl = buf.rfind(b"\n")
        if nl >= attempted:
            segment = buf[attempted:nl + 1]
            attempted = nl + 1
            line = complete_reply(segment, reply_id)
            if line is not None:
                return line.rstrip(b"\n")
        chunk = sock.recv(recv_size)
        if not chunk:
            raise OSError("server closed before the reply completed")
        buf += chunk