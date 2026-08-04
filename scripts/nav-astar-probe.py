#!/usr/bin/env python3
"""Does vanilla's A* run for the CLIENT player? The experiment that decides Fork B.

docs/agency/command-to-action.md section 5 Fork B asks whether navigation should wrap vanilla's
shipped pathfinder or write a local steerer over LocalGrid. Vanilla ships a complete A* that a
decade of mobs have exercised, but it has never been run for EntityPlayerSP here, and no agent can
settle it -- it needs a live client.

Why this is a script and not a JUnit LiveIT: GameAccess reads Minecraft.getMinecraft(), a static
singleton that only exists in the GAME's JVM. A surefire JVM sees null, so isInWorld() is false and
an assume-gated test skips forever without probing anything. DigLiveIT has the same shape and there
is no evidence it has ever actually run. Live verification in this repo goes through the MCP socket
and eval_java, the way scripts/live-dwm-probe.py does.

What reading already settled, so this does not test it: createEntityPathTo calls initProcessor
itself (PathFinder.java:44) and NodeProcessor.initProcessor sizes the entity from the entity
(NodeProcessor.java:17), so entitySizeX/Y/Z need no manual setup -- that was the main suspected
blocker. WalkNodeProcessor's flags (canEnterDoors, canBreakDoors, avoidsWater, canSwim) have no
setters and default to false: doors treated as closed, water passable but not preferred.

What it does NOT prove: that a path can be FOLLOWED. Producing nodes and walking them are
different problems, and following needs the closed-loop locomotion MOVE does not have (MoveApplier
is a pure lifecycle counter). This answers "is the routing engine reusable", nothing more.

Usage:
    ./scripts/run-mcp.sh            # then get in a world, stand somewhere open
    python3 scripts/nav-astar-probe.py [--port 25599] [--offset 12]

The socket client, the eval_java wrapper, the ticking guard and the record/report harness live in
scripts/mcp_probe.py, shared with the other live probes. Exit codes follow smoke-live-gl.sh:
0 PASS, 1 FAIL, 2 TIMEOUT, 3 SETUP.
"""

import argparse
import json
import os
import sys
import time

# scripts/ is not on sys.path when this file is loaded BY PATH -- which live-hold-probe.py and
# test_probe_framing.py both do, because the hyphen in the filename rules out a plain import.
# Running it directly puts scripts/ on the path already; being imported does not.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_probe import (  # noqa: E402 - the sys.path line above has to run first
    EXIT_SETUP, Mcp, PREAMBLE, allow_unfocused, probe_in_world, record, report, require_ticking,
)


def probe_path(mcp, offset):
    """The whole question: does createEntityPathTo return a usable path for the client player."""
    out = mcp.java("NavPath", PREAMBLE + f"""
        net.minecraft.util.BlockPos to = from.add({offset}, 0, {offset});
        // Pad the cache a chunk beyond the query box: the processor probes neighbours of edge
        // nodes, and sub=0 means no padding of its own.
        net.minecraft.world.ChunkCache cache = new net.minecraft.world.ChunkCache(
            w, from.add(-16, -16, -16), to.add(16, 16, 16), 0);
        net.minecraft.pathfinding.PathFinder finder = new net.minecraft.pathfinding.PathFinder(
            new net.minecraft.world.pathfinder.WalkNodeProcessor());
        long t0 = System.nanoTime();
        net.minecraft.pathfinding.PathEntity path = finder.createEntityPathTo(cache, p, to, 32.0F);
        long us = (System.nanoTime() - t0) / 1000L;
        if (path == null) return "NULL-PATH to=" + to + " took=" + us + "us";
        StringBuilder sb = new StringBuilder();
        sb.append("NODES ").append(path.getCurrentPathLength())
          .append(" took=").append(us).append("us to=").append(to)
          .append(" final=").append(path.getFinalPathPoint());
        int n = Math.min(path.getCurrentPathLength(), 8);
        for (int i = 0; i < n; i++) sb.append("\\n    node ").append(i)
          .append(" -> ").append(path.getPathPointFromIndex(i));
        return sb.toString();
    """)
    print(f"        {out.strip()[:900]}")
    if out.startswith("THREW"):
        return record("vanilla A* runs for the client player", False,
                      "it THREW -- the 'wrap vanilla' branch of Fork B is dead. " + out[:400])
    if out.startswith("NULL-PATH"):
        # A null path is an ANSWER, not a crash: unreachable within range. Not a pass either,
        # because it leaves the question open -- re-run somewhere open.
        return record("vanilla A* runs for the client player", False,
                      "returned null: unreachable within 32 blocks, or the processor could not "
                      "resolve a start. Re-run standing somewhere open before concluding. " + out)
    ok = out.startswith("NODES ")
    nodes = 0
    if ok:
        try:
            nodes = int(out.split()[1])
        except (IndexError, ValueError):
            ok = False
    return record("vanilla A* runs for the client player and yields nodes", ok and nodes > 0,
                  f"nodes={nodes}" if ok else out[:300])


def probe_undersized_cache(mcp, offset):
    """Does the known IBlockAccess bypass bite outside the cached window?

    func_176170_a's rail check reads entityIn.worldObj.getBlockState directly
    (WalkNodeProcessor.java:235-237), bypassing the IBlockAccess handed in. If a deliberately tiny
    cache throws while the full one worked, any wrapper must size its cache to the whole query box
    rather than trusting the argument.
    """
    out = mcp.java("NavTiny", PREAMBLE + f"""
        net.minecraft.util.BlockPos to = from.add({offset}, 0, {offset});
        net.minecraft.world.ChunkCache tiny = new net.minecraft.world.ChunkCache(w, from, from, 0);
        net.minecraft.pathfinding.PathFinder finder = new net.minecraft.pathfinding.PathFinder(
            new net.minecraft.world.pathfinder.WalkNodeProcessor());
        net.minecraft.pathfinding.PathEntity path = finder.createEntityPathTo(tiny, p, to, 32.0F);
        return path == null ? "SURVIVED null" : "SURVIVED " + path.getCurrentPathLength() + " nodes";
    """)
    threw = out.startswith("THREW")
    return record("an undersized ChunkCache does not crash the processor", not threw,
                  ("it threw -- a wrapper MUST cover the whole query box: " + out[:300]) if threw
                  else out.strip()[:200])


def probe_walkability_codes(mcp):
    """Is func_176170_a callable from outside, and does it answer for real terrain?

    Perception track's Part A wants one walkability char per column taken from vanilla's own
    verdict rather than inventing a block taxonomy. That only works if this public static is
    genuinely callable with a player and an IBlockAccess.
    """
    out = mcp.java("NavCodes", PREAMBLE + """
        net.minecraft.world.ChunkCache cache = new net.minecraft.world.ChunkCache(
            w, from.add(-8, -8, -8), from.add(8, 8, 8), 0);
        StringBuilder sb = new StringBuilder("CODES");
        for (int dx = -2; dx <= 2; dx++) {
          sb.append("\\n    dx=").append(dx).append(":");
          for (int dy = -2; dy <= 1; dy++) {
            int c = net.minecraft.world.pathfinder.WalkNodeProcessor.func_176170_a(
                cache, p, from.getX() + dx, from.getY() + dy, from.getZ(), 1, 2, 1,
                false, false, false);
            sb.append(" dy").append(dy).append("=").append(c);
          }
        }
        return sb.toString();
    """)
    print(f"        {out.strip()[:700]}")
    return record("WalkNodeProcessor.func_176170_a is callable and answers for live terrain",
                  out.startswith("CODES"), out.strip()[:200] if not out.startswith("CODES") else "")


def probe_open_loop_straight_line(mcp):
    """Does an open-loop MOVE intent hold a straight line?

    This is the load-bearing assumption behind calling today's locomotion "awkward but not
    impossible" (docs/agency/command-to-action.md section 6, unknown 5). Submit forward for 40
    ticks, then compare the bearing actually travelled against the yaw it was facing.
    """
    if not require_ticking(mcp, "an open-loop MOVE intent actually displaces the player"):
        return False
    # A use in progress slows locomotion to 20% in vanilla (onLivingUpdate scales moveForward
    # while isUsingItem), and the use check runs just before this one -- so clear it or this
    # measures eating, not walking.
    mcp.java("ClearUse", PREAMBLE + """
        if (p.isUsingItem()) mc.playerController.onStoppedUsingItem(p);
        return "isUsing=" + p.isUsingItem();
    """)
    before = mcp.java("NavPosA", PREAMBLE + """
        return "POS " + p.posX + " " + p.posZ + " yaw=" + p.rotationYaw;
    """)
    if not before.startswith("POS "):
        return record("an open-loop MOVE intent holds a straight line", False,
                      "could not read start position: " + before.strip()[:200])

    got = mcp.call("act_set", {"move": {"forward": 1.0, "durationTicks": 40}})
    if "error" in got or got.get("isError"):
        return record("an open-loop MOVE intent holds a straight line", False,
                      "act_set rejected: " + str(got)[:250])
    # Let the game run the intent. Deliberately wall-clock: the intent is measured in ticks and
    # the probe cannot pump frames from outside.
    time.sleep(3.0)

    after = mcp.java("NavPosB", PREAMBLE + """
        return "POS " + p.posX + " " + p.posZ + " yaw=" + p.rotationYaw
             + " onGround=" + p.onGround + " collided=" + p.isCollidedHorizontally;
    """)
    mcp.call("act_cancel", {"slots": ["move"]})
    if not after.startswith("POS "):
        return record("an open-loop MOVE intent holds a straight line", False,
                      "could not read end position: " + after.strip()[:200])

    try:
        ax, az = float(before.split()[1]), float(before.split()[2])
        bx, bz = float(after.split()[1]), float(after.split()[2])
    except (IndexError, ValueError) as e:
        return record("an open-loop MOVE intent holds a straight line", False, f"unparseable: {e}")

    dist = ((bx - ax) ** 2 + (bz - az) ** 2) ** 0.5
    print(f"        moved {dist:.2f} blocks | before: {before.strip()[:90]}"
          f" | after: {after.strip()[:120]}")
    # Not asserting a bearing: the point is only whether the player moved AT ALL under an
    # open-loop intent, which is what "awkward but possible" rests on. A displacement near zero
    # means even that is false, and step 1 of the critical path becomes mandatory rather than
    # merely valuable.
    return record("an open-loop MOVE intent actually displaces the player", dist > 1.0,
                  f"moved {dist:.2f} blocks in ~40 ticks")


def probe_use_reports_started(mcp):
    """A use with a DURATION must not be reported as a rejection.

    LivePlayerActuator.useItemInAir returned sendUseItem's value, which answers "did the stack
    change" -- false for food, a bow, a potion, even though the use began. InteractController then
    failed with "use rejected in air" on a use that had started. Measured: sendUseItem false while
    getItemInUseCount went to 32.

    Survival is required: a creative player has disableDamage set, so canEat is false and the eat
    would legitimately not start. Getting that wrong once already produced a false confirmation.
    """
    if not require_ticking(mcp, "a use with a duration is not reported as a rejection"):
        return False
    setup = mcp.java("UseSetup", PREAMBLE + """
        mc.playerController.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        p.capabilities.isCreativeMode = false;
        p.capabilities.disableDamage = false;
        p.getFoodStats().setFoodLevel(6);
        p.inventory.currentItem = 0;
        p.inventory.mainInventory[0] = new net.minecraft.item.ItemStack(
            net.minecraft.init.Items.bread, 5);
        if (p.isUsingItem()) mc.playerController.onStoppedUsingItem(p);
        return "canEat=" + p.canEat(false) + " held="
             + (p.getHeldItem() == null ? "null" : p.getHeldItem().getDisplayName());
    """)
    if "canEat=true" not in setup:
        return record("a use with a duration is not reported as a rejection", False,
                      "PREMISE FAILED, so this proves nothing: " + setup.strip()[:200])

    mcp.call("act_cancel", {"slots": ["interact"]})
    mcp.call("act_set", {"interact": {"kind": "use"}})
    time.sleep(0.6)

    status = mcp.call("act_status", {}).get("text", "")
    slot = ""
    try:
        for s in json.loads(status).get("slots", []):
            if s.get("slot") == "interact":
                slot = json.dumps(s)
    except ValueError:
        slot = status[:200]
    started = mcp.java("UseAfter", PREAMBLE + """
        return "useCount=" + p.getItemInUseCount()
             + " isUsing=" + p.isUsingItem();
    """).strip()
    mcp.call("act_cancel", {"slots": ["interact"]})

    rejected = '"use rejected in air"' in slot
    print(f"        {slot[:220]}\n        {started[:120]}")
    return record("a use with a duration is not reported as a rejection", not rejected,
                  "InteractController still reports 'use rejected in air' while the use started"
                  if rejected else "reported as started")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--offset", type=int, default=12,
                    help="horizontal target offset in blocks (default 12)")
    ap.add_argument("--skip-move", action="store_true",
                    help="skip the locomotion probe, which MOVES the player")
    ap.add_argument("--allow-unfocused", action="store_true",
                    help="clear pauseOnLostFocus so the world keeps ticking in the background, "
                         "instead of needing the game window focused for the whole run")
    args = ap.parse_args()

    mcp = Mcp(args.port, client_name="nav-astar-probe")
    print(f"-- nav/A* probe on port {args.port}, target offset {args.offset}\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {args.port}: {ping['error']}")
        print("       start the client first: ./scripts/run-mcp.sh")
        return EXIT_SETUP

    print("-- is anybody home")
    if not probe_in_world(mcp):
        print("\nSETUP: not in a world. Load a world, stand somewhere open, re-run.")
        return EXIT_SETUP

    if args.allow_unfocused:
        print(f"-- unfocused ticking: {allow_unfocused(mcp)}")

    print("\n-- Fork B: can we reuse vanilla's A*")
    probe_path(mcp, args.offset)
    probe_undersized_cache(mcp, args.offset)

    print("\n-- perception: is vanilla's walkability verdict callable")
    probe_walkability_codes(mcp)

    print("\n-- the use path reports a started use honestly")
    probe_use_reports_started(mcp)

    if not args.skip_move:
        print("\n-- unknown 5: does open-loop MOVE displace the player at all")
        probe_open_loop_straight_line(mcp)

    return report()


if __name__ == "__main__":
    sys.exit(main())
