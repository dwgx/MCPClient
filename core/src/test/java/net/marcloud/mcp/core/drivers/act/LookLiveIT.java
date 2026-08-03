package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.LiveGameGate;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * LIVE scaffold. Physically requires a running Minecraft client with a live game
 * thread and the player in a world.
 *
 * <p>HONEST TOMBSTONE, not a working test. {@link GameAccess} reads
 * {@code Minecraft.getMinecraft()}, a static singleton populated only by the game's
 * own bootstrap, so it is null in a forked surefire/failsafe JVM by construction and
 * FAIL is the only branch reachable here. It used to gate TWICE — one Assume on the
 * flag, a second on {@code inWorld()} — so {@code -Dmcp.it.live=true} produced a skip
 * and BUILD SUCCESS. {@link LiveGameGate} now turns that case red; see its javadoc.
 *
 * <p>Real live verification goes through the MCP socket and {@code eval_java}, the
 * way {@code scripts/nav-astar-probe.py} does.
 *
 * <p>Mirrors {@code GuiClickLiveIT}. Covers the one path the headless
 * {@code LookControllerTest} cannot: driving {@link LookController} against the
 * REAL {@link LivePlayerActuator} (rotation fields on the live {@code EntityPlayerSP}).
 *
 * <p>Runs under failsafe, skipped by default:
 * {@code ./mvnw -pl core verify -Dcore.it.skip=false -Dmcp.it.live=true}
 * inside a JVM that has Core started against a live client.
 */
public class LookLiveIT {

    private static void requireInWorld(ActActuator act) {
        LiveGameGate.require("player in a world", act::inWorld);
    }

    @Test
    public void slewToAbsoluteYawOnTheLivePlayer() {
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
