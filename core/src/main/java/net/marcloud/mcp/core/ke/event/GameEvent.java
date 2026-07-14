package net.marcloud.mcp.core.ke.event;

import net.marcloud.mcp.core.ke.GameClock;

/**
 * Marker base for all events published on the {@link EventBus}. Events are
 * immutable snapshots of something that happened in the game (a packet arrived,
 * the connection dropped, a tick elapsed), delivered to subscribers so the MCP
 * layer can observe without polling.
 *
 * <p><b>PHASE T:</b> every event now captures, at construction, the tickId that
 * was current on the single {@link GameClock} — so any observation can be placed
 * on the one authoritative timeline. Events created ON the game thread (a tick,
 * a hook fire) get the tick they belong to; events created on an OFF thread (a
 * Netty-worker inbound/outbound packet) get {@link GameClock#lastCompletedTick()}
 * — "the tick the game had reached when this arrived", the correct attribution
 * for an asynchronous arrival. {@code arrivalMono} is {@link #timestampNanos()}.
 */
public abstract class GameEvent {

    private final long timestampNanos = System.nanoTime();
    private final long tickId = GameClock.INSTANCE.lastCompletedTick();

    /** Monotonic time the event was created (for ordering/latency). Alias: arrivalMono. */
    public long timestampNanos() {
        return timestampNanos;
    }

    /**
     * The {@link GameClock} tickId current when this event was created. For a
     * game-thread event that is the tick in progress; for an off-thread event
     * (packet on a Netty worker) it is the last tick the game completed. {@code 0}
     * before the first tick / when the clock never armed.
     */
    public long tickId() {
        return tickId;
    }
}
