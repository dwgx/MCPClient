import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.LiveGameGate;
import net.marcloud.mcp.core.drivers.gui.GuiActions;
import net.marcloud.mcp.core.drivers.gui.GuiSnapshot;
import net.marcloud.mcp.core.drivers.gui.GuiSnapshotService;
import org.junit.Test;

/**
 * LIVE scaffold. Physically requires a running Minecraft client with a GUI screen
 * open and a live game thread ({@code GameBridge} wired).
 *
 * <p>HONEST TOMBSTONE, not a working test. {@link GameAccess} reads
 * {@code Minecraft.getMinecraft()}, a static singleton populated only by the game's
 * own bootstrap, so it is null in a forked surefire/failsafe JVM by construction and
 * FAIL is the only branch reachable here. It used to gate TWICE — one Assume on the
 * flag, a second on the client — so {@code -Dmcp.it.live=true} produced skips and
 * BUILD SUCCESS: a live request answered with silent success, indistinguishable from
 * verification. {@link LiveGameGate} now turns that case red; see its javadoc.
 *
 * <p>Real live verification goes through the MCP socket and {@code eval_java}, the
 * way {@code scripts/nav-astar-probe.py} does. Kept as a specification of what a
 * live GUI click must satisfy, and as a signpost to that route.
 *
 * <p>Runs under failsafe, skipped by default:
 * {@code ./mvnw -pl core verify -Dcore.it.skip=false -Dmcp.it.live=true}
 * inside a JVM that has Core started against a live client (so
 * {@code Minecraft.getMinecraft()} and the {@code KeGameDispatcher} exist).
 *
 * <p>Covers the one path the headless {@code GuiSnapshotTest} cannot: taking a
 * snapshot of the REAL open screen and driving {@link GuiActions#click} on the
 * real game thread against a live handler.
 */
public class GuiClickLiveIT {

    /** SKIP without the live flag; FAIL with it and no client. Never a silent pass. */
    private static void requireLiveClient(GameAccess game) {
        LiveGameGate.require("live Minecraft client with a GUI screen open",
                () -> game.mc() != null);
    }

    @Test
    public void snapshotThenClickFirstElementOnTheLiveScreen() throws Exception {
        GameAccess game = new GameAccess();
        requireLiveClient(game);
        GuiSnapshotService svc = new GuiSnapshotService();

        // Ground on the real open screen.
        GuiSnapshot snap = svc.snapshot(game, true);
        assertNotNull("a GUI screen must be open for this live test", snap.screen());
        assertTrue("live screen must expose at least one interactable element; "
                        + "open a screen with a button/slot before running",
                !snap.elements().isEmpty());

        // Drive the real handler for the first element on the game thread.
        String elementId = snap.elements().get(0).id();
        GuiActions actions = new GuiActions(game, svc, null);
        GuiActions.Result r = actions.click(snap.epoch(), snap.fingerprint(), elementId, 0);
        assertTrue("live click should be accepted (not stale): " + r.message(), r.ok());
    }

    @Test
    public void staleFingerprintIsRefusedOnTheLiveScreen() throws Exception {
        GameAccess game = new GameAccess();
        requireLiveClient(game);
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiSnapshot snap = svc.snapshot(game, true);
        assertNotNull("a GUI screen must be open", snap.screen());

        // A deliberately-wrong fingerprint must be rejected by the stale guard.
        GuiActions actions = new GuiActions(game, svc, null);
        GuiActions.Result r = actions.click(snap.epoch(), "bogus#0#0",
                snap.elements().isEmpty() ? "b0" : snap.elements().get(0).id(), 0);
        assertTrue("stale/bogus fingerprint must be refused", !r.ok());
    }
}
