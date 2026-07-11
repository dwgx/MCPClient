package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Phase-2 regression tests for the frozen {@link Trace} pub/sub contract:
 * typed delivery (incl. subtype/{@link Signal.Cancellable} matching),
 * {@link Clock} priority ordering across all five tiers, insertion order within
 * a tier, exception isolation, and cooperative cancellation — where a
 * high-priority subscriber cancels and lower-priority subscribers (run later by
 * {@code Clock} ordering) observe {@link Signal.Cancellable#isCancelled()} and
 * skip their guarded action.
 *
 * <p>Each test asserts an observable effect that is absent on old/broken code
 * (wrong ordering, no exception isolation, or no cancel propagation).
 */
public class TracePropagationTest {

    static final class MoveSignal extends Signal {
    }

    /** A cancellable that carries the phase (PRE/POST) via the base ctor. */
    static final class AttackSignal extends Signal.Cancellable {
        AttackSignal() {
            super();
        }

        AttackSignal(State state) {
            super(state);
        }
    }

    private static Trace.Listener<MoveSignal> record(final List<String> log, final String tag) {
        return new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                log.add(tag);
            }
        };
    }

    @Test
    public void fullClockOrderingHighestToLowest() {
        Trace trace = new Trace();
        final List<String> order = new ArrayList<String>();
        // Subscribe deliberately out of priority order to prove sorting, not luck.
        trace.subscribe(MoveSignal.class, record(order, "normal"), Clock.NORMAL);
        trace.subscribe(MoveSignal.class, record(order, "lowest"), Clock.LOWEST);
        trace.subscribe(MoveSignal.class, record(order, "highest"), Clock.HIGHEST);
        trace.subscribe(MoveSignal.class, record(order, "low"), Clock.LOW);
        trace.subscribe(MoveSignal.class, record(order, "high"), Clock.HIGH);

        trace.publish(new MoveSignal());

        assertEquals(5, order.size());
        assertEquals("highest", order.get(0));
        assertEquals("high", order.get(1));
        assertEquals("normal", order.get(2));
        assertEquals("low", order.get(3));
        assertEquals("lowest", order.get(4));
    }

    @Test
    public void insertionOrderPreservedWithinSameTier() {
        Trace trace = new Trace();
        final List<String> order = new ArrayList<String>();
        // No explicit tier => Clock.DEFAULT (NORMAL) for all three.
        trace.subscribe(MoveSignal.class, record(order, "first"));
        trace.subscribe(MoveSignal.class, record(order, "second"));
        trace.subscribe(MoveSignal.class, record(order, "third"));

        trace.publish(new MoveSignal());

        assertEquals("first", order.get(0));
        assertEquals("second", order.get(1));
        assertEquals("third", order.get(2));
    }

    @Test
    public void defaultTierRunsBetweenHighAndLow() {
        Trace trace = new Trace();
        final List<String> order = new ArrayList<String>();
        trace.subscribe(MoveSignal.class, record(order, "explicitLow"), Clock.LOW);
        trace.subscribe(MoveSignal.class, record(order, "default")); // Clock.DEFAULT == NORMAL
        trace.subscribe(MoveSignal.class, record(order, "explicitHigh"), Clock.HIGH);

        trace.publish(new MoveSignal());

        assertEquals("explicitHigh", order.get(0));
        assertEquals("default", order.get(1));
        assertEquals("explicitLow", order.get(2));
    }

    @Test
    public void subscribingToCancellableBaseCatchesConcreteSubtype() {
        Trace trace = new Trace();
        final int[] hits = {0};
        // Subscribe by the abstract Cancellable type; publish a concrete subtype.
        trace.subscribe(Signal.Cancellable.class, new Trace.Listener<Signal.Cancellable>() {
            @Override
            public void on(Signal.Cancellable s) {
                hits[0]++;
            }
        });
        trace.publish(new AttackSignal());
        trace.publish(new AttackSignal(Signal.Cancellable.State.POST));
        assertEquals(2, hits[0]);
    }

    @Test
    public void subscribingToSignalBaseCatchesEverything() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(Signal.class, new Trace.Listener<Signal>() {
            @Override
            public void on(Signal s) {
                hits[0]++;
            }
        });
        trace.publish(new MoveSignal());
        trace.publish(new AttackSignal());
        assertEquals(2, hits[0]);
    }

    @Test
    public void throwingSubscriberDoesNotAbortRemainingOrPublisher() {
        Trace trace = new Trace();
        final List<String> order = new ArrayList<String>();
        trace.subscribe(MoveSignal.class, new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                order.add("before");
            }
        }, Clock.HIGHEST);
        trace.subscribe(MoveSignal.class, new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                order.add("boom");
                throw new IllegalStateException("subscriber fault");
            }
        }, Clock.HIGH);
        trace.subscribe(MoveSignal.class, new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                order.add("after");
            }
        }, Clock.LOW);

        // publish must not propagate the subscriber's exception to the caller.
        MoveSignal returned = trace.publish(new MoveSignal());

        assertEquals(3, order.size());
        assertEquals("before", order.get(0));
        assertEquals("boom", order.get(1));
        assertEquals("after", order.get(2));
        assertTrue(returned instanceof MoveSignal);
    }

    @Test
    public void errorThrowingSubscriberIsAlsoIsolated() {
        Trace trace = new Trace();
        final int[] survived = {0};
        trace.subscribe(MoveSignal.class, new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                throw new StackOverflowError("hard fault"); // Error, not Exception
            }
        }, Clock.HIGHEST);
        trace.subscribe(MoveSignal.class, new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                survived[0]++;
            }
        }, Clock.LOWEST);

        trace.publish(new MoveSignal());
        assertEquals(1, survived[0]);
    }

    @Test
    public void cancellationByHighPriorityStopsLowPriorityGuardedAction() {
        Trace trace = new Trace();
        final boolean[] guardedActionRan = {false};

        // HIGHEST: a veto guard that cancels the action.
        trace.subscribe(AttackSignal.class, new Trace.Listener<AttackSignal>() {
            @Override
            public void on(AttackSignal s) {
                s.cancel();
            }
        }, Clock.HIGHEST);
        // LOWEST: the actor. Because Clock ordering runs it AFTER the guard, it
        // observes the cancelled flag and must skip its guarded action.
        trace.subscribe(AttackSignal.class, new Trace.Listener<AttackSignal>() {
            @Override
            public void on(AttackSignal s) {
                if (!s.isCancelled()) {
                    guardedActionRan[0] = true;
                }
            }
        }, Clock.LOWEST);

        AttackSignal published = trace.publish(new AttackSignal());

        assertTrue(published.isCancelled());
        assertFalse(guardedActionRan[0]);
    }

    @Test
    public void withoutVetoTheGuardedActionRuns() {
        Trace trace = new Trace();
        final boolean[] guardedActionRan = {false};
        // A guard that does NOT cancel.
        trace.subscribe(AttackSignal.class, new Trace.Listener<AttackSignal>() {
            @Override
            public void on(AttackSignal s) {
                // observe only
            }
        }, Clock.HIGHEST);
        trace.subscribe(AttackSignal.class, new Trace.Listener<AttackSignal>() {
            @Override
            public void on(AttackSignal s) {
                if (!s.isCancelled()) {
                    guardedActionRan[0] = true;
                }
            }
        }, Clock.LOWEST);

        AttackSignal published = trace.publish(new AttackSignal());

        assertFalse(published.isCancelled());
        assertTrue(guardedActionRan[0]);
    }

    @Test
    public void unCancelViaSetCancelledRestoresFlag() {
        AttackSignal s = new AttackSignal();
        assertFalse(s.isCancelled());
        s.cancel();
        assertTrue(s.isCancelled());
        s.setCancelled(false);
        assertFalse(s.isCancelled());
    }

    @Test
    public void subscriberCountAndClearReflectState() {
        Trace trace = new Trace();
        assertEquals(0, trace.subscriberCount());
        trace.subscribe(MoveSignal.class, record(new ArrayList<String>(), "a"));
        trace.subscribe(MoveSignal.class, record(new ArrayList<String>(), "b"));
        assertEquals(2, trace.subscriberCount());
        trace.clear();
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    public void publishNullIsANoOpReturningNull() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(MoveSignal.class, new Trace.Listener<MoveSignal>() {
            @Override
            public void on(MoveSignal s) {
                hits[0]++;
            }
        });
        assertEquals(null, trace.publish(null));
        assertEquals(0, hits[0]);
    }
}
