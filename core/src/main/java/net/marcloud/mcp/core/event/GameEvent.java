package net.marcloud.mcp.core.event;

/**
 * Marker base for all events published on the {@link EventBus}. Events are
 * immutable snapshots of something that happened in the game (a packet arrived,
 * the connection dropped, a tick elapsed), delivered to subscribers so the MCP
 * layer can observe without polling.
 */
public abstract class GameEvent {

    private final long timestampNanos = System.nanoTime();

    /** Monotonic time the event was created (for ordering/latency). */
    public long timestampNanos() {
        return timestampNanos;
    }
}
