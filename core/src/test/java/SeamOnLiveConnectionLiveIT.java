import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.LiveGameGate;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.flt.seam.SeamController;
import org.junit.Test;

/**
 * LIVE scaffold. Physically requires a running client that is CONNECTED to a server,
 * so the Netty tap has a real channel to attach to.
 *
 * <p>HONEST TOMBSTONE, not a working test. {@link GameAccess} reads
 * {@code Minecraft.getMinecraft()}, a static singleton populated only by the game's
 * own bootstrap, so it is null in a forked surefire/failsafe JVM by construction and
 * FAIL is the only branch reachable here. It used to gate TWICE — one Assume on the
 * flag, a second on the connection — so {@code -Dmcp.it.live=true} produced a skip
 * and BUILD SUCCESS, which reads exactly like a passed live check. {@link LiveGameGate}
 * now turns that case red; see its javadoc.
 *
 * <p>Real live verification goes through the MCP socket and {@code eval_java}, the
 * way {@code scripts/nav-astar-probe.py} does.
 *
 * <p>Runs under failsafe, skipped by default:
 * {@code ./mvnw -pl core verify -Dcore.it.skip=false -Dmcp.it.live=true}
 * while joined to a world/server.
 *
 * <p>Covers what {@code SeamControllerTest} (headless, refuses to install) and
 * {@code NettyTapLifecycleTest} (EmbeddedChannel) cannot: acquiring the REAL game
 * channel and installing the tap on the live pipeline.
 */
public class SeamOnLiveConnectionLiveIT {

    @Test
    public void installTapOnTheLiveGameChannel() {
        GameAccess game = new GameAccess();
        // Touching the Minecraft singleton off a real client throws; the gate keeps the
        // throw's reason in its message instead of collapsing every cause to "not live",
        // which is what let a genuine seam breakage hide behind "no game running".
        LiveGameGate.require("client connected to a server (join a world first)",
                game::isConnected);

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
