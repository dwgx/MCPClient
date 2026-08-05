package net.marcloud.mcp.core.ke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import org.junit.Test;

import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.GameEvent;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * Boundary pins for the kernel event plumbing (EventBus dispatch cache, Timeline
 * Option C packet gate, GameClock liveness stamp, PacketJournal projection,
 * PacketFilter noise drop).
 *
 * <p>The existing suite drives each of these seams in a shape that cannot tell the
 * real behaviour from a degenerate one: it subscribes before the first publish, it
 * only ever publishes INBOUND packets, it hard-codes empty summaries in fixtures,
 * and it asserts {@code after >= before} on a monotonic stamp. Each test here pins
 * the VALUE a caller actually depends on, so a silent regression in the plumbing
 * fails loudly instead of quietly emptying an MCP tool's output.
 */
public class KernelEventPlumbingIsPinnedAtItsBoundaryTest {

    private static final String KEEPALIVE = "net.minecraft.network.play.server.S00PacketKeepAlive";
    private static final String TIME_UPDATE = "net.minecraft.network.play.server.S03PacketTimeUpdate";
    private static final String VELOCITY = "net.minecraft.network.play.server.S12PacketEntityVelocity";
    private static final String POS_LOOK = "net.minecraft.network.play.server.S08PacketPlayerPosLook";
    private static final String PLAYER_MOVE = "net.minecraft.network.play.client.C03PacketPlayer";

    /** One nanosecond budget floor: a real advance separated by a 2ms sleep must clear 1ms. */
    private static final long ONE_MILLI_NS = 1_000_000L;

    private static NettyTap.PacketTapHandler.MessageSnapshot snap(String cls, String summary) {
        return new NettyTap.PacketTapHandler.MessageSnapshot(cls, summary, null);
    }

    /**
     * EventBus.java:49 — subscribe() must clear the WHOLE dispatch cache, not just
     * the key equal to the declared type. Cache keys are CONCRETE event classes, so
     * evicting only {@code GameEvent.class} leaves the warm {@code TickEvent} entry
     * (built when nobody was listening) in place and the new subscriber never fires.
     * This is the SSE case: SseStream subscribes GameEvent.class per HTTP connection,
     * long after the tick seam has been publishing TickEvents.
     */
    @Test
    public void subscribeInvalidatesTheWholeDispatchCacheNotJustItsOwnKey() {
        EventBus bus = new EventBus();
        // Warm the cache for the CONCRETE class while there are no subscribers at all.
        bus.publish(new TickEvent(1L));

        AtomicInteger seen = new AtomicInteger();
        bus.subscribe(GameEvent.class, e -> seen.incrementAndGet());

        bus.publish(new TickEvent(2L));
        bus.publish(new TickEvent(3L));

        assertEquals("a base-type subscriber that joins mid-session must receive every later "
                + "event; a per-key eviction leaves the warm concrete-class entry empty and "
                + "the SSE client gets zero frames for the rest of its connection",
                2, seen.get());
    }

    /**
     * Same defect through the production wiring: Timeline.attach subscribes the
     * GameEvent base type, so a Timeline wired up after any event has flowed must
     * still fold subsequent events. Pins the tickIds, not just "non-empty".
     */
    @Test
    public void timelineAttachedAfterEventsAlreadyFlowedStillFoldsLaterEvents() {
        EventBus bus = new EventBus();
        bus.publish(new TickEvent(10L));   // warms TickEvent with an empty matcher list

        Timeline tl = new Timeline(16);
        tl.attach(bus);

        bus.publish(new TickEvent(11L));
        bus.publish(new TickEvent(12L));

        List<Timeline.Entry> tail = tl.tail();
        assertEquals("timeline_tail is useless if attaching after the first tick silently "
                + "wires a dead subscription", 2, tail.size());
        assertEquals("the tick published right after attach must be the oldest entry",
                11L, tail.get(0).tickId());
        assertEquals(12L, tail.get(1).tickId());
    }

    /**
     * Timeline.java:91 (Option C) — the OUTBOUND half of the Netty tap must be
     * dropped too. The existing regression tests only publish inbound events, so
     * losing the outbound clause lets packetSummary() render client packet content
     * ("C03PacketPlayer x=.. y=.. z=..") into the ungated R3 timeline_tail tool,
     * surviving a revoke of CAP_NETWORK_RECV_TAP.
     *
     * <p>The positive half is asserted in the same test: the TickEvent published on
     * the same bus still lands, so "drop everything" does not satisfy this.
     */
    @Test
    public void outboundPacketEventsAreDroppedFromTimelineWhileTicksStillLand() {
        EventBus bus = new EventBus();
        Timeline tl = new Timeline(16);
        tl.attach(bus);

        bus.publish(new SeamPacketOutboundEvent(snap(PLAYER_MOVE, "x=1.0 y=64.0 z=-3.5")));
        bus.publish(new TickEvent(42L));

        List<Timeline.Entry> tail = tl.tail();
        assertEquals("the outbound packet must not reach the ungated timeline, and the "
                + "non-packet observation must survive the filter", 1, tail.size());
        assertEquals("the surviving entry is the tick, not the packet",
                "TickEvent", tail.get(0).kind());
        assertEquals(42L, tail.get(0).tickId());
        // Reachable content check: the one surviving entry may not name or quote the
        // outbound packet, in kind or in summary.
        for (Timeline.Entry e : tail) {
            assertFalse("timeline_tail would leak the outbound packet class to an R3 caller",
                    String.valueOf(e.kind()).contains("C03PacketPlayer"));
            assertFalse("timeline_tail would leak outbound packet field content to an R3 caller",
                    String.valueOf(e.summary()).contains("x=1.0"));
        }
        assertEquals("a TickEvent has no toString projection, so its summary stays empty — "
                + "any content here came from a packet", "", tail.get(0).summary());
    }

    /**
     * GameClock.java:105 — lastTickMonoNs() must report the nanoTime of the real
     * advance. clock_now publishes it so a caller can tell "game ticking" from
     * "game frozen / seam dead"; pinned to a constant, tick age is uncomputable.
     * Brackets the stamp between two readings taken around the advance, which a
     * constant cannot satisfy.
     */
    @Test
    public void lastTickMonoNsReportsTheNanoTimeOfTheAdvanceThatJustHappened() {
        GameClock c = new GameClock();
        long before = System.nanoTime();
        c.advance();
        long after = System.nanoTime();

        long stamp = c.lastTickMonoNs();
        assertTrue("clock_now's lastTickMonoNs must be the instant the seam last fired "
                        + "(observed window [" + before + "," + after + "], got " + stamp
                        + ") — a caller computes tick age from it to decide whether the game "
                        + "is alive",
                stamp >= before && stamp <= after);
    }

    /**
     * The same stamp must also MOVE between ticks: a reader diffs two samples to get
     * tick age, so a frozen (or constant) field reads as "the last tick happened an
     * ever-growing time ago" no matter how healthy the seam is. The 2ms sleep makes
     * the expected delta far larger than clock resolution.
     */
    @Test
    public void lastTickMonoNsMovesForwardBetweenTwoTicksSoTickAgeIsComputable()
            throws InterruptedException {
        GameClock c = new GameClock();
        c.advance();
        long first = c.lastTickMonoNs();
        Thread.sleep(2L);
        c.advance();
        long second = c.lastTickMonoNs();

        assertTrue("two ticks 2ms apart must produce stamps at least 1ms apart (got "
                        + (second - first) + "ns); without that, a reader cannot distinguish a "
                        + "live tick seam from a dead one",
                second - first >= ONE_MILLI_NS);
    }

    /**
     * PacketJournal.java:156 — the tap's reference-free summary must reach the
     * Entry. It is the entire human-readable payload of packets_tail / packet_get;
     * an empty string leaves every row as a bare class name. Every existing fixture
     * hard-codes summary="", so pass-through was never observable.
     */
    @Test
    public void journalEntryCarriesTheTapSummaryVerbatimNotAnEmptyString() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        bus.publish(new SeamPacketInboundEvent(snap(POS_LOOK, "x=1.0 y=64.0 z=-3.5 yaw=90.0")));

        PacketJournal.Entry e = j.tail().get(0);
        assertEquals("packets_tail and packet_get render exactly this string; dropping it "
                        + "reverts the summarizer work and leaves callers with class names only",
                "x=1.0 y=64.0 z=-3.5 yaw=90.0", e.summary());
        assertTrue("toString appends the summary after the direction arrow and class name",
                e.toString().endsWith("<- S08PacketPlayerPosLook x=1.0 y=64.0 z=-3.5 yaw=90.0"));
    }

    /**
     * PacketJournal.java:150 — byteLen must be the {@code -1} sentinel for a decoded
     * object snapshot, because ObserveTools OMITS the field when {@code byteLen < 0}.
     * A 0 turns "not a wire buffer, length unknown" into the affirmative reading
     * "this packet was zero bytes on the wire".
     *
     * <p>Positive counterpart in the same test: a real ByteBuf still reports its
     * readable length, so returning -1 unconditionally does not satisfy this.
     */
    @Test
    public void decodedSnapshotUsesTheMinusOneSentinelWhileByteBufReportsItsRealLength() {
        EventBus bus = new EventBus();
        PacketJournal j = new PacketJournal(16);
        j.attach(bus);

        bus.publish(new SeamPacketInboundEvent(snap(POS_LOOK, "x=1.0")));
        bus.publish(new SeamPacketInboundEvent(
                Unpooled.unmodifiableBuffer(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4}))));

        List<PacketJournal.Entry> t = j.tail();
        assertEquals(2, t.size());
        assertEquals("a decoded object was never measured on the wire; the -1 sentinel is what "
                        + "makes packets_tail omit byteLen instead of publishing a fake 0-byte "
                        + "reading", -1, t.get(0).byteLen());
        assertEquals("a real wire buffer must still report its readable byte count",
                4, t.get(1).byteLen());
        assertEquals("a raw ByteBuf carries no tap summary, so the row falls back to the class",
                "", t.get(1).summary());
    }

    /**
     * PacketFilter.java:79 — the default noise drop must actually fire for real
     * entries. Gated behind {@code className == null} it never triggers (classOf
     * never yields null), so 20/s keepalives plus time and velocity updates flood
     * every packets_tail response and bury the signal the caller asked for.
     */
    @Test
    public void defaultNoiseDropRejectsAllThreeHighFrequencyPacketClasses() {
        PacketFilter f = PacketFilter.of(PacketFilter.Dir.ANY, null, null, true);

        assertFalse("keepalives arrive ~20/s and would crowd out every real packet in a "
                        + "packets_tail page",
                f.accepts("S00PacketKeepAlive", KEEPALIVE, PacketJournal.Dir.IN));
        assertFalse("time updates are per-tick noise with no diagnostic value",
                f.accepts("S03PacketTimeUpdate", TIME_UPDATE, PacketJournal.Dir.IN));
        assertFalse("entity velocity updates arrive per entity per tick",
                f.accepts("S12PacketEntityVelocity", VELOCITY, PacketJournal.Dir.IN));
    }

    /**
     * The positive counterpart to the noise drop: signal packets must pass, and a
     * caller who explicitly asks for a noise class must get it. A filter that
     * rejects everything would break packets_tail rather than de-noise it.
     */
    @Test
    public void noiseDropKeepsSignalPacketsAndYieldsToAnExplicitRequestForNoise() {
        PacketFilter dropping = PacketFilter.of(PacketFilter.Dir.ANY, null, null, true);
        assertTrue("position updates are the packets a caller is usually looking for",
                dropping.accepts("S08PacketPlayerPosLook", POS_LOOK, PacketJournal.Dir.IN));
        assertTrue("outbound movement is signal too; the noise list is closed, not a whitelist",
                dropping.accepts("C03PacketPlayer", PLAYER_MOVE, PacketJournal.Dir.OUT));

        PacketFilter asked = PacketFilter.of(PacketFilter.Dir.ANY,
                List.of("S00PacketKeepAlive"), null, false);
        assertTrue("includeNoise=true / an explicit include must be able to reach a noise "
                        + "class, otherwise keepalive latency is undebuggable",
                asked.accepts("S00PacketKeepAlive", KEEPALIVE, PacketJournal.Dir.IN));
        assertTrue("the pass-through filter drops nothing at all",
                PacketFilter.NONE.accepts("S00PacketKeepAlive", KEEPALIVE, PacketJournal.Dir.IN));
    }
}
