package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.LiveGameGate;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold. Requires a running Minecraft client with the player in a world.
 *
 * <p>HONEST TOMBSTONE, not a working test. {@link GameAccess} reads
 * {@code Minecraft.getMinecraft()}, a static singleton populated only by the game's
 * own bootstrap, so it is null in a forked surefire/failsafe JVM by construction and
 * FAIL is the only branch reachable here. It used to gate TWICE — one Assume on the
 * flag, a second on {@code inWorld()} — so {@code -Dmcp.it.live=true} produced skips
 * and BUILD SUCCESS. {@link LiveGameGate} now turns that case red; see its javadoc.
 *
 * <p>Real live verification goes through the MCP socket and {@code eval_java}, the
 * way {@code scripts/nav-astar-probe.py} does.
 *
 * <p>Mirrors {@code GuiClickLiveIT}. Covers the real interaction paths (hotbar
 * select via the live inventory, use-in-air via {@code PlayerControllerMP}) that
 * the headless {@code InteractControllerTest}/{@code HotbarControllerTest} can only
 * exercise through a fake.
 *
 * <p>Runs under failsafe, skipped by default:
 * {@code ./mvnw -pl core verify -Dcore.it.skip=false -Dmcp.it.live=true}
 */
public class InteractLiveIT {

    private static void requireInWorld(ActActuator act) {
        LiveGameGate.require("player in a world", act::inWorld);
    }

    @Test
    public void selectHotbarSlotOnTheLivePlayer() {
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        int target = (act.heldSlot() + 1) % 9;
        HotbarController c = new HotbarController(InteractIntent.hotbar(target));
        ActOutcome out = c.tick(act);
        assertTrue("live hotbar select should complete ok: " + out.message(),
                out.terminal() && out.ok());
    }

    @Test
    public void useHeldItemInAirOnTheLivePlayer() {
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        InteractController c = new InteractController(InteractIntent.useInAir());
        ActOutcome out = c.tick(act);
        // Use may legitimately fail (empty hand); assert only that it terminated.
        assertTrue("live use should terminate: " + out.message(), out.terminal());
    }
}
