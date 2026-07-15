package net.marcloud.mcp.core.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.board.Signal;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.core.flt.seam.NettyTap.PacketTapHandler.MessageSnapshot;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.DisconnectedEvent;
import net.marcloud.mcp.core.ke.event.events.HookFiredEvent;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import net.minecraft.util.ChatComponentText;
import org.junit.Before;
import org.junit.Test;

/**
 * PHASE E (E.2): the core→board world-event pump.
 *
 * <p>Wires a {@link BoardWorldEventBridge} to a real board {@link Trace} (via an
 * injected {@link BoardTraceLink}, board on the TEST classpath), records the SIMPLE
 * NAMES of whatever board {@code Signal}s land on the trace, then publishes core
 * events and asserts the exact mapping. Teeth:
 * <ul>
 *   <li>{@code DisconnectedEvent} → exactly one {@code DisconnectSignal} carrying
 *       the plain-text reason.</li>
 *   <li>inbound {@code S02PacketChat} snapshot → one {@code ChatReceiveSignal}
 *       carrying the parsed text.</li>
 *   <li>inbound {@code S23PacketBlockChange} snapshot → one {@code BlockChangeSignal}
 *       carrying the parsed coords + state.</li>
 *   <li>a NON-chat / non-whitelisted inbound snapshot → nothing.</li>
 *   <li>{@code TickEvent} / {@code HookFiredEvent} → nothing (bounded whitelist —
 *       the bridge subscribes leaf types, never the {@code GameEvent} base).</li>
 * </ul>
 */
public class BoardWorldEventBridgeTest {

    private Trace trace;
    private EventBus bus;
    private final List<String> published = new ArrayList<>();
    private final List<Signal> signals = new ArrayList<>();

    private static Method tracePublishMethod() {
        for (Method m : Trace.class.getMethods()) {
            if (m.getName().equals("publish") && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("Trace.publish not found");
    }

    @Before
    public void setUp() {
        trace = new Trace();
        // record every signal that reaches the trace, by simple name.
        trace.subscribe(Signal.class, s -> {
            published.add(s.getClass().getSimpleName());
            signals.add(s);
        });

        BoardTraceLink link = new BoardTraceLink();
        link.setTraceForTest(trace, tracePublishMethod());

        bus = new EventBus();
        new BoardWorldEventBridge(bus, link).attach();
    }

    private MessageSnapshot snap(String className, String summary) {
        return new MessageSnapshot(className, summary);
    }

    // ---- Tier-1 mappings ----------------------------------------------------

    @Test
    public void disconnectMapsToOneDisconnectSignal() {
        bus.publish(new DisconnectedEvent(new ChatComponentText("Kicked: afk")));
        assertEquals(1, published.size());
        assertEquals("DisconnectSignal", published.get(0));
    }

    @Test
    public void chatInboundMapsToOneChatReceiveSignal() {
        bus.publish(new SeamPacketInboundEvent(snap(
                "net.minecraft.network.play.server.S02PacketChat",
                "chat type=0 text=\"hello world\"")));
        assertEquals(1, published.size());
        assertEquals("ChatReceiveSignal", published.get(0));
    }

    // ---- Tier-2 mapping: block change --------------------------------------

    @Test
    public void blockChangeInboundMapsToOneBlockChangeSignal() {
        bus.publish(new SeamPacketInboundEvent(snap(
                "net.minecraft.network.play.server.S23PacketBlockChange",
                "blockChange at=10,64,-20 state=minecraft:stone")));
        assertEquals(1, published.size());
        assertEquals("BlockChangeSignal", published.get(0));
    }

    @Test
    public void blockChangeWithUnparseablePositionEmitsNothing() {
        // summarizer emits "at=?" when the packet had no BlockPos — no fake coords.
        bus.publish(new SeamPacketInboundEvent(snap(
                "net.minecraft.network.play.server.S23PacketBlockChange",
                "blockChange at=? state=air")));
        assertTrue("unparseable position => no signal", published.isEmpty());
    }

    // ---- bounded whitelist: teeth ------------------------------------------

    @Test
    public void nonChatInboundSnapshotEmitsNothing() {
        bus.publish(new SeamPacketInboundEvent(snap(
                "net.minecraft.network.play.server.S00PacketKeepAlive",
                "keepAlive id=42")));
        assertTrue("non-whitelisted inbound packet => no signal", published.isEmpty());
    }

    @Test
    public void tickEventEmitsNothing() {
        bus.publish(new TickEvent(1L));
        assertTrue("TickEvent is not whitelisted by this bridge => no signal",
                published.isEmpty());
    }

    @Test
    public void hookFiredEventEmitsNothing() {
        bus.publish(new HookFiredEvent(1, "net.minecraft.network.NetworkManager",
                "channelRead0", new Object[] { "x" }));
        assertTrue("HookFiredEvent is not whitelisted => no signal", published.isEmpty());
    }

    @Test
    public void rawByteBufSnapshotEmitsNothing() {
        // a non-MessageSnapshot rawMsg (e.g. a frozen ByteBuf) must be ignored.
        bus.publish(new SeamPacketInboundEvent(new Object()));
        assertTrue(published.isEmpty());
    }

    // ---- payload fidelity ---------------------------------------------------

    @Test
    public void disconnectSignalCarriesReasonText() throws Exception {
        bus.publish(new DisconnectedEvent(new ChatComponentText("banned")));
        assertEquals(1, signals.size());
        Signal s = signals.get(0);
        String reason = (String) s.getClass().getMethod("reason").invoke(s);
        assertEquals("banned", reason);
    }

    @Test
    public void chatReceiveSignalCarriesParsedText() throws Exception {
        bus.publish(new SeamPacketInboundEvent(snap(
                "net.minecraft.network.play.server.S02PacketChat",
                "chat type=0 text=\"gg wp\"")));
        Signal s = signals.get(0);
        String text = (String) s.getClass().getMethod("text").invoke(s);
        assertEquals("gg wp", text);
    }

    @Test
    public void blockChangeSignalCarriesParsedCoordsAndState() throws Exception {
        bus.publish(new SeamPacketInboundEvent(snap(
                "net.minecraft.network.play.server.S23PacketBlockChange",
                "blockChange at=1,2,3 state=minecraft:dirt")));
        Signal s = signals.get(0);
        Class<?> c = s.getClass();
        assertEquals(1, ((Integer) c.getMethod("x").invoke(s)).intValue());
        assertEquals(2, ((Integer) c.getMethod("y").invoke(s)).intValue());
        assertEquals(3, ((Integer) c.getMethod("z").invoke(s)).intValue());
        assertEquals("minecraft:dirt", c.getMethod("state").invoke(s));
    }

    @Test
    public void nullBusAttachIsSafe() {
        new BoardWorldEventBridge(null).attach(); // no throw
    }
}
