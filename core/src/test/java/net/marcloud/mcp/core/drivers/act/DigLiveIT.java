package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.LiveGameGate;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold. Requires a running Minecraft client with the player looking at a
 * breakable block.
 *
 * <p>HONEST TOMBSTONE, not a working test. {@link GameAccess} reads
 * {@code Minecraft.getMinecraft()}, a static singleton populated only by the game's
 * own bootstrap, so it is null in a forked surefire/failsafe JVM by construction and
 * FAIL is the only branch reachable here. It used to gate TWICE — one Assume on the
 * flag, a second on the crosshair target — so {@code -Dmcp.it.live=true} produced a
 * skip and BUILD SUCCESS. That is why {@code docs/agency/command-to-action.md} records
 * "no evidence it has ever actually run": nothing it reported could distinguish a run
 * from a skip. {@link LiveGameGate} now turns that case red.
 *
 * <p>Real live verification goes through the MCP socket and {@code eval_java}, the
 * way {@code scripts/nav-astar-probe.py} does.
 *
 * <p>Mirrors {@code GuiClickLiveIT}. Covers the real {@code PlayerControllerMP}
 * dig path (clickBlock / onPlayerDamageBlock / resetBlockRemoving) that the
 * headless {@code DigControllerTest} can only exercise through a fake.
 *
 * <p>Runs under failsafe, skipped by default:
 * {@code ./mvnw -pl core verify -Dcore.it.skip=false -Dmcp.it.live=true}
 */
public class DigLiveIT {

    private static void requireBlockAtCrosshair(ActActuator act) {
        LiveGameGate.require("player in a world aiming at a block",
                () -> act.inWorld() && act.mouseOver().kind() == ActActuator.Target.Kind.BLOCK);
    }

    @Test
    public void digTheBlockUnderTheCrosshair() {
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
