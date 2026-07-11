package net.marcloud.mcp.board.signals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import net.marcloud.mcp.board.Trace;
import org.junit.Test;

/**
 * Regression tests for the reusable END-phase {@link TickSignal} singleton
 * ({@link TickSignal#END_ZERO} / {@link TickSignal#endOfTick()}), added to kill
 * the per-tick allocation that publishing {@code new TickSignal()} every game
 * tick caused (GC jitter). The singleton is safe only because a TickSignal is
 * immutable and not cancellable.
 *
 * <p>These would all FAIL on the pre-change code: {@code endOfTick()} /
 * {@code END_ZERO} did not exist there, so the file would not compile — a real
 * (non-placeholder) guard that the reusable instance is present, has END/0
 * semantics, is genuinely reused (reference-identical), and is delivered to
 * subscribers exactly like a freshly constructed {@code new TickSignal()}.
 */
public class TickSignalReuseTest {

    @Test
    public void reusedInstanceIsEndPhaseTickZero() {
        assertEquals("reused heartbeat must be END phase", TickSignal.Phase.END,
                TickSignal.endOfTick().phase());
        assertEquals("reused heartbeat must carry tick 0", 0L,
                TickSignal.endOfTick().tick());
        // And the field form agrees.
        assertEquals(TickSignal.Phase.END, TickSignal.END_ZERO.phase());
        assertEquals(0L, TickSignal.END_ZERO.tick());
    }

    @Test
    public void everyCallReturnsTheSameInstance() {
        TickSignal a = TickSignal.endOfTick();
        TickSignal b = TickSignal.endOfTick();
        assertSame("endOfTick() must reuse one instance, not allocate", a, b);
        assertSame("endOfTick() must be the END_ZERO field", TickSignal.END_ZERO, a);
    }

    @Test
    public void reusedInstanceIsSemanticallyEquivalentToNoArgConstructor() {
        TickSignal fresh = new TickSignal();
        TickSignal reused = TickSignal.endOfTick();
        assertEquals("same phase as new TickSignal()", fresh.phase(), reused.phase());
        assertEquals("same tick as new TickSignal()", fresh.tick(), reused.tick());
    }

    @Test
    public void reusedInstanceIsDeliveredToSubscribersLikeAFreshOne() {
        Trace trace = new Trace();
        final int[] hits = {0};
        final TickSignal[] seen = {null};
        trace.subscribe(TickSignal.class, s -> {
            hits[0]++;
            seen[0] = s;
        });

        // Publishing the reused instance repeatedly (the GC-friendly hot path)
        // must reach the subscriber every time, delivering the exact instance.
        trace.publish(TickSignal.endOfTick());
        trace.publish(TickSignal.endOfTick());

        assertEquals("subscriber must receive the reused signal each publish", 2, hits[0]);
        assertSame("subscriber must receive the very same reused instance",
                TickSignal.END_ZERO, seen[0]);
    }
}
