#!/usr/bin/env python3
"""Live verification of the INTERACT hold channel, over the MCP socket.

    ./scripts/run-mcp.sh &            # start the client, get into a world
    python3 scripts/live-hold-probe.py

WHY A PROBE AND NOT A JUnit LiveIT: GameAccess reads Minecraft.getMinecraft(), a static
singleton that exists only in the game's own JVM, so a forked surefire/failsafe JVM can never
satisfy it -- those ITs are honest tombstones (see core/src/test/.../LiveGameGate.java). Live
verification in this repo goes through the MCP socket and eval_java.

WHAT THIS EXISTS TO SETTLE. The hold channel's entire mechanism is live-only: a write into
KeyBinding's static hash, vanilla reading it back a tick later, and a server packet ending a
meal. Every headless test asserts against a MODEL of those rules in FakeActuator -- and that
model was wrong once already in the same direction as the controller, so the two agreed and
twenty tests stayed green while the reported outcome was the opposite of vanilla's. These
checks are the only thing that can catch that class of agreement.

Exit codes follow smoke-live-gl.sh: 0 PASS, 1 FAIL, 2 TIMEOUT, 3 SETUP.
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_nav_probe():
    """Reuse nav-astar-probe.py's socket client and guards rather than a second copy.

    A second implementation of the framing would drift, and this repo has already paid for one
    such drift: the id=2 read loop existed twice and only one copy was fixed, so the other
    silently truncated every reply larger than a single recv.
    """
    path = os.path.join(HERE, "nav-astar-probe.py")
    spec = importlib.util.spec_from_file_location("nav_astar_probe", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


NAV = _load_nav_probe()
Mcp = NAV.Mcp
record = NAV.record
require_ticking = NAV.require_ticking
allow_unfocused = NAV.allow_unfocused
PREAMBLE = NAV.PREAMBLE

# The actuator the hold controller drives, built fresh per snippet: it holds no state of its
# own, and constructing it inside the game thread keeps every read on the thread that owns it.
# The integrated server's own player object. In single player it lives in this JVM, and it is the
# authority on whether a use completes: the client predicts, the server decides and sends status
# id 9. A precondition checked only on the client can therefore be satisfied while the run is
# doomed -- which is exactly what happened on this probe's first live run.
SERVER_PLAYER = """
        net.minecraft.entity.player.EntityPlayerMP sp = null;
        {
            net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
            if (srv != null && !srv.getConfigurationManager().getPlayerList().isEmpty())
                sp = srv.getConfigurationManager().getPlayerList().get(0);
        }
"""

ACT = """
        net.marcloud.mcp.core.drivers.act.ActActuator act =
            new net.marcloud.mcp.core.drivers.act.LivePlayerActuator(
                new net.marcloud.mcp.core.GameAccess());
"""


def probe_seam_writes_and_reads_back(mcp):
    """The one step no headless test can speak to: does the key write actually land.

    FakeActuator's key state is a field. The real one is a private field inside a KeyBinding
    looked up from a static IntHashMap by keyCode, and setKeyBindState is a void that silently
    does nothing when that lookup misses -- a state a mid-session rebind can genuinely produce.
    If this fails, every hold above it is built on sand and the headless suite cannot tell.
    """
    out = mcp.java("HoldSeam", PREAMBLE + ACT + """
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        boolean before = act.useKeyHeld();
        boolean took = act.holdUseKey();
        boolean readsHeld = act.useKeyHeld();
        act.releaseUseKey();
        boolean afterRelease = act.useKeyHeld();
        return "keyCode=" + keyCode + " before=" + before + " took=" + took
             + " readsHeld=" + readsHeld + " afterRelease=" + afterRelease;
    """)
    ok = "took=true" in out and "readsHeld=true" in out and "afterRelease=false" in out
    return record("the use key can be asserted and read back, and released", ok, out.strip())


def select_hotbar(mcp, slot):
    """Put a hotbar slot in hand through the PRODUCTION path, so the server agrees.

    Writing {@code p.inventory.currentItem} directly does not: the field is client-side and the
    server only learns of a change from the C09 packet, so a directly-written slot leaves the two
    sides disagreeing about what is held. The first version of this probe did exactly that and the
    server kept eating from slot 0 while the client showed a bow -- and the resulting "no arrow
    appeared" looked like a controller failure. act_set's HOTBAR intent goes through
    HotbarController, which sends the packet.
    """
    import json as _json
    import time as _time
    mcp.call("act_set", {"interact": {"kind": "hotbar", "hotbarSlot": slot}})
    _time.sleep(0.5)
    return mcp.java("Selected", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        String serverHeld = "n/a";
        if (srv != null && !srv.getConfigurationManager().getPlayerList().isEmpty()) {
            net.minecraft.item.ItemStack s =
                srv.getConfigurationManager().getPlayerList().get(0).getHeldItem();
            serverHeld = s == null ? "empty" : s.getDisplayName();
        }
        return "client=" + (p.getHeldItem() == null ? "empty" : p.getHeldItem().getDisplayName())
             + " server=" + serverHeld;
    """).strip()


def drive_hold(mcp, hold_ticks=0, max_wait_s=12.0, poll_s=0.25):
    """Submit a hold through act_set and poll act_status until the INTERACT slot goes terminal.

    THE PRODUCTION PATH, and using it is not merely tidier -- it is the only thing that works.
    A game-thread submission runs entirely inside ONE tick: measured, getTotalWorldTime() is
    unchanged across a 50-iteration loop in one eval, and advances only between submissions. So
    the first version of this probe, which called controller.tick() 120 times inside a single
    submission, gave the controller 120 turns while the world stood still -- the count never
    decremented, the server never answered, and the controller correctly reported that the use
    was going nowhere. That honest report looked like a failure of the code under test. It is the
    same mistake this repo already recorded once, in scroll_into_view (live-verification.md
    section 8), and the same lesson: a loop on the game thread starves the very ticks it waits for.

    ActTickLoop advances the controller one step per real game tick, so polling from outside is
    what lets those ticks happen. It also exercises the act_set wiring rather than reaching past it.
    """
    args = {"interact": {"kind": "hold"}}
    if hold_ticks > 0:
        args["interact"]["holdTicks"] = hold_ticks
    submitted = mcp.call("act_set", args)
    if submitted.get("isError"):
        return {"error": "act_set rejected: " + str(submitted.get("text"))[:200]}

    import json as _json
    import time as _time
    deadline = _time.time() + max_wait_s
    last = None
    while _time.time() < deadline:
        _time.sleep(poll_s)
        raw = mcp.call("act_status", {})
        try:
            st = _json.loads(raw.get("text", "{}"))
        except ValueError:
            continue
        slot = next((s for s in st.get("slots", []) if s.get("slot") == "interact"), None)
        if slot is None:
            continue
        last = slot
        if slot.get("phase") in ("COMPLETE", "FAILED", "CANCELLED"):
            return {"phase": slot["phase"], "message": slot.get("message", ""),
                    "ticksActive": slot.get("ticksActive")}
    return {"phase": "TIMEOUT", "message": (last or {}).get("message", "no status"),
            "ticksActive": (last or {}).get("ticksActive")}


def probe_eating_completes_and_food_rises(mcp):
    """A meal must actually finish, and the caller's report must match the hunger bar.

    Before this channel a one-shot use started an eat that vanilla cancelled within a couple of
    ticks: measured, the count fell 32 -> 0 and food did not move. So the assertion that matters
    is not "it terminated" but "food went UP and the outcome said so".
    """
    print(f"     (hand: {select_hotbar(mcp, 0)})")
    before = mcp.java("EatBefore", PREAMBLE + SERVER_PLAYER + """
        net.minecraft.item.ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof net.minecraft.item.ItemFood))
            return "SETUP-NO-FOOD client held=" + (held == null ? "empty" : held.getDisplayName());
        if (!p.canEat(false)) return "SETUP-NOT-HUNGRY food=" + p.getFoodStats().getFoodLevel();
        // The SERVER decides whether a use completes, so its view is the precondition that
        // matters. Checking only the client is how the first run of this probe mistook an
        // unfed server for a broken controller.
        if (sp == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.item.ItemStack sheld = sp.getHeldItem();
        if (sheld == null || !(sheld.getItem() instanceof net.minecraft.item.ItemFood))
            return "SETUP-SERVER-NO-FOOD server held=" + (sheld == null ? "empty" : sheld.getDisplayName());
        if (!sp.canEat(false)) return "SETUP-SERVER-NOT-HUNGRY serverFood=" + sp.getFoodStats().getFoodLevel();
        return "food=" + p.getFoodStats().getFoodLevel() + " stack=" + held.stackSize
             + " serverStack=" + sheld.stackSize;
    """).strip()
    if before.startswith("SETUP"):
        return record("eating completes and the food level rises", False,
                      "SKIPPED-NOT-MEASURED: " + before)

    result = drive_hold(mcp)
    after = mcp.java("EatAfter", PREAMBLE + """
        net.minecraft.item.ItemStack held = p.getHeldItem();
        return "food=" + p.getFoodStats().getFoodLevel()
             + " stack=" + (held == null ? 0 : held.stackSize)
             + " using=" + p.isUsingItem()
             + " keyDown=" + mc.gameSettings.keyBindUseItem.isKeyDown();
    """).strip()

    def num(text, key):
        try:
            return int(text.split(key + "=", 1)[1].split(" ", 1)[0])
        except (IndexError, ValueError):
            return None

    f0, f1 = num(before, "food"), num(after, "food")
    rose = f0 is not None and f1 is not None and f1 > f0
    ok = result.get("phase") == "COMPLETE" and rose and "keyDown=false" in after
    return record("eating completes and the food level rises", ok,
                  f"{before} -> {after} | {result.get('phase')} after "
                  f"{result.get('ticksActive')} ticks: {str(result.get('message'))[:150]}")


def probe_exactly_one_item_is_consumed(mcp):
    """Vanilla restarts a use while the key is still down (Minecraft.java:2158, gated on
    rightClickDelayTimer), so a release that lands a tick late eats a SECOND item. The
    controller releases on the completing tick specifically to avoid that, and only a live run
    can show whether the timing holds against the real client."""
    print(f"     (hand: {select_hotbar(mcp, 0)})")
    before = mcp.java("OneBefore", PREAMBLE + SERVER_PLAYER + """
        if (sp == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.item.ItemStack sheld = sp.getHeldItem();
        if (sheld == null || !(sheld.getItem() instanceof net.minecraft.item.ItemFood))
            return "SETUP-SERVER-NO-FOOD";
        if (sheld.stackSize < 2) return "SETUP-NEED-2-OR-MORE stack=" + sheld.stackSize;
        if (!sp.canEat(false)) return "SETUP-SERVER-NOT-HUNGRY";
        // Counted on the SERVER: it owns the decrement (ItemFood.onItemUseFinish), and the client
        // stack is a prediction that a resync can overwrite either way.
        return "stack=" + sheld.stackSize;
    """).strip()
    if before.startswith("SETUP"):
        return record("exactly one item is consumed, not two", False,
                      "SKIPPED-NOT-MEASURED: " + before)

    result = drive_hold(mcp)
    # Deliberately wait AFTER the hold ended: a key left asserted starts the next use here, and
    # vanilla's restart is gated on rightClickDelayTimer, so it needs a few real ticks to show.
    import time as _time
    _time.sleep(1.5)
    after = mcp.java("OneAfter", PREAMBLE + SERVER_PLAYER + """
        net.minecraft.item.ItemStack sheld = sp == null ? null : sp.getHeldItem();
        return "stack=" + (sheld == null ? 0 : sheld.stackSize)
             + " using=" + p.isUsingItem()
             + " keyDown=" + mc.gameSettings.keyBindUseItem.isKeyDown();
    """).strip()

    def num(text, key):
        try:
            return int(text.split(key + "=", 1)[1].split(" ", 1)[0])
        except (IndexError, ValueError):
            return None

    s0, s1 = num(before, "stack"), num(after, "stack")
    consumed = None if s0 is None or s1 is None else s0 - s1
    ok = (consumed == 1 and "keyDown=false" in after and "using=false" in after
          and result.get("phase") == "COMPLETE")
    return record("exactly one item is consumed, not two", ok,
                  f"consumed={consumed} ({before} -> {after}) | {result.get('phase')}")


def probe_screen_gates_vanillas_stop(mcp):
    """The claim now in the tool description, and the one this session got backwards first.

    Minecraft.java:2118-2122 ends a held use, but the whole block sits inside
    "currentScreen == null || currentScreen.allowUserInput" at Minecraft.java:1829, and
    allowUserInput defaults false with only GuiInventory/GuiContainerCreative setting it. So a
    screen both clears the key AND gates off the code that would end the use: the item keeps
    being used with nobody driving it. Read from source; never observed. This observes it.
    """
    out = mcp.java("HoldScreen", PREAMBLE + ACT + """
        net.minecraft.item.ItemStack held = p.getHeldItem();
        if (held == null) return "SETUP-EMPTY-HAND";

        // Start a use the way vanilla does, then assert the key so it is sustained.
        act.holdUseKey();
        boolean started = act.useItemInAir();
        if (!p.isUsingItem()) { act.releaseUseKey(); return "SETUP-USE-DID-NOT-START started=" + started; }

        int countAtOpen = act.itemInUseCount();
        // A chat screen: it does NOT set allowUserInput, so it should gate vanilla's stop branch.
        mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat());
        String screen = mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName();
        boolean allowsInput = mc.currentScreen != null && mc.currentScreen.allowUserInput;
        boolean keyAfterOpen = act.useKeyHeld();
        return "screen=" + screen + " allowUserInput=" + allowsInput
             + " keyAfterOpen=" + keyAfterOpen + " usingAtOpen=" + p.isUsingItem()
             + " countAtOpen=" + countAtOpen;
    """)
    if out.startswith("SETUP"):
        return record("a screen clears the key without ending the use", False,
                      "SKIPPED-NOT-MEASURED: " + out.strip())
    opened = record("opening chat clears the use key (unPressAllKeys via displayGuiScreen)",
                    "keyAfterOpen=false" in out and "allowUserInput=false" in out, out.strip())

    # Second eval, so real game ticks pass in between: the whole question is what vanilla does
    # on the ticks AFTER the screen opened. Reading it in the same submission would only show
    # the instant of opening, which is the mistake the headless version of this made.
    out2 = mcp.java("HoldScreen2", PREAMBLE + """
        boolean using = p.isUsingItem();
        int count = p.getItemInUseCount();
        String screen = mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName();
        mc.displayGuiScreen(null);
        return "screen=" + screen + " stillUsing=" + using + " count=" + count;
    """)
    still = "stillUsing=true" in out2
    record("and vanilla does NOT end the use while that screen is open -- the claim in the "
           "act_set description", still, out2.strip())
    return opened and still


def probe_bow_fires_on_release(mcp):
    """A bow's arrow is created in ItemBow.onPlayerStoppedUsing, reached only from vanilla's own
    stop branch, so the RELEASE is the shot. Counting arrows in the world is the observation;
    the draw-tick number the controller reports is the thing that was wrong before (it measured
    from adoption rather than from the item's own duration)."""
    print(f"     (hand: {select_hotbar(mcp, 1)})")
    # Aim UP before firing, and that is load-bearing rather than tidy. Fired level at ground
    # height the arrow lands within a tick or two, and a survival player standing right there
    # picks it straight back up: the stack goes 32 -> 31 -> 32 and the entity is gone, so every
    # signal reads exactly like "no shot was fired". That is what the first live run of this check
    # measured, and it looked like a controller defect for three rounds of diagnosis. Skyward, the
    # arrow is in flight for seconds and cannot be reabsorbed inside the sampling window.
    mcp.call("act_set", {"look": {"mode": "set", "yaw": 0, "pitch": -80}})
    import time as _settle
    _settle.sleep(0.6)
    before = mcp.java("BowBefore", PREAMBLE + SERVER_PLAYER + """
        net.minecraft.item.ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof net.minecraft.item.ItemBow))
            return "SETUP-NO-BOW client held=" + (held == null ? "empty" : held.getDisplayName());
        // ItemBow.onPlayerStoppedUsing runs SERVER-side and checks the server's inventory, so
        // client-side arrows prove nothing about whether an arrow can exist.
        if (sp == null) return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.item.ItemStack sheld = sp.getHeldItem();
        if (sheld == null || !(sheld.getItem() instanceof net.minecraft.item.ItemBow))
            return "SETUP-SERVER-NO-BOW server held=" + (sheld == null ? "empty" : sheld.getDisplayName());
        if (!sp.capabilities.isCreativeMode && !sp.inventory.hasItem(net.minecraft.init.Items.arrow))
            return "SETUP-SERVER-NO-ARROWS";
        // Three signals, and the STACK is the decisive one: an arrow entity can be picked back
        // up within a tick, and the bow's damage increments inside the firing block so it proves
        // the code path ran even when the entity is already gone.
        int flying = 0;
        for (Object o : new java.util.ArrayList<>(sp.worldObj.loadedEntityList))
            if (o instanceof net.minecraft.entity.projectile.EntityArrow) flying++;
        net.minecraft.item.ItemStack sarr = sp.inventory.mainInventory[2];
        return "arrows=" + (sarr == null ? 0 : sarr.stackSize)
             + " bowDmg=" + sheld.getItemDamage() + " flying=" + flying;
    """).strip()
    if before.startswith("SETUP"):
        return record("a bow fires on release, with an honest draw count", False,
                      "SKIPPED-NOT-MEASURED: " + before)

    # 20 ticks is exactly full charge under vanilla's formula ((t^2+2t)/3 reaches 1.0 at t=1.0).
    result = drive_hold(mcp, hold_ticks=20)
    import time as _time
    _time.sleep(1.0)      # the arrow spawns on vanilla's stop tick, after our release lands
    after = mcp.java("BowAfter", PREAMBLE + SERVER_PLAYER + """
        int flying = 0;
        for (Object o : new java.util.ArrayList<>(sp.worldObj.loadedEntityList))
            if (o instanceof net.minecraft.entity.projectile.EntityArrow) flying++;
        net.minecraft.item.ItemStack sarr = sp.inventory.mainInventory[2];
        net.minecraft.item.ItemStack sbow = sp.inventory.mainInventory[1];
        return "arrows=" + (sarr == null ? 0 : sarr.stackSize)
             + " bowDmg=" + (sbow == null ? -1 : sbow.getItemDamage())
             + " flying=" + flying + " using=" + p.isUsingItem()
             + " keyDown=" + mc.gameSettings.keyBindUseItem.isKeyDown();
    """).strip()

    def num(text, key):
        try:
            return int(text.split(key + "=", 1)[1].split(" ", 1)[0])
        except (IndexError, ValueError):
            return None

    a0, a1 = num(before, "arrows"), num(after, "arrows")
    d0, d1 = num(before, "bowDmg"), num(after, "bowDmg")
    consumed = a0 is not None and a1 is not None and a1 == a0 - 1
    damaged = d0 is not None and d1 is not None and d1 > d0
    msg = str(result.get("message", ""))
    honest = "20 draw ticks" in msg
    return record("a bow fires on release, with an honest draw count",
                  consumed and damaged and result.get("phase") == "COMPLETE" and honest,
                  f"arrows {a0}->{a1} bowDmg {d0}->{d1} flying={num(after, 'flying')} | "
                  f"{result.get('phase')}: {msg[:150]}")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 25599
    mcp = Mcp(port)
    print(f"-- hold-channel probe on port {port}\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {port}: {ping['error']}")
        print("       start the client first: ./scripts/run-mcp.sh")
        return 3

    if not NAV.probe_in_world(mcp):
        print("\nSETUP: not in a world. Load a world and re-run.")
        return 3

    # The window will not have focus while a script drives it, and an unfocused vanilla stops
    # ticking -- which would freeze every count this probe reads and look exactly like a
    # controller bug. Clear the gate first, then insist the world really is advancing.
    print(f"-- unfocused ticking: {allow_unfocused(mcp)}")
    if not require_ticking(mcp, "the world is advancing before any hold is measured"):
        print("\nSETUP: world not ticking; nothing below would measure the code.")
        return 3

    print("\n-- the seam itself")
    probe_seam_writes_and_reads_back(mcp)

    print("\n-- eating (hold a food item to exercise these)")
    probe_eating_completes_and_food_rises(mcp)
    probe_exactly_one_item_is_consumed(mcp)

    print("\n-- a screen gates vanilla's stop branch")
    probe_screen_gates_vanillas_stop(mcp)

    print("\n-- a bow fires on release (hold a bow to exercise this)")
    probe_bow_fires_on_release(mcp)

    passed = sum(1 for _, ok, _ in NAV.results if ok)
    total = len(NAV.results)
    print(f"\n{'PASS' if passed == total else 'FAIL'}: {passed}/{total} checks")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
