#!/usr/bin/env python3
"""Live verification of NavController -- walking to a coordinate -- over the MCP socket.

    ./scripts/run-mcp.sh &                            # start the client, get into a world
    python3 scripts/live-nav-probe.py --allow-unfocused

    python3 scripts/live-nav-probe.py --self-check    # no game needed; see SELF-CHECK below

WHAT THIS EXISTS TO SETTLE. Three claims about navigation have never been confirmed on a live
client (handoff-2026-08-03 section 6): a clean pass in all FOUR cardinal directions, ANY diagonal
arrival at all, and a one-block step-up failing honestly instead of retrying to the timeout. Three
directions arrived once; the diagonal has never succeeded even once, and it is the only case that
uses forward and strafe together, so it is the one that would expose a mirrored axis convention --
which NavController's own javadoc calls out as untrustworthy (MoveIntent documents strafe +1 as
LEFT, moveFlying names nothing).

Both earlier pollution sources are fixed: replies larger than one recv were truncated (af0e964)
and an unfocused window silently froze the world (254b760, pauseOnLostFocus). A fresh run is
therefore expected to be informative rather than to re-measure the harness.

WHY A PROBE AND NOT A JUnit LiveIT: GameAccess reads Minecraft.getMinecraft(), a static singleton
that exists only in the game's JVM, so a forked surefire JVM sees null and an assume-gated test
skips forever. Live verification here goes through the MCP socket and eval_java (docs/debugging.md
section 10).

IT REWRITES TERRAIN. Making the four rays and the diagonal genuinely walkable needs flat ground,
so unless --no-flatten is passed this clears a square of side 2*(distance+3)+1 around the player
to air at foot level and lays stone underneath -- server-side, in batches. Anything standing there
(a chest and its contents, a build) is destroyed. Stand somewhere disposable, or pass --no-flatten
and stand on natural flat ground.

SELF-CHECK. The verdict functions below are the only part of this file measurable without a game,
and they are where a hollow assertion would hide, so --self-check runs them against payloads that
must be rejected: a 3D arrival distance, a timeout dressed up as a jam, an "arrival" with no
walking in it. Mutate any verdict and --self-check goes red.

Exit codes follow the sibling probes: 0 PASS, 1 FAIL, 2 TIMEOUT (a slot never went terminal), 3
SETUP (nothing was measured).
"""

import argparse
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# Imported BY NAME, from the shared module, rather than by loading nav-astar-probe.py by path.
#
# This file was written in parallel with the extraction of mcp_probe.py and originally reached
# through nav-astar-probe.py, which is where those helpers used to live. Two reasons the direct
# import is not merely tidier. One: load-by-path execs a FRESH module object every call, so the
# class it hands back is a different object with the same name -- which is exactly why the contract
# test could only compare Mcp.__module__ as a string back then, a check two copies in one file
# would also satisfy. Importing by name resolves through sys.modules, so the identity assertion in
# scripts/test_probe_framing.py becomes possible and a copy cannot masquerade as the original.
# Two: the framing bug this repo paid for (af0e964) survived a session precisely because the fix
# lived in one copy of the read loop and not the other, so every extra hop toward the shared
# implementation is another place a future edit can fork it.
from mcp_probe import (  # noqa: E402 - the sys.path line above has to run first
    PREAMBLE,
    Mcp,
    allow_unfocused,
    probe_in_world,
    record,
    report,
    require_ticking,
    results,
)

# The integrated server's own player. In single player it lives in this JVM and it is the authority:
# the client predicts a walk and the server validates it, reverting a client that moved into a block
# the server still has (NetHandlerPlayServer.processPlayer). So terrain is written on the SERVER
# world and every precondition is asked of BOTH sides -- asking only the client can report
# "precondition holds" for a run that is already doomed, which is how the hold probe wasted a
# session (docs/debugging.md section 10 rule 2). The Java is duplicated from live-hold-probe.py on
# purpose; what must not be duplicated is the socket client, and that one is imported above.
SERVER_PLAYER = """
        net.minecraft.entity.player.EntityPlayerMP sp = null;
        net.minecraft.world.World sw = null;
        {
            net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
            if (srv != null && !srv.getConfigurationManager().getPlayerList().isEmpty()) {
                sp = srv.getConfigurationManager().getPlayerList().get(0);
                sw = sp.worldObj;
            }
        }
"""

# Arrival tolerance for the probe's OWN measurement, in blocks. NavController's ARRIVE_EPSILON is
# 0.6; this is deliberately looser because the probe samples position one round trip after the
# controller stopped steering and a walking player carries momentum for a tick or two. Tighter than
# 0.6 would fail on physics rather than on the code.
ARRIVED_WITHIN = 1.0

# Ticks a leg is given. About 0.2 blocks per tick walking (measured, 4.2 blocks/second), so 8 blocks
# is ~45 ticks; 200 leaves room for a jam to be detected and still ends the leg long before the
# controller's own 400-tick default.
LEG_TIMEOUT_TICKS = 200

# ===== verdicts: pure, so --self-check can prove they are not hollow =====


def num(text, key, default=None):
    """The float after {@code key=} in a probe reply, or {@code default}.

    Scoped to a WHOLE key on both sides, which is the part that took a bug to learn: the snapshot
    below reports both {@code x=} (client) and {@code sx=} (server), and a bare {@code x=} search
    happily reads the server's number when the field order changes. This repo has already shipped one
    assertion that matched "24" against a "-4 OPEN trapdoor" elsewhere in the same string, so the
    boundary is deliberate rather than defensive.
    """
    m = re.search(r"(?:^|[^A-Za-z0-9_])" + re.escape(key)
                  + r"=(-?\d+(?:\.\d+)?(?:[eE]-?\d+)?)", text or "")
    return float(m.group(1)) if m else default


def reported_arrival_distance(message):
    """The number NavController itself printed in "arrived within N.NN blocks", or None."""
    m = re.search(r"arrived within (-?\d+(?:\.\d+)?) blocks", message or "")
    return float(m.group(1)) if m else None


def arrival_verdict(phase, message, start_dist, end_dist, min_start):
    """Did this leg ARRIVE, having actually walked there.

    Three terms, and the third is the one that stops the check being hollow. {@code min_start}
    rejects a leg whose start distance was near zero: with a failed teleport the player is already on
    the target, the slot completes on its first tick, and "COMPLETE + arrived + gap small" is true
    without a single step being taken. That is the exact defect class this repo keeps finding -- an
    assertion that a deleted setup would still satisfy.

    The gap is measured by the probe from live positions, not read out of the message, so a
    controller that reported arrival while standing still fails here.
    """
    return (phase == "COMPLETE"
            and "arrived within" in (message or "")
            and start_dist is not None and start_dist >= min_start
            and end_dist is not None and end_dist <= ARRIVED_WITHIN)


def both_axes_verdict(max_forward, max_strafe, floor=0.15):
    """Was the walk driven by forward AND strafe together -- the diagonal's whole point.

    Sampled off {@code EntityPlayerSP.movementInput}, which is what vanilla reads each tick, so this
    says the axes were really applied rather than merely computed. A cardinal leg moves one axis
    only, so a diagonal that arrives with a zero axis arrived by luck or by a mirrored convention.
    """
    return (max_forward is not None and max_strafe is not None
            and abs(max_forward) >= floor and abs(max_strafe) >= floor)


def stuck_verdict(phase, message, ticks, timeout_ticks):
    """A blocked leg must FAIL as a jam, promptly -- not spin to the timeout.

    "gave up after N ticks" is the timeout ending and "stuck against a wall" is the honest one, and
    the tick bound is what separates them even if both words ever appeared together: a controller
    that retried until the deadline cannot satisfy it.
    """
    return (phase == "FAILED"
            and "stuck against a wall" in (message or "")
            and "gave up" not in (message or "")
            and ticks is not None and timeout_ticks > 0 and ticks < timeout_ticks)


def horizontal_only_verdict(phase, message, horiz_gap, y_gap, min_y_gap=5.0):
    """act_set's contract: y is RECORDED and never steered toward, so arrival is horizontal.

    Asserts against the number the controller printed as well as the measured gap. If arrival were
    ever computed in 3D, a target 25 blocks up could never be within 0.6 blocks, the leg would end
    "gave up", and this goes red -- which makes the y term genuinely load-bearing rather than
    decorative.
    """
    reported = reported_arrival_distance(message)
    return (phase == "COMPLETE"
            and reported is not None and reported <= ARRIVED_WITHIN
            and horiz_gap is not None and horiz_gap <= ARRIVED_WITHIN
            and y_gap is not None and abs(y_gap) >= min_y_gap)


# ===== setup: the arena, built and checked on both sides =====


def read_anchor(mcp):
    """The block the player stands on, plus every precondition that would void a measurement.

    Taken from the CLIENT position but with the server asked to agree, because the two can differ and
    the walk is predicted by one and validated by the other. Gamemode is read and NOT changed: an
    earlier session switched a hovering player out of creative and lost the whole inventory to
    "fell out of the world" (docs/debugging.md section 10 rule 3), so a flying player is a SETUP
    report and the human lands them.
    """
    return mcp.java("NavAnchor", PREAMBLE + SERVER_PLAYER + """
        if (sp == null || sw == null) return "SETUP-NO-SERVER-PLAYER";
        if (p.ridingEntity != null || sp.ridingEntity != null)
            return "SETUP-RIDING (vanilla skips the 0.2x use scaling and the axes for a rider)";
        if (p.capabilities.isFlying)
            return "SETUP-FLYING (this controller walks; land first, and do NOT let a script "
                 + "toggle creative for you while airborne)";
        if (!p.onGround) return "SETUP-AIRBORNE clientY=" + p.posY;
        double feet = p.getEntityBoundingBox().minY;
        int bx = net.minecraft.util.MathHelper.floor_double(p.posX);
        int by = net.minecraft.util.MathHelper.floor_double(feet + 0.01D);
        int bz = net.minecraft.util.MathHelper.floor_double(p.posZ);
        double drift = Math.sqrt((sp.posX - p.posX) * (sp.posX - p.posX)
                              + (sp.posZ - p.posZ) * (sp.posZ - p.posZ));
        return "ax=" + bx + " ay=" + by + " az=" + bz
             + " drift=" + drift + " yaw=" + p.rotationYaw
             + " gameType=" + sp.theItemInWorldManager.getGameType()
             + " dim=" + w.provider.getDimensionId();
    """).strip()


def flatten(mcp, anchor, radius, rows_per_batch=3):
    """Lay a flat arena on the SERVER world, in batches, and report how much each batch wrote.

    BATCHES, because one eval writing ~12,000 blocks silently truncated against GameBridge's 5s cap
    and every "stall" chased afterwards turned out to be that or focus loss
    (handoff-2026-08-03 section 5 point 3). Each batch echoes the x range it covered and its own
    counts, and the caller checks the ranges tile the arena exactly -- a truncated or never-executed
    batch then shows as a missing range instead of as a mysterious obstacle later.

    Flag 2 (send to clients) WITHOUT flag 1 (notify neighbours) on purpose: neighbour notification is
    what makes adjacent water flow into the cleared columns and gravel fall into them, i.e. it would
    let the arena change underneath the measurement. Vanilla needs a block update to re-evaluate a
    fluid, so withholding it keeps the run reproducible.
    """
    ax, ay, az = anchor
    x0, x1 = ax - radius, ax + radius
    out = []
    x = x0
    while x <= x1:
        hi = min(x + rows_per_batch - 1, x1)
        reply = mcp.java("NavFlatten", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.block.state.IBlockState air = net.minecraft.init.Blocks.air.getDefaultState();
        net.minecraft.block.state.IBlockState floor =
            net.minecraft.init.Blocks.stone.getDefaultState();
        int cols = 0, cleared = 0, filled = 0;
        for (int bx = {x}; bx <= {hi}; bx++) {{
            for (int bz = {az - radius}; bz <= {az + radius}; bz++) {{
                cols++;
                net.minecraft.util.BlockPos below = new net.minecraft.util.BlockPos(bx, {ay - 1}, bz);
                // isBlockNormalCube, NOT isFullCube. Block.isFullCube() returns true
                // unconditionally (Block.java:366) and BlockAir does not override it -- it overrides
                // only isOpaqueCube -- so air.isFullCube() is TRUE. Measured live: air, stone and
                // grass all report isFullCube=true, while air/water/tallgrass/lava report
                // isBlockNormalCube=false and stone/grass report true.
                //
                // With isFullCube this condition was `!true` for a hole, so THE FLATTEN NEVER FILLED
                // ONE. That is not hypothetical: a leftover pit at the far end of the +X ray dropped
                // the player a block mid-leg, NavController honestly reported "stuck against a wall"
                // against the pit side, and the run read as a controller defect for three rounds of
                // investigation.
                if (!sw.getBlockState(below).getBlock().isBlockNormalCube()
                        && sw.setBlockState(below, floor, 2)) {{
                    filled++;
                }}
                // Three clear above the floor: the player box is 1.8 tall, and the third leaves the
                // step-up wall somewhere to be removed to without leaving a lip behind.
                for (int dy = 0; dy < 3; dy++) {{
                    net.minecraft.util.BlockPos bp =
                        new net.minecraft.util.BlockPos(bx, {ay} + dy, bz);
                    if (!sw.isAirBlock(bp) && sw.setBlockState(bp, air, 2)) {{
                        cleared++;
                    }}
                }}
            }}
        }}
        return "x={x}..{hi} cols=" + cols + " cleared=" + cleared + " filled=" + filled;
        """).strip()
        out.append(reply)
        print(f"        {reply[:160]}")
        if reply.startswith("SETUP") or reply.startswith("THREW") or reply.startswith("PROBE-ERROR"):
            return False, out
        x = hi + 1

    # The ranges must tile [x0, x1] exactly. This is the truncation guard: a batch that never ran, or
    # that came back short, leaves a hole here rather than a wall the walk trips over ten legs later.
    expect = []
    x = x0
    while x <= x1:
        hi = min(x + rows_per_batch - 1, x1)
        expect.append(f"x={x}..{hi}")
        x = hi + 1
    got = [r.split(" ", 1)[0] for r in out]
    return got == expect, out


def verify_arena(mcp, anchor, dist):
    """Walk every ray the probe will use, column by column, on BOTH sides.

    The server view matters because it reverts a client that walked into a block the server still has;
    the client view matters because it is what predicts the walk and what NavController reads back.
    An earlier live run lost a leg to exactly this -- the player stopped at z=149.700, one block
    boundary short, on terrain the flatten had not reached.
    """
    ax, ay, az = anchor
    return mcp.java("NavArena", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        int[][] rays = {{ {{1, 0}}, {{-1, 0}}, {{0, 1}}, {{0, -1}}, {{1, 1}} }};
        int checked = 0, bad = 0;
        StringBuilder first = new StringBuilder();
        for (int[] ray : rays) {{
            for (int s = 0; s <= {dist}; s++) {{
                net.minecraft.util.BlockPos bp = new net.minecraft.util.BlockPos(
                    {ax} + ray[0] * s, {ay}, {az} + ray[1] * s);
                checked++;
                boolean serverClear = sw.isAirBlock(bp) && sw.isAirBlock(bp.up());
                boolean clientClear = w.isAirBlock(bp) && w.isAirBlock(bp.up());
                // isBlockNormalCube, not isFullCube: air.isFullCube() is TRUE (see flatten's note),
                // so this floor check COULD NOT FAIL. It reported bad=0 over a real pit, which is
                // the hollow-assertion shape this repo keeps finding in itself -- a check that
                // structurally cannot see the thing it exists to see.
                boolean serverFloor = sw.getBlockState(bp.down()).getBlock().isBlockNormalCube();
                boolean clientFloor = w.getBlockState(bp.down()).getBlock().isBlockNormalCube();
                if (!serverClear || !clientClear || !serverFloor || !clientFloor) {{
                    bad++;
                    if (first.length() < 300) {{
                        first.append(" [").append(bp.getX()).append(",").append(bp.getZ())
                             .append(" srvClear=").append(serverClear)
                             .append(" cliClear=").append(clientClear)
                             .append(" srvFloor=").append(serverFloor)
                             .append(" cliFloor=").append(clientFloor).append("]");
                    }}
                }}
            }}
        }}
        return "checked=" + checked + " bad=" + bad + first;
    """).strip()


def teleport_home(mcp, anchor):
    """Put the player back on the anchor between legs, from the SERVER side.

    setPositionAndUpdate goes through the net handler, which sends the pos-look packet and then
    IGNORES client movement until the client reports within 0.5 blocks of the target
    (NetHandlerPlayServer.processPlayer, the {@code !hasMoved && d3 < 0.25} gate) -- so the settle
    below is required, not cosmetic. Writing the client position instead would leave the server
    thinking the player is elsewhere and the first leg would be reverted mid-walk.

    Not navigating home: a failed leg would then poison the next one's start, and a probe whose setup
    depends on the code under test cannot report on it.
    """
    ax, ay, az = anchor
    reply = mcp.java("NavHome", PREAMBLE + SERVER_PLAYER + f"""
        if (sp == null) return "SETUP-NO-SERVER-PLAYER";
        sp.setPositionAndUpdate({ax} + 0.5D, {ay}, {az} + 0.5D);
        return "sent";
    """)
    if not reply.strip().startswith("sent"):
        return "teleport rejected: " + reply.strip()[:160]
    time.sleep(0.8)
    return mcp.java("NavHomeCheck", PREAMBLE + SERVER_PLAYER + f"""
        double cd = Math.sqrt((p.posX - ({ax} + 0.5D)) * (p.posX - ({ax} + 0.5D))
                            + (p.posZ - ({az} + 0.5D)) * (p.posZ - ({az} + 0.5D)));
        double sd = sp == null ? -1 : Math.sqrt((sp.posX - ({ax} + 0.5D)) * (sp.posX - ({ax} + 0.5D))
                            + (sp.posZ - ({az} + 0.5D)) * (sp.posZ - ({az} + 0.5D)));
        return "clientOff=" + cd + " serverOff=" + sd + " onGround=" + p.onGround;
    """).strip()


# ===== driving a leg through the production path =====


def clear_use(mcp):
    """End any use in progress, on both sides, before measuring locomotion.

    Vanilla scales moveStrafe and moveForward to 0.2x while isUsingItem (EntityPlayerSP:788-792), so
    a leg run with a half-eaten apple in hand travels a fifth as far and reports "gave up" -- a
    failure of the setup wearing the code's clothes.
    """
    mcp.call("act_cancel", {"slots": ["interact"]})
    return mcp.java("NavClearUse", PREAMBLE + SERVER_PLAYER + """
        if (p.isUsingItem()) mc.playerController.onStoppedUsingItem(p);
        if (sp != null && sp.isUsingItem()) sp.stopUsingItem();
        return "clientUsing=" + p.isUsingItem()
             + " serverUsing=" + (sp == null ? "n/a" : String.valueOf(sp.isUsingItem()));
    """).strip()


def face_yaw(mcp, yaw):
    """Point the camera through the LOOK slot, and confirm it took.

    Every leg starts from the same yaw so the axis decomposition is known in advance rather than
    inferred: NavController steers relative to whatever yaw IS, and it never turns the camera, so at
    yaw 0 (facing +Z) forward is +Z and strafe is +X. That is what makes the diagonal's
    "both axes were used" claim checkable instead of a guess.

    Through act_set rather than by writing rotationYaw: the LOOK slot is the production path and a
    direct write leaves prevRotationYaw stale, which vanilla interpolates from.
    """
    mcp.call("act_set", {"look": {"mode": "set", "yaw": yaw, "pitch": 0}})
    time.sleep(0.6)
    return mcp.java("NavYaw", PREAMBLE + """
        return "yaw=" + net.minecraft.util.MathHelper.wrapAngleTo180_float(p.rotationYaw)
             + " pitch=" + p.rotationPitch;
    """).strip()


def snapshot(mcp):
    """Client position and the axes vanilla is reading this tick, plus the server's own position."""
    return mcp.java("NavSnap", PREAMBLE + SERVER_PLAYER + """
        // movementInput is what vanilla actually consumes each tick, so reading it -- rather than the
        // axes the controller published -- is what makes "the input reached the game" observable.
        return "x=" + p.posX + " y=" + p.getEntityBoundingBox().minY + " z=" + p.posZ
             + " yaw=" + net.minecraft.util.MathHelper.wrapAngleTo180_float(p.rotationYaw)
             + " fwd=" + p.movementInput.moveForward + " str=" + p.movementInput.moveStrafe
             + " onGround=" + p.onGround + " collided=" + p.isCollidedHorizontally
             + " sx=" + (sp == null ? Double.NaN : sp.posX)
             + " sz=" + (sp == null ? Double.NaN : sp.posZ);
    """).strip()


def drive_nav(mcp, target, timeout_ticks=LEG_TIMEOUT_TICKS, poll_s=0.4, sample_axes=False):
    """Submit a destination through act_set and poll act_status until the MOVE slot goes terminal.

    THE PRODUCTION PATH, and that is not tidiness. A game-thread submission runs entirely inside ONE
    tick -- measured, getTotalWorldTime() does not move across a loop in a single eval -- so calling
    controller.tick() in a loop gives the controller many turns while the world stands still, the
    position never changes, and the controller honestly reports it is going nowhere. That honest
    report is indistinguishable from a broken controller, and this repo has now chased it twice
    (docs/debugging.md section 10 rule 1). ActTickLoop advances the controller once per real game
    tick, so polling from OUTSIDE is what lets those ticks happen -- and it exercises the act_set
    wiring rather than reaching past it.

    A first poll cannot read the PREVIOUS leg's terminal record, which would be a false pass for a leg
    that never ran: a slot keeps its intent after going terminal, but ActRuntime.submit stores a fresh
    SlotRecord at phase IDLE synchronously before act_set returns (SlotRecord.submitted), so anything
    terminal seen afterwards belongs to this submission.
    """
    import json as _json
    tx, ty, tz = target
    submitted = mcp.call("act_set", {"move": {"to": [tx, ty, tz], "timeoutTicks": timeout_ticks}})
    if submitted.get("isError") or "error" in submitted:
        return {"phase": "SETUP", "message": "act_set rejected: " + str(submitted)[:200],
                "ticksActive": None, "timedout": False, "fwd": None, "str": None}

    # Generous against the controller's own budget: the deadline here must not be the thing that ends
    # a leg, or a real timeout and a slow round trip would be reported as the same thing.
    deadline = time.time() + timeout_ticks / 20.0 + 8.0
    last, max_fwd, max_str, samples, saw_contact = None, 0.0, 0.0, 0, False
    while time.time() < deadline:
        time.sleep(poll_s)
        if sample_axes:
            snap = snapshot(mcp)
            # Contact is sampled WHILE walking, not afterwards. isCollidedHorizontally is recomputed
            # by every moveEntity call (Entity.java:818), so once the controller stops steering the
            # player stops pressing and the flag goes false again -- reading it after the leg ended
            # would report "no wall" for a leg that jammed against one.
            saw_contact = saw_contact or "collided=true" in snap
            f, s = num(snap, "fwd"), num(snap, "str")
            if f is not None and s is not None:
                samples += 1
                max_fwd = max(max_fwd, abs(f))
                max_str = max(max_str, abs(s))
        raw = mcp.call("act_status", {})
        try:
            st = _json.loads(raw.get("text", "{}"))
        except ValueError:
            continue
        slot = next((s for s in st.get("slots", []) if s.get("slot") == "move"), None)
        if slot is None:
            continue
        last = slot
        if slot.get("phase") in ("COMPLETE", "FAILED", "CANCELLED"):
            return {"phase": slot["phase"], "message": slot.get("message", ""),
                    "ticksActive": slot.get("ticksActive"), "timedout": False,
                    "fwd": max_fwd if samples else None, "str": max_str if samples else None,
                    "samples": samples, "sawContact": saw_contact if sample_axes else None}
    # The slot never went terminal: a harness timeout, exit code 2, and NOT the same statement as
    # "the controller gave up" -- which is a FAILED phase with its own message.
    return {"phase": "POLL-TIMEOUT", "message": (last or {}).get("message", "no status"),
            "ticksActive": (last or {}).get("ticksActive"), "timedout": True,
            "fwd": max_fwd if samples else None, "str": max_str if samples else None,
            "samples": samples, "sawContact": saw_contact if sample_axes else None}


def run_leg(mcp, anchor, dx, dz, dy=0, sample_axes=False):
    """One walk: reset state, teleport home, face yaw 0, walk to anchor+(dx,dy,dz), measure.

    Returns the measured numbers rather than a verdict, so each check states its own claim over them.
    """
    ax, ay, az = anchor
    target = (ax + 0.5 + dx, ay + dy, az + 0.5 + dz)
    mcp.call("act_cancel", {"slots": ["move"]})
    used = clear_use(mcp)
    home = teleport_home(mcp, anchor)
    yaw = face_yaw(mcp, 0.0)
    before = snapshot(mcp)
    result = drive_nav(mcp, target, sample_axes=sample_axes)
    after = snapshot(mcp)
    mcp.call("act_cancel", {"slots": ["move"]})

    def gap(snap):
        x, z = num(snap, "x"), num(snap, "z")
        if x is None or z is None:
            return None
        return ((target[0] - x) ** 2 + (target[2] - z) ** 2) ** 0.5

    y_end = num(after, "y")
    return {
        "target": target, "result": result, "before": before, "after": after,
        "home": home, "use": used, "yawSet": yaw,
        "startGap": gap(before), "endGap": gap(after),
        "yGap": None if y_end is None else target[1] - y_end,
        "yawDrift": None if num(before, "yaw") is None or num(after, "yaw") is None
                    else num(after, "yaw") - num(before, "yaw"),
    }


def fmt(v):
    return "n/a" if v is None else f"{v:.2f}"


def leg_detail(leg):
    """One line carrying the measured numbers, because "PASS" alone is not a measurement."""
    r = leg["result"]
    start, end = leg["startGap"], leg["endGap"]
    closed = None if start is None or end is None else start - end
    axes = ("" if r.get("fwd") is None
            else f" | axes max |fwd|={r['fwd']:.3f} |str|={r['str']:.3f} over {r['samples']} samples")
    return (f"gap {fmt(start)} -> {fmt(end)} blocks (closed {fmt(closed)}) in "
            f"{r.get('ticksActive')} ticks, yaw drift {fmt(leg['yawDrift'])} deg{axes}\n"
            f"          {r['phase']}: {str(r['message'])[:170]}")


def probe_four_cardinals(mcp, anchor, dist):
    """A clean pass in all four cardinal directions -- never yet observed in one run.

    Three arrived on 2026-08-03 (+Z 0.23 blocks/151 ticks, -Z 0.33/68, +X 0.74/183) and the fourth
    was lost to a truncated flatten, so "all four" has stayed a claim rather than a measurement.

    At yaw 0 the decomposition is fixed and each leg therefore isolates one axis: +Z/-Z are pure
    forward, +X/-X are pure strafe. A mirrored strafe convention would show as the +X and -X legs
    walking away from their targets -- which is why the assertion is that the gap CLOSED, not that
    the player moved.

    The yaw affects only those axis LABELS, not arrival: the controller recomputes its axes from
    whatever yaw is, so a leg that started at some other yaw still arrives and still counts. The
    diagonal check below is the one that needs the yaw pinned, and it guards on it.
    """
    ok_all = True
    timed_out = False
    for name, (dx, dz), axis in (("+Z (south)", (0, dist), "forward"),
                                 ("-Z (north)", (0, -dist), "forward"),
                                 ("+X (east)", (dist, 0), "strafe"),
                                 ("-X (west)", (-dist, 0), "strafe")):
        leg = run_leg(mcp, anchor, dx, dz)
        timed_out = timed_out or leg["result"]["timedout"]
        ok = arrival_verdict(leg["result"]["phase"], leg["result"]["message"],
                             leg["startGap"], leg["endGap"], min_start=dist * 0.5)
        # The axis is stated in the DETAIL with the yaw it depended on, not in the check's name: the
        # verdict tests arrival, and a name that also claimed "strafe only" would be asserting
        # something this leg never looked at.
        ok_all = record(f"cardinal {name} reached", ok,
                        f"{axis}-only at yaw 0; leg ran at yaw {fmt(num(leg['before'], 'yaw'))}. "
                        + leg_detail(leg)) and ok_all
    return ok_all, timed_out


def probe_diagonal(mcp, anchor, dist):
    """THE important one: a diagonal target reached, with both axes proved to have been applied.

    It is the only case that drives forward and strafe together, it has never once succeeded, and it
    is where a mirrored convention hides: with one axis inverted the player walks off at ninety
    degrees and every cardinal leg still passes, because each of those uses one axis alone.

    Two claims, recorded separately so a partial result is legible: the target was reached, and both
    axes were non-zero in vanilla's own movementInput while it happened. Arrival alone would not
    settle it -- a player who walked the two legs of the triangle in sequence would also arrive.
    """
    leg = run_leg(mcp, anchor, dist, dist, sample_axes=True)
    r = leg["result"]
    reached = arrival_verdict(r["phase"], r["message"], leg["startGap"], leg["endGap"],
                              min_start=dist * 1.414 * 0.5)
    ok = record("DIAGONAL (+X+Z) target reached -- the case that has never arrived", reached,
                leg_detail(leg))

    # The axis claim is only readable at a known yaw, and a yaw of 45 would make this diagonal PURE
    # forward -- so a LOOK that silently did not take would report "one axis only" for a controller
    # doing exactly the right thing. A false FAIL is worse than a skip, so say so instead.
    yaw_at_start = num(leg["before"], "yaw")
    if yaw_at_start is None or abs(yaw_at_start) > 5.0:
        record("and vanilla was driven by forward AND strafe together, not one axis at a time", False,
               f"SKIPPED-NOT-MEASURED: the leg ran at yaw {fmt(yaw_at_start)} rather than 0, and the "
               f"axis split is only interpretable at a known yaw (at yaw 45 this diagonal is pure "
               f"forward). LOOK reported: {leg['yawSet'][:80]}")
        return False, r["timedout"]
    both = both_axes_verdict(r.get("fwd"), r.get("str"))
    record("and vanilla was driven by forward AND strafe together, not one axis at a time", both,
           f"max |fwd|={fmt(r.get('fwd'))} max |str|={fmt(r.get('str'))} over {r.get('samples')} "
           f"samples (expected about 0.71 each at yaw 0 for a 45-degree bearing); "
           f"camera yaw drift {fmt(leg['yawDrift'])} deg -- nav must not steer the camera")
    return ok and both, r["timedout"]


def probe_arrival_is_horizontal_only(mcp, anchor, dist):
    """act_set's contract: the y you pass is RECORDED and never steered toward.

    So a target 25 blocks overhead must still be "arrived" the moment the horizontal gap closes, and
    the number in the message must be that horizontal gap. If arrival were ever computed in 3D this
    leg cannot complete at all -- it would end "gave up after 200 ticks" with a 25-block residual --
    which is what makes the y term here load-bearing rather than decoration.
    """
    lift = 25
    leg = run_leg(mcp, anchor, 0, dist, dy=lift)
    r = leg["result"]
    ok = horizontal_only_verdict(r["phase"], r["message"], leg["endGap"], leg["yGap"])
    return record("arrival is HORIZONTAL only: a target 25 blocks up still arrives, and the "
                  "reported distance is the horizontal one", ok,
                  f"reported arrival {fmt(reported_arrival_distance(r['message']))} blocks, "
                  f"measured horizontal gap {fmt(leg['endGap'])}, y gap {fmt(leg['yGap'])} "
                  f"(target y {fmt(leg['target'][1])})\n          {leg_detail(leg)}"), r["timedout"]


def build_step(mcp, anchor, at, half_width, fill):
    """One row of blocks across the +X ray at anchor+at, or air again to take it back down.

    Wide enough that steering cannot slip round the end: NavController aims straight at the target
    with no pathfinding, so a narrow block would be walked around and the leg would measure nothing.
    Written on the SERVER, like the arena, because a step the server does not have would be walked
    straight through by the very validation that is supposed to stop it.

    Reports the resulting SERVER state (how many of the row are solid) rather than how many writes
    changed something. setBlockState returns false when the block already held that state, so a change
    count reads 0 both for "already built" and for "wrote nothing" -- and this repo has already
    shipped one gate that reported success for having checked nothing.

    The CLIENT view is deliberately not read here: a server write reaches the client through the
    player manager on a LATER server tick, so reading it in this same submission would report the old
    state and a correctly built step would look like a failed one. {@link step_state} asks after a
    settle, which is the same "second eval, so real ticks pass" discipline the hold probe needed for
    the screen check.
    """
    ax, ay, az = anchor
    state = ("net.minecraft.init.Blocks.stone" if fill else "net.minecraft.init.Blocks.air")
    return mcp.java("NavStep", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.block.state.IBlockState st = {state}.getDefaultState();
        int width = 0, serverSolid = 0;
        for (int bz = {az} - {half_width}; bz <= {az} + {half_width}; bz++) {{
            net.minecraft.util.BlockPos bp = new net.minecraft.util.BlockPos({ax + at}, {ay}, bz);
            sw.setBlockState(bp, st, 2);
            width++;
            // isBlockNormalCube: with isFullCube this counted AIR as solid, so the removal call
            // reported "serverSolid=11" for a row it had just cleared -- the built and the removed
            // states printed the identical number and nobody could tell them apart.
            if (sw.getBlockState(bp).getBlock().isBlockNormalCube()) serverSolid++;
        }}
        return "width=" + width + " serverSolid=" + serverSolid
             + " atX=" + {ax + at} + " y=" + {ay};
    """).strip()


def step_state(mcp, anchor, at, half_width):
    """How much of the step row both sides now agree is solid. Call after a settle, not before."""
    ax, ay, az = anchor
    return mcp.java("NavStepState", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        int width = 0, serverSolid = 0, clientSolid = 0;
        for (int bz = {az} - {half_width}; bz <= {az} + {half_width}; bz++) {{
            net.minecraft.util.BlockPos bp = new net.minecraft.util.BlockPos({ax + at}, {ay}, bz);
            width++;
            if (sw.getBlockState(bp).getBlock().isBlockNormalCube()) serverSolid++;
            if (w.getBlockState(bp).getBlock().isBlockNormalCube()) clientSolid++;
        }}
        return "width=" + width + " serverSolid=" + serverSolid + " clientSolid=" + clientSolid;
    """).strip()


def probe_step_up_fails_honestly(mcp, anchor, dist):
    """A one-block step must be an honest jam, not a retry loop -- this controller walks.

    Vanilla gives a player stepHeight 0.6 (EntityLivingBase:208), so a full block cannot be walked up
    without a jump and Entity.moveEntity clamps the motion, setting isCollidedHorizontally
    (Entity.java:818). NavController needs BOTH that flag and 8 still ticks before it calls a jam, so
    the two endings it can produce here are "stuck against a wall for 8 ticks, N blocks short" and
    "gave up after 200 ticks". The first is the honest one and the difference is the whole check:
    a wall reported as a timeout tells a caller to wait longer when it should reroute.

    The step goes up right before the leg and comes down right after, so a re-run starts from the same
    arena. Leaving it would poison the +X cardinal leg on the next run.
    """
    half = 5
    print(f"     (step built: {build_step(mcp, anchor, at=3, half_width=half, fill=True)[:140]})")
    # Settle, then ask BOTH sides. The client is what predicts the walk into the step and the server
    # is what refuses to let it through, so a step only one side has proves nothing either way.
    time.sleep(1.2)
    state = step_state(mcp, anchor, at=3, half_width=half)
    print(f"     (step state: {state[:140]})")
    width = num(state, "width")
    if width is None or num(state, "serverSolid") != width or num(state, "clientSolid") != width:
        build_step(mcp, anchor, at=3, half_width=half, fill=False)
        return record("a one-block step-up fails honestly rather than retrying to the timeout",
                      False, "SKIPPED-NOT-MEASURED: the step is not solid on both sides, so a "
                             "failure below would be about the terrain: " + state[:200]), False

    # Sampled, because the contact evidence has to be collected mid-walk: see drive_nav.
    leg = run_leg(mcp, anchor, dist, 0, sample_axes=True)
    r = leg["result"]
    down = build_step(mcp, anchor, at=3, half_width=half, fill=False)
    print(f"     (step removed: {down[:140]})")

    # ASSERTED, not merely printed, and that distinction is the whole reason this block exists.
    # The removal was printed and never checked, so when isFullCube counted air as solid the built
    # row and the cleared row reported the IDENTICAL "serverSolid=11" and the teardown looked like it
    # had done nothing wrong. A step left standing poisons the NEXT run's +X cardinal leg, which then
    # fails as a controller defect a run later -- a delayed, cross-run failure with no visible cause,
    # and the worst shape a probe can leave behind.
    time.sleep(0.8)
    after = step_state(mcp, anchor, at=3, half_width=half)
    left_standing = num(after, "serverSolid") or 0
    if left_standing:
        record("the step is torn down, so the next run's +X leg is not poisoned", False,
               f"{left_standing} of {num(after, 'width')} columns are still solid after the "
               f"teardown; the next run would walk into them: {after[:160]}")
    else:
        record("the step is torn down, so the next run's +X leg is not poisoned", True, after[:160])

    # The verdict rests on the controller's own message, and the sampled flag is REPORTED beside it
    # rather than gated on. The jam message can only be produced when the controller read
    # collidedHorizontally true on the deciding tick, so it already carries the contact evidence --
    # while the probe's sample only lands if a poll happens to fall inside the few hundred
    # milliseconds of contact before the jam is declared. Requiring the sample too would fail a leg
    # that was right, which is the inversion this file exists to avoid.
    ok = stuck_verdict(r["phase"], r["message"], r["ticksActive"], LEG_TIMEOUT_TICKS)
    return record("a one-block step-up fails honestly rather than retrying to the timeout", ok,
                  f"ended after {r.get('ticksActive')} of {LEG_TIMEOUT_TICKS} allowed ticks; "
                  f"the message is the contact evidence, and the probe independently saw "
                  f"isCollidedHorizontally while walking={r.get('sawContact')}\n"
                  f"          {leg_detail(leg)}"), r["timedout"]


# ===== guards and self-check =====


def require_act_ticking(mcp):
    """The act layer only steps on the Minecraft.runTick seam, so a still clock means nothing runs.

    Separate from require_ticking(): that one answers "is the world advancing", this one answers "is
    the seam that drives ActTickLoop armed". Without it every intent is accepted and sits at IDLE
    forever, and a probe would read that as a controller that never moved the player -- the failure
    mode act_set's own description warns about. Reported as SETUP because nothing was measured.
    """
    import json as _json

    def tick_now():
        try:
            return _json.loads(mcp.call("act_status", {}).get("text", "{}")).get("tickNow")
        except ValueError:
            return None

    first = tick_now()
    time.sleep(1.0)
    second = tick_now()
    ok = first is not None and second is not None and second > first
    if not ok:
        print(f"SETUP: act tick seam not advancing (tickNow {first} -> {second}). Every intent "
              "would sit at IDLE. Start with -javaagent:core-<ver>.jar (the clock arms by default) "
              "or call seam_tick_enable, and re-run.")
    else:
        print(f"-- act tick seam: tickNow {first} -> {second}")
    return ok


def self_check():
    """Prove the verdicts reject the payloads they exist to reject. No game required.

    These are the only assertions in this file measurable without a live client, and they are exactly
    where a hollow one would hide: every case below is a real ending this probe can receive, and each
    is paired with the wrong ending it must be told apart from. Mutate any verdict above and this
    goes red.
    """
    arrived = "arrived within 0.41 blocks after 47 ticks (47 ticks)"
    gave_up = "gave up after 200 ticks, still 7.80 blocks out -- the target may be unreachable"
    jammed = "stuck against a wall for 8 ticks, 5.31 blocks short of the target (24 ticks)"
    cases = [
        ("a real cardinal arrival is accepted",
         arrival_verdict("COMPLETE", arrived, 8.0, 0.41, 4.0), True),
        ("a timeout is not an arrival",
         arrival_verdict("FAILED", gave_up, 8.0, 7.8, 4.0), False),
        ("COMPLETE without the arrival message is not an arrival",
         arrival_verdict("COMPLETE", "movement complete after 3 ticks", 8.0, 0.4, 4.0), False),
        ("an arrival with the gap still open is rejected",
         arrival_verdict("COMPLETE", arrived, 8.0, 3.0, 4.0), False),
        ("an arrival that never left the target is rejected (a failed teleport)",
         arrival_verdict("COMPLETE", arrived, 0.2, 0.15, 4.0), False),
        ("a diagonal driven by both axes is accepted",
         both_axes_verdict(0.707, 0.707), True),
        ("one axis alone is not a diagonal",
         both_axes_verdict(1.0, 0.0), False),
        ("a jam before the deadline is an honest failure",
         stuck_verdict("FAILED", jammed, 24, 200), True),
        ("a timeout is not a jam",
         stuck_verdict("FAILED", gave_up, 201, 200), False),
        ("a jam message on a COMPLETE slot is not a jam",
         stuck_verdict("COMPLETE", jammed, 24, 200), False),
        ("a jam reported exactly at the deadline is not prompt",
         stuck_verdict("FAILED", jammed, 200, 200), False),
        ("arriving 25 blocks below the named y is the horizontal contract",
         horizontal_only_verdict("COMPLETE", arrived, 0.41, 25.0), True),
        ("a 3D arrival test would time out, and that is not a pass",
         horizontal_only_verdict("FAILED", gave_up, 0.41, 25.0), False),
        # The number in the message must be the HORIZONTAL gap. A controller measuring in 3D with a
        # looser epsilon would arrive and print the hypotenuse, and every other term here would still
        # be satisfied -- so without this case the reported-distance term is decoration. Measured: it
        # is the one term a mutation survived until this case existed.
        ("an arrival whose reported distance is the 3D one is rejected",
         horizontal_only_verdict("COMPLETE", "arrived within 25.01 blocks after 47 ticks",
                                 0.41, 25.0), False),
        ("a level target proves nothing about y being ignored",
         horizontal_only_verdict("COMPLETE", arrived, 0.41, 0.0), False),
        ("the reported distance is read from the message, not guessed",
         reported_arrival_distance(arrived), 0.41),
        ("a key is matched whole, not as a prefix of another key",
         num("ticksActive=24 shortfall=-4", "ticks"), None),
        ("and the right key still parses",
         num("ticksActive=24 shortfall=-4", "shortfall"), -4.0),
        ("the client x is not read out of the server's sx, whatever the field order",
         num("sx=101.5 x=2.5 sz=9.0 z=3.5", "x"), 2.5),
        ("and neither is z",
         num("sz=9.0 z=3.5", "z"), 3.5),
    ]
    bad = 0
    for label, got, want in cases:
        if got != want:
            bad += 1
        print(("  PASS  " if got == want else "  FAIL  ") + label
              + ("" if got == want else f"\n          got {got!r}, want {want!r}"))
    print(f"\n{'PASS' if bad == 0 else 'FAIL'}: {len(cases) - bad}/{len(cases)} verdict cases")
    return 0 if bad == 0 else 1


def main():
    ap = argparse.ArgumentParser(description="live verification of NavController (walk to a "
                                            "coordinate). REWRITES TERRAIN around the player.")
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--distance", type=int, default=8,
                    help="how far each leg walks, in blocks (default 8)")
    ap.add_argument("--no-flatten", action="store_true",
                    help="do not rewrite terrain; only sensible on ground that is already flat, and "
                         "the arena check below will say so if it is not")
    ap.add_argument("--allow-unfocused", action="store_true",
                    help="clear pauseOnLostFocus so the world keeps ticking while the window is in "
                         "the background, instead of needing focus for the whole run")
    ap.add_argument("--self-check", action="store_true",
                    help="run the verdict functions against known-good and known-bad payloads and "
                         "exit; needs no game")
    args = ap.parse_args()

    if args.self_check:
        print("-- verdict self-check (no game)\n")
        return self_check()

    dist = args.distance
    radius = dist + 3
    mcp = Mcp(args.port)
    print(f"-- nav probe on port {args.port}, legs of {dist} blocks\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {args.port}: {ping['error']}")
        print("       start the client first: ./scripts/run-mcp.sh")
        return 3

    if not probe_in_world(mcp):
        # 两种原因都可能:没进世界,或者进了但死了 —— probe_in_world 上面已分别报过。
        # 别在这里断言是哪一种:说错了会让读者去 load 一个已经加载好的世界。
        print("\nSETUP: no live player (not in a world, or in one but DEAD -- see which line above "
              "failed). Load a world and stand somewhere open and disposable, or respawn, re-run.")
        return 3

    # The window will not have focus while a script drives it, and an unfocused vanilla stops
    # advancing -- which freezes every position this probe reads and looks exactly like a controller
    # that cannot walk. That mistake produced a false bug report here once already.
    if args.allow_unfocused:
        print(f"-- unfocused ticking: {allow_unfocused(mcp)}")
    if not require_ticking(mcp, "the world is advancing before any walk is measured"):
        print("\nSETUP: world not ticking; nothing below would measure the code. Focus the game "
              "window, or pass --allow-unfocused.")
        return 3
    if not require_act_ticking(mcp):
        return 3

    print("\n-- preconditions, asked of both sides")
    where = read_anchor(mcp)
    print(f"        {where[:220]}")
    if where.startswith("SETUP") or where.startswith("THREW") or where.startswith("PROBE-ERROR"):
        print("\nSETUP: " + where[:300])
        return 3
    anchor = (int(num(where, "ax")), int(num(where, "ay")), int(num(where, "az")))
    print(f"        anchor block {anchor}")

    if args.no_flatten:
        print("\n-- arena: NOT flattened (--no-flatten); the check below decides whether that holds")
    else:
        box = (anchor[0] - radius, anchor[2] - radius, anchor[0] + radius, anchor[2] + radius)
        print(f"\n-- arena: clearing x {box[0]}..{box[2]}, z {box[1]}..{box[3]} at y {anchor[1]}"
              f"..{anchor[1] + 2}, stone at y {anchor[1] - 1}. THIS DESTROYS WHAT IS THERE.")
        tiled, batches = flatten(mcp, anchor, radius)
        if not tiled:
            print("\nSETUP: the flatten did not tile the arena -- a batch truncated or never ran, "
                  "which is the 5s-cap failure that poisoned every earlier attempt. Batches: "
                  + str(batches)[:300])
            return 3
        # Let the last batch reach the client before asking the client about it. A server write goes
        # out through the player manager on a later server tick (and a chunk with more than 64 changes
        # is resent whole), so checking immediately would report the pre-flatten world and turn a
        # correct arena into a SETUP failure.
        time.sleep(1.5)

    arena = verify_arena(mcp, anchor, dist)
    if not record("the arena is walkable on BOTH sides along every ray this probe uses",
                  "bad=0" in arena, arena[:400]):
        print("\nSETUP: the ground is not clear, so a stall below would measure the terrain rather "
              "than the controller. Re-run without --no-flatten, or move somewhere open.")
        return 3

    timed_out = False
    print(f"\n-- claim 1: a clean pass in all four cardinal directions")
    _, t = probe_four_cardinals(mcp, anchor, dist)
    timed_out = timed_out or t

    print(f"\n-- claim 2: a diagonal arrival, which has never happened")
    _, t = probe_diagonal(mcp, anchor, dist)
    timed_out = timed_out or t

    print(f"\n-- the contract: arrival is horizontal only")
    _, t = probe_arrival_is_horizontal_only(mcp, anchor, dist)
    timed_out = timed_out or t

    print(f"\n-- claim 3: a one-block step-up fails honestly")
    _, t = probe_step_up_fails_honestly(mcp, anchor, dist)
    timed_out = timed_out or t

    mcp.call("act_cancel", {"slots": ["move"]})
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n{'PASS' if passed == total else 'FAIL'}: {passed}/{total} checks")
    if passed == total:
        return 0
    # A slot that never went terminal is a different report from a slot that failed: exit 2 says the
    # run did not finish, so nobody records "nav is broken" on the strength of a stalled poll.
    return 2 if timed_out else 1


if __name__ == "__main__":
    sys.exit(main())
