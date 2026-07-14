package net.marcloud.mcp.core.ke.event.events;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.GameEvent;

/**
 * Fired once per client tick (attached at the {@code Minecraft.runTick} seam).
 * Lets observers sample state on a regular cadence and lets time-based logic
 * run on the game thread.
 *
 * <p><b>PHASE T:</b> the tick number now comes from the single {@link GameClock}
 * (not a private seam counter), and the event carries the {@link GameClock.Phase}
 * so a subscriber can tell an entry tick from a (future) post-world one. The
 * legacy {@link #tickCount()} accessor is retained and returns the same value as
 * {@link #tickId()} for backward compatibility with existing subscribers.
 */
public final class TickEvent extends GameEvent {

    private final long tickId;
    private final GameClock.Phase phase;

    /** A tick at {@link GameClock.Phase#START} with the given id. */
    public TickEvent(long tickId) {
        this(tickId, GameClock.Phase.START);
    }

    /** A tick carrying its id and phase (null phase coerced to START). */
    public TickEvent(long tickId, GameClock.Phase phase) {
        this.tickId = tickId;
        this.phase = phase == null ? GameClock.Phase.START : phase;
    }

    /** The {@link GameClock} tickId this event represents. */
    public long tickId() {
        return tickId;
    }

    /** The phase of the tick. */
    public GameClock.Phase phase() {
        return phase;
    }

    /**
     * Legacy accessor — same value as {@link #tickId()}. Retained so existing
     * subscribers written against {@code tickCount()} keep compiling and behaving
     * unchanged. Prefer {@link #tickId()} in new code.
     */
    public long tickCount() {
        return tickId;
    }
}
