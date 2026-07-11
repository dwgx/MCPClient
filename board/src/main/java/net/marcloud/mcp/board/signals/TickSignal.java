package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * A game tick elapsed — the heartbeat of the client. Chips that need per-tick
 * logic (movement helpers, timers, automation) subscribe to this on a
 * {@link net.marcloud.mcp.board.Trace}.
 *
 * <p>This is the ONE canonical tick signal for the whole framework. It carries
 * both a monotonically increasing {@link #tick() tick number} (so subscribers can
 * reason about elapsed time without touching the game clock) and a {@link Phase}
 * (so they can act before or after the game's own tick logic). The no-arg form is
 * the common "end of tick" case, matching the design-doc example
 * {@code Board.trace().publish(new TickSignal())}.
 *
 * <p>Immutable; not cancellable — a tick is a fact, not a vetoable action.
 */
public final class TickSignal extends Signal {

    /** Where in the game's tick this signal fires. */
    public enum Phase {
        /** Before the game's own tick logic runs. */
        START,
        /** After the game's own tick logic ran. */
        END
    }

    private final long tick;
    private final Phase phase;

    /**
     * Shared, reusable END-phase heartbeat with an unspecified ({@code 0}) tick
     * number — the framework's canonical "a tick just ended" pulse when no caller
     * needs a concrete tick count.
     *
     * <p>Because {@code TickSignal} is immutable and not cancellable (a tick is a
     * fact, never vetoed), one instance can safely be published over and over: no
     * subscriber can mutate or consume it, so there is no cross-talk between
     * publishes. Reusing it avoids allocating a fresh object every game tick —
     * the same GC-pressure win Meteor gets from its singleton {@code TickEvent.get()}.
     *
     * <p><b>Use {@link #endOfTick()} / this instance</b> for the common per-tick
     * heartbeat where the tick number is irrelevant (movement helpers, timers,
     * automation that only cares "a tick passed"). <b>Still {@code new} a
     * TickSignal</b> when you need a real, monotonically increasing
     * {@link #tick() tick number} ({@code new TickSignal(n)}) or a
     * {@link Phase#START START}-phase pulse ({@code new TickSignal(Phase.START)}) —
     * those carry per-signal state the shared instance cannot represent.
     *
     * <p>Note: {@link #timestampNanos()} is fixed at class-load time for this
     * shared instance, so do not rely on it for per-publish latency/ordering when
     * reusing — allocate a fresh signal if you need an accurate timestamp.
     */
    public static final TickSignal END_ZERO = new TickSignal(0L, Phase.END);

    /**
     * The shared END-phase, tick-{@code 0} heartbeat — see {@link #END_ZERO}.
     * Returns the SAME instance on every call (no allocation). Prefer this for the
     * per-tick pulse when you have no concrete tick number; {@code new TickSignal(n)}
     * when you do, and {@code new TickSignal(Phase.START)} for a START-phase pulse.
     *
     * @return the reused {@link #END_ZERO} instance
     */
    public static TickSignal endOfTick() {
        return END_ZERO;
    }

    /** An END-phase tick with an unspecified ({@code 0}) tick number. */
    public TickSignal() {
        this(0L, Phase.END);
    }

    /** An END-phase tick carrying the given monotonically increasing tick number. */
    public TickSignal(long tick) {
        this(tick, Phase.END);
    }

    /** A tick in the given phase (null coerced to {@link Phase#END}) with tick number {@code 0}. */
    public TickSignal(Phase phase) {
        this(0L, phase);
    }

    /** A tick carrying both a tick number and a phase (null phase coerced to {@link Phase#END}). */
    public TickSignal(long tick, Phase phase) {
        this.tick = tick;
        this.phase = phase == null ? Phase.END : phase;
    }

    /** The tick number this signal represents (non-decreasing across a session; {@code 0} if unspecified). */
    public long tick() {
        return tick;
    }

    /** The phase of the tick this signal represents. */
    public Phase phase() {
        return phase;
    }
}
