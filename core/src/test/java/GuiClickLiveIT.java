import static org.junit.Assert.assertNotNull;

import net.marcloud.mcp.core.ke.KeGameDispatcher;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.drivers.gui.GuiActions;
import net.marcloud.mcp.core.drivers.gui.GuiSnapshot;
import net.marcloud.mcp.core.drivers.gui.GuiSnapshotService;
import org.junit.Assume;
import org.junit.Test;

/**
 * LIVE scaffold (default SKIPPED). Physically requires a running Minecraft client
 * with a GUI screen open and a live game thread ({@code GameBridge} wired), so it
 * cannot run in CI. Gated behind {@code -Dmcp.it.live=true}; without that flag
 * every test {@link Assume#assumeTrue assume-skips} with a clear message and
 * NEVER fails.
 *
 * <p>Run live with:
 * {@code ./mvnw -pl core test -Dtest=GuiClickLiveIT -Dmcp.it.live=true}
 * inside a JVM that has Core started against a live client (so
 * {@code Minecraft.getMinecraft()} and the {@code KeGameDispatcher} exist).
 *
 * <p>Covers the one path the headless {@code GuiSnapshotTest} cannot: taking a
 * snapshot of the REAL open screen and driving {@link GuiActions#click} on the
 * real game thread against a live handler.
 */
public class GuiClickLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    private static void requireLive() {
        Assume.assumeTrue(
                "requires live game window; run with -Dmcp.it.live=true", LIVE);
    }

    /** Assume-skip if there is no live Minecraft client (touching it would throw). */
    private static void requireLiveClient(GameAccess game) {
        boolean up;
        try {
            up = game.mc() != null;
        } catch (Throwable noGame) {
            up = false;
        }
        Assume.assumeTrue("requires a live Minecraft client; run inside the game", up);
    }

    @Test
    public void snapshotThenClickFirstElementOnTheLiveScreen() throws Exception {
        requireLive();
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
        requireLive();
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
