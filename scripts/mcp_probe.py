#!/usr/bin/env python3
"""The parts every live probe needs: an MCP socket client, the game-thread eval wrapper, the
ticking guard, and the record/report harness.

Not a script -- import it. The underscore in the name is deliberate: the probes are hyphenated
(nav-astar-probe.py) and therefore only loadable by path, while this one has to be importable, so
it is the one file here spelled as a module.

WHY IT EXISTS. A copy is where a lesson goes to die. The id=2 read loop existed twice --
nav-astar-probe.py fixed the truncation and live-dwm-probe.py carried the identical defect for
another whole session, because the fix lived in a copy rather than in something shared. Measured
there: world_view at radius 16 is 180149 bytes over 4 recv calls, and the naive loop returned
"unparseable reply: Unterminated string". scripts/test_probe_framing.py pins that contract, and
after this module it pins it in ONE place.

The count is not symmetric, which is the other half of the argument. live-dwm-probe.py had four of
these (client, reply reader, eval wrapper, record/report) and NEVER had the ticking guard or the
pauseOnLostFocus hatch: those were learned on nav-astar-probe.py, after a frozen world produced
failures that said nothing about the code. So sharing is not only about the copies that drifted --
it is about the next probe inheriting the guard instead of rediscovering it the expensive way.

Where the copies disagreed, the most careful version won and the note says which probe it came from
and what the weaker one got wrong. Those differences are the accumulated lessons; a silent merge
would have erased them.
"""

import json
import socket
import time

# Exit codes, from smoke-live-gl.sh. All three probes already returned these numbers literally;
# named here so the convention has one home rather than three sets of magic returns.
EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_TIMEOUT = 2      # nothing listening on the MCP port within the deadline
EXIT_SETUP = 3        # the port answers but the world/client is not in a state that can be probed

RECV_SIZE = 65536     # the read chunk; chunk boundaries are what the old framing tripped over

# Shared because a probe process runs exactly one probe: nav and dwm each had their own list and
# their own tally, which is the same list twice.
results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"\n          {detail}" if detail else ""))
    return ok


def report():
    """Print the tally and return the process exit code.

    From live-dwm-probe.py, which reprinted the FAILED names at the end; nav-astar-probe.py and
    live-hold-probe.py printed only "n/m checks" and left the reader scrolling a few hundred lines
    of probe output to find which one broke. Same exit codes either way, so taking the louder
    version costs nothing.
    """
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n{'PASS' if passed == total else 'FAIL'}: {passed}/{total} checks")
    if passed != total:
        print("failed:")
        for name, ok, detail in results:
            if not ok:
                print(f"  - {name}: {detail.splitlines()[0] if detail else ''}")
        return EXIT_FAIL
    return EXIT_PASS


class Mcp:
    """One JSON-RPC call per connection.

    Deliberately not a persistent session: the kernel's socket transport expects a fresh
    initialize handshake, and a probe that reconnects per call cannot leave a half-read stream
    behind to confuse the next assertion.
    """

    def __init__(self, port, timeout=30, client_name="mcp-probe"):
        """timeout is nav-astar-probe.py's 30s, not live-dwm-probe.py's 25s.

        Nothing was measured to distinguish them, so the longer one wins by the only argument
        available: the timeout bounds how long a slow or large reply may take to finish arriving,
        and cutting a reply short is the failure mode this module exists to prevent. client_name
        only reaches the handshake's clientInfo, which no kernel code reads -- it is there so a
        server-side log still says which probe called.
        """
        self.port = port
        self.timeout = timeout
        self.client_name = client_name

    def call(self, tool, args):
        try:
            sock = socket.create_connection(("127.0.0.1", self.port), 5)
        except OSError as e:
            return {"error": f"connect failed: {e}"}
        sock.settimeout(self.timeout)
        try:
            for msg in (
                {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                 "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                            "clientInfo": {"name": self.client_name, "version": "1"}}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                 "params": {"name": tool, "arguments": args}},
            ):
                sock.sendall((json.dumps(msg) + "\n").encode())
            # Read until the id=2 line is COMPLETE, not merely present. Breaking the moment
            # '"id":2' appears anywhere in the buffer truncates any reply larger than one recv --
            # measured, world_view at radius 16 is 180149 bytes over 4 chunks, and the naive
            # version returned "unparseable reply: Unterminated string".
            buf = b""
            deadline = time.time() + self.timeout
            while time.time() < deadline:
                try:
                    chunk = sock.recv(RECV_SIZE)
                except socket.timeout:
                    break
                if not chunk:
                    break
                buf += chunk
                if self._complete_reply(buf) is not None:
                    break
        finally:
            sock.close()

        line = self._complete_reply(buf)
        if line is None:
            return {"error": f"no complete reply in {len(buf)} bytes"}
        try:
            reply = json.loads(line)
        except ValueError as e:
            return {"error": f"unparseable reply: {e}"}
        content = reply.get("result", {}).get("content", [])
        text = content[0].get("text", "") if content else ""
        return {"text": text, "isError": reply.get("result", {}).get("isError", False)}

    @staticmethod
    def _complete_reply(buf):
        """The id=2 line, but only once it parses as whole JSON. None while still arriving.

        Presence of the marker is not the same as arrival of the message: a large reply spans
        several recv calls, so this is what makes the read loop wait for the rest instead of
        truncating mid-string.
        """
        for line in buf.split(b"\n"):
            if b'"id":2' not in line:
                continue
            try:
                json.loads(line)
            except ValueError:
                return None
            return line
        return None

    def java(self, class_name, body):
        """Run a snippet on the GAME thread and return its text.

        Marshalling is not optional: eval_java runs on a worker thread, and the things probes
        touch -- live chunk state, the screen, GL -- are game-thread property. Reading them off
        the game thread is a race at best. Applied here once rather than in each snippet.
        """
        source = (
            "package gen;\n"
            f"public class {class_name} {{\n"
            "  public Object run() throws Exception {\n"
            "    return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {\n"
            "      try {\n"
            f"{body}\n"
            "      } catch (Throwable t) {\n"
            "        java.io.StringWriter w = new java.io.StringWriter();\n"
            "        t.printStackTrace(new java.io.PrintWriter(w));\n"
            "        return \"THREW \" + w;\n"
            "      }\n"
            "    });\n"
            "  }\n"
            "}\n"
        )
        reply = self.call("eval_java", {"className": f"gen.{class_name}", "source": source})
        if "error" in reply:
            return "PROBE-ERROR " + reply["error"]
        return reply.get("text", "")


# The opening lines of almost every snippet: the client, the player, the world, and the block the
# player is standing on. A snippet that needs none of them can be written without it.
PREAMBLE = """
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return "NOT-IN-WORLD";
        net.minecraft.client.entity.EntityPlayerSP p = mc.thePlayer;
        net.minecraft.client.multiplayer.WorldClient w = mc.theWorld;
        net.minecraft.util.BlockPos from = new net.minecraft.util.BlockPos(
            p.posX, p.getEntityBoundingBox().minY, p.posZ);
"""


def probe_in_world(mcp):
    """In a world AND ALIVE. The second half was missing and it cost a confusing round.

    A dead player still satisfies `thePlayer != null`, so this returned PASS with the death screen
    up -- and then every probe below failed for a reason that had nothing to do with the code. Seen
    for real: the LOOK track never terminated, the hold could not eat ("using=true keyDown=false"),
    the bow fired nothing, and nav scored 3/11. Three probes, eight failures, one cause, and none of
    the messages pointed at it. That is exactly the false-FAIL this module's guards exist to prevent,
    and it is worse than most because it looks like a regression in whatever was just changed.

    Reported as its own line rather than folded into the world check, so the answer to "why did
    everything break" is on screen instead of inferred. The caller treats a false return as SETUP,
    the same as not being in a world at all -- because that is what it is: the player has to be
    respawned before anything below measures anything.
    """
    out = mcp.java("NavWhere", PREAMBLE + """
        return "AT " + from + " onGround=" + p.onGround + " dim=" + w.provider.getDimensionId()
             + " hp=" + p.getHealth()
             + " screen=" + (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName());
    """)
    if not record("the player is in a world", out.startswith("AT "), out.strip()[:200]):
        return False
    # Parsed rather than pattern-matched on "hp=0.0": a float formats differently across paths, and
    # the question is "is this player alive", which is a number comparison.
    alive = True
    try:
        alive = float(out.split("hp=", 1)[1].split(" ", 1)[0]) > 0.0
    except (IndexError, ValueError):
        pass
    return record("and ALIVE (a dead player satisfies every other precondition and fails "
                  "everything below for reasons that are not about the code)", alive,
                  "" if alive else "SETUP-DEAD: respawn first -- mc.thePlayer.respawnPlayer(), then "
                                   "clear the screen. " + out.strip()[:160])


def is_ticking(mcp):
    """Whether the world is actually advancing, and why not if it is not.

    Vanilla single-player stops advancing on focus loss, and the game thread keeps servicing
    eval_java throughout -- so every tick-dependent check reads a frozen world and fails for a
    reason that has nothing to do with the code under test. That is exactly what happened before
    this guard existed.

    The reopening screen comes from EntityRenderer.updateCameraAndRender:1071-1076 (500ms
    unfocused -> displayInGameMenu), gated on gameSettings.pauseOnLostFocus. Minecraft.java:1184
    only *reads* that screen to set isGamePaused. Clearing currentScreen alone does not help,
    because the gate reopens it every frame -- clear the gate instead, via allow_unfocused().
    """
    out = mcp.java("Ticking", PREAMBLE + """
        return "paused=" + mc.isGamePaused() + " active=" + org.lwjgl.opengl.Display.isActive()
             + " t=" + mc.theWorld.getTotalWorldTime();
    """)
    if "paused=false" in out:
        return True, out.strip()
    return False, out.strip()


def allow_unfocused(mcp):
    """Stop the world freezing while the window is in the background, and report the state.

    pauseOnLostFocus is a public GameSettings field that vanilla itself toggles with F3+P, so
    this is a supported state rather than a hack. Preferred over the shareToLAN workaround an
    earlier session used: that one also defeats the pause, but it moves the player onto a
    different server path mid-run and was itself a source of bogus stalls.

    Static reasoning only when written -- verify the returned state rather than assuming it took.
    """
    return mcp.java("AllowUnfocused", PREAMBLE + """
        mc.gameSettings.pauseOnLostFocus = false;
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            mc.displayGuiScreen(null);
        }
        return "pauseOnLostFocus=" + mc.gameSettings.pauseOnLostFocus
             + " screen=" + (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getName())
             + " paused=" + mc.isGamePaused();
    """).strip()


def require_ticking(mcp, what):
    """Skip rather than fail when the world is frozen. A false FAIL is worse than a skip."""
    ok, detail = is_ticking(mcp)
    if not ok:
        record(what, False,
               "SKIPPED-NOT-MEASURED: the world is not ticking, so this proves nothing about the "
               "code. Focus the game window, or call allow_unfocused(mcp), and re-run. "
               + detail[:160])
    return ok
