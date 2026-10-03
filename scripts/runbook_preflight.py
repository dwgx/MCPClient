#!/usr/bin/env python3
"""Three one-command preflight checks for the night run. Read-only; starts nothing but itself.

Why a script and not three long shell lines: the checks have to be runnable by someone who has
never read this repository, and each one has to end in a single unambiguous PASS/FAIL line.

    python scripts/runbook_preflight.py artifacts   # are the pieces on disk
    python scripts/runbook_preflight.py wire        # is the GAME up and speaking MCP on 25599
    python scripts/runbook_preflight.py registry    # does the isolated spawn see ONLY our 3 tools

**None of these asks the model what it has.** A model asked to list its tools under `--no-tools`
was MEASURED inventing ten of them (bash/read/write/edit/...) for a run that had none, so every
tool-surface question is answered from omp's own registry -- the `scripts/tooltable.ts` hook,
whose shape is copied from `SingleCallProbe.HOOK_SOURCE`.

Order matters and is not cosmetic: `wire` before `registry`. The bridge exits 3 in ~2.1s when
nothing is listening, so a `registry` run against a dead game reports `mcpActive 0` and looks
exactly like a broken registration. `wire` is what tells the two apart.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: The kernel's socket transport default (SocketTransportServer.DEFAULT_PORT).
HOST = "127.0.0.1"
PORT = 25599

#: The isolated profile ModelBridge.ensureProfile() seeds. Its OWN agent/mcp.json is where the
#: bridge is registered -- see the runbook for why that file and not the default profile's.
PROFILE = "modelbridge-iso"

#: Closes the PROJECT scope. Kept as a file rather than inlined so the exact bytes omp reads are
#: visible in the repository instead of buried in a subprocess argument list.
OVERLAY = ROOT / ".ai-notes" / "scratch" / "runbook-overlay.yml"
OVERLAY_TEXT = "mcp:\n  enableProjectConfig: false\n"

#: The three north-star rulers. A registry that shows any OTHER active tool is a failed isolation,
#: even when these three are present -- the point of the profile is that nothing else got through.
EXPECTED = {"mcp__mcpclient_night_shelter", "mcp__mcpclient_night_health", "mcp__mcpclient_night_box"}

#: Servers that must NOT be reachable. Each of these drove a real terminal or a real browser in a
#: round that was voided on 2026-10-03 (76 smartcli calls, 32 chrome_devtools calls).
FORBIDDEN_PREFIXES = ("mcp__codegraph", "mcp__smartcli", "mcp__chrome_devtools")


def _say(ok: bool, label: str, detail: str = "") -> bool:
    print(("  PASS  " if ok else "  FAIL  ") + label + ((" -- " + detail) if detail else ""))
    return ok


# --------------------------------------------------------------------------- artifacts


def check_artifacts() -> int:
    """Everything run-mcp.bat needs, on disk, before it is worth launching."""
    print("== artifacts ==")
    jars = [
        ROOT / "client" / "target" / "MCP-1.8.9.jar",
        ROOT / "core" / "target" / "core-1.8.9-all.jar",
    ]
    ok = True
    for jar in jars:
        ok &= _say(jar.is_file(), str(jar.relative_to(ROOT)),
                   "" if jar.is_file() else "missing -- scripts\\build-jars.bat")
    # run-mcp.bat passes --assetsDir assets --assetIndex 1.8 with cwd=test_run, so the index the
    # JVM looks for is test_run/assets/indexes/1.8.json -- NOT test_run/indexes/1.8.json.
    for extra in (ROOT / "test_run", ROOT / "test_run" / "assets" / "indexes" / "1.8.json",
                  ROOT / "scripts" / "mcp_stdio_tcp_bridge.py",
                  Path.home() / ".omp" / "profiles" / PROFILE / "agent" / "mcp.json"):
        present = extra.exists()
        detail = ""
        if not present and extra.name == "mcp.json":
            detail = "missing -- register the bridge there (runbook section 2)"
        ok &= _say(present, str(extra), detail)
    saves = ROOT / "test_run" / "saves"
    worlds = len([d for d in saves.iterdir() if d.is_dir()]) if saves.is_dir() else 0
    ok &= _say(worlds > 0, "test_run/saves", "%d world save(s)" % worlds)
    print("RESULT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


# ------------------------------------------------------------------------------- wire


def _rpc(sock: socket.socket, obj: dict) -> dict:
    sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8"))
    buf = b""
    sock.settimeout(10.0)
    while b"\n" not in buf:
        chunk = sock.recv(65536)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b"\n", 1)[0]
    return json.loads(line.decode("utf-8")) if line else {}


def check_wire() -> int:
    """Is something on 25599 speaking MCP, and is it the game's tool surface?

    This is the check that separates "the bridge is wired right" from "the game is running".
    A bridge that starts proves nothing: it starts happily against a dead port and only fails
    2.1s later with exit 3.
    """
    try:
        sock = socket.create_connection((HOST, PORT), timeout=5.0)
    except OSError as exc:
        print("  FAIL  nothing is listening on %s:%d -- %s" % (HOST, PORT, exc))
        print("         The game is not up. scripts\\run-mcp.bat starts it, and it needs Owner"
              " to authorize the launch.")
        print("RESULT: FAIL")
        return 1
    with sock:
        try:
            _rpc(sock, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                "protocolVersion": "2024-11-05", "capabilities": {},
                "clientInfo": {"name": "runbook-preflight", "version": "1"}}})
            answer = _rpc(sock, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        except (OSError, ValueError) as exc:
            print("  FAIL  something is listening but did not answer MCP -- %s: %s"
                  % (type(exc).__name__, exc))
            print("RESULT: FAIL")
            return 1
    tools = [t.get("name", "") for t in answer.get("result", {}).get("tools", [])]
    print("  tools/list returned %d tool(s): %s" % (len(tools), ", ".join(tools) or "(none)"))
    ok = _say(bool(tools), "the game answered tools/list")
    ok &= _say("night_shelter" in tools, "night_shelter is registered",
               "" if "night_shelter" in tools else "the kernel never wired ShelterTools")
    print("RESULT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


# --------------------------------------------------------------------------- registry

def _purge_tool_cache() -> int:
    """Delete every `mcp_tools:*` row from the isolated profile's agent.db. See check_registry.

    Bounded to that one profile and that one key prefix: this file also holds `auth_credentials`,
    so it must not be a wildcard.
    """
    db = Path.home() / ".omp" / "profiles" / PROFILE / "agent" / "agent.db"
    if not db.is_file():
        return 0
    try:
        import sqlite3
    except ImportError:
        return 0
    try:
        con = sqlite3.connect(str(db), timeout=10.0)
        try:
            cur = con.execute("DELETE FROM cache WHERE key LIKE 'mcp_tools:%'")
            con.commit()
            return cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
        finally:
            con.close()
    except sqlite3.Error as exc:
        print("  WARN  could not purge the MCP tool cache (%s) -- the verdict below may then be"
              " served from a stale cache" % exc)
        return 0



def check_registry() -> int:
    """Dump the LIVE tool registry out of an isolated spawn and judge it.

    Read-only and provider-free: the hook registers a command, writes JSON, and exits. No model
    call, no token spent.

    **The tool cache is purged first, and that is not optional.** omp caches every MCP server's
    tool list in the profile's `agent.db` under the key `mcp_tools:<server>`, for 30 days
    (`src/mcp/tool-cache.ts`: CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000), and `MCPManager` falls
    back to that cache when a connection is still pending. MEASURED on 2026-10-03: with the game
    dead, this check reported `mcpActive 3` and PASS -- serving three tools out of the cache that
    a previous run had written. A green registry against a dead game is worse than a red one,
    because it is a green one. So the row is deleted before the spawn, and the check is only
    ever as honest as that delete.

    Run `wire` first regardless: this check alone cannot tell "the game is down" from
    "the registration is wrong", and it costs one MCP round trip to tell them apart.
    """
    print("== registry (isolated spawn, --no-tools, cache purged) ==")
    purged = _purge_tool_cache()
    print("  purged %d cached mcp_tools row(s) from the profile's agent.db" % purged)
    OVERLAY.parent.mkdir(parents=True, exist_ok=True)
    if not OVERLAY.is_file() or OVERLAY.read_text(encoding="utf-8") != OVERLAY_TEXT:
        OVERLAY.write_text(OVERLAY_TEXT, encoding="utf-8")
    out = Path(tempfile.mkdtemp(prefix="runbook-tooltable-")) / "tooltable.json"
    env = dict(os.environ, TOOLTABLE_OUT=str(out))
    cmd = ["omp", "-p", "--mode", "json", "--no-session",
           "--profile", PROFILE, "--config", str(OVERLAY),
           "--no-tools",
           "--hook", str(ROOT / "scripts" / "tooltable.ts"),
           "--max-time", "150s", "/tooltable"]
    try:
        proc = subprocess.run(cmd, cwd=str(ROOT), env=env, stdin=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=180)
    except FileNotFoundError:
        print("  FAIL  omp is not on PATH")
        print("RESULT: FAIL")
        return 1
    except subprocess.TimeoutExpired:
        print("  FAIL  the registry dump did not finish in 180s")
        print("RESULT: FAIL")
        return 1
    if not out.is_file():
        err = proc.stderr.decode("utf-8", "replace").strip()[:300]
        print("  FAIL  no dump was written (omp exit %d) -- %s" % (proc.returncode, err))
        print("RESULT: FAIL")
        return 1
    table = json.loads(out.read_text(encoding="utf-8"))
    active = set(table.get("activeNames", []))
    mcp_active = [n for n in sorted(active) if n.startswith("mcp__")]
    print("  registry: %d tool(s) total, %d mcp__ registered, %d mcp__ active"
          % (table.get("total", -1), table.get("mcpTotal", -1), table.get("mcpActive", -1)))
    print("  active mcp__ tools: %s" % (", ".join(mcp_active) or "(none)"))
    ok = True
    missing = sorted(EXPECTED - set(mcp_active))
    ok &= _say(not missing, "all three north-star rulers are ACTIVE",
               "" if not missing else "missing: " + ", ".join(missing))
    leaked = [n for n in sorted(active) if n.startswith(FORBIDDEN_PREFIXES)]
    ok &= _say(not leaked, "nothing else got through the isolation",
               "" if not leaked else "LEAKED: " + ", ".join(leaked))
    builtin = [n for n in sorted(active) if not n.startswith("mcp__")]
    ok &= _say(not builtin, "--no-tools closed the built-in surface",
               "" if not builtin else "still active: " + ", ".join(builtin))
    print("RESULT: " + ("PASS" if ok else "FAIL"))
    if not ok:
        print("  If the three are missing, the game is probably not up -- run `wire` first.")
    return 0 if ok else 1


CHECKS = {"artifacts": check_artifacts, "wire": check_wire, "registry": check_registry}


def main(argv: list) -> int:
    if len(argv) != 1 or argv[0] not in CHECKS:
        print(__doc__.strip())
        print("\nusage: python scripts/runbook_preflight.py {%s}" % "|".join(CHECKS))
        return 2
    return CHECKS[argv[0]]()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
