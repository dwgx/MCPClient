package net.marcloud.mcp.core.flt.seam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import org.junit.Test;

/**
 * KI-9 teeth: the built-in tap must observe INBOUND packets on a pipeline whose
 * tail is a {@link SimpleChannelInboundHandler} that consumes them (the real
 * vanilla {@code NetworkManager} shape). Adding the tap with {@code addLast}
 * (behind the terminal) sees zero inbound; installing BEFORE the terminal sees
 * every inbound exactly once while the terminal still consumes it.
 */
public class NettyTapInboundBeforeTerminalTest {

    /** A stand-in for vanilla NetworkManager: consumes inbound, never re-fires. */
    private static final class TerminalConsumer extends SimpleChannelInboundHandler<Object> {
        final AtomicInteger consumed = new AtomicInteger();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            consumed.incrementAndGet();
            // Deliberately does NOT call fireChannelRead — mirrors the terminal.
        }
    }

    private static NettyTap tapWithoutGame(EventBus bus) {
        // GameAccess is only used by acquireChannel(); these tests drive the
        // package-private channel-taking overloads directly, so a null game is fine.
        return new NettyTap(null, bus);
    }

    @Test
    public void addLastBehindTerminalSeesZeroInbound_reproducesBug() {
        EventBus bus = new EventBus();
        AtomicInteger events = new AtomicInteger();
        bus.subscribe(SeamPacketInboundEvent.class, e -> events.incrementAndGet());

        TerminalConsumer terminal = new TerminalConsumer();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", terminal);

        NettyTap tap = tapWithoutGame(bus);
        // Old behavior: addLast puts the tap AFTER the terminal on the inbound chain.
        assertTrue(tap.installHandler(channel, "mcp_packet_tap",
                new NettyTap.PacketTapHandler(bus)));

        channel.writeInbound("hello");

        assertEquals("terminal consumed the inbound", 1, terminal.consumed.get());
        assertEquals("tap behind terminal observes NOTHING (the bug)", 0, events.get());
        channel.finishAndReleaseAll();
    }

    @Test
    public void installBeforeTerminalObservesInboundExactlyOnce_fixed() {
        EventBus bus = new EventBus();
        AtomicInteger events = new AtomicInteger();
        bus.subscribe(SeamPacketInboundEvent.class, e -> events.incrementAndGet());

        TerminalConsumer terminal = new TerminalConsumer();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", terminal);

        NettyTap tap = tapWithoutGame(bus);
        assertTrue(tap.installBefore(channel, "mcp_packet_tap",
                new NettyTap.PacketTapHandler(bus), terminal));

        channel.writeInbound("hello");

        assertEquals("tap before terminal observes the inbound exactly once", 1, events.get());
        assertEquals("terminal still consumed it exactly once", 1, terminal.consumed.get());
        channel.finishAndReleaseAll();
    }

    @Test
    public void resolvesTerminalByIdentityEvenWhenRenamed() {
        EventBus bus = new EventBus();
        AtomicInteger events = new AtomicInteger();
        bus.subscribe(SeamPacketInboundEvent.class, e -> events.incrementAndGet());

        TerminalConsumer terminal = new TerminalConsumer();
        EmbeddedChannel channel = new EmbeddedChannel();
        // Registered under a NON-vanilla name; identity resolution must still find it.
        channel.pipeline().addLast("some_other_name", terminal);

        NettyTap tap = tapWithoutGame(bus);
        assertTrue(tap.installBefore(channel, "mcp_packet_tap",
                new NettyTap.PacketTapHandler(bus), terminal));

        channel.writeInbound("hello");

        assertEquals("resolved terminal by identity, observed inbound", 1, events.get());
        assertEquals(1, terminal.consumed.get());
        channel.finishAndReleaseAll();
    }

    @Test
    public void nullTerminalFallsBackToTailWithoutBreakingPipeline() {
        EventBus bus = new EventBus();
        AtomicInteger events = new AtomicInteger();
        bus.subscribe(SeamPacketInboundEvent.class, e -> events.incrementAndGet());

        // No terminal consumer in the pipeline at all.
        EmbeddedChannel channel = new EmbeddedChannel();

        NettyTap tap = tapWithoutGame(bus);
        // terminalInstance null and no "packet_handler"/SimpleChannelInboundHandler:
        // must fall back to addLast and NOT throw.
        assertTrue(tap.installBefore(channel, "mcp_packet_tap",
                new NettyTap.PacketTapHandler(bus), null));

        // With no terminal to consume, EmbeddedChannel leaves the inbound for read;
        // the point of this test is that install did not break the pipeline.
        channel.writeInbound("hello");
        assertEquals("tail tap still observes inbound when it is the last handler", 1, events.get());
        channel.finishAndReleaseAll();
    }
}
