package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold (default SKIPPED). Physically requires a running Minecraft client
 * with a live game thread and the player in a world, so it cannot run in CI. Gated
 * behind {@code -Dmcp.it.live=true}; without that flag every test
 * {@link Assume#assumeTrue assume-skips} with a clear message and NEVER fails.
 *
 * <p>Mirrors {@code GuiClickLiveIT}. Covers the one path the headless
 * {@code LookControllerTest} cannot: driving {@link LookController} against the
 * REAL {@link LivePlayerActuator} (rotation fields on the live {@code EntityPlayerSP}).
 *
 * <p>Run live with:
 * {@code ./mvnw -pl core test -Dtest=LookLiveIT -Dmcp.it.live=true}
 * inside a JVM that has Core started against a live client.
 */
public class LookLiveIT {

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
        Assume.assumeTrue("requires the player to be in a world; run inside the game", up);
    }

    @Test
    public void slewToAbsoluteYawOnTheLivePlayer() {
        requireLive();
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireInWorld(act);

        float targetYaw = act.yaw() + 30f;
        LookController c = new LookController(LookIntent.set(targetYaw, act.pitch(), 5f));
        ActOutcome out = null;
        for (int i = 0; i < 60 && (out == null || !out.terminal()); i++) {
            out = c.tick(act);
        }
        assertTrue("live slew should complete: " + (out == null ? "null" : out.message()),
                out != null && out.terminal() && out.ok());
    }
}
