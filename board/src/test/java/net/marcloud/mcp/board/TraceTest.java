package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Regression tests for the {@link Trace} event bus contract. */
public class TraceTest {

    static final class TickSignal extends Signal {
    }

    static final class AttackSignal extends Signal.Cancellable {
    }

    @Test
    public void deliversToSubscribersOfMatchingType() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                hits[0]++;
            }
        });
        trace.publish(new TickSignal());
        trace.publish(new TickSignal());
        assertEquals(2, hits[0]);
    }

    @Test
    public void doesNotDeliverToNonMatchingType() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(AttackSignal.class, new Trace.Listener<AttackSignal>() {
            @Override
            public void on(AttackSignal s) {
                hits[0]++;
            }
        });
        trace.publish(new TickSignal());
        assertEquals(0, hits[0]);
    }

    @Test
    public void respectsClockPriorityOrdering() {
        Trace trace = new Trace();
        final List<String> order = new ArrayList<String>();
        trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                order.add("low");
            }
        }, Clock.LOWEST);
        trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                order.add("high");
            }
        }, Clock.HIGHEST);
        trace.publish(new TickSignal());
        assertEquals("high", order.get(0));
        assertEquals("low", order.get(1));
    }

    @Test
    public void throwingSubscriberIsIsolated() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                throw new RuntimeException("boom");
            }
        }, Clock.HIGHEST);
        trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                hits[0]++;
            }
        }, Clock.LOWEST);
        trace.publish(new TickSignal());
        assertEquals(1, hits[0]);
    }

    @Test
    public void unsubscribeStopsDelivery() {
        Trace trace = new Trace();
        final int[] hits = {0};
        Trace.Listener<TickSignal> l = new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                hits[0]++;
            }
        };
        trace.subscribe(TickSignal.class, l);
        trace.publish(new TickSignal());
        trace.unsubscribe(l);
        trace.publish(new TickSignal());
        assertEquals(1, hits[0]);
    }

    /**
     * Review finding S1: the documented pattern subscribe(Type, this::method)
     * uses a fresh lambda instance that unsubscribe(Listener) can never match by
     * identity — a leak. The Subscription handle returned by subscribe is the
     * leak-proof path: cancel() removes exactly this registration.
     */
    @Test
    public void subscriptionHandleCancelsAMethodRefListener() {
        Trace trace = new Trace();
        final int[] hits = {0};
        // A method-ref-style listener: a distinct instance that the caller does
        // NOT hold a reference to for unsubscribe — only the handle.
        Trace.Subscription sub = trace.subscribe(TickSignal.class, s -> hits[0]++);
        assertTrue(sub.isActive());
        trace.publish(new TickSignal());
        assertEquals(1, hits[0]);
        assertEquals(1, trace.subscriberCount());

        sub.cancel();
        assertFalse(sub.isActive());
        assertEquals(0, trace.subscriberCount());
        trace.publish(new TickSignal());
        assertEquals("cancelled handle stops delivery — no leak", 1, hits[0]);

        // cancel() is idempotent
        sub.cancel();
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    public void subscriptionWorksAsTryWithResources() {
        Trace trace = new Trace();
        final int[] hits = {0};
        try (Trace.Subscription ignored = trace.subscribe(TickSignal.class, s -> hits[0]++)) {
            trace.publish(new TickSignal());
        }
        // auto-closed at end of try → unsubscribed
        assertEquals(0, trace.subscriberCount());
        trace.publish(new TickSignal());
        assertEquals(1, hits[0]);
    }

    @Test
    public void cancellableSignalCanBeVetoed() {
        Trace trace = new Trace();
        trace.subscribe(AttackSignal.class, new Trace.Listener<AttackSignal>() {
            @Override
            public void on(AttackSignal s) {
                s.cancel();
            }
        });
        AttackSignal signal = trace.publish(new AttackSignal());
        assertTrue(signal.isCancelled());
        assertEquals(Signal.Cancellable.State.PRE, signal.state());
    }

    @Test
    public void freshSignalIsNotCancelled() {
        assertFalse(new AttackSignal().isCancelled());
    }
}
