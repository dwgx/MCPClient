package net.marcloud.mcp.core.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bounded, drop-oldest sink for {@link DebugEvent}s pushed from the native JVMTI
 * callback thread, plus a listener fan-out. Two hard constraints drive the
 * design:
 * <ul>
 *   <li>The native callback thread may hold a raw JVMTI monitor, so
 *       {@link #offer} must NEVER block and must NEVER re-enter JVMTI — it does a
 *       non-blocking enqueue (dropping the oldest event if full) and dispatches
 *       to listeners inside a Throwable boundary.</li>
 *   <li>{@link #INSTANCE} exists eagerly (before McpCore wiring) so a stray early
 *       native callback can never NPE.</li>
 * </ul>
 */
public final class DebugEventQueue {

    /** The process-wide sink the native bridge offers into. */
    public static final DebugEventQueue INSTANCE = new DebugEventQueue(1024);

    private final ArrayBlockingQueue<DebugEvent> recent;
    private final CopyOnWriteArrayList<DebugEventListener> listeners = new CopyOnWriteArrayList<>();

    public DebugEventQueue(int capacity) {
        this.recent = new ArrayBlockingQueue<>(Math.max(16, capacity));
    }

    /**
     * Enqueue an event (non-blocking, drop-oldest) and dispatch to listeners.
     * Safe to call from the JVMTI callback thread: never blocks, never throws.
     */
    public void offer(DebugEvent e) {
        if (e == null) {
            return;
        }
        // Drop-oldest so a single-step storm can't wedge the callback thread.
        while (!recent.offer(e)) {
            recent.poll();
        }
        for (DebugEventListener l : listeners) {
            try {
                l.onDebugEvent(e);
            } catch (Throwable ignored) {
                // A misbehaving listener must not break the callback thread
                // (same isolation discipline as EventBus.publish).
            }
        }
    }

    public void addListener(DebugEventListener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    /** Snapshot of the retained recent events (oldest first). */
    public List<DebugEvent> snapshot() {
        return new ArrayList<>(recent);
    }

    public void clear() {
        recent.clear();
    }

    public int size() {
        return recent.size();
    }
}
