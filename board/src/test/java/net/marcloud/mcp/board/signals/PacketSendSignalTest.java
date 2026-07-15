package net.marcloud.mcp.board.signals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.Signal;
import net.marcloud.mcp.board.Trace;
import org.junit.Test;

/**
 * Contract for {@link PacketSendSignal}: it is the packet-level twin of
 * {@link ChatSendSignal}, so — unlike the plain world signals — it MUST be a
 * {@link Signal.Cancellable} in the {@code PRE} phase, round-trip its packet-class
 * payload, and support {@code cancel(reason)} / {@code reason()}.
 */
public class PacketSendSignalTest {

    @Test
    public void isCancellableInPrePhase() {
        PacketSendSignal s = new PacketSendSignal("some.Packet");
        assertTrue("PacketSendSignal must be Cancellable (it is a veto signal)",
                s instanceof Signal.Cancellable);
        assertEquals(Signal.Cancellable.State.PRE, s.state());
    }

    @Test
    public void roundTripsPacketClass() {
        assertEquals("net.minecraft.network.play.client.C02PacketUseEntity",
                new PacketSendSignal("net.minecraft.network.play.client.C02PacketUseEntity").packetClass());
    }

    @Test
    public void cancelWithReasonRecordsIt() {
        PacketSendSignal s = new PacketSendSignal("x");
        assertFalse(s.isCancelled());
        s.cancel("blocked");
        assertTrue(s.isCancelled());
        assertEquals("blocked", s.reason());
    }

    @Test
    public void plainCancelLeavesReasonNull() {
        PacketSendSignal s = new PacketSendSignal("x");
        s.cancel();
        assertTrue(s.isCancelled());
        assertNull(s.reason());
    }

    @Test
    public void travelsOnTraceAndSubscriberCanVeto() {
        Trace trace = new Trace();
        trace.subscribe(PacketSendSignal.class, s -> s.cancel("nope"));
        PacketSendSignal s = new PacketSendSignal("x");
        Object published = trace.publish(s);
        assertTrue(((PacketSendSignal) published).isCancelled());
        assertEquals("nope", ((PacketSendSignal) published).reason());
    }
}
