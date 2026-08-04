#!/usr/bin/env python3
"""Live verification of the LOOK tracking mode (LookIntent.AimMode.KEEP, commit 8cea6c6).

WHAT ONLY A LIVE CLIENT CAN ANSWER. The headless suite drives LookController over FakeActuator,
whose rotation is a pair of fields. Three things it cannot speak to:

  1. Whether the rotation write LANDS. LivePlayerActuator writes rotationYaw/prevRotationYaw on the
     live EntityPlayerSP; nothing headless proves the client renders or keeps them.
  2. Whether a track RE-ASSERTS against an outside write. This is the property that makes it
     tracking rather than aiming, and it is the documented hazard: the track fights a human moving
     the mouse and overrides a server S08PacketPlayerPosLook. FakeActuator has nothing else writing
     to it, so headless cannot produce the contest at all.
  3. Whether an unbounded track really survives across real game ticks driven by the real
     ActTickLoop, rather than across loop iterations in a test.

THE HARNESS RULE THIS FILE OBEYS (debugging.md section 10, rule 1). One eval_java submission is ONE
game tick: measured, getTotalWorldTime() does not move across a 50-iteration loop inside a single
submission. So a track is never driven by calling controller.tick() in a loop -- it goes through the
PRODUCTION path (act_set to submit, act_status to poll), which lets real ticks happen between reads
and exercises the tool wiring at the same time. The hold probe learned this the expensive way: its
first version gave a controller 120 turns while the world stood still, and the controller's honest
"this is not progressing" looked like a bug in the code under test.

Usage:
  ./scripts/run-mcp.sh                              # start the client, load a world
  python3 scripts/live-look-probe.py --allow-unfocused
  python3 scripts/live-look-probe.py --self-check    # verdicts only, no game needed
"""

import argparse
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_probe import (  # noqa: E402 - the sys.path line above has to run first
    EXIT_SETUP, Mcp, PREAMBLE, allow_unfocused, probe_in_world, record, report, require_ticking,
)

# Degrees of slack when comparing a measured yaw against the angle the geometry implies.
#
# Not tight. The aim is written once per tick and the sample is taken from outside the game, so a
# reading can be one tick stale; a track on a teleporting target is exactly on the angle or one
# correction behind it. 2.0 is far below the smallest thing this probe has to distinguish -- every
# "did it follow" case below moves the target by 90 degrees.
YAW_TOL = 2.0

# How far a target must be from the player for the yaw to it to be well-conditioned.
#
# Close targets make yaw hypersensitive to sub-block position error: at 1 block, a 0.3-block drift
# swings the angle by ~17 degrees, which would put noise inside YAW_TOL. At 6 blocks the same drift
# is under 3 degrees.
TARGET_DIST = 6

# An armour stand is 1.975 blocks tall, so a stand placed at feet level has its eyes near the
# player's. Kept as a name rather than a bare number because the probe reasons about it twice.
STAND_EYE_DY = 1.6


def yaw_to(ex, ez, tx, tz):
    """Vanilla-convention yaw aiming from (ex,ez) at (tx,tz).

    The same formula LookController.anglesTo uses, restated here on purpose: a probe that asked the
    production code what the right answer was could not tell a wrong answer from a wrong question.
    The pitch term is not needed -- every case below moves the target horizontally.
    """
    return math.degrees(math.atan2(tz - ez, tx - ex)) - 90.0


def yaw_gap(a, b):
    """Shortest signed arc from a to b, in [-180, 180]. Yaw wraps; subtraction does not."""
    d = (b - a) % 360.0
    if d > 180.0:
        d -= 360.0
    return d


# ===== verdicts, each paired with the wrong ending it must reject (see self_check) =====


def following_verdict(phase, message):
    """A track that is still following: ACTIVE, and saying which of the two live states it is in.

    Phase alone is too weak. An ACTIVE LOOK slot is also what a slewing ONCE aim looks like on its
    way to the target, so the message has to name the KEEP-only state -- and both KEEP states carry
    a tick counter, which is what distinguishes a live track from a one-shot mid-slew.
    """
    if phase != "ACTIVE":
        return False
    return "holding aim" in message or ("slewing" in message and "tick " in message)


def landed_verdict(phase, message):
    """Landed AND still tracking. The whole point: arrival must not be an ending."""
    return phase == "ACTIVE" and "holding aim" in message


def cancel_verdict(phase, message, min_ticks=1):
    """A cancelled track reports CANCELLED and how long it was held.

    The tick count is the load-bearing half: an unbounded track has no other ending, so this report
    is its entire account, and a cancel that lost the number would be indistinguishable from one
    that never ran. Parsed rather than pattern-matched so a zero cannot pass as a number.
    """
    if phase != "CANCELLED" or "look cancelled after" not in message:
        return False
    n = _int_after(message, "look cancelled after")
    return n is not None and n >= min_ticks


def bounded_verdict(phase, message, bound):
    """A bounded track that held its aim: COMPLETE, naming the bound it actually reached."""
    if phase != "COMPLETE" or "tracked for" not in message:
        return False
    if "aim held on" not in message:
        return False
    return _int_after(message, "tracked for") == bound


def never_aimed_verdict(phase, message):
    """A bounded track whose slew never caught the target: FAILED, and says so in those terms.

    The dangerous ending. "tracked for N ticks" reads as success and a caller acts on it by
    swinging, so a track that never arrived must not be able to report done.
    """
    return (phase == "FAILED"
            and "never reached the target" in message
            and "degrees of yaw out" in message)


def gone_verdict(phase, message):
    """A tracked entity that disappeared: FAILED, naming it as gone rather than timing out."""
    return phase == "FAILED" and "gone" in message


def reasserted_verdict(yaw_after_shove, expected, shoved_to, tol=YAW_TOL):
    """The track pulled the aim back after something else wrote rotation.

    Two conditions, and the second is what makes it teeth: the aim must be back ON target, AND it
    must have actually MOVED from where the shove put it. Without the second, a shove that silently
    failed to take would satisfy this -- the aim would still be on target because it never left.
    """
    back_on_target = abs(yaw_gap(yaw_after_shove, expected)) <= tol
    left_the_shove = abs(yaw_gap(yaw_after_shove, shoved_to)) > tol
    return back_on_target and left_the_shove


def _int_after(text, prefix):
    """The first integer following `prefix`, or None."""
    i = text.find(prefix)
    if i < 0:
        return None
    tail = text[i + len(prefix):].strip()
    digits = ""
    for ch in tail:
        if ch.isdigit():
            digits += ch
        elif digits:
            break
        else:
            break
    return int(digits) if digits else None


# ===== live plumbing =====


def look_slot(mcp):
    """The LOOK slot's status row, or None."""
    raw = mcp.call("act_status", {})
    try:
        st = json.loads(raw.get("text", "{}"))
    except ValueError:
        return None
    return next((s for s in st.get("slots", []) if s.get("slot") == "look"), None)


def wait_for_look(mcp, predicate, max_wait_s=8.0, poll_s=0.25):
    """Poll the LOOK slot until `predicate(slot)` or the deadline. Returns the last slot seen."""
    deadline = time.time() + max_wait_s
    last = None
    while time.time() < deadline:
        slot = look_slot(mcp)
        if slot is not None:
            last = slot
            if predicate(slot):
                return slot
        time.sleep(poll_s)
    return last


def submit_look(mcp, **look):
    """act_set the LOOK slot. Returns an error string or None."""
    r = mcp.call("act_set", {"look": look})
    if r.get("isError"):
        return "act_set rejected: " + str(r.get("text"))[:200]
    if "error" in r:
        return r["error"]
    return None


def cancel_look(mcp):
    mcp.call("act_cancel", {"slots": ["look"]})
    time.sleep(0.4)


def read_pose(mcp):
    """Player eye x/z and current yaw, as floats, plus the world tick."""
    out = mcp.java("LookPose", PREAMBLE + """
        return "x=" + p.posX + " z=" + p.posZ + " yaw=" + p.rotationYaw
             + " pitch=" + p.rotationPitch + " t=" + w.getTotalWorldTime();
    """).strip()
    vals = {}
    for part in out.split():
        if "=" in part:
            k, v = part.split("=", 1)
            try:
                vals[k] = float(v)
            except ValueError:
                pass
    return vals if "yaw" in vals else None


def spawn_stand(mcp, dx, dz, name="LookProbeTarget"):
    """Spawn an armour stand on the SERVER, dx/dz from the player, and return its CLIENT entity id.

    Server-side rather than client-side, and the difference is not cosmetic: a client-only entity
    would be visible to LivePlayerActuator (which resolves ids against the client world) while being
    something no real caller could ever track. Spawning on the integrated server lets the entity
    reach the client the way every real entity does, through a spawn packet, so the id this probe
    tracks is a real one.

    Armour stand rather than a mob: it does not walk, so every movement in this probe is one the
    probe made deliberately. A wandering mob would make "did the aim follow" depend on where the mob
    chose to go.
    """
    out = mcp.java("LookSpawn", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        if (srv == null) return "NO-SERVER";
        net.minecraft.world.WorldServer ws = srv.worldServerForDimension(w.provider.getDimensionId());
        if (ws == null) return "NO-WORLDSERVER";
        net.minecraft.entity.item.EntityArmorStand stand =
            new net.minecraft.entity.item.EntityArmorStand(ws,
                p.posX + %f, p.posY, p.posZ + %f);
        stand.setCustomNameTag("%s");
        stand.setAlwaysRenderNameTag(false);
        if (!ws.spawnEntityInWorld(stand)) return "SPAWN-REFUSED";
        return "SPAWNED serverId=" + stand.getEntityId()
             + " at=" + stand.posX + "," + stand.posY + "," + stand.posZ;
    """ % (float(dx), float(dz), name))
    if not out.strip().startswith("SPAWNED"):
        return None, out.strip()

    # The id has to be confirmed on the CLIENT: that is the world LivePlayerActuator resolves
    # against, and the spawn packet takes a tick or two to arrive.
    deadline = time.time() + 5.0
    while time.time() < deadline:
        found = mcp.java("LookFind", PREAMBLE + """
            for (Object o : new java.util.ArrayList<Object>(w.loadedEntityList)) {
                if (o instanceof net.minecraft.entity.item.EntityArmorStand) {
                    net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) o;
                    if ("%s".equals(e.getCustomNameTag()))
                        return "FOUND id=" + e.getEntityId()
                             + " at=" + e.posX + "," + e.posY + "," + e.posZ;
                }
            }
            return "NOT-YET";
        """ % name).strip()
        if found.startswith("FOUND"):
            return _int_after(found, "id="), found
        time.sleep(0.3)
    return None, "the spawned stand never reached the client world"


def move_stand(mcp, entity_id, dx, dz):
    """Teleport the stand to dx/dz from the player, on the server, and wait for the client to agree.

    Server-side teleport so the movement propagates as a real S18PacketEntityTeleport rather than
    being written into the client's copy -- the same reason the spawn is server-side. Returns the
    client-visible position once it has moved, so the caller compares against what the aim could
    actually have seen.
    """
    out = mcp.java("LookMove", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        if (srv == null) return "NO-SERVER";
        net.minecraft.world.WorldServer ws = srv.worldServerForDimension(w.provider.getDimensionId());
        net.minecraft.entity.Entity se = ws == null ? null : ws.getEntityByID(%d);
        if (se == null) return "NO-SERVER-ENTITY";
        se.setPositionAndUpdate(p.posX + %f, p.posY, p.posZ + %f);
        return "MOVED to=" + se.posX + "," + se.posZ;
    """ % (int(entity_id), float(dx), float(dz)))
    if not out.strip().startswith("MOVED"):
        return None, out.strip()
    deadline = time.time() + 5.0
    while time.time() < deadline:
        pos = client_entity_pos(mcp, entity_id)
        if pos and abs(pos["ex"] - (pos["px"] + dx)) < 0.6 and abs(pos["ez"] - (pos["pz"] + dz)) < 0.6:
            return pos, "client agrees"
        time.sleep(0.3)
    return client_entity_pos(mcp, entity_id), "client never caught up to the teleport"


def client_entity_pos(mcp, entity_id):
    """The entity's position as the CLIENT sees it, alongside the player's, in one sample.

    One snippet for both so they are from the same tick: comparing an entity read now against a
    player read a moment ago is how a probe invents a discrepancy that the game never had.
    """
    out = mcp.java("LookEntPos", PREAMBLE + """
        net.minecraft.entity.Entity e = w.getEntityByID(%d);
        if (e == null) return "GONE";
        net.minecraft.util.Vec3 eye = e.getPositionEyes(1.0F);
        return "ex=" + eye.xCoord + " ey=" + eye.yCoord + " ez=" + eye.zCoord
             + " px=" + p.posX + " py=" + (p.posY + p.getEyeHeight()) + " pz=" + p.posZ
             + " yaw=" + p.rotationYaw;
    """ % int(entity_id)).strip()
    if out == "GONE" or "ex=" not in out:
        return None
    vals = {}
    for part in out.split():
        if "=" in part:
            k, v = part.split("=", 1)
            try:
                vals[k] = float(v)
            except ValueError:
                pass
    return vals


def kill_stand(mcp, entity_id):
    return mcp.java("LookKill", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        net.minecraft.world.WorldServer ws = srv == null ? null
            : srv.worldServerForDimension(w.provider.getDimensionId());
        net.minecraft.entity.Entity se = ws == null ? null : ws.getEntityByID(%d);
        if (se != null) se.setDead();
        return "KILLED";
    """ % int(entity_id)).strip()


def shove_yaw(mcp, to_yaw):
    """Write rotation from OUTSIDE the track, the way a mouse move or a server packet does.

    This is the contest headless cannot stage. Written directly to the same fields
    LivePlayerActuator writes, because that is precisely what the two real competitors do:
    EntityPlayerSP's mouse handling adds to rotationYaw, and S08PacketPlayerPosLook assigns it.
    """
    return mcp.java("LookShove", PREAMBLE + """
        p.rotationYaw = %ff;
        p.prevRotationYaw = %ff;
        return "SHOVED yaw=" + p.rotationYaw;
    """ % (float(to_yaw), float(to_yaw))).strip()


def clear_look(mcp):
    """Leave the LOOK slot idle between probes, so one probe's track cannot survive into the next."""
    cancel_look(mcp)


# ===== the probes =====


def probe_rotation_seam_writes_and_reads_back(mcp):
    """The step no headless test can speak to: does the rotation write actually land.

    FakeActuator's yaw is a field it owns. The real one writes four fields on the live
    EntityPlayerSP, and prev==cur on a snap is what stops the client rendering an interpolated
    whip-around. If this fails, every track below is built on sand.
    """
    out = mcp.java("LookSeam", PREAMBLE + """
        net.marcloud.mcp.core.drivers.act.ActActuator act =
            new net.marcloud.mcp.core.drivers.act.LivePlayerActuator(
                new net.marcloud.mcp.core.GameAccess());
        float wasYaw = p.rotationYaw, wasPitch = p.rotationPitch;
        act.setRotation(12.5f, -7.25f);
        String snap = "yaw=" + p.rotationYaw + " prevYaw=" + p.prevRotationYaw
                    + " pitch=" + p.rotationPitch + " readBack=" + act.yaw();
        act.setRotationInterp(1f, 2f, 3f, 4f);
        String interp = "yaw=" + p.rotationYaw + " prevYaw=" + p.prevRotationYaw;
        p.rotationYaw = wasYaw; p.rotationPitch = wasPitch;
        p.prevRotationYaw = wasYaw; p.prevRotationPitch = wasPitch;
        return "snap[" + snap + "] interp[" + interp + "]";
    """).strip()
    ok = ("snap[yaw=12.5 prevYaw=12.5" in out
          and "readBack=12.5" in out
          and "interp[yaw=3.0 prevYaw=1.0" in out)
    return record("rotation writes land on the live player and read back "
                  "(snap sets prev==cur, interp does not)", ok, out[:220])


def probe_default_aim_stops_correcting(mcp, entity_id):
    """The BASELINE, and it has to be established live or the next probe proves nothing.

    A default (ONCE) aim ends the tick it lands and nothing corrects it afterwards -- which is
    correct, and is exactly why aiming at a mob leaves the crosshair where the mob used to be. If
    this probe cannot demonstrate the old behaviour on the live client, then a passing track probe
    might just mean the target never moved.
    """
    clear_look(mcp)
    before = client_entity_pos(mcp, entity_id)
    if not before:
        return record("a default aim stops correcting once it lands", False,
                      "SKIPPED-NOT-MEASURED: the target entity is not on the client")

    err = submit_look(mcp, mode="look_at", entityId=entity_id)
    if err:
        return record("a default aim stops correcting once it lands", False, err)
    slot = wait_for_look(mcp, lambda s: s["phase"] in ("COMPLETE", "FAILED", "CANCELLED"))
    if not slot or slot["phase"] != "COMPLETE":
        return record("a default aim stops correcting once it lands", False,
                      f"expected COMPLETE, got {slot and slot['phase']}: "
                      f"{slot and slot.get('message')}")

    aimed = read_pose(mcp)
    moved, note = move_stand(mcp, entity_id, TARGET_DIST, TARGET_DIST)
    time.sleep(1.0)
    after = read_pose(mcp)
    if not (aimed and after and moved):
        return record("a default aim stops correcting once it lands", False,
                      f"could not sample: {note}")

    drift = abs(yaw_gap(aimed["yaw"], after["yaw"]))
    expected_now = yaw_to(moved["px"], moved["pz"], moved["ex"], moved["ez"])
    still_wrong = abs(yaw_gap(after["yaw"], expected_now))
    ok = drift <= YAW_TOL and still_wrong > 10.0
    return record("a default aim stops correcting once it lands (so the crosshair is left behind)",
                  ok, f"aim stayed at {aimed['yaw']:.1f} (drift {drift:.2f}) while the target moved "
                      f"to yaw {expected_now:.1f}, leaving it {still_wrong:.1f} degrees off. "
                      f"phase={slot['phase']}")


def probe_track_follows_a_moving_target(mcp, entity_id):
    """THE regression, live: the target moves after the aim landed, and the aim follows."""
    clear_look(mcp)
    err = submit_look(mcp, mode="look_at", entityId=entity_id, track=True)
    if err:
        return record("a track follows a target that moves after the aim landed", False, err)

    slot = wait_for_look(mcp, lambda s: landed_verdict(s["phase"], s.get("message", "")))
    if not slot or not landed_verdict(slot["phase"], slot.get("message", "")):
        clear_look(mcp)
        return record("a track follows a target that moves after the aim landed", False,
                      f"the track never reported holding aim: phase={slot and slot['phase']} "
                      f"msg={slot and slot.get('message')}")

    # Two moves, opposite directions, so a stale-but-lucky reading cannot pass twice.
    checks = []
    for dx, dz in ((TARGET_DIST, TARGET_DIST), (-TARGET_DIST, TARGET_DIST)):
        moved, note = move_stand(mcp, entity_id, dx, dz)
        if not moved:
            checks.append((False, f"could not move the target: {note}"))
            continue
        time.sleep(1.0)
        pos = client_entity_pos(mcp, entity_id)
        if not pos:
            checks.append((False, "the target vanished mid-probe"))
            continue
        expected = yaw_to(pos["px"], pos["pz"], pos["ex"], pos["ez"])
        gap = abs(yaw_gap(pos["yaw"], expected))
        checks.append((gap <= YAW_TOL,
                       f"target at ({dx:+d},{dz:+d}) wants yaw {expected:.1f}, "
                       f"aim is {pos['yaw']:.1f} (off by {gap:.2f})"))

    still = look_slot(mcp)
    alive = still is not None and following_verdict(still["phase"], still.get("message", ""))
    clear_look(mcp)

    ok = all(c[0] for c in checks) and alive
    return record("a track follows a target that moves after the aim landed", ok,
                  " | ".join(c[1] for c in checks)
                  + f" | still tracking: {alive} ({still and still.get('message')})")


def probe_track_reasserts_against_an_outside_write(mcp):
    """The property headless CANNOT reach, and the documented hazard in the same breath.

    A track writes rotation every tick, so it wins against anything else writing rotation -- a human
    on the mouse, or the server's S08PacketPlayerPosLook. FakeActuator has no competitor, so the
    contest cannot be staged there at all. Uses a BLOCK target, not the entity: a fixed target means
    the expected yaw is a constant, so a recovery cannot be confused with the target having moved.
    """
    clear_look(mcp)
    anchor = mcp.java("LookAnchor", PREAMBLE + """
        int bx = (int) Math.floor(p.posX) + %d;
        int bz = (int) Math.floor(p.posZ);
        int by = (int) Math.floor(p.posY);
        return "bx=" + bx + " by=" + by + " bz=" + bz
             + " px=" + p.posX + " pz=" + p.posZ;
    """ % TARGET_DIST).strip()
    vals = {}
    for part in anchor.split():
        if "=" in part:
            k, v = part.split("=", 1)
            try:
                vals[k] = float(v)
            except ValueError:
                pass
    if "bx" not in vals:
        return record("a track re-asserts the aim after something else writes rotation", False,
                      f"could not pick a block target: {anchor[:160]}")

    block = [int(vals["bx"]), int(vals["by"]), int(vals["bz"])]
    err = submit_look(mcp, mode="look_at", block=block, track=True)
    if err:
        return record("a track re-asserts the aim after something else writes rotation", False, err)

    slot = wait_for_look(mcp, lambda s: landed_verdict(s["phase"], s.get("message", "")))
    if not slot or not landed_verdict(slot["phase"], slot.get("message", "")):
        clear_look(mcp)
        return record("a track re-asserts the aim after something else writes rotation", False,
                      f"the track never held aim: {slot and slot.get('message')}")

    on_target = read_pose(mcp)
    shoved_to = on_target["yaw"] + 90.0
    shove = shove_yaw(mcp, shoved_to)
    time.sleep(0.8)   # several real ticks
    after = read_pose(mcp)
    still = look_slot(mcp)
    clear_look(mcp)

    if not after:
        return record("a track re-asserts the aim after something else writes rotation", False,
                      "could not read the pose back")

    expected = yaw_to(vals["px"], vals["pz"], block[0] + 0.5, block[2] + 0.5)
    ok = reasserted_verdict(after["yaw"], expected, shoved_to)
    return record("a track re-asserts the aim after something else writes rotation "
                  "(the mouse/server contest headless cannot stage)", ok,
                  f"held {on_target['yaw']:.1f}, shoved to {shoved_to:.1f} ({shove[:60]}), "
                  f"recovered to {after['yaw']:.1f}, target yaw {expected:.1f}. "
                  f"still tracking: {still and still.get('phase')}")


def probe_unbounded_track_survives_many_real_ticks(mcp, entity_id):
    """An unbounded track stays ACTIVE across real ticks, and its own tick count grows.

    The count is what separates "still ACTIVE" from "restarted every tick": a controller rebuilt on
    each tick would report tick 1 forever while still looking perfectly alive from the outside.
    """
    clear_look(mcp)
    err = submit_look(mcp, mode="look_at", entityId=entity_id, track=True)
    if err:
        return record("an unbounded track survives many real game ticks", False, err)

    first = wait_for_look(mcp, lambda s: following_verdict(s["phase"], s.get("message", "")))
    if not first:
        clear_look(mcp)
        return record("an unbounded track survives many real game ticks", False, "never started")
    t0 = first.get("ticksActive")
    time.sleep(4.0)
    later = look_slot(mcp)
    clear_look(mcp)

    if not later:
        return record("an unbounded track survives many real game ticks", False, "no status")
    t1 = later.get("ticksActive")
    grew = isinstance(t0, int) and isinstance(t1, int) and t1 > t0 + 20
    ok = following_verdict(later["phase"], later.get("message", "")) and grew
    return record("an unbounded track survives many real game ticks (and is ONE controller, "
                  "not one per tick)", ok,
                  f"ticksActive {t0} -> {t1} over ~4s, phase={later['phase']}, "
                  f"msg={later.get('message')}")


def probe_cancel_frees_the_slot(mcp, entity_id):
    """An unbounded track's only caller-side ending: cancel must end it AND free the slot."""
    clear_look(mcp)
    err = submit_look(mcp, mode="look_at", entityId=entity_id, track=True)
    if err:
        return record("cancelling a track ends it and frees the slot", False, err)
    if not wait_for_look(mcp, lambda s: following_verdict(s["phase"], s.get("message", ""))):
        clear_look(mcp)
        return record("cancelling a track ends it and frees the slot", False, "never started")

    time.sleep(1.0)
    mcp.call("act_cancel", {"slots": ["look"]})
    ended = wait_for_look(mcp, lambda s: s["phase"] in ("CANCELLED", "COMPLETE", "FAILED"))
    if not ended:
        return record("cancelling a track ends it and frees the slot", False, "no terminal status")
    cancelled = cancel_verdict(ended["phase"], ended.get("message", ""))

    # And the slot is genuinely free: a plain aim now runs to COMPLETE on it.
    pose = read_pose(mcp)
    target = (pose["yaw"] + 40.0) if pose else 40.0
    submit_look(mcp, mode="set", yaw=target, pitch=0.0)
    took = wait_for_look(mcp, lambda s: s["phase"] in ("COMPLETE", "FAILED", "CANCELLED"))
    after = read_pose(mcp)
    reused = (took is not None and took["phase"] == "COMPLETE" and after is not None
              and abs(yaw_gap(after["yaw"], target)) <= YAW_TOL)

    return record("cancelling a track ends it and frees the slot for the next aim",
                  cancelled and reused,
                  f"cancel: {ended['phase']} '{ended.get('message')}' | "
                  f"replacement aim: {took and took['phase']} -> yaw "
                  f"{after and after['yaw']:.1f} (wanted {target:.1f})")


def probe_bounded_track_completes_at_its_bound(mcp, entity_id):
    """A bounded track ends COMPLETE naming the bound, having actually held the aim."""
    clear_look(mcp)
    bound = 40
    err = submit_look(mcp, mode="look_at", entityId=entity_id, track=True, durationTicks=bound)
    if err:
        return record("a bounded track completes at its own tick bound", False, err)
    ended = wait_for_look(mcp, lambda s: s["phase"] in ("COMPLETE", "FAILED", "CANCELLED"),
                          max_wait_s=12.0)
    if not ended:
        clear_look(mcp)
        return record("a bounded track completes at its own tick bound", False, "no terminal status")
    ok = bounded_verdict(ended["phase"], ended.get("message", ""), bound)
    return record("a bounded track completes at its own tick bound", ok,
                  f"{ended['phase']}: {ended.get('message')}")


def probe_a_slew_too_slow_fails_rather_than_claiming_it_aimed(mcp, entity_id):
    """The dangerous ending, live: a bound reached without ever arriving must FAIL.

    Staged by pointing the player away from the target and capping the slew far too low to cover the
    gap within the bound. "tracked for N ticks" reads as success and a caller acts on it by swinging.
    """
    clear_look(mcp)
    pos = client_entity_pos(mcp, entity_id)
    if not pos:
        return record("a track whose slew never caught the target fails honestly", False,
                      "SKIPPED-NOT-MEASURED: no target on the client")
    want = yaw_to(pos["px"], pos["pz"], pos["ex"], pos["ez"])
    # Point 150 degrees away, then allow 0.5 deg/tick for 20 ticks = 10 degrees of travel.
    shove_yaw(mcp, want + 150.0)
    err = submit_look(mcp, mode="look_at", entityId=entity_id, track=True,
                      slewDegPerTick=0.5, durationTicks=20)
    if err:
        return record("a track whose slew never caught the target fails honestly", False, err)
    ended = wait_for_look(mcp, lambda s: s["phase"] in ("COMPLETE", "FAILED", "CANCELLED"),
                          max_wait_s=12.0)
    if not ended:
        clear_look(mcp)
        return record("a track whose slew never caught the target fails honestly", False,
                      "no terminal status")
    ok = never_aimed_verdict(ended["phase"], ended.get("message", ""))
    return record("a track whose slew never caught the target fails rather than claiming it aimed",
                  ok, f"{ended['phase']}: {ended.get('message')}")


def probe_losing_the_target_fails_honestly(mcp, entity_id):
    """Killing the tracked entity ends the track FAILED and says it is gone."""
    clear_look(mcp)
    err = submit_look(mcp, mode="look_at", entityId=entity_id, track=True)
    if err:
        return record("losing the tracked entity fails honestly", False, err)
    if not wait_for_look(mcp, lambda s: following_verdict(s["phase"], s.get("message", ""))):
        clear_look(mcp)
        return record("losing the tracked entity fails honestly", False, "never started")

    time.sleep(0.8)
    kill_stand(mcp, entity_id)
    ended = wait_for_look(mcp, lambda s: s["phase"] in ("FAILED", "COMPLETE", "CANCELLED"))
    if not ended:
        clear_look(mcp)
        return record("losing the tracked entity fails honestly", False, "no terminal status")
    ok = gone_verdict(ended["phase"], ended.get("message", ""))
    return record("losing the tracked entity fails honestly (FAILED, named as gone)", ok,
                  f"{ended['phase']}: {ended.get('message')}")


def probe_durationticks_without_track_is_rejected(mcp):
    """The wire guard, on the real tool: a duration without track must be refused, not discarded."""
    r = mcp.call("act_set", {"look": {"mode": "set", "yaw": 0, "durationTicks": 40}})
    text = str(r.get("text", ""))
    ok = bool(r.get("isError")) and "durationTicks" in text and "track" in text
    clear_look(mcp)
    return record("act_set rejects durationTicks without track rather than silently ignoring it",
                  ok, text[:200])


def require_act_ticking(mcp):
    """The act layer only steps on the runTick seam; a still clock means nothing below runs.

    Taken from live-nav-probe.py rather than rewritten -- same guard, same reason. Without it every
    intent is accepted and sits at IDLE, and this probe would read that as a track that never
    followed anything.
    """
    def tick_now():
        try:
            return json.loads(mcp.call("act_status", {}).get("text", "{}")).get("tickNow")
        except ValueError:
            return None

    first = tick_now()
    time.sleep(1.0)
    second = tick_now()
    ok = first is not None and second is not None and second > first
    if ok:
        print(f"-- act tick seam: tickNow {first} -> {second}")
    else:
        print(f"SETUP: act tick seam not advancing (tickNow {first} -> {second}). Every intent "
              "would sit at IDLE forever, and nothing below would measure the code.")
    return ok


def self_check():
    """Prove the verdicts reject the endings they exist to reject. No game required.

    Every case below is a real status this probe can receive, paired with the wrong ending it must be
    told apart from. This is where a hollow verdict would hide: `phase == "ACTIVE"` alone would pass
    a slewing ONCE aim as a live track, and `"tracked for" in message` alone would pass the FAILED
    never-arrived ending as a success.
    """
    holding = "holding aim on yaw=-90.0 pitch=-0.0 (tick 12)"
    slewing_track = "slewing toward yaw=-90.0 (now -10.0, tick 4/20)"
    slewing_once = "slewing toward yaw=-90.0 (now -10.0)"
    cancelled = "look cancelled after 37 ticks"
    bounded_ok = "tracked for 40 ticks, aim held on yaw=-90.0 pitch=-0.0"
    never = ("tracked for 20 ticks but the crosshair never reached the target, still 140.0 degrees "
             "of yaw out -- at 0.5 deg/tick the aim could not catch it, so do not read this as "
             "having looked at it")
    gone = "look target entity 214 is gone after 16 ticks of tracking"

    cases = [
        ("a holding track is following", following_verdict("ACTIVE", holding), True),
        ("a slewing TRACK is following", following_verdict("ACTIVE", slewing_track), True),
        ("a slewing ONCE aim is NOT a live track",
         following_verdict("ACTIVE", slewing_once), False),
        ("a COMPLETE aim is not following", following_verdict("COMPLETE", holding), False),
        ("landed means holding, not merely active", landed_verdict("ACTIVE", holding), True),
        ("landed rejects a mid-slew track", landed_verdict("ACTIVE", slewing_track), False),

        ("a real cancel carries its tick count", cancel_verdict("CANCELLED", cancelled), True),
        ("a cancel without a count is rejected",
         cancel_verdict("CANCELLED", "look cancelled"), False),
        ("a zero-tick cancel is rejected",
         cancel_verdict("CANCELLED", "look cancelled after 0 ticks"), False),
        ("COMPLETE is not a cancel", cancel_verdict("COMPLETE", cancelled), False),

        ("a bounded track that held its aim is accepted",
         bounded_verdict("COMPLETE", bounded_ok, 40), True),
        ("the wrong bound is rejected", bounded_verdict("COMPLETE", bounded_ok, 20), False),
        ("THE DANGEROUS ONE: never-arrived must not pass as a bounded success",
         bounded_verdict("FAILED", never, 20), False),
        ("a bounded success without 'aim held' is rejected",
         bounded_verdict("COMPLETE", "tracked for 40 ticks", 40), False),

        ("the never-arrived ending is recognised", never_aimed_verdict("FAILED", never), True),
        ("a real success is not the never-arrived ending",
         never_aimed_verdict("COMPLETE", bounded_ok), False),

        ("a gone target is recognised", gone_verdict("FAILED", gone), True),
        ("a timeout is not a gone target",
         gone_verdict("FAILED", "tracked for 20 ticks but the crosshair never reached"), False),

        # Re-assertion: back on target AND actually moved off the shove.
        ("a recovered aim is accepted", reasserted_verdict(-90.0, -90.5, 0.0), True),
        ("an aim still sitting where it was shoved is rejected",
         reasserted_verdict(0.0, -90.0, 0.0), False),
        ("A SHOVE THAT NEVER TOOK is rejected, though the aim is on target",
         reasserted_verdict(-90.0, -90.0, -90.0), False),

        # Yaw arithmetic, since every comparison above rides on it.
        ("yaw_gap takes the short arc across the wrap",
         abs(yaw_gap(179.0, -179.0) - 2.0) < 1e-6, True),
        ("yaw_gap is signed the other way too",
         abs(yaw_gap(-179.0, 179.0) + 2.0) < 1e-6, True),
        ("yaw_to points at +Z as vanilla's 0",
         abs(yaw_gap(yaw_to(0, 0, 0, 5), 0.0)) < 1e-6, True),
        ("yaw_to points at +X as vanilla's -90",
         abs(yaw_gap(yaw_to(0, 0, 5, 0), -90.0)) < 1e-6, True),
        ("_int_after reads the number after its prefix",
         _int_after("look cancelled after 37 ticks", "look cancelled after") == 37, True),
        ("_int_after returns None when the prefix is absent",
         _int_after("nothing here", "look cancelled after") is None, True),
    ]
    for name, got, want in cases:
        record(name, got == want, "" if got == want else f"got {got}, wanted {want}")
    return report()


def main():
    ap = argparse.ArgumentParser(
        description="live verification of the LOOK tracking mode (AimMode.KEEP)")
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--allow-unfocused", action="store_true",
                    help="clear pauseOnLostFocus so the world keeps ticking without window focus")
    ap.add_argument("--self-check", action="store_true",
                    help="run the verdict self-checks only; no client needed")
    args = ap.parse_args()

    if args.self_check:
        return self_check()

    mcp = Mcp(args.port, client_name="live-look-probe")
    print(f"-- LOOK tracking probe on port {args.port}\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {args.port}: {ping['error']}")
        print("       start the client first: ./scripts/run-mcp.sh")
        return EXIT_SETUP

    if not probe_in_world(mcp):
        print("\nSETUP: not in a world. Load a world and re-run.")
        return EXIT_SETUP

    if args.allow_unfocused:
        print(f"-- unfocused ticking: {allow_unfocused(mcp)}")
    if not require_ticking(mcp, "the world is advancing before any track is measured"):
        print("\nSETUP: world not ticking; nothing below would measure the code. "
              "Focus the window or pass --allow-unfocused.")
        return EXIT_SETUP
    if not require_act_ticking(mcp):
        return EXIT_SETUP

    print("\n-- the rotation seam itself")
    probe_rotation_seam_writes_and_reads_back(mcp)

    print("\n-- the wire guard")
    probe_durationticks_without_track_is_rejected(mcp)

    print("\n-- staging a target")
    entity_id, note = spawn_stand(mcp, TARGET_DIST, 0)
    print(f"   {note}")
    if entity_id is None:
        print("\nSETUP: could not stage a trackable entity; the entity probes below need one.")
        print("-- the block-target probes can still run")
        probe_track_reasserts_against_an_outside_write(mcp)
        return report()

    print("\n-- the baseline: a default aim does NOT follow")
    probe_default_aim_stops_correcting(mcp, entity_id)

    print("\n-- tracking")
    probe_track_follows_a_moving_target(mcp, entity_id)
    probe_unbounded_track_survives_many_real_ticks(mcp, entity_id)
    probe_track_reasserts_against_an_outside_write(mcp)

    print("\n-- endings")
    probe_cancel_frees_the_slot(mcp, entity_id)
    probe_bounded_track_completes_at_its_bound(mcp, entity_id)
    probe_a_slew_too_slow_fails_rather_than_claiming_it_aimed(mcp, entity_id)
    probe_losing_the_target_fails_honestly(mcp, entity_id)

    clear_look(mcp)
    return report()


if __name__ == "__main__":
    sys.exit(main())
