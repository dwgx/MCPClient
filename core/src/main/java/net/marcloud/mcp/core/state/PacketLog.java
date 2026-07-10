package net.marcloud.mcp.core.state;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size ring buffer of recent packet observations (in and out). Fed by the
 * network hooks; read when building a disconnect report so the AI can see "the
 * last N packets before I got kicked" — the heart of the "why was I kicked" use
 * case. Thread-safe: hooks publish from network threads, MCP reads from others.
 */
public final class PacketLog {

    /** One recorded packet: direction, type name, wall-clock time. */
    public record Entry(boolean inbound, String type, long timeMillis) {
        @Override
        public String toString() {
            return (inbound ? "<- " : "-> ") + type;
        }
    }

    private final Object lock = new Object();
    private final Entry[] ring;
    private int head;   // next write index
    private int size;   // number of valid entries

    public PacketLog(int capacity) {
        this.ring = new Entry[Math.max(1, capacity)];
    }

    /** Record an inbound packet type. */
    public void recordInbound(String type) {
        add(new Entry(true, type, System.currentTimeMillis()));
    }

    /** Record an outbound packet type. */
    public void recordOutbound(String type) {
        add(new Entry(false, type, System.currentTimeMillis()));
    }

    private void add(Entry e) {
        synchronized (lock) {
            ring[head] = e;
            head = (head + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
        }
    }

    /** Snapshot of recent entries, oldest first. */
    public List<Entry> recent() {
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
    public List<Entry> recent(int n) {
        int k = Math.max(0, n);
        List<Entry> all = recent();
        int from = Math.max(0, all.size() - k);
        return all.subList(from, all.size());
    }

    public int capacity() {
        return ring.length;
    }
}
