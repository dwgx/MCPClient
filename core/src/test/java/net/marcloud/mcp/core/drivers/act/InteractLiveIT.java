package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold (default SKIPPED). Requires a running Minecraft client with the
 * player in a world. Gated behind {@code -Dmcp.it.live=true}; without it every
 * test {@link Assume#assumeTrue assume-skips} and never fails.
 *
 * <p>Mirrors {@code GuiClickLiveIT}. Covers the real interaction paths (hotbar
 * select via the live inventory, use-in-air via {@code PlayerControllerMP}) that
 * the headless {@code InteractControllerTest}/{@code HotbarControllerTest} can only
 * exercise through a fake.
 *
 * <p>Run live with:
 * {@code ./mvnw -pl core test -Dtest=InteractLiveIT -Dmcp.it.live=true}
 */
public class InteractLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    private static void requireLive() {
        Assume.assumeTrue("requires live game window; run with -Dmcp.it.live=true", LIVE);
    }

    private static void requireInWorld(ActActuator act) {
        boolean up;
        try {
            up = act.inWorld();
        } catch (Throwable noGame) {
            up = false;
        }
        Assume.assumeTrue("requires the player to be in a world", up);
    }

    @Test
    public void selectHotbarSlotOnTheLivePlayer() {
        requireLive();
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
        requireLive();
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        InteractController c = new InteractController(InteractIntent.useInAir());
        ActOutcome out = c.tick(act);
        // Use may legitimately fail (empty hand); assert only that it terminated.
        assertTrue("live use should terminate: " + out.message(), out.terminal());
    }
}
