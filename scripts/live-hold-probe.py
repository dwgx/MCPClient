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

The socket client, the eval_java wrapper, the ticking guard and the record/report harness live in
scripts/mcp_probe.py, shared with the other live probes. Exit codes follow smoke-live-gl.sh:
0 PASS, 1 FAIL, 2 TIMEOUT, 3 SETUP.
"""

import os
import sys

# scripts/ is not on sys.path when this file is loaded BY PATH, which test_probe_framing.py does
# (the hyphen in the filename rules out a plain import). Running it directly already puts it there.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Previously this file EXEC'D nav-astar-probe.py by path to borrow its Mcp and guards -- which
# worked, but made a sibling probe's checks the price of importing a socket client, and every
# import of the hold probe ran the nav probe's module body. The shared parts now have their own
# home, so the dependency is on the module rather than on a neighbour.
from mcp_probe import (  # noqa: E402 - the sys.path line above has to run first
    EXIT_SETUP, Mcp, PREAMBLE, allow_unfocused, probe_in_world, record, report, require_ticking,
)

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


def probe_screen_gates_vanillas_stop(mcp, focus):
    """The claim in the tool description -- and it is CONDITIONAL, which this probe first missed.

    Minecraft.java:2118-2122 ends a held use, but the whole block sits inside
    "currentScreen == null || currentScreen.allowUserInput" at Minecraft.java:1829, and
    allowUserInput defaults false with only GuiInventory/GuiContainerCreative setting it. So a
    screen gates off the code that would end the use: the item keeps being used with nobody
    driving it.

    THE KEY-CLEARING HALF IS GATED ON FOCUS, and asserting it unconditionally is what made this
    probe report a false failure. KeyBinding.unPressAllKeys runs inside setIngameNotInFocus, which
    is guarded by "if (this.inGameHasFocus)" (Minecraft.java:1467-1469). Measured both ways on a
    live client: with in-game focus, opening chat cleared the key; WITHOUT it, the key survived.
    A script-driven client is normally unfocused, so the probe was asserting the branch it was
    least likely to be in -- and the resulting FAIL looked like a defect in the hold channel.

    So the condition is now ESTABLISHED rather than assumed, and the probe runs BOTH ways: the
    caller passes the focus state it wants and the expectation follows from it. A probe that can
    only make its assertion true by luck is not measuring anything.
    """
    prefix = "focused" if focus else "unfocused"
    out = mcp.java("HoldScreen", PREAMBLE + ACT + """
        net.minecraft.item.ItemStack held = p.getHeldItem();
        if (held == null) return "SETUP-EMPTY-HAND";
        // Establish the focus state this run is about, so the expectation below is not a guess.
        java.lang.reflect.Field ff =
            net.minecraft.client.Minecraft.class.getDeclaredField("inGameHasFocus");
        ff.setAccessible(true);
        ff.setBoolean(mc, %s);

        // Start a use the way vanilla does, then assert the key so it is sustained.
        act.holdUseKey();
        boolean started = act.useItemInAir();
        if (!p.isUsingItem()) { act.releaseUseKey(); return "SETUP-USE-DID-NOT-START started=" + started; }

        int countAtOpen = act.itemInUseCount();
        // Read BEFORE opening, because displayGuiScreen -> setIngameNotInFocus SETS IT FALSE
        // (Minecraft.java:1470) as part of the very branch under test. Reading it afterwards
        // reports false on both runs, so the field would prove nothing about which branch ran --
        // the focused case passed with "focus=false" printed beside it until this was fixed, which
        // is a probe agreeing with itself rather than measuring.
        boolean focusBeforeOpen = ff.getBoolean(mc);
        // Chat: it does NOT set allowUserInput, so it gates vanilla's stop branch -- and its
        // doesGuiPauseGame() is false, so it does NOT pause the world. Both halves matter.
        mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat());
        String screen = mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName();
        boolean allowsInput = mc.currentScreen != null && mc.currentScreen.allowUserInput;
        boolean keyAfterOpen = act.useKeyHeld();
        return "focusBeforeOpen=" + focusBeforeOpen + " focusAfterOpen=" + ff.getBoolean(mc)
             + " screen=" + screen + " allowUserInput=" + allowsInput
             + " pausesGame=" + mc.currentScreen.doesGuiPauseGame()
             + " keyAfterOpen=" + keyAfterOpen + " usingAtOpen=" + p.isUsingItem()
             + " countAtOpen=" + countAtOpen;
    """ % ("true" if focus else "false"))
    if out.startswith("SETUP"):
        return record(f"[{prefix}] a screen gates vanilla's stop branch", False,
                      "SKIPPED-NOT-MEASURED: " + out.strip())

    # The expectation FOLLOWS from the focus state rather than being fixed: cleared when the game
    # had in-game focus, survived when it did not. Either outcome is correct for its own branch, and
    # asserting the pair is what pins the gate itself rather than one accident of it.
    want_cleared = "keyAfterOpen=false" if focus else "keyAfterOpen=true"
    # The precondition is ASSERTED, not assumed: if the focus state this run is named for was not
    # actually in force when the screen opened, the run measured the other branch and saying so is
    # the only honest outcome.
    want_focus = "focusBeforeOpen=true" if focus else "focusBeforeOpen=false"
    opened = record(
        f"[{prefix}] opening chat {'clears' if focus else 'does NOT clear'} the use key "
        f"(unPressAllKeys is gated on inGameHasFocus, Minecraft.java:1467-1469)",
        want_focus in out and want_cleared in out and "allowUserInput=false" in out, out.strip())

    # Second eval, so real game ticks pass in between: the whole question is what vanilla does
    # on the ticks AFTER the screen opened. Reading it in the same submission would only show
    # the instant of opening, which is the mistake the headless version of this made.
    out2 = mcp.java("HoldScreen2", PREAMBLE + """
        boolean using = p.isUsingItem();
        int count = p.getItemInUseCount();
        String screen = mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName();
        boolean paused = mc.isGamePaused();
        mc.displayGuiScreen(null);
        net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        return "screen=" + screen + " stillUsing=" + using + " count=" + count
             + " isGamePaused=" + paused;
    """)
    still = "stillUsing=true" in out2
    record(f"[{prefix}] and vanilla does NOT end the use while that screen is open -- the claim in "
           "the act_set description", still, out2.strip())
    # Chat must NOT pause the world, which is the half the first version of this session's fix got
    # backwards: it listed chat among the screens that freeze a use. Measured, chat lets a meal
    # finish. Asserted here so a future change cannot quietly reintroduce that claim.
    record(f"[{prefix}] and chat does NOT pause the game, so the use keeps counting down",
           "isGamePaused=false" in out2,
           "GuiChat.doesGuiPauseGame() is false -- the one override that returns false, so unlike "
           "the pause menu or a chest it does not stop theWorld.updateEntities: " + out2.strip())
    return opened and still


def probe_a_pausing_screen_freezes_the_use(mcp):
    """A PAUSING screen freezes the use, and the deadline must blame the client rather than the server.

    The defect this found on a live client: the deadline said "the server never sent the finish
    (status id 9)" while the game was simply paused. In single player isGamePaused is true for any
    screen whose doesGuiPauseGame() is true -- GuiScreen's DEFAULT, so the pause menu, a chest, a
    furnace -- and that gate stops theWorld.updateEntities (Minecraft.java:2195-2202), so nothing
    counts down and the integrated server is not running either. A caller told to check its
    connection would be looking in the wrong place entirely.

    Reachable only on an unfocused client, because a focused one has its key cleared by the same
    screen and ends on the key-lost branch instead. So this probe establishes that state explicitly.
    """
    import json as _json
    import time as _time

    setup = mcp.java("PauseSetup", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        if (srv == null || srv.getConfigurationManager().getPlayerList().isEmpty())
            return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.entity.player.EntityPlayerMP sp =
            srv.getConfigurationManager().getPlayerList().get(0);
        net.minecraft.item.ItemStack sheld = sp.getHeldItem();
        if (sheld == null || !(sheld.getItem() instanceof net.minecraft.item.ItemFood))
            return "SETUP-SERVER-NO-FOOD held=" + (sheld == null ? "empty" : sheld.getDisplayName());
        if (!sp.canEat(false)) return "SETUP-SERVER-NOT-HUNGRY food=" + sp.getFoodStats().getFoodLevel();
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        // Unfocused: this is the state in which the key SURVIVES a screen, which is what makes the
        // deadline reachable at all.
        java.lang.reflect.Field ff =
            net.minecraft.client.Minecraft.class.getDeclaredField("inGameHasFocus");
        ff.setAccessible(true); ff.setBoolean(mc, false);
        return "food=" + sp.getFoodStats().getFoodLevel() + " focus=" + ff.getBoolean(mc);
    """).strip()
    if setup.startswith("SETUP"):
        return record("a pausing screen freezes the use, and the deadline blames the client", False,
                      "SKIPPED-NOT-MEASURED: " + setup)

    mcp.call("act_set", {"interact": {"kind": "hold"}})
    _time.sleep(0.7)

    def interact():
        try:
            st = _json.loads(mcp.call("act_status", {}).get("text", "{}"))
        except ValueError:
            return {}
        return next((s for s in st.get("slots", []) if s.get("slot") == "interact"), {})

    if interact().get("phase") != "ACTIVE":
        return record("a pausing screen freezes the use, and the deadline blames the client", False,
                      "SKIPPED-NOT-MEASURED: the hold did not start: "
                      + str(interact().get("message"))[:160])

    opened = mcp.java("OpenPause", PREAMBLE + """
        mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
        return "screen=" + mc.currentScreen.getClass().getSimpleName()
             + " pausesGame=" + mc.currentScreen.doesGuiPauseGame();
    """).strip()

    # The deadline is initialCount + SERVER_FINISH_SLACK_TICKS, i.e. about 72 ticks for food, so a
    # ~12s bound is generous while still failing rather than hanging if it never terminates.
    last = {}
    deadline = _time.time() + 14.0
    while _time.time() < deadline:
        _time.sleep(0.6)
        last = interact()
        if last.get("phase") in ("COMPLETE", "FAILED", "CANCELLED"):
            break

    mcp.java("ClosePause", PREAMBLE + """
        mc.displayGuiScreen(null);
        net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        return "closed";
    """)
    mcp.call("act_cancel", {"slots": ["interact"]})

    msg = str(last.get("message", ""))
    ok = (last.get("phase") == "FAILED"
          and "the count has not moved for" in msg
          and "isGamePaused" in msg
          and "the server never sent the finish" not in msg)
    return record("a pausing screen freezes the use, and the deadline blames the CLIENT rather "
                  "than the server", ok, f"{opened} | {last.get('phase')} after "
                  f"{last.get('ticksActive')} ticks: {msg[:400]}")


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


def stage_preconditions(mcp):
    """Put food, a bow and arrows in hand and make the player hungry, on the SERVER.

    Every probe below has a precondition it can only SKIP on, and a run of skips is indistinguishable
    from a run of passes in the tally -- worse, the first eat probe SATISFIES its own precondition
    away by filling the hunger bar, so the second and third then skip. Measured: a first run gave
    4/6 with two SKIPPED-NOT-MEASURED, and nothing about that output said the channel was unverified.

    Staged on the integrated server because the server is the authority on whether a use completes
    (debugging.md section 10 rule 2): a client-only stack leaves the server with an empty hand, it
    never starts eating, status id 9 never arrives, and the controller's honest "this is not
    progressing" reads as a defect. sendContainerToPlayer pushes the change back so the two agree.

    It MUTATES the world, deliberately and loudly: this probe is for a throwaway world, the same
    assumption live-nav-probe.py makes when it flattens an arena.
    """
    out = mcp.java("HoldStage", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        if (srv == null || srv.getConfigurationManager().getPlayerList().isEmpty())
            return "SETUP-NO-SERVER-PLAYER";
        net.minecraft.entity.player.EntityPlayerMP sp =
            srv.getConfigurationManager().getPlayerList().get(0);
        // Survival, because EntityPlayer.canEat also requires !capabilities.disableDamage
        // (EntityPlayer.java:2088) and creative sets it -- a creative player cannot eat at all.
        if (sp.theItemInWorldManager.getGameType()
                != net.minecraft.world.WorldSettings.GameType.SURVIVAL) {
            sp.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        }
        sp.inventory.mainInventory[0] =
            new net.minecraft.item.ItemStack(net.minecraft.init.Items.bread, 16);
        sp.inventory.mainInventory[1] =
            new net.minecraft.item.ItemStack(net.minecraft.init.Items.bow, 1);
        sp.inventory.mainInventory[2] =
            new net.minecraft.item.ItemStack(net.minecraft.init.Items.arrow, 64);
        sp.inventory.markDirty();
        sp.sendContainerToPlayer(sp.inventoryContainer);
        // Hungry enough that canEat passes for every eat probe, not just the first.
        sp.getFoodStats().setFoodLevel(6);
        return "gamemode=" + sp.theItemInWorldManager.getGameType()
             + " serverFood=" + sp.getFoodStats().getFoodLevel()
             + " slot0=" + sp.inventory.mainInventory[0].getDisplayName()
             + " slot1=" + sp.inventory.mainInventory[1].getDisplayName();
    """).strip()
    return record("the preconditions are staged on the SERVER (food, bow, arrows, hunger)",
                  not out.startswith(("SETUP", "THREW", "PROBE-ERROR")), out[:220])


def refresh_hunger(mcp):
    """Make the player hungry again between eat probes.

    The first eat probe fills the bar to 20, which is exactly the precondition the next one needs
    absent. Without this the second and third eat checks skip, and a skip in the tally looks like a
    measurement that happened.
    """
    return mcp.java("Rehunger", PREAMBLE + """
        net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
        if (srv == null || srv.getConfigurationManager().getPlayerList().isEmpty())
            return "NO-SERVER-PLAYER";
        net.minecraft.entity.player.EntityPlayerMP sp =
            srv.getConfigurationManager().getPlayerList().get(0);
        sp.getFoodStats().setFoodLevel(6);
        if (sp.inventory.mainInventory[0] == null
                || sp.inventory.mainInventory[0].stackSize < 4) {
            sp.inventory.mainInventory[0] =
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.bread, 16);
            sp.inventory.markDirty();
            sp.sendContainerToPlayer(sp.inventoryContainer);
        }
        return "serverFood=" + sp.getFoodStats().getFoodLevel()
             + " bread=" + (sp.inventory.mainInventory[0] == null ? 0
                            : sp.inventory.mainInventory[0].stackSize);
    """).strip()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 25599
    mcp = Mcp(port, client_name="live-hold-probe")
    print(f"-- hold-channel probe on port {port}\n")

    ping = mcp.call("read_player_state", {})
    if "error" in ping:
        print(f"SETUP: cannot reach MCP on port {port}: {ping['error']}")
        print("       start the client first: ./scripts/run-mcp.sh")
        return EXIT_SETUP

    if not probe_in_world(mcp):
        print("\nSETUP: not in a world. Load a world and re-run.")
        return EXIT_SETUP

    # The window will not have focus while a script drives it, and an unfocused vanilla stops
    # ticking -- which would freeze every count this probe reads and look exactly like a
    # controller bug. Clear the gate first, then insist the world really is advancing.
    print(f"-- unfocused ticking: {allow_unfocused(mcp)}")
    if not require_ticking(mcp, "the world is advancing before any hold is measured"):
        print("\nSETUP: world not ticking; nothing below would measure the code.")
        return EXIT_SETUP

    print("\n-- staging (mutates the world; use a throwaway one)")
    stage_preconditions(mcp)

    print("\n-- the seam itself")
    probe_seam_writes_and_reads_back(mcp)

    print("\n-- eating")
    probe_eating_completes_and_food_rises(mcp)
    print(f"     (rehunger: {refresh_hunger(mcp)})")
    probe_exactly_one_item_is_consumed(mcp)
    print(f"     (rehunger: {refresh_hunger(mcp)})")

    # BOTH focus states, because the key-clearing half is gated on inGameHasFocus and asserting one
    # branch unconditionally is what made this probe report a false failure. See the docstring.
    print("\n-- a screen gates vanilla's stop branch (focused: the key IS cleared)")
    probe_screen_gates_vanillas_stop(mcp, focus=True)
    print("\n-- the same screen unfocused (the key SURVIVES -- the branch a script actually hits)")
    probe_screen_gates_vanillas_stop(mcp, focus=False)

    print("\n-- a PAUSING screen freezes the use (hold food to exercise this)")
    probe_a_pausing_screen_freezes_the_use(mcp)

    print("\n-- a bow fires on release (hold a bow to exercise this)")
    probe_bow_fires_on_release(mcp)

    return report()


if __name__ == "__main__":
    sys.exit(main())
