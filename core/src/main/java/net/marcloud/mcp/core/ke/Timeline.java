package net.marcloud.mcp.core.ke;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.GameEvent;

/**
 * A fixed-size ring buffer of recent observations placed on the single
 * {@link GameClock} timeline (PHASE T). Every {@link GameEvent} published on the
 * {@link EventBus} is folded into a compact, immutable {@link Entry}
 * {@code {tickId, arrivalMono, kind, summary}} so a caller can reconstruct the
 * ordered story of the last N events — "what happened, in what tick order" —
 * without holding live game objects.
 *
 * <p><b>Why a projection, not the raw events:</b> a {@link GameEvent} may
 * reference live packets / chat components (L7 boundary). The Timeline stores only
 * a String kind + a short String summary, so what a reader (or an MCP tool) sees
 * is a safe, deep-copied snapshot — never a mutable game reference. This is the
 * {@code drivers}/tool-facing read model of the event stream.
 *
 * <p><b>Threading:</b> {@link #record} is called from {@link EventBus#publish}
 * (game thread, Netty worker — wherever the event fired); {@link #tail} is read
 * from MCP tool threads. A single lock guards the ring, mirroring
 * {@code PacketLog}. Recording is O(1) and allocation-light (one Entry).
 */
public final class Timeline {

    /**
     * One observation on the timeline: the tick it belongs to, the monotonic
     * arrival time, a short kind tag (usually the event's simple class name), and
     * a summary string (safe projection — never a live game object).
     */
    public record Entry(long tickId, long arrivalMono, String kind, String summary) {
        @Override
        public String toString() {
            return "t" + tickId + " " + kind + (summary == null || summary.isEmpty() ? "" : " " + summary);
        }
    }

    private final Object lock = new Object();
    private final Entry[] ring;
    private int head;   // next write index
    private int size;   // number of valid entries

    /** A timeline holding up to {@code capacity} recent entries (min 1). */
    public Timeline(int capacity) {
        this.ring = new Entry[Math.max(1, capacity)];
    }

    /**
     * Subscribe this timeline to every {@link GameEvent} on {@code bus}, folding
     * each into an {@link Entry}. Registers ONE subscription for the {@code
     * GameEvent} base type, so all current and future event subclasses are
     * captured without per-type wiring. The kind is the event's simple class name;
     * the summary is {@link #summarize(GameEvent)} (short + reference-free).
     */
    public void attach(EventBus bus) {
        if (bus == null) {
            return;
        }
        bus.subscribe(GameEvent.class, this::record);
    }

    /** Fold one event into the ring. Never throws into the publisher. */
    public void record(GameEvent event) {
        if (event == null) {
            return;
        }
        Entry e;
        try {
            e = new Entry(event.tickId(), event.timestampNanos(),
                    event.getClass().getSimpleName(), summarize(event));
        } catch (Throwable t) {
            // A misbehaving summary must never break the publishing (game) thread.
            return;
        }
        synchronized (lock) {
            ring[head] = e;
            head = (head + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
        }
    }

    /**
     * A short, reference-free summary of an event for the timeline. The base
     * implementation calls {@code toString()} and truncates — subclasses of
     * GameEvent that expose a safe {@code packetType()} / {@code reasonText()} etc.
     * already render cleanly. PHASE P's summarizers will enrich this per packet;
     * for now this keeps the entry compact and holds no live reference.
     */
    private static String summarize(GameEvent event) {
        // Prefer nothing over a giant/looping toString; cap hard.
        String s;
        try {
            s = event.toString();
        } catch (Throwable t) {
            return "";
        }
        if (s == null) {
            return "";
        }
        // Drop the default "ClassName@hash" noise — kind already carries the class.
        String simple = event.getClass().getSimpleName();
        if (s.startsWith(event.getClass().getName() + "@") || s.equals(simple)) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
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
