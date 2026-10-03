#!/usr/bin/env python3
"""A STAND-IN for the game's MCP server, on 127.0.0.1:25599.

Speaks the same newline-delimited JSON-RPC the kernel speaks (initialize +
tools/list + tools/call), so `scripts/mcp_stdio_tcp_bridge.py` and omp cannot tell it from a
running `scripts\\run-mcp.bat`. It exists so the REGISTRATION and the ISOLATION can be measured
without Owner authorizing a Minecraft launch.

It is NOT evidence that the game works. It proves the wire: config read -> bridge spawned ->
tools registered. Only the real game proves the rest.

Usage:  python .ai-notes/scratch/fake-game-mcp.py [--port 25599]
"""
import json
import socket
import sys
import threading

TOOLS = [
    {
        "name": "night_shelter",
        "description": "STUB. Read-only: how the CURRENT night has gone, tick by tick.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "night_health",
        "description": "STUB. Read-only: the LOWEST your health bar has been this night.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "night_box",
        "description": "STUB. Read-only: whether a CHEST stood through this night.",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
]


def reply(f, obj):
    f.write((json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8"))
    f.flush()


def serve(conn):
    with conn, conn.makefile("rwb") as f:
        for raw in f:
            if not raw.strip():
                continue
            try:
                msg = json.loads(raw.decode("utf-8"))
            except ValueError:
                continue
            method = msg.get("method")
            mid = msg.get("id")
            if method == "initialize":
                reply(f, {"jsonrpc": "2.0", "id": mid, "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {"listChanged": True}},
                    "serverInfo": {"name": "mcp-core-STUB", "version": "1.8.9"},
                }})
            elif method == "tools/list":
                reply(f, {"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
            elif method == "tools/call":
                reply(f, {"jsonrpc": "2.0", "id": mid, "result": {
                    "content": [{"type": "text",
                                 "text": "STUB: this is not the real game. night_shelter/"
                                         " never measured anything."}],
                    "isError": False,
                }})
            elif mid is not None:
                reply(f, {"jsonrpc": "2.0", "id": mid, "result": {}})
            sys.stderr.write("[stub-game-mcp] " + str(method) + "\n")
            sys.stderr.flush()


def main():
    port = 25599
    if "--port" in sys.argv:
        port = int(sys.argv[sys.argv.index("--port") + 1])
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(4)
    sys.stderr.write("[stub-game-mcp] listening on 127.0.0.1:%d -- STUB, not the game\n" % port)
    sys.stderr.flush()
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
