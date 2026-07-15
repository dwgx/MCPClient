package net.marcloud.mcp.core.ke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import io.netty.buffer.Unpooled;
import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

public class PacketJournalTest {

    private static NettyTap.PacketTapHandler.MessageSnapshot snap(String cls) {
        return new NettyTap.PacketTapHandler.MessageSnapshot(cls, "", null);
    }

    private static NettyTap.PacketTapHandler.MessageSnapshot snapFields(
            String cls, java.util.Map<String, Object> fields) {
        return new NettyTap.PacketTapHandler.MessageSnapshot(cls, "", fields);
    }

    @Test
    public void structuredFieldsFlowFromSnapshotIntoEntry() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("hp", 20.0);
        fields.put("food", 18);
        bus.publish(new SeamPacketInboundEvent(
                snapFields("net.minecraft.network.play.server.S06PacketUpdateHealth", fields)));
        // a snapshot with null fields (B/C tier) must carry null through, not crash
        bus.publish(new SeamPacketInboundEvent(
                snap("net.minecraft.network.play.server.S00PacketKeepAlive")));

        List<PacketJournal.Entry> t = j.tail();
        assertEquals(2, t.size());
        assertEquals(20.0, ((Number) t.get(0).fields().get("hp")).doubleValue(), 0.0001);
        assertEquals(18, ((Number) t.get(0).fields().get("food")).intValue());
        assertTrue("B/C-tier snapshot carries null fields", t.get(1).fields() == null);
    }

    @Before
    public void resetClock() {
        GameClock.INSTANCE.reset();
    }

    @Test
    public void recordsInboundAndOutboundWithDir() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        bus.publish(new SeamPacketInboundEvent(
                snap("net.minecraft.network.play.server.S08PacketPlayerPosLook")));
        bus.publish(new SeamPacketOutboundEvent(
                snap("net.minecraft.network.play.client.C03PacketPlayer")));

        List<PacketJournal.Entry> t = j.tail();
        assertEquals(2, t.size());
        assertEquals(PacketJournal.Dir.IN, t.get(0).dir());
        assertTrue(t.get(0).packetClass().endsWith("S08PacketPlayerPosLook"));
        assertEquals(PacketJournal.Dir.OUT, t.get(1).dir());
        assertTrue(t.get(1).packetClass().endsWith("C03PacketPlayer"));
    }

    @Test
    public void onlyPacketEventsAreRecorded() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        // These are GameEvents but NOT Seam packet events — must be ignored.
        bus.publish(new TickEvent(5));

        assertEquals("journal subscribes to packet events only, not GameEvent base", 0, j.size());
    }

    @Test
    public void stampsTickIdFromEvent() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        GameClock.INSTANCE.advance(); // tick 1
        bus.publish(new SeamPacketInboundEvent(snap("a.B")));
        GameClock.INSTANCE.advance(); // tick 2
        bus.publish(new SeamPacketInboundEvent(snap("a.C")));

        List<PacketJournal.Entry> t = j.tail();
        assertEquals(1L, t.get(0).tickId());
        assertEquals(2L, t.get(1).tickId());
    }

    @Test
    public void assignsMonotonicUniqueSeq() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        for (int i = 0; i < 3; i++) {
            bus.publish(new SeamPacketInboundEvent(snap("a.B")));
        }
        List<PacketJournal.Entry> t = j.tail();
        assertEquals(1L, t.get(0).seq());
        assertEquals(2L, t.get(1).seq());
        assertEquals(3L, t.get(2).seq());
    }

    @Test
    public void ringEvictsOldestKeepsNewest() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(2);
        j.attach(bus);

        for (int i = 0; i < 3; i++) {
            bus.publish(new SeamPacketInboundEvent(snap("a.B")));
        }
        List<PacketJournal.Entry> t = j.tail();
        assertEquals(2, t.size());
        assertEquals("oldest (seq 1) evicted", 2L, t.get(0).seq());
        assertEquals(3L, t.get(1).seq());
    }

    @Test
    public void byIdFindsAndMisses() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        bus.publish(new SeamPacketInboundEvent(snap("a.B")));
        bus.publish(new SeamPacketInboundEvent(snap("a.C")));

        assertTrue(j.byId(1L).isPresent());
        assertEquals("a.B", j.byId(1L).get().packetClass());
        assertFalse(j.byId(9999L).isPresent());
    }

    @Test
    public void byteBufInboundRecordsLenNotBuffer() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        bus.publish(new SeamPacketInboundEvent(
                Unpooled.unmodifiableBuffer(Unpooled.wrappedBuffer(new byte[] {1, 2, 3}))));

        PacketJournal.Entry e = j.tail().get(0);
        assertEquals("io.netty.buffer.ByteBuf", e.packetClass());
        assertEquals(3, e.byteLen());
        // Entry fields are long/Dir/String/int only — no ByteBuf reference (reference-free at L7).
    }
}
