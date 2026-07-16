package net.marcloud.mcp.core.ke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * PHASE T (T.5): the Timeline folds every EventBus event into a safe, tick-stamped
 * entry, bounded by its ring capacity, oldest-first.
 *
 * <p><b>Option C:</b> raw packet events are excluded from the timeline so the
 * ungated {@code timeline_tail} tool cannot leak packet traffic — verified by
 * {@link #packetEventsAreNotFoldedIntoTimeline()} and
 * {@link #nonPacketEventsStillRecordedAlongsidePackets()}.
 */
public class TimelineTest {

    @Test
    public void recordsEventsWithTickIdAndKind() {
        Timeline tl = new Timeline(16);
        tl.record(new TickEvent(7L, GameClock.Phase.START));
        List<Timeline.Entry> tail = tl.tail();
        assertEquals(1, tail.size());
        assertEquals("tickId is carried onto the timeline", 7L, tail.get(0).tickId());
        assertEquals("kind is the event simple name", "TickEvent", tail.get(0).kind());
    }

    @Test
    public void attachViaBusFoldsPublishedEvents() {
        EventBus bus = new EventBus();
        Timeline tl = new Timeline(16);
        tl.attach(bus);
        bus.publish(new TickEvent(1L));
        bus.publish(new TickEvent(2L));
        List<Timeline.Entry> tail = tl.tail();
        assertEquals("both published events landed on the timeline", 2, tail.size());
        assertEquals(1L, tail.get(0).tickId());
        assertEquals(2L, tail.get(1).tickId());
    }

    @Test
    public void ringEvictsOldestWhenOverCapacity() {
        Timeline tl = new Timeline(3);
        for (long i = 1; i <= 5; i++) {
            tl.record(new TickEvent(i));
        }
        List<Timeline.Entry> tail = tl.tail();
        assertEquals("capped at capacity", 3, tail.size());
        assertEquals("oldest evicted, keeps newest 3 oldest-first", 3L, tail.get(0).tickId());
        assertEquals(5L, tail.get(2).tickId());
    }

    @Test
    public void tailLimitReturnsMostRecent() {
        Timeline tl = new Timeline(16);
        for (long i = 1; i <= 6; i++) {
            tl.record(new TickEvent(i));
        }
        List<Timeline.Entry> last2 = tl.tail(2);
        assertEquals(2, last2.size());
        assertEquals(5L, last2.get(0).tickId());
        assertEquals(6L, last2.get(1).tickId());
        assertTrue("negative limit yields none", tl.tail(-1).isEmpty());
    }

    /**
     * Option C regression: a raw inbound packet event carrying a recognisable
     * packet class name must NOT surface on the timeline. Before the fix this
     * failed — the SeamPacketInboundEvent was folded in and packetSummary()
     * rendered "S08PacketPlayerPosLook ..." into the entry, leaking packet
     * content through the ungated {@code timeline_tail} tool.
     */
    @Test
    public void packetEventsAreNotFoldedIntoTimeline() {
        EventBus bus = new EventBus();
        Timeline tl = new Timeline(16);
        tl.attach(bus);

        // A reference-free snapshot with an identifiable packet class name, exactly
        // as the Netty tap publishes it.
        NettyTap.PacketTapHandler.MessageSnapshot snap =
                new NettyTap.PacketTapHandler.MessageSnapshot(
                        "net.minecraft.network.play.server.S08PacketPlayerPosLook",
                        "x=1.0 y=2.0 z=3.0", null);
        bus.publish(new SeamPacketInboundEvent(snap));

        List<Timeline.Entry> tail = tl.tail();
        assertTrue("packet event must not be folded into the timeline", tail.isEmpty());
        for (Timeline.Entry e : tail) {
            assertTrue("no entry may name the packet class",
                    !String.valueOf(e.kind()).contains("S08PacketPlayerPosLook")
                            && !String.valueOf(e.summary()).contains("S08PacketPlayerPosLook"));
        }
    }

    /**
     * Option C must not harm non-packet observations: a TickEvent published on the
     * same bus alongside a packet event still lands on the timeline (only the
     * packet event is dropped).
     */
    @Test
    public void nonPacketEventsStillRecordedAlongsidePackets() {
        EventBus bus = new EventBus();
        Timeline tl = new Timeline(16);
        tl.attach(bus);

        bus.publish(new SeamPacketInboundEvent(
                new NettyTap.PacketTapHandler.MessageSnapshot(
                        "net.minecraft.network.play.server.S08PacketPlayerPosLook",
                        "x=1.0", null)));
        bus.publish(new TickEvent(42L));

        List<Timeline.Entry> tail = tl.tail();
        assertEquals("only the non-packet event survives", 1, tail.size());
        assertEquals("kind is the non-packet event", "TickEvent", tail.get(0).kind());
        assertEquals(42L, tail.get(0).tickId());
    }
}
