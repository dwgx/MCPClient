package net.marcloud.mcp.core.ke;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;

/**
 * A fixed-size ring of recent NETWORK PACKETS placed on the {@link GameClock}
 * timeline (PHASE P). Unlike {@link Timeline} — which subscribes to the
 * {@code GameEvent} base type and folds EVERY event into a generic entry — this
 * journal subscribes ONLY to the two Netty-tap packet events
 * ({@link SeamPacketInboundEvent}/{@link SeamPacketOutboundEvent}), giving a
 * packet-only read model with direction, packet class, a stable per-packet
 * sequence id, and a reference-free summary.
 *
 * <p><b>Why a dedicated ring, not folded into Timeline:</b> {@code packet_get}
 * (P.7) needs to address a single packet, and {@code tickId} is not unique (many
 * packets per tick), so each entry carries a monotonic {@code seq}. The generic
 * Timeline entry has no dir/class/seq columns and its contract stays frozen.
 * Timeline still sees packets generically via its base-type subscription — the two
 * coexist by design.
 *
 * <p><b>Reference-free (L7):</b> an {@link Entry} holds only primitives, an enum,
 * and Strings — never a {@code ByteBuf} or live message. The tap already froze the
 * message (read-only ByteBuf copy, or a class-name/summary snapshot), so nothing
 * mutable escapes here either.
 *
 * <p><b>Threading:</b> {@link #record} runs on the publishing thread (a Netty
 * worker for packets); {@link #tail}/{@link #byId} are read from MCP tool threads.
 * A single lock guards the ring, mirroring {@link Timeline}.
 */
public final class PacketJournal {

    /** Packet direction relative to the client. */
    public enum Dir { IN, OUT }

    /**
     * One packet on the timeline: a stable session-unique {@code seq} (the address
     * {@code packet_get} uses), the {@code tickId} it belongs to, monotonic arrival
     * time, direction, packet class name, wire byte length ({@code -1} when the
     * message was a decoded object, not a ByteBuf), and a reference-free summary.
     */
    public record Entry(long seq, long tickId, long arrivalMono, Dir dir,
                        String packetClass, int byteLen, String summary,
                        Map<String, Object> fields) {

        /** The unqualified packet class name (last dot segment). */
        public String simpleName() {
            if (packetClass == null) {
                return "null";
            }
            int dot = packetClass.lastIndexOf('.');
            int dollar = packetClass.lastIndexOf('$');
            int cut = Math.max(dot, dollar);
            return cut >= 0 && cut + 1 < packetClass.length()
                    ? packetClass.substring(cut + 1) : packetClass;
        }

        @Override
        public String toString() {
            String arrow = dir == Dir.IN ? "<-" : "->";
            String s = arrow + " " + simpleName();
            return summary == null || summary.isEmpty() ? s : s + " " + summary;
        }
    }

    private final Object lock = new Object();
    private final Entry[] ring;
    private int head;       // next write index
    private int size;       // number of valid entries
    private long seqGen;    // monotonic id source (1-based)

    /** A journal holding up to {@code capacity} recent packets (min 1). */
    public PacketJournal(int capacity) {
        this.ring = new Entry[Math.max(1, capacity)];
    }

    /**
     * Subscribe to the two Netty-tap packet events on {@code bus}. Deliberately
     * does NOT subscribe to {@code GameEvent} — only packets are journaled.
     */
    public void attach(EventBus bus) {
        if (bus == null) {
            return;
        }
        bus.subscribe(SeamPacketInboundEvent.class, this::recordInbound);
        bus.subscribe(SeamPacketOutboundEvent.class, this::recordOutbound);
    }

    void recordInbound(SeamPacketInboundEvent e) {
        if (e == null) {
            return;
        }
        record(e.tickId(), e.timestampNanos(), Dir.IN, e.rawMsg());
    }

    void recordOutbound(SeamPacketOutboundEvent e) {
        if (e == null) {
            return;
        }
        record(e.tickId(), e.timestampNanos(), Dir.OUT, e.rawMsg());
    }

    private void record(long tickId, long arrivalMono, Dir dir, Object rawMsg) {
        Entry entry;
        try {
            entry = new Entry(0L, tickId, arrivalMono, dir,
                    classOf(rawMsg), lenOf(rawMsg), summaryOf(rawMsg), fieldsOf(rawMsg));
        } catch (Throwable t) {
            // A misbehaving projection must never break the publishing thread.
            return;
        }
        synchronized (lock) {
            long seq = ++seqGen;
            ring[head] = new Entry(seq, entry.tickId(), entry.arrivalMono(), entry.dir(),
                    entry.packetClass(), entry.byteLen(), entry.summary(), entry.fields());
            head = (head + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
        }
    }

    /** The packet class name from the frozen message (MessageSnapshot / ByteBuf / null). */
    private static String classOf(Object rawMsg) {
        if (rawMsg == null) {
            return "null";
        }
        if (rawMsg instanceof NettyTap.PacketTapHandler.MessageSnapshot snap) {
            return snap.className();
        }
        if (rawMsg instanceof ByteBuf) {
            return "io.netty.buffer.ByteBuf";
        }
        return rawMsg.getClass().getName();
    }

    /** Byte length for a ByteBuf message, or -1 for a decoded-object snapshot. */
    private static int lenOf(Object rawMsg) {
        if (rawMsg instanceof ByteBuf b) {
            return b.readableBytes();
        }
        return -1;
    }

    /** Reference-free summary: the MessageSnapshot's summary if present, else empty. */
    private static String summaryOf(Object rawMsg) {
        if (rawMsg instanceof NettyTap.PacketTapHandler.MessageSnapshot snap) {
            return snap.summary();
        }
        return "";
    }

    /**
     * Reference-free structured fields: the MessageSnapshot's typed projection if
     * present (A-tier packets), else null. The map is already an unmodifiable copy
     * of immutable scalars (built by {@code PacketView}), so nothing mutable escapes.
     */
    private static Map<String, Object> fieldsOf(Object rawMsg) {
        if (rawMsg instanceof NettyTap.PacketTapHandler.MessageSnapshot snap) {
            return snap.fields();
        }
        return null;
    }

    /** Snapshot of all entries, oldest first. */
    public List<Entry> tail() {
        synchronized (lock) {
            List<Entry> out = new ArrayList<>(size);
            int start = (head - size + ring.length) % ring.length;
            for (int i = 0; i < size; i++) {
                out.add(ring[(start + i) % ring.length]);
            }
            return out;
        }
    }

    /** Most recent {@code n} entries, oldest first. Negative {@code n} yields none. */
    public List<Entry> tail(int n) {
        int k = Math.max(0, n);
        List<Entry> all = tail();
        int from = Math.max(0, all.size() - k);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    /** Find an entry by its stable {@code seq} id, if still in the ring. */
    public Optional<Entry> byId(long seq) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                Entry e = ring[i];
                if (e != null && e.seq() == seq) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }
    }

    /** Ring capacity. */
    public int capacity() {
        return ring.length;
    }

    /** Number of entries currently held. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }
}
