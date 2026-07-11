import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.seam.SeamController;
import org.junit.Assume;
import org.junit.Test;

/**
 * LIVE scaffold (default SKIPPED). Physically requires a running client that is
 * CONNECTED to a server, so the Netty tap has a real channel to attach to — cannot
 * run in CI. Gated behind {@code -Dmcp.it.live=true}; otherwise every test
 * assume-skips with a clear message and never fails.
 *
 * <p>Run live with:
 * {@code ./mvnw -pl core test -Dtest=SeamOnLiveConnectionLiveIT -Dmcp.it.live=true}
 * while joined to a world/server.
 *
 * <p>Covers what {@code SeamControllerTest} (headless, refuses to install) and
 * {@code NettyTapLifecycleTest} (EmbeddedChannel) cannot: acquiring the REAL game
 * channel and installing the tap on the live pipeline.
 */
public class SeamOnLiveConnectionLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    private static void requireLive() {
        Assume.assumeTrue(
                "requires a live server connection; run with -Dmcp.it.live=true", LIVE);
    }

    @Test
    public void installTapOnTheLiveGameChannel() {
        requireLive();
        GameAccess game = new GameAccess();
        // Touching the Minecraft singleton off a real client throws (no game): treat
        // any such failure as "not live" and assume-skip rather than error.
        boolean connected;
        try {
            connected = game.isConnected();
        } catch (Throwable noGame) {
            connected = false;
        }
        Assume.assumeTrue("must be connected to a server (join a world first)", connected);

        EventBus bus = new EventBus();
        SeamController controller = new SeamController(bus, game);
        try {
            boolean installed = controller.installNettyTap();
            assertTrue("tap should install on the live channel", installed);
            assertTrue("controller reports the tap installed",
                    controller.isNettyTapInstalled());
            assertNotNull("network manager present while connected", game.networkManager());
        } finally {
            controller.uninstallAll();
        }
    }
}
