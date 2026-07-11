package net.marcloud.mcp.core.seam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.seam.events.SeamPacketOutboundEvent;
import org.junit.Test;

/**
 * AUTOMATABLE. Full seam tap lifecycle — install → observe a real Netty write →
 * uninstall → confirm no further observation — driven against a real
 * {@link EmbeddedChannel} through {@link NettyTap}'s own install/remove
 * bookkeeping. {@code NettyTapAuditTest} proves the freeze/unwrap safety of a
 * single handler; {@code SeamControllerTest} proves the headless controller
 * refuses to install without a live channel. This fills the gap in between: the
 * install → fire → uninstall state machine observed end-to-end on a live channel.
 */
public class NettyTapLifecycleTest {

    @Test
    public void installObserveWriteThenUninstallStopsObservation() {
        EventBus bus = new EventBus();
        AtomicInteger outbound = new AtomicInteger(0);
        bus.subscribe(SeamPacketOutboundEvent.class, e -> outbound.incrementAndGet());

        NettyTap tap = new NettyTap(new GameAccess(), bus);
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyTap.PacketTapHandler handler = new NettyTap.PacketTapHandler(bus);

        // 1) install on the live pipeline.
        assertTrue("handler installs on the channel",
                tap.installHandler(channel, "mcp_packet_tap", handler));
        assertTrue("pipeline now carries the handler",
                channel.pipeline().get("mcp_packet_tap") != null);
        // Re-installing the same name is refused (no double-install).
        assertFalse("second install with the same name is refused",
                tap.installHandler(channel, "mcp_packet_tap", handler));

        // 2) a real outbound write is observed exactly once and passes through intact.
        ByteBuf wire = Unpooled.buffer().writeByte(0x2A).writeByte(0x2B);
        assertTrue(channel.writeOutbound(wire));
        assertEquals("write observed exactly once", 1, outbound.get());
        ByteBuf onWire = channel.readOutbound();
        assertEquals("wire byte survives observation (frozen)", 0x2A, onWire.getUnsignedByte(0));
        onWire.release();

        // 3) uninstall via removeAll — the handler is gone from the pipeline.
        tap.removeAll();
        assertNull("handler removed from the pipeline",
                channel.pipeline().get("mcp_packet_tap"));

        // 4) subsequent writes are no longer observed.
        ByteBuf second = Unpooled.buffer().writeByte(0x7F);
        assertTrue(channel.writeOutbound(second));
        assertEquals("no new observation after uninstall", 1, outbound.get());
        ByteBuf secondWire = channel.readOutbound();
        secondWire.release();

        channel.finishAndReleaseAll();
    }

    @Test
    public void removeHandlerReturnsFalseWhenNothingInstalled() {
        // Honest lifecycle: removing a tap that was never installed reports false,
        // not a pretend success. (A regression here would let uninstall lie.)
        NettyTap tap = new NettyTap(new GameAccess(), new EventBus());
        assertFalse("remove of an un-installed handler is honestly false",
                tap.removeHandler("never_installed"));
    }
}
