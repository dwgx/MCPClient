package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold (default SKIPPED). Requires a running Minecraft client with the
 * player looking at a breakable block. Gated behind {@code -Dmcp.it.live=true};
 * without it every test {@link Assume#assumeTrue assume-skips} and never fails.
 *
 * <p>Mirrors {@code GuiClickLiveIT}. Covers the real {@code PlayerControllerMP}
 * dig path (clickBlock / onPlayerDamageBlock / resetBlockRemoving) that the
 * headless {@code DigControllerTest} can only exercise through a fake.
 *
 * <p>Run live with:
 * {@code ./mvnw -pl core test -Dtest=DigLiveIT -Dmcp.it.live=true}
 */
public class DigLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    private static void requireLive() {
        Assume.assumeTrue("requires live game window; run with -Dmcp.it.live=true", LIVE);
    }

    private static void requireBlockAtCrosshair(ActActuator act) {
        boolean ok;
        try {
            ok = act.inWorld() && act.mouseOver().kind() == ActActuator.Target.Kind.BLOCK;
        } catch (Throwable noGame) {
            ok = false;
        }
        Assume.assumeTrue("requires the player to be aiming at a block", ok);
    }

    @Test
    public void digTheBlockUnderTheCrosshair() {
        requireLive();
        ActActuator act = new LivePlayerActuator(new GameAccess());
        requireBlockAtCrosshair(act);

        ActActuator.Target t = act.mouseOver();
        DigController c = new DigController(
                InteractIntent.dig(t.x(), t.y(), t.z(), t.side() == null ? 1 : t.side().index()));
        ActOutcome out = null;
        for (int i = 0; i < 200 && (out == null || !out.terminal()); i++) {
            out = c.tick(act);
        }
        assertTrue("live dig should terminate: " + (out == null ? "null" : out.message()),
                out != null && out.terminal());
    }
}
