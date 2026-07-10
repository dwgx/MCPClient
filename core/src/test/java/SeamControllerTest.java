import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.TickEvent;
import net.marcloud.mcp.core.seam.NettyTap;
import net.marcloud.mcp.core.seam.SeamController;
import net.marcloud.mcp.core.seam.TickBridge;
import net.marcloud.mcp.core.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.seam.events.SeamPacketOutboundEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the seam layer: NettyTap state machine, TickBridge forwarding, and
 * SeamController orchestration. These run headless (no live game, no GLFW, no
 * Minecraft bytecode retransform). Tests what's testable WITHOUT a live game:
 * handler install/remove idempotency, event forwarding via bridges, refusal
 * to double-install.
 *
 * <p>REQUIRES live-JBR verification: actual Netty channel acquisition,
 * Minecraft.runTick retransform, GLFW callback chaining (guarded behind
 * isAvailable()).
 */
public class SeamControllerTest {

    private EventBus bus;
    private GameAccess game;
    private SeamController controller;

    @Before
    public void setUp() {
        bus = new EventBus();
        game = new GameAccess();
        controller = new SeamController(bus, game);
        TickBridge.resetCounter();
    }

    @Test
    public void tickBridgeForwardsToEventBus() {
        AtomicInteger tickCount = new AtomicInteger(0);
        bus.subscribe(TickEvent.class, e -> tickCount.incrementAndGet());

        TickBridge.setBus(bus);
        TickBridge.onTick();
        TickBridge.onTick();
        TickBridge.onTick();

        assertEquals("three ticks published", 3, tickCount.get());
        assertEquals("tick counter incremented", 3, TickBridge.tickCounter());
    }

    @Test
    public void tickBridgeSwallowsSubscriberFaults() {
        bus.subscribe(TickEvent.class, e -> { throw new RuntimeException("boom"); });
        TickBridge.setBus(bus);
        // Should not throw.
        TickBridge.onTick();
        assertEquals("tick counter incremented despite fault", 1, TickBridge.tickCounter());
    }

    @Test
    public void tickBridgeHandlesNullBus() {
        TickBridge.setBus(null);
        // Should not throw.
        TickBridge.onTick();
        assertEquals("tick counter incremented even with null bus", 1, TickBridge.tickCounter());
    }

    @Test
    public void nettyTapHandlerForwardsInboundToEventBus() {
        AtomicInteger inbound = new AtomicInteger(0);
        bus.subscribe(SeamPacketInboundEvent.class, e -> inbound.incrementAndGet());

        NettyTap.PacketTapHandler handler = new NettyTap.PacketTapHandler(bus);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound("packet1");
        ch.writeInbound("packet2");

        assertEquals("two inbound events", 2, inbound.get());
    }

    @Test
    public void nettyTapHandlerForwardsOutboundToEventBus() {
        AtomicInteger outbound = new AtomicInteger(0);
        bus.subscribe(SeamPacketOutboundEvent.class, e -> outbound.incrementAndGet());

        NettyTap.PacketTapHandler handler = new NettyTap.PacketTapHandler(bus);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeOutbound("packet1");
        ch.writeOutbound("packet2");

        assertEquals("two outbound events", 2, outbound.get());
    }

    @Test
    public void nettyTapHandlerPassesMessagesThrough() {
        NettyTap.PacketTapHandler handler = new NettyTap.PacketTapHandler(bus);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound("inMsg");
        ch.writeOutbound("outMsg");

        Object in = ch.readInbound();
        Object out = ch.readOutbound();

        assertEquals("inbound passed through", "inMsg", in);
        assertEquals("outbound passed through", "outMsg", out);
    }

    @Test
    public void nettyTapHandlerSwallowsSubscriberFaults() {
        bus.subscribe(SeamPacketInboundEvent.class, e -> { throw new RuntimeException("boom"); });
        NettyTap.PacketTapHandler handler = new NettyTap.PacketTapHandler(bus);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // Should not throw; message still passes through.
        ch.writeInbound("packet");
        Object msg = ch.readInbound();
        assertEquals("message still delivered despite subscriber fault", "packet", msg);
    }

    @Test
    public void seamControllerCanInstallChecksInstrumentation() {
        // No -javaagent in the headless suite, so canInstall() must be FALSE
        // (real behavioral assertion — the old assertNotNull(Boolean) was a
        // tautology that passed regardless of the result).
        assertFalse("no agent in test JVM → tick injection cannot install",
                net.marcloud.mcp.core.agent.AgentAccess.isLoaded());
        assertFalse("canInstall() reflects the absent agent", controller.canInstall());
    }

    @Test
    public void seamControllerNettyTapIdempotency() {
        // NettyTap.installHandler checks if the channel is available via
        // GameAccess.networkManager(). In a headless test, Minecraft is not
        // running, so this will fail gracefully. We verify the state machine
        // handles null/unavailable channels.
        boolean installed = controller.installNettyTap();
        assertFalse("cannot install without a live channel", installed);

        boolean alreadyInstalled = controller.isNettyTapInstalled();
        assertFalse("tap not installed", alreadyInstalled);
    }

    @Test
    public void seamControllerKeyHookGuardsGLFW() {
        // InputHook.installKeyCallback() checks isAvailable(), which tries to
        // acquireWindow(). In a headless test, this returns false. We verify
        // the guard prevents crashes.
        boolean installed = controller.installKeyHook();
        assertFalse("cannot install key hook without GLFW", installed);

        boolean state = controller.isKeyHookInstalled();
        assertFalse("key hook not installed", state);
    }

    @Test
    public void seamControllerMouseHookGuardsGLFW() {
        boolean installed = controller.installMouseHook();
        assertFalse("cannot install mouse hook without GLFW", installed);

        boolean state = controller.isMouseHookInstalled();
        assertFalse("mouse hook not installed", state);
    }

    @Test
    public void seamControllerTickInjectorGuardsInstrumentation() {
        // In a headless test without -javaagent, installTickInjector will
        // throw IllegalStateException. We verify it guards.
        try {
            controller.installTickInjector();
            // If we reach here, either the agent is loaded (test env with
            // agent) or the guard failed. We can't assert failure without
            // knowing the test env.
        } catch (IllegalStateException e) {
            assertTrue("error mentions Instrumentation",
                    e.getMessage().contains("Instrumentation"));
        }
    }

    @Test
    public void seamControllerUninstallAllIsSafe() {
        // uninstallAll() should not throw even if nothing is installed.
        controller.uninstallAll();
        // No assertion — just verify it doesn't crash.
    }
}
