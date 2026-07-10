package net.marcloud.mcp.core.seam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.seam.events.SeamPacketOutboundEvent;
import org.junit.Test;

public class NettyTapAuditTest {

    @Test
    public void byteBufObserverCannotMutateOutboundWireThroughUnwrap() {
        EventBus bus = new EventBus();
        AtomicReference<ByteBuf> observed = new AtomicReference<>();
        bus.subscribe(SeamPacketOutboundEvent.class, event -> {
            ByteBuf snapshot = (ByteBuf) event.rawMsg();
            observed.set(snapshot);
            ByteBuf writable = snapshot;
            while (writable.unwrap() != null && writable.unwrap() != writable) {
                writable = writable.unwrap();
            }
            writable.setByte(0, 99);
        });

        EmbeddedChannel channel = new EmbeddedChannel(new NettyTap.PacketTapHandler(bus));
        ByteBuf live = Unpooled.buffer().writeByte(7).writeByte(8);
        assertTrue(channel.writeOutbound(live));

        ByteBuf wire = channel.readOutbound();
        assertNotSame("observer receives a detached buffer", live, observed.get());
        assertEquals("wire content survives mutation through unwrap", 7, wire.getUnsignedByte(0));
        wire.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void decodedObjectObserverReceivesMetadataNotLiveMessage() {
        EventBus bus = new EventBus();
        AtomicReference<Object> observed = new AtomicReference<>();
        bus.subscribe(SeamPacketOutboundEvent.class, event -> {
            observed.set(event.rawMsg());
            if (event.rawMsg() instanceof MutableMessage message) {
                message.value = "mutated";
            }
        });

        EmbeddedChannel channel = new EmbeddedChannel(new NettyTap.PacketTapHandler(bus));
        MutableMessage live = new MutableMessage("original");
        assertTrue(channel.writeOutbound(live));

        assertTrue(observed.get() instanceof NettyTap.PacketTapHandler.MessageSnapshot);
        assertEquals("subscriber cannot reach mutable wire object", "original", live.value);
        assertSame("the real message continues down the pipeline", live, channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void removeAllCleansSameHandlerNameFromEveryReconnectChannel() {
        EventBus bus = new EventBus();
        NettyTap tap = new NettyTap(new GameAccess(), bus);
        NettyTap.PacketTapHandler handler = new NettyTap.PacketTapHandler(bus);
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();

        assertTrue(tap.installHandler(first, "packet-tap", handler));
        assertTrue(tap.installHandler(second, "packet-tap", handler));
        tap.removeAll();

        assertNull("stale channel handler removed", first.pipeline().get("packet-tap"));
        assertNull("current channel handler removed", second.pipeline().get("packet-tap"));
        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
    }

    private static final class MutableMessage {
        private String value;

        private MutableMessage(String value) {
            this.value = value;
        }
    }
}
