#!/usr/bin/env python3
"""Live verification that act_plan advances on the Windows tick seam.

    scripts\\build-jars.bat
    scripts\\run-mcp.bat                         # load New World
    python -X utf8 scripts/live-act-plan-probe.py --allow-unfocused

    python -X utf8 scripts/live-act-plan-probe.py --self-check    # no game

WHAT THIS EXISTS TO SETTLE. Headless already proves the sidecar submits step 0, waits
for COMPLETE + identity, then submits step 1, and that forgetting ActTickLoop.stepPlan
leaves INTERACT empty. Windows live is the remaining claim: the production tool, on a
ticking client, runs look-then-hotbar and act_status.plan reports COMPLETE with
"plan complete" while the camera and the held slot actually moved.

WHY A PROBE AND NOT A JUnit LiveIT: GameAccess reads Minecraft.getMinecraft(). Drive
via act_plan + act_status so ActTickLoop steps once per real tick. Do not loop a
controller inside one eval_java.

THE MUTANT that gives this teeth: comment out runtime.stepPlan(tick) in ActTickLoop.
Step 0 (look) still binds and can COMPLETE; step 1 (hotbar) is never submitted; the
plan stays RUNNING; this probe must go red. Restored, it must go green again.

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
)

SERVER_PLAYER = """
        net.minecraft.entity.player.EntityPlayerMP sp = null;
        {
            net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
            if (srv != null && !srv.getConfigurationManager().getPlayerList().isEmpty()) {
                sp = srv.getConfigurationManager().getPlayerList().get(0);
            }
        }
"""

TARGET_YAW = 90.0
YAW_TOL = 8.0
POLL_S = 0.3
MAX_WAIT_S = 12.0


def wrap180(deg):
    if deg is None:
        return None
    x = (deg + 180.0) % 360.0 - 180.0
    if x <= -180.0:
        x += 360.0
    return x


def num(text, key, default=None):
    m = re.search(r"(?:^|[^A-Za-z0-9_])" + re.escape(key)
                  + r"=(-?\d+(?:\.\d+)?(?:[eE]-?\d+)?)", text or "")
    return float(m.group(1)) if m else default


def plan_complete_verdict(plan_phase, plan_message, start_slot, end_slot, target_slot,
                          start_yaw, end_yaw, target_yaw, yaw_tol=YAW_TOL):
    """Did the TWO-STEP plan finish, having actually turned AND changed the hotbar.

    COMPLETE + "plan complete" alone is hollow: a sequencer that never submitted step 1
    could still be lied about, and a look-only COMPLETE would not prove the hook that
    submits the next step. The hotbar must change to the requested slot, and yaw must
    land near the requested angle. A first-tick no-op that copied the wording fails
    both.
    """
    if plan_phase != "COMPLETE":
        return False
    if "plan complete" not in (plan_message or ""):
        return False
    if None in (start_slot, end_slot, target_slot, start_yaw, end_yaw, target_yaw):
        return False
    if int(start_slot) == int(end_slot):
        return False
    if int(end_slot) != int(target_slot):
        return False
    err = wrap180(end_yaw - target_yaw)
    return err is not None and abs(err) <= yaw_tol


def snapshot(mcp):
    return mcp.java("PlanSnap", PREAMBLE + SERVER_PLAYER + """
        int clientSlot = p.inventory.currentItem;
        int serverSlot = sp == null ? -1 : sp.inventory.currentItem;
        float yaw = net.minecraft.util.MathHelper.wrapAngleTo180_float(p.rotationYaw);
        return "slot=" + clientSlot + " serverSlot=" + serverSlot
             + " yaw=" + yaw + " pitch=" + p.rotationPitch
             + " onGround=" + p.onGround;
    """).strip()


def parse_status(raw):
    try:
        return json.loads(raw.get("text", "{}"))
    except ValueError:
        return {}


def require_act_ticking(mcp):
    def tick_now():
        st = parse_status(mcp.call("act_status", {}))
        return st.get("tickNow")

    first = tick_now()
    time.sleep(1.0)
    second = tick_now()
    ok = first is not None and second is not None and second > first
    if not ok:
        print(f"SETUP: act tick seam not advancing (tickNow {first} -> {second}).")
    else:
        print(f"-- act tick seam: tickNow {first} -> {second}")
    return ok


def drive_plan(mcp, steps, max_wait_s=MAX_WAIT_S, poll_s=POLL_S):
    submitted = mcp.call("act_plan", {"steps": steps})
    if submitted.get("isError") or "error" in submitted:
        return {"phase": "SETUP", "message": "act_plan rejected: " + str(submitted)[:240],
                "index": None, "size": None, "waitingOn": [], "timedout": False,
                "lookPhase": None, "interactPhase": None, "raw": submitted}
    deadline = time.time() + max_wait_s
    last = {}
    while time.time() < deadline:
        time.sleep(poll_s)
        st = parse_status(mcp.call("act_status", {}))
        plan = st.get("plan") or {}
        last = st
        phase = plan.get("phase")
        if phase in ("COMPLETE", "FAILED", "CANCELLED"):
            slots = {s.get("slot"): s for s in st.get("slots", [])}
            return {"phase": phase, "message": plan.get("message", ""),
                    "index": plan.get("index"), "size": plan.get("size"),
                    "waitingOn": plan.get("waitingOn") or [], "timedout": False,
                    "lookPhase": (slots.get("look") or {}).get("phase"),
                    "interactPhase": (slots.get("interact") or {}).get("phase"),
                    "raw": st}
    plan = (last.get("plan") or {})
    slots = {s.get("slot"): s for s in last.get("slots", [])}
    return {"phase": plan.get("phase", "POLL-TIMEOUT"), "message": plan.get("message", "no status"),
            "index": plan.get("index"), "size": plan.get("size"),
            "waitingOn": plan.get("waitingOn") or [], "timedout": True,
            "lookPhase": (slots.get("look") or {}).get("phase"),
            "interactPhase": (slots.get("interact") or {}).get("phase"),
            "raw": last}


def self_check():
    cases = [
        ("look then hotbar COMPLETE with displacement on both channels",
         plan_complete_verdict("COMPLETE", "plan complete", 0, 3, 3, 10.0, 90.0, 90.0), True),
        ("COMPLETE wording without a hotbar change is a no-op",
         plan_complete_verdict("COMPLETE", "plan complete", 3, 3, 3, 10.0, 90.0, 90.0), False),
        ("COMPLETE wording with the wrong slot is not the second step",
         plan_complete_verdict("COMPLETE", "plan complete", 0, 1, 3, 10.0, 90.0, 90.0), False),
        ("COMPLETE wording without yaw landing is a look that never took",
         plan_complete_verdict("COMPLETE", "plan complete", 0, 3, 3, 10.0, 12.0, 90.0), False),
        ("RUNNING is not COMPLETE even if the slot already moved",
         plan_complete_verdict("RUNNING", "running step 1/2", 0, 3, 3, 10.0, 90.0, 90.0), False),
        ("the empty sequencer COMPLETE is not this plan",
         plan_complete_verdict("COMPLETE", "nothing to do", 0, 3, 3, 10.0, 90.0, 90.0), False),
        ("FAILED is not COMPLETE",
         plan_complete_verdict("FAILED", "plan complete", 0, 3, 3, 10.0, 90.0, 90.0), False),
        ("the mutant (no stepPlan) stays RUNNING at step 0",
         plan_complete_verdict("RUNNING", "running step 0/2", 0, 0, 3, 10.0, 90.0, 90.0), False),
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
    ap = argparse.ArgumentParser(description="live verification of act_plan look-then-hotbar")
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--allow-unfocused", action="store_true")
    ap.add_argument("--self-check", action="store_true")
    args = ap.parse_args()

    if args.self_check:
        print("-- verdict self-check (no game)\n")
        return self_check()

    mcp = Mcp(args.port, client_name="live-act-plan-probe")
    print(f"-- act_plan probe on port {args.port}\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {args.port}: {ping['error']}")
        print("       start the client first: scripts\\build-jars.bat then scripts\\run-mcp.bat")
        return 3

    dry = mcp.call("act_plan", {})
    if dry.get("error") and "unknown" in str(dry.get("error", "")).lower():
        print("SETUP: act_plan is not on this JVM. Rebuild scripts\\build-jars.bat and relaunch.")
        return 3

    if not probe_in_world(mcp):
        print("\nSETUP: no live player. Load New World and stand somewhere, or respawn.")
        return 3

    if args.allow_unfocused:
        print(f"-- unfocused ticking: {allow_unfocused(mcp)}")
    if not require_ticking(mcp, "the world is advancing before any plan is measured"):
        print("\nSETUP: world not ticking. Focus the game window, or pass --allow-unfocused.")
        return 3
    if not require_act_ticking(mcp):
        return 3

    mcp.call("act_cancel", {"slots": "all"})
    time.sleep(0.4)

    before = snapshot(mcp)
    print(f"-- before {before[:200]}")
    if before.startswith("SETUP") or before.startswith("THREW") or before.startswith("PROBE-ERROR"):
        print("\nSETUP: " + before[:300])
        return 3
    start_slot = int(num(before, "slot")) if num(before, "slot") is not None else -1
    start_yaw = num(before, "yaw")
    target_slot = (start_slot + 3) % 9
    steps = [
        {"look": {"mode": "set", "yaw": TARGET_YAW, "pitch": 0.0}},
        {"interact": {"kind": "hotbar", "hotbarSlot": target_slot}},
    ]
    print(f"-- act_plan look yaw={TARGET_YAW} then hotbar {start_slot} -> {target_slot}")

    result = drive_plan(mcp, steps)
    after = snapshot(mcp)
    print(f"-- after  {after[:200]}")
    end_slot = int(num(after, "slot")) if num(after, "slot") is not None else -1
    end_yaw = num(after, "yaw")
    detail = (f"plan {result['phase']} index={result['index']} size={result['size']} "
              f"look={result['lookPhase']} interact={result['interactPhase']}\n"
              f"          {result['message'][:180]}\n"
              f"          slot {start_slot} -> {end_slot} (want {target_slot}); "
              f"yaw {start_yaw} -> {end_yaw} (want {TARGET_YAW})")
    ok = plan_complete_verdict(result["phase"], result["message"],
                               start_slot, end_slot, target_slot,
                               start_yaw, end_yaw, TARGET_YAW)
    record("look-then-hotbar plan COMPLETE with yaw landing and a real hotbar change "
           "(not the no-stepPlan RUNNING stall)",
           ok, detail)
    if result["timedout"] and not ok:
        record("the plan went terminal rather than sitting at RUNNING",
               False, "POLL-TIMEOUT: " + str(result.get("waitingOn")))

    code = report()
    if result["timedout"] and code != 0:
        return 2
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\naborted")
        sys.exit(130)
