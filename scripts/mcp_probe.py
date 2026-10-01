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

# Checks that measured NOTHING, kept out of `results` because their outcome is neither of the two
# `results` can hold: a FAIL would blame the code for a world that never moved, and a PASS would
# claim a measurement that did not happen. report() turns a non-empty list into EXIT_SETUP.
skips = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"\n          {detail}" if detail else ""))
    return ok


def record_skip(name, detail=""):
    """Record a check that measured nothing: not a pass, not a fail, EXIT_SETUP.

    The distinction is what this exists for. record() prints FAIL and report() returns EXIT_FAIL,
    so a frozen world used to end a run as a red FAIL naming code that was never exercised -- and
    a FAIL is the line a reader believes. Five probes hid that by returning EXIT_SETUP before
    report() ever ran; nav-astar-probe.py has no such guard, so the same frozen world printed
    "FAIL: n/m checks" there. Returns False, so `if not require_ticking(mcp, what)` keeps working
    for callers that want to stop at the guard.
    """
    skips.append(name)
    print("  SKIP  " + name + (f"\n          {detail}" if detail else ""))
    return False


def report():
    """Print the tally and return the process exit code.

    From live-dwm-probe.py, which reprinted the FAILED names at the end; nav-astar-probe.py and
    live-hold-probe.py printed only "n/m checks" and left the reader scrolling a few hundred lines
    of probe output to find which one broke. Same exit codes either way, so taking the louder
    version costs nothing.

    A skip outranks a pass: with nothing failed but something unmeasured, the run proved less than
    it looks like it did, and EXIT_SETUP says exactly that.
    """
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    verdict = "FAIL" if passed != total else ("SETUP" if skips else "PASS")
    print(f"\n{verdict}: {passed}/{total} checks" + (f", {len(skips)} skipped" if skips else ""))
    if passed != total:
        print("failed:")
        for name, ok, detail in results:
            if not ok:
                print(f"  - {name}: {detail.splitlines()[0] if detail else ''}")
        return EXIT_FAIL
    if skips:
        print("skipped (nothing was measured by these, so they are not a pass):")
        for name in skips:
            print(f"  - {name}")
        return EXIT_SETUP
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
        # A JSON-RPC error is NOT an empty tool result. Flattening it to {"text": "", "isError":
        # False} -- which is what this used to do, because result was simply absent -- made a
        # transport failure indistinguishable from a tool that legitimately returned nothing, and
        # every caller's `if "error" in reply` guard is blind to it. That is not theoretical: it is
        # how nav-astar-probe.py's `"use rejected in air" not in slot` check came to PASS on a
        # reply that carried no slot at all. The same rule covers a reply that is not a result at
        # all: "I could not read this" must never be handed back looking like a success.
        err = reply.get("error") if isinstance(reply, dict) else None
        if err is not None:
            if isinstance(err, dict):
                return {"error": f"jsonrpc error {err.get('code')}: {err.get('message')}"}
            return {"error": f"jsonrpc error: {err}"}
        result = reply.get("result") if isinstance(reply, dict) else None
        if not isinstance(result, dict):
            return {"error": f"reply carried neither result nor error: {line[:200]!r}"}
        content = result.get("content")
        if not isinstance(content, list):
            return {"error": "result carried no content list: "
                             f"{json.dumps(result)[:200]}"}
        # The first TEXT item, not blindly the first item: a result may carry an image (gui_snapshot
        # with includeImage) before or after its text, and content[0] on an image item is an empty
        # string that reads as "the tool said nothing".
        #
        # An EMPTY content list is not that: a tool that returned nothing is a real result, and the
        # line between "nothing to say" and "I could not read this" is the whole point of the
        # branches above and below.
        if not content:
            return {"text": "", "isError": result.get("isError", False)}
        text = next((c.get("text", "") for c in content
                     if isinstance(c, dict) and "text" in c), None)
        if text is None:
            return {"error": f"result carried no text content: {json.dumps(content)[:200]}"}
        return {"text": text, "isError": result.get("isError", False)}

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


def _world_sample(mcp):
    """One reading of (isGamePaused, totalWorldTime), and the raw line it came from.

    The raw line comes back on every path because it is the only explanation a caller has when the
    answer is no: it carries the pause flag, whether the display window is active, and the clock.
    """
    out = mcp.java("Ticking", PREAMBLE + """
        return "paused=" + mc.isGamePaused() + " active=" + org.lwjgl.opengl.Display.isActive()
             + " time=" + mc.theWorld.getTotalWorldTime();
    """).strip()
    if not out.startswith("paused="):
        return None, out
    try:
        paused = out.split("paused=", 1)[1].split(" ", 1)[0] == "true"
        # int(float(...)): the Java prints a long, but a client that ever formats it as a double
        # would otherwise turn a live world into "unreadable" and a skip.
        world_time = int(float(out.split("time=", 1)[1].split(" ", 1)[0]))
    except (IndexError, ValueError):
        return None, out
    return (paused, world_time), out


def is_ticking(mcp, settle_s=0.5, tries=3):
    """Whether the world is actually advancing, and why not if it is not.

    Vanilla single-player stops advancing on focus loss, and the game thread keeps servicing
    eval_java throughout -- so every tick-dependent check reads a frozen world and fails for a
    reason that has nothing to do with the code under test. That is exactly what happened before

    The reopening screen comes from EntityRenderer.updateCameraAndRender:1071-1076 (500ms
    unfocused -> displayInGameMenu), gated on gameSettings.pauseOnLostFocus. Minecraft.java:1184
    only *reads* that screen to set isGamePaused. Clearing currentScreen alone does not help,
    because the gate reopens it every frame -- clear the gate instead, via allow_unfocused().

    The decision is a COMPARISON of two clock samples, not a reading of the pause flag. The old
    body returned "ticking" whenever isGamePaused() was false -- and printed, but never looked at,
    the world time it had fetched. A world whose clock is frozen while that flag says otherwise
    (the game thread suspended by this repo's own debug_suspend_thread, an integrated-server
    stall, a resource reload) then passed the guard, and every probe below measured a world that
    was standing still and reported the result as a defect in the code. The sibling guard
    (live-nav-probe.py's require_act_ticking) already sampled twice and compared; this is the same
    shape against the world clock.

    The retries buy slack in one direction only. A frozen world repeats its answer, so a second
    look costs time on a path that was about to report SETUP anyway; a LIVE world that is merely
    busy -- the integrated server generating chunks, a GC pause -- can miss a single 0.5s window on
    a fast clock, and being told "the world is not ticking" when it is would abort a whole run for
    nothing. Paused=true is the one state that needs no second look: that is the gate itself.
    """
    raw = ""
    for _ in range(tries):
        before, raw = _world_sample(mcp)
        if before is None:
            return False, raw
        if before[0]:
            return False, raw
        time.sleep(settle_s)
        after, raw = _world_sample(mcp)
        if after is None:
            return False, raw
        if after[1] > before[1]:
            return True, raw
    return False, raw


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
    """Skip rather than fail when the world is frozen. A false FAIL is worse than a skip.

    And a skip has to end as one. It is recorded through record_skip rather than record(..., False),
    so a caller that returns EXIT_SETUP on the spot and a caller that just runs on (nav-astar-probe)
    both end in EXIT_SETUP -- the code the module reserves for "the world/client is not in a state
    that can be probed". Recorded as a FAIL it named code that was never exercised, and read exactly
    like a regression.
    """
    ok, detail = is_ticking(mcp)
    if not ok:
        record_skip(what,
                    "SKIPPED-NOT-MEASURED: the world is not ticking, so this proves nothing about the "
                    "code. Focus the game window, or call allow_unfocused(mcp), and re-run. "
                    + detail[:160])
    return ok
