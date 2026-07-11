package net.marcloud.mcp.core.drivers.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size ring buffer of recent GUI actions, recorded as
 * {@code screen-before → action → screen-after}. Fed by {@link GuiActions} after
 * every {@code click}/{@code type}/{@code press}; read by the {@code gui_trajectory}
 * tool so the AI can review "what did I just do to this GUI and what changed".
 *
 * <p>Modelled on {@link net.marcloud.mcp.core.drivers.world.PacketLog}: fixed capacity,
 * {@code record*}, {@code recent()} / {@code recent(n)}. To stay cheap it stores
 * only the compact structural {@linkplain GuiSnapshotService#fingerprint fingerprint}
 * of the screen before and after the action — never a full snapshot.
 *
 * <p>Thread-safe: {@link GuiActions} publishes from the game thread, MCP reads from
 * others.
 */
public final class GuiTrajectory {

    /** Action kinds recorded in the trajectory. */
    public static final String KIND_CLICK = "click";
    public static final String KIND_TYPE = "type";
    public static final String KIND_PRESS = "press";

    /**
     * One recorded GUI action: what kind, which element/key, whether it succeeded
     * (with the human message), and the structural fingerprints of the screen
     * before and after the action ran.
     */
    public record Entry(long timeMillis, String kind, String elementId, boolean ok,
                        String message, String beforeFingerprint, String afterFingerprint) {
        @Override
        public String toString() {
            return (ok ? "[ok] " : "[fail] ") + kind + " " + elementId
                    + " : " + beforeFingerprint + " -> " + afterFingerprint;
        }
    }

    private final Object lock = new Object();
    private final Entry[] ring;
    private int head;   // next write index
    private int size;   // number of valid entries

    public GuiTrajectory(int capacity) {
        this.ring = new Entry[Math.max(1, capacity)];
    }

    /**
     * Record one action. {@code elementId} carries the element id for click/type
     * or the key for press. {@code before}/{@code after} are the screen
     * fingerprints captured around the action.
     */
    public void record(String kind, String elementId, boolean ok, String message,
                       String before, String after) {
        add(new Entry(System.currentTimeMillis(), kind, elementId, ok, message, before, after));
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
