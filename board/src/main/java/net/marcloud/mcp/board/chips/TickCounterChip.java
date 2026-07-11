package net.marcloud.mcp.board.chips;

import java.util.concurrent.atomic.AtomicLong;

import net.marcloud.mcp.board.Board;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.TickSignal;

/**
 * A SAMPLE tick-driven {@link Chip}: while enabled, it counts every
 * {@link TickSignal} that travels on the {@link Trace}. Proves the framework's
 * core use case — a feature that observes a game event stream — end to end:
 * {@code onEnable} subscribes on the bus, {@code onDisable} unsubscribes, and the
 * subscription is idempotent across toggles so no ticks are ever double-counted.
 *
 * <p>Neutral by contract: no cheat/legit layering, just an optional
 * {@link #category()} of {@code "diagnostic"}.
 */
public final class TickCounterChip extends Chip {

    private final Trace trace;
    private final AtomicLong ticks = new AtomicLong();

    /** The listener instance is kept so {@code onDisable} can unsubscribe exactly it. */
    private final Trace.Listener<TickSignal> onTick = new Trace.Listener<TickSignal>() {
        @Override
        public void on(TickSignal signal) {
            ticks.incrementAndGet();
        }
    };

    /** Subscribe on the shared {@link Board#trace()} bus. */
    public TickCounterChip() {
        this(Board.trace());
    }

    /** Subscribe on an explicit {@link Trace} (used by tests for isolation). */
    public TickCounterChip(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        this.trace = trace;
    }

    @Override
    public String category() {
        return "diagnostic";
    }

    @Override
    protected void onEnable() {
        // Defensive: drop any stale subscription before re-adding, so repeated
        // enable/disable cycles never leave two live subscriptions behind.
        trace.unsubscribe(onTick);
        trace.subscribe(TickSignal.class, onTick);
    }

    @Override
    protected void onDisable() {
        trace.unsubscribe(onTick);
    }

    /** Total ticks counted while enabled since the last {@link #reset()}. */
    public long count() {
        return ticks.get();
    }

    /** Zero the counter without touching the enabled state. */
    public void reset() {
        ticks.set(0L);
    }
}
