#!/usr/bin/env python3
"""Live protocol surface check: what an agent is actually offered, and whether it can be lied to.

**Why this exists, and why it is in `scripts/` rather than in a scratch directory.** The audit
that drove the 2026-09-30/10-01 sessions rests on a claim that "four tools were verified on a
live client". The script that produced that evidence lived in `_scratch/`, which is gitignored,
and it is gone. So the claim became folklore: the next person could not re-run it, and nobody
could tell whether the surface had drifted since. A check that cannot be re-run is not evidence,
it is a memory.

**What it does NOT do.** It does not start the client, and it does not need a world. Everything
here is a property of the tool surface itself, so it runs against a client sitting at the main
menu. That is deliberate: the checks that need a world are the ones that get skipped, and the
checks that never need one are the ones that should always have run.

**The failure shapes it hunts** are all the same shape -- a surface that tells the agent
something false, in a way that reads as helpful:

- a description naming a tool that does not exist (an agent cannot follow it, and it survives
  review because it reads as assistance);
- a description naming a FIELD that does not exist, so following the instruction has no way to
  confirm anything;
- a read that answers "nothing happened" when the honest answer is "I could not have seen".

Each of those was found on a live client in this project, and none of them was caught by the
test suite at the time.

**Usage.**

    scripts\\run-mcp.bat                # in another window
    python scripts/verify-protocol.py --port 25599

Exit 0 when every assertion held, 1 otherwise, 2 when nothing is listening.

Transport note: it does not import mcp_probe.Mcp, because that class hardcodes `tools/call` and
this script needs `tools/list` as well. The framing rules are copied rather than shared on
purpose -- one connection per call, and read until the id line is COMPLETE -- because they are
the two things that broke the earlier probes, and a probe that gets the framing wrong reports
transport errors as product failures.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys

EXIT_PASS, EXIT_FAIL, EXIT_NOTHING_LISTENING = 0, 1, 2
RECV_SIZE = 65536

# Tools the audit named as delivered. Each is a claim someone will repeat, so each is checked
# against the live `tools/list` rather than against a document.
EXPECTED_TOOLS = {
    "chat_read", "do_enchant_item", "inspect_block", "transfer_item", "server_info",
    "open_overlay", "open_pause_menu", "press_key_binding",
}

# Names the surface must never mention, because nothing in it is called that. Each of these has
# been shipped at some point in this project's history.
PHANTOM_NAMES = ("netty-tap", "read_inventory", "heldSlot")


class Rpc:
    """One JSON-RPC exchange per connection, fresh socket each time."""

    def __init__(self, port: int, timeout: int = 30) -> None:
        self.port = port
        self.timeout = timeout

    def request(self, method: str, params: dict | None = None) -> dict:
        sock = socket.create_connection(("127.0.0.1", self.port), 5)
        sock.settimeout(self.timeout)
        try:
            for msg in (
                {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                 "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                            "clientInfo": {"name": "verify-protocol", "version": "1"}}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": method, "params": params or {}},
            ):
                sock.sendall((json.dumps(msg) + "\n").encode())
            buf = b""
            while b'"id":2' not in buf or not buf.rstrip().endswith(b"}"):
                chunk = sock.recv(RECV_SIZE)
                if not chunk:
                    raise OSError("server closed before the reply completed")
                buf += chunk
        finally:
            sock.close()

        for line in buf.decode("utf-8", "replace").splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            msg = json.loads(line)
            if msg.get("id") == 2:
                if "error" in msg:
                    raise RuntimeError(f"{method} -> {msg['error']}")
                return msg.get("result", {})
        raise RuntimeError(f"no id=2 reply in {len(buf)} bytes for {method}")

    def call(self, tool: str, args: dict | None = None) -> dict:
        result = self.request("tools/call", {"name": tool, "arguments": args or {}})
        content = result.get("content") or []
        text = next((c.get("text", "") for c in content if c.get("type") == "text"), "")
        return {"isError": bool(result.get("isError")), "text": text}


def main() -> int:
    ap = argparse.ArgumentParser(description="Live MCP surface check.")
    ap.add_argument("--port", type=int, default=25599)
    args = ap.parse_args()

    try:
        rpc = Rpc(args.port)
        init = rpc.request("initialize")
    except OSError as e:
        print(f"NOTHING LISTENING on 127.0.0.1:{args.port}: {e}\n"
              f"Start the client first:  scripts\\run-mcp.bat")
        return EXIT_NOTHING_LISTENING

    server = init.get("serverInfo", {})
    print(f"server  {server.get('name')} {server.get('version')}")

    tools = rpc.request("tools/list").get("tools", [])
    by_name = {t["name"]: t for t in tools}
    budget = sum(len(json.dumps(t.get("description", ""))) + len(json.dumps(t.get("inputSchema", {})))
                 for t in tools)
    print(f"surface {len(tools)} tools, {budget} chars of description+schema "
          f"(~{budget // 4} tokens)\n")

    failures: list[str] = []

    # 1. the delivered tools are actually there. This is the assertion that would have caught the
    #    2026-09-30 "Tool not found", where every test was green and the jar was stale.
    missing = sorted(EXPECTED_TOOLS - set(by_name))
    if missing:
        failures.append(f"tools the audit says exist are not in tools/list: {missing}")

    # 2. no description may name a tool or field that does not exist.
    for name, tool in sorted(by_name.items()):
        blob = json.dumps(tool)
        for phantom in PHANTOM_NAMES:
            if phantom in blob:
                failures.append(f"{name}: description names {phantom!r}, which is not a tool "
                                f"or a field in this surface")

    # 3. the honesty case, live. With no packet tap installed the read must refuse rather than
    #    report a silence it cannot vouch for.
    if "chat_read" in by_name:
        chat = rpc.call("chat_read")
        if not chat["isError"]:
            failures.append("chat_read answered without a packet tap installed; an agent would "
                            "read that as an authoritative 'nobody spoke'")
        elif "NOT an authoritative" not in chat["text"]:
            failures.append(f"chat_read refused but did not say why: {chat['text'][:160]!r}")

    # 4. act_set's channels are DECLARED, not described in prose. Every channel used to be
    #    {"type":"object"} with zero properties, which is how a move that reported ACTIVE while
    #    the player never moved got through: nothing was declared, so nothing was checked, and
    #    prose inside a description is invisible to a validator.
    act = by_name.get("act_set")
    if act:
        props = (act.get("inputSchema") or {}).get("properties") or {}
        bare = sorted(k for k, v in props.items()
                      if isinstance(v, dict) and v.get("type") == "object"
                      and not (v.get("properties") or v.get("oneOf")))
        if bare:
            failures.append(f"act_set declares these channels as bare objects with no properties: "
                            f"{bare}. A schema that declares nothing checks nothing.")
        else:
            print("act_set channels declared: " + ", ".join(sorted(props)))

    # 5. cost concentration, reported not asserted: the top five are where a context-budget
    #    review should start.
    ranked = sorted(
        ((len(json.dumps(t.get("description", ""))) + len(json.dumps(t.get("inputSchema", {}))), n)
         for n, t in by_name.items()),
        reverse=True,
    )
    print("largest five (chars of description+schema):")
    for size, name in ranked[:5]:
        print(f"  {size:6d}  {name}")

    if failures:
        print(f"\nFAIL {len(failures)} problem(s):")
        for f in failures:
            print(f"  - {f}")
        return EXIT_FAIL

    print("\nOK  every promised tool is registered, no description names something that does not "
          "exist, and an unreadable channel says so.")
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())