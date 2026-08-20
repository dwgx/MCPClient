#!/usr/bin/env python3
"""Live verification that act_set move.route reaches ActPhase.COMPLETE on a Windows client.

    scripts\\run-mcp.bat                         # load New World, stand on open ground
    python -X utf8 scripts/live-route-probe.py --allow-unfocused

    python -X utf8 scripts/live-route-probe.py --self-check    # no game; verdict teeth

WHAT THIS EXISTS TO SETTLE. Headless already proves a RouteIntent occupies MOVE, the applier
builds a RouteExecutor, and a terminal ActOutcome.done becomes COMPLETE. Windows live COMPLETE
is the remaining claim: the production tool, on a ticking client, walks the player to a named
block and act_status reports COMPLETE with "route complete:" -- not the empty-plan COMPLETE
("nothing to do") that fires when the target is the cell the player already stands on.

WHY A PROBE AND NOT A JUnit LiveIT: GameAccess reads Minecraft.getMinecraft(), which exists only
in the game JVM. docs/debugging.md section 10. Drive via act_set + act_status so ActTickLoop
steps once per real tick. Do not loop a controller inside one eval_java.

IT REWRITES TERRAIN in a small box around the player (stone floor, air above, a wall that is
torn down afterwards). Stand somewhere disposable.

Do not resurrect scripts/live-route-drive.py (gitignored, excised). This file imports mcp_probe.

Exit codes: 0 PASS, 1 FAIL, 2 POLL-TIMEOUT, 3 SETUP.
"""

import argparse
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from mcp_probe import (  # noqa: E402
    PREAMBLE,
    Mcp,
    allow_unfocused,
    probe_in_world,
    record,
    report,
    require_ticking,
    results,
)

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

# RouteExecutor.ARRIVE_TOLERANCE is 0.7. Probe measurement is looser because we sample one
# round-trip after steering stops and walking carries momentum for a tick or two.
ARRIVED_WITHIN = 1.0
LEG_TIMEOUT_TICKS = 200
MIN_WALK_TICKS = 10


def num(text, key, default=None):
    """The float after key= in a probe reply. Whole-key on both sides (not a suffix of another)."""
    m = re.search(r"(?:^|[^A-Za-z0-9_])" + re.escape(key)
                  + r"=(-?\d+(?:\.\d+)?(?:[eE]-?\d+)?)", text or "")
    return float(m.group(1)) if m else default


def horiz(ax, az, bx, bz):
    if None in (ax, az, bx, bz):
        return None
    return ((ax - bx) ** 2 + (az - bz) ** 2) ** 0.5


def route_complete_verdict(phase, message, start_dist, end_dist, ticks,
                           min_start=4.0, max_end=ARRIVED_WITHIN, min_ticks=MIN_WALK_TICKS):
    """Did this route ARRIVE, having actually walked, through the route machine.

    COMPLETE alone is hollow: an empty plan completes with "nothing to do" on the first tick
    while the player stands still. The distinctive production string is "route complete:".
    Displacement and a lower bound on ticksActive reject a first-tick no-op that copied the
    success wording.
    """
    return (phase == "COMPLETE"
            and "route complete:" in (message or "")
            and "nothing to do" not in (message or "")
            and start_dist is not None and start_dist >= min_start
            and end_dist is not None and end_dist <= max_end
            and ticks is not None and ticks >= min_ticks)


def empty_plan_is_not_a_walk(phase, message):
    """The no-op COMPLETE must not satisfy the walk verdict."""
    return not route_complete_verdict(phase, message, 0.05, 0.05, 1, min_start=4.0)


# ===== arena =====


def read_anchor(mcp):
    return mcp.java("RouteAnchor", PREAMBLE + SERVER_PLAYER + """
        if (sp == null || sw == null) return "SETUP-NO-SERVER-PLAYER";
        if (p.ridingEntity != null || sp.ridingEntity != null)
            return "SETUP-RIDING";
        if (p.capabilities.isFlying)
            return "SETUP-FLYING (this route walks; land first)";
        if (!p.onGround) return "SETUP-AIRBORNE clientY=" + p.posY;
        double feet = p.getEntityBoundingBox().minY;
        int bx = net.minecraft.util.MathHelper.floor_double(p.posX);
        int by = net.minecraft.util.MathHelper.floor_double(feet + 0.01D);
        int bz = net.minecraft.util.MathHelper.floor_double(p.posZ);
        return "ax=" + bx + " ay=" + by + " az=" + bz
             + " x=" + p.posX + " y=" + feet + " z=" + p.posZ
             + " sx=" + sp.posX + " sz=" + sp.posZ
             + " onGround=" + p.onGround
             + " gameType=" + sp.theItemInWorldManager.getGameType();
    """).strip()


def flatten(mcp, anchor, radius, rows_per_batch=3):
    """Stone floor + three air on the SERVER. isBlockNormalCube, not isFullCube."""
    ax, ay, az = anchor
    x0, x1 = ax - radius, ax + radius
    out = []
    x = x0
    while x <= x1:
        hi = min(x + rows_per_batch - 1, x1)
        reply = mcp.java("RouteFlatten", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.block.state.IBlockState air = net.minecraft.init.Blocks.air.getDefaultState();
        net.minecraft.block.state.IBlockState floor =
            net.minecraft.init.Blocks.stone.getDefaultState();
        int cols = 0, cleared = 0, filled = 0;
        for (int bx = {x}; bx <= {hi}; bx++) {{
            for (int bz = {az - radius}; bz <= {az + radius}; bz++) {{
                cols++;
                net.minecraft.util.BlockPos below = new net.minecraft.util.BlockPos(bx, {ay - 1}, bz);
                if (!sw.getBlockState(below).getBlock().isBlockNormalCube()
                        && sw.setBlockState(below, floor, 2)) {{
                    filled++;
                }}
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
    expect = []
    x = x0
    while x <= x1:
        hi = min(x + rows_per_batch - 1, x1)
        expect.append(f"x={x}..{hi}")
        x = hi + 1
    got = [r.split(" ", 1)[0] for r in out]
    return got == expect, out


def verify_plus_x(mcp, anchor, dist):
    """The +X walk used below is clear and floored on BOTH sides."""
    ax, ay, az = anchor
    return mcp.java("RouteArena", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        int checked = 0, bad = 0;
        StringBuilder first = new StringBuilder();
        for (int s = 0; s <= {dist}; s++) {{
            net.minecraft.util.BlockPos bp = new net.minecraft.util.BlockPos({ax} + s, {ay}, {az});
            checked++;
            boolean serverClear = sw.isAirBlock(bp) && sw.isAirBlock(bp.up());
            boolean clientClear = w.isAirBlock(bp) && w.isAirBlock(bp.up());
            boolean serverFloor = sw.getBlockState(bp.down()).getBlock().isBlockNormalCube();
            boolean clientFloor = w.getBlockState(bp.down()).getBlock().isBlockNormalCube();
            if (!serverClear || !clientClear || !serverFloor || !clientFloor) {{
                bad++;
                if (first.length() < 240) {{
                    first.append(" [").append(bp.getX()).append(",").append(bp.getZ())
                         .append(" srvClear=").append(serverClear)
                         .append(" cliClear=").append(clientClear)
                         .append(" srvFloor=").append(serverFloor)
                         .append(" cliFloor=").append(clientFloor).append("]");
                }}
            }}
        }}
        return "checked=" + checked + " bad=" + bad + first;
    """).strip()


def wall_row(mcp, anchor, at, half_width, fill, height=2):
    """A stone wall across +X, two blocks high so STEP_UP is not a neighbour.

    A one-block wall at foot height looks like a STEP_UP to the planner (solid at y, air at y+1)
    and NavController cannot climb a full block (vanilla stepHeight 0.6, no jump). That is a
    different claim than "route goes around". Two blocks high makes going around the only walk
    with budget 0.
    """
    ax, ay, az = anchor
    state = ("net.minecraft.init.Blocks.stone" if fill else "net.minecraft.init.Blocks.air")
    return mcp.java("RouteWall", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.block.state.IBlockState st = {state}.getDefaultState();
        int cells = 0, serverSolid = 0;
        for (int bz = {az} - {half_width}; bz <= {az} + {half_width}; bz++) {{
            for (int dy = 0; dy < {height}; dy++) {{
                net.minecraft.util.BlockPos bp = new net.minecraft.util.BlockPos({ax + at}, {ay} + dy, bz);
                sw.setBlockState(bp, st, 2);
                cells++;
                if (sw.getBlockState(bp).getBlock().isBlockNormalCube()) serverSolid++;
            }}
        }}
        return "cells=" + cells + " serverSolid=" + serverSolid
             + " atX=" + {ax + at} + " y=" + {ay} + " height=" + {height};
    """).strip()


def wall_state(mcp, anchor, at, half_width, height=2):
    ax, ay, az = anchor
    return mcp.java("RouteWallState", PREAMBLE + SERVER_PLAYER + f"""
        if (sw == null) return "SETUP-NO-SERVER-PLAYER";
        int cells = 0, serverSolid = 0, clientSolid = 0;
        for (int bz = {az} - {half_width}; bz <= {az} + {half_width}; bz++) {{
            for (int dy = 0; dy < {height}; dy++) {{
                net.minecraft.util.BlockPos bp = new net.minecraft.util.BlockPos({ax + at}, {ay} + dy, bz);
                cells++;
                if (sw.getBlockState(bp).getBlock().isBlockNormalCube()) serverSolid++;
                if (w.getBlockState(bp).getBlock().isBlockNormalCube()) clientSolid++;
            }}
        }}
        return "cells=" + cells + " serverSolid=" + serverSolid + " clientSolid=" + clientSolid;
    """).strip()


def snapshot_pos(mcp):
    return mcp.java("RouteSnap", PREAMBLE + SERVER_PLAYER + """
        return "x=" + p.posX + " y=" + p.getEntityBoundingBox().minY + " z=" + p.posZ
             + " sx=" + (sp == null ? Double.NaN : sp.posX)
             + " sz=" + (sp == null ? Double.NaN : sp.posZ)
             + " onGround=" + p.onGround;
    """).strip()


def teleport_home(mcp, anchor):
    ax, ay, az = anchor
    reply = mcp.java("RouteHome", PREAMBLE + SERVER_PLAYER + f"""
        if (sp == null) return "SETUP-NO-SERVER-PLAYER";
        sp.setPositionAndUpdate({ax} + 0.5D, {ay}, {az} + 0.5D);
        return "sent";
    """)
    if not reply.strip().startswith("sent"):
        return "teleport rejected: " + reply.strip()[:160]
    time.sleep(0.8)
    return mcp.java("RouteHomeCheck", PREAMBLE + SERVER_PLAYER + f"""
        double cd = Math.sqrt((p.posX - ({ax} + 0.5D)) * (p.posX - ({ax} + 0.5D))
                            + (p.posZ - ({az} + 0.5D)) * (p.posZ - ({az} + 0.5D)));
        double sd = sp == null ? -1 : Math.sqrt((sp.posX - ({ax} + 0.5D)) * (sp.posX - ({ax} + 0.5D))
                            + (sp.posZ - ({az} + 0.5D)) * (sp.posZ - ({az} + 0.5D)));
        return "clientOff=" + cd + " serverOff=" + sd + " onGround=" + p.onGround;
    """).strip()


def clear_use(mcp):
    mcp.call("act_cancel", {"slots": ["interact"]})
    return mcp.java("RouteClearUse", PREAMBLE + SERVER_PLAYER + """
        if (p.isUsingItem()) mc.playerController.onStoppedUsingItem(p);
        if (sp != null && sp.isUsingItem()) sp.stopUsingItem();
        return "clientUsing=" + p.isUsingItem()
             + " serverUsing=" + (sp == null ? "n/a" : String.valueOf(sp.isUsingItem()));
    """).strip()


def require_act_ticking(mcp):
    def tick_now():
        try:
            return json.loads(mcp.call("act_status", {}).get("text", "{}")).get("tickNow")
        except ValueError:
            return None

    first = tick_now()
    time.sleep(1.0)
    second = tick_now()
    ok = first is not None and second is not None and second > first
    if not ok:
        print(f"SETUP: act tick seam not advancing (tickNow {first} -> {second}).")
    else:
        print(f"-- act tick seam: tickNow {first} -> {second}")
    return ok


def drive_route(mcp, target, block_budget=0, timeout_ticks=LEG_TIMEOUT_TICKS, poll_s=0.4):
    """Production path: act_set move.route, poll act_status until MOVE is terminal."""
    tx, ty, tz = target
    submitted = mcp.call("act_set", {
        "move": {"route": [tx, ty, tz], "blockBudget": block_budget},
    })
    if submitted.get("isError") or "error" in submitted:
        return {"phase": "SETUP", "message": "act_set rejected: " + str(submitted)[:200],
                "ticksActive": None, "timedout": False}
    deadline = time.time() + timeout_ticks / 20.0 + 8.0
    last = None
    while time.time() < deadline:
        time.sleep(poll_s)
        raw = mcp.call("act_status", {})
        try:
            st = json.loads(raw.get("text", "{}"))
        except ValueError:
            continue
        slot = next((s for s in st.get("slots", []) if s.get("slot") == "move"), None)
        if slot is None:
            continue
        last = slot
        if slot.get("phase") in ("COMPLETE", "FAILED", "CANCELLED"):
            return {"phase": slot["phase"], "message": slot.get("message", ""),
                    "ticksActive": slot.get("ticksActive"), "timedout": False}
    return {"phase": "POLL-TIMEOUT", "message": (last or {}).get("message", "no status"),
            "ticksActive": (last or {}).get("ticksActive"), "timedout": True}


def run_route(mcp, anchor, dx, dz, block_budget=0):
    ax, ay, az = anchor
    target = (ax + dx, ay, az + dz)
    mcp.call("act_cancel", {"slots": ["move"]})
    used = clear_use(mcp)
    home = teleport_home(mcp, anchor)
    before = snapshot_pos(mcp)
    start = horiz(num(before, "x"), num(before, "z"), target[0] + 0.5, target[2] + 0.5)
    result = drive_route(mcp, target, block_budget=block_budget)
    after = snapshot_pos(mcp)
    end = horiz(num(after, "x"), num(after, "z"), target[0] + 0.5, target[2] + 0.5)
    return {
        "target": target,
        "before": before,
        "after": after,
        "startGap": start,
        "endGap": end,
        "result": result,
        "used": used,
        "home": home,
    }


def self_check():
    cases = [
        ("walk COMPLETE with displacement and route wording",
         route_complete_verdict("COMPLETE",
                                "route complete: 8 move(s), 0 block(s) spent, ending (8.5, 64.0, 0.5)",
                                8.0, 0.32, 47), True),
        ("empty-plan COMPLETE is not a walk",
         route_complete_verdict("COMPLETE",
                                "nothing to do: the plan was empty, so the player is already where it asked to be",
                                0.05, 0.04, 1), False),
        ("empty-plan wording inside an otherwise walking payload is rejected",
         empty_plan_is_not_a_walk("COMPLETE",
                                  "nothing to do: the plan was empty, so the player is already where it asked to be"),
         True),
        ("COMPLETE without the route wording is not a route",
         route_complete_verdict("COMPLETE", "arrived within 0.32 blocks after 47 ticks",
                                8.0, 0.32, 47), False),
        ("COMPLETE with the wording but no start gap is a no-op",
         route_complete_verdict("COMPLETE",
                                "route complete: 1 move(s), 0 block(s) spent, ending (0.5, 64.0, 0.5)",
                                0.12, 0.10, 40), False),
        ("COMPLETE with the wording but one tick is a first-tick no-op",
         route_complete_verdict("COMPLETE",
                                "route complete: 8 move(s), 0 block(s) spent, ending (8.5, 64.0, 0.5)",
                                8.0, 0.32, 1), False),
        ("FAILED is not COMPLETE",
         route_complete_verdict("FAILED",
                                "route complete: 8 move(s), 0 block(s) spent, ending (8.5, 64.0, 0.5)",
                                8.0, 0.32, 47), False),
        ("end gap still open is not arrival",
         route_complete_verdict("COMPLETE",
                                "route complete: 8 move(s), 0 block(s) spent, ending (4.5, 64.0, 0.5)",
                                8.0, 4.0, 47), False),
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
    ap = argparse.ArgumentParser(description="live verification of act_set move.route COMPLETE. "
                                            "REWRITES TERRAIN around the player.")
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--distance", type=int, default=8)
    ap.add_argument("--no-flatten", action="store_true")
    ap.add_argument("--allow-unfocused", action="store_true")
    ap.add_argument("--self-check", action="store_true")
    args = ap.parse_args()

    if args.self_check:
        print("-- verdict self-check (no game)\n")
        return self_check()

    dist = args.distance
    radius = dist + 4
    mcp = Mcp(args.port, client_name="live-route-probe")
    print(f"-- route probe on port {args.port}, walk of {dist} blocks\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {args.port}: {ping['error']}")
        print("       start the client first: scripts\\run-mcp.bat")
        return 3

    if not probe_in_world(mcp):
        print("\nSETUP: no live player. Load New World and stand somewhere open, or respawn.")
        return 3

    if args.allow_unfocused:
        print(f"-- unfocused ticking: {allow_unfocused(mcp)}")
    if not require_ticking(mcp, "the world is advancing before any route is measured"):
        print("\nSETUP: world not ticking. Focus the game window, or pass --allow-unfocused.")
        return 3
    if not require_act_ticking(mcp):
        return 3

    print("\n-- preconditions")
    where = read_anchor(mcp)
    print(f"        {where[:220]}")
    if where.startswith("SETUP") or where.startswith("THREW") or where.startswith("PROBE-ERROR"):
        print("\nSETUP: " + where[:300])
        return 3
    anchor = (int(num(where, "ax")), int(num(where, "ay")), int(num(where, "az")))
    print(f"        anchor block {anchor}")

    if not args.no_flatten:
        print(f"\n-- arena: flattening around {anchor}, radius {radius}. THIS DESTROYS WHAT IS THERE.")
        tiled, batches = flatten(mcp, anchor, radius)
        if not tiled:
            print("\nSETUP: flatten did not tile: " + str(batches)[:300])
            return 3
        time.sleep(1.5)
    arena = verify_plus_x(mcp, anchor, dist)
    print(f"        arena {arena[:180]}")
    if num(arena, "bad", default=-1) != 0:
        print("\nSETUP: +X ray is not walkable on both sides: " + arena[:300])
        return 3

    # 1. Open-ground walk. blockBudget 0 so a success is walking, not bridging.
    print("\n-- open walk +X through act_set move.route, blockBudget 0")
    open_leg = run_route(mcp, anchor, dist, 0, block_budget=0)
    r = open_leg["result"]
    detail = (f"gap {open_leg['startGap']} -> {open_leg['endGap']} in {r.get('ticksActive')} ticks\n"
              f"          {r['phase']}: {str(r['message'])[:180]}")
    timed_out = r["timedout"]
    record("open +X route COMPLETE with displacement (not the empty-plan COMPLETE)",
           route_complete_verdict(r["phase"], r["message"],
                                  open_leg["startGap"], open_leg["endGap"],
                                  r.get("ticksActive")),
           detail)

    # 2. Distinctive claim vs move.to: a wall across +X that a straight walk jams on.
    #    Route with budget 0 must go AROUND, still COMPLETE, still displace.
    half = 2
    at = 4
    print(f"\n-- wall at +{at} X, half-width {half}; route must go around (budget 0)")
    print(f"        built: {wall_row(mcp, anchor, at=at, half_width=half, fill=True)[:140]}")
    time.sleep(1.2)
    wst = wall_state(mcp, anchor, at=at, half_width=half)
    print(f"        state: {wst[:140]}")
    cells = num(wst, "cells")
    wall_ok = (cells is not None
               and num(wst, "serverSolid") == cells
               and num(wst, "clientSolid") == cells)
    if not wall_ok:
        wall_row(mcp, anchor, at=at, half_width=half, fill=False)
        record("route around a two-block wall COMPLETE (the claim that is not just walk-straight)",
               False, "SKIPPED-NOT-MEASURED: wall not solid on both sides: " + wst[:200])
    else:
        around = run_route(mcp, anchor, dist, 0, block_budget=0)
        ar = around["result"]
        record("route around a two-block wall COMPLETE (the claim that is not just walk-straight)",
               route_complete_verdict(ar["phase"], ar["message"],
                                      around["startGap"], around["endGap"],
                                      ar.get("ticksActive")),
               f"gap {around['startGap']} -> {around['endGap']} in {ar.get('ticksActive')} ticks\n"
               f"          {ar['phase']}: {str(ar['message'])[:180]}")
        timed_out = timed_out or ar["timedout"]
        down = wall_row(mcp, anchor, at=at, half_width=half, fill=False)
        print(f"        removed: {down[:140]}")
        time.sleep(0.8)
        after = wall_state(mcp, anchor, at=at, half_width=half)
        left = num(after, "serverSolid") or 0
        record("the wall is torn down, so the next run is not poisoned",
               left == 0, after[:160])

    code = report()
    if timed_out and code == 0:
        return 2
    if timed_out:
        return 2
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\naborted")
        sys.exit(130)
