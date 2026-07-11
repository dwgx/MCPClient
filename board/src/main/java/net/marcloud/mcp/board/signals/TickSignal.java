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
