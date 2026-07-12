package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.marcloud.mcp.board.signals.TickSignal;
import org.junit.Test;

/**
 * Regression tests for {@link Chip}'s auto-subscription bag ({@code track} +
 * disable-time auto-cancel). The invariant under test is {@code enabled ==
 * subscribed}: a chip that only ever calls {@link Chip#track} in
 * {@link Chip#onEnable()} — and NEVER cancels anything itself — must still leave
 * zero live subscriptions after being disabled, and must re-subscribe cleanly on
 * the next enable. These would fail on the pre-track {@link Chip} (which had no
 * way to auto-cancel), so they are non-trivial guards, not placeholder greens.
 */
public class ChipSubscriptionBagTest {

    /**
     * A chip that subscribes in {@code onEnable} via {@code track(...)} and
     * deliberately has NO {@code onDisable} cancel logic — proving the framework
     * (not the author) enforces {@code enabled == subscribed}.
     */
    static final class LeakyAuthorChip extends Chip {
        final Trace trace;
        final AtomicInteger hits = new AtomicInteger();

        LeakyAuthorChip(Trace trace) {
            this.trace = trace;
        }

        @Override
        protected void onEnable() {
            // Chainable track(trace.subscribe(...)) — the whole point of the bag.
            track(trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
                @Override
                public void on(TickSignal signal) {
                    hits.incrementAndGet();
                }
            }));
        }
        // NOTE: no onDisable — author "forgot" to cancel; bag must save them.
    }

    @Test
    public void enableSubscribesDisableCancelsAndReEnableReSubscribes() {
        Trace trace = new Trace();
        LeakyAuthorChip chip = new LeakyAuthorChip(trace);
        assertEquals(0, trace.subscriberCount());

        // enable -> subscriber count rises
        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());

        // published signal reaches the handler while enabled
        trace.publish(new TickSignal());
        assertEquals(1, chip.hits.get());

        // disable -> bag auto-cancelled, zero live subscriptions, handler silent
        chip.setEnabled(false);
        assertEquals(0, trace.subscriberCount());
        trace.publish(new TickSignal());
        assertEquals("handler must not fire after disable", 1, chip.hits.get());

        // re-enable -> re-subscribes exactly once (no stale duplicate left behind)
        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());
        trace.publish(new TickSignal());
        assertEquals(2, chip.hits.get());

        // final disable leaves the bus clean again
        chip.setEnabled(false);
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    public void trackReturnsHandleForChainingAndRegistersIt() {
        final Trace trace = new Trace();
        final Trace.Subscription[] handed = new Trace.Subscription[1];
        Chip chip = new Chip() {
            @Override
            protected void onEnable() {
                Trace.Subscription s = trace.subscribe(TickSignal.class,
                        new Trace.Listener<TickSignal>() {
                            @Override
                            public void on(TickSignal signal) {
                            }
                        });
                // track must return the SAME handle it was given (chainable).
                handed[0] = track(s);
                assertSame(s, handed[0]);
            }
        };

        chip.setEnabled(true);
        assertTrue(handed[0].isActive());
        assertEquals(1, trace.subscriberCount());

        chip.setEnabled(false);
        // the tracked handle is now cancelled by the framework
        assertFalse(handed[0].isActive());
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    public void multipleTrackedSubscriptionsAllCancelledOnDisable() {
        Trace trace = new Trace();
        Chip chip = new Chip() {
            @Override
            protected void onEnable() {
                track(trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
                    @Override
                    public void on(TickSignal s) {
                    }
                }));
                track(trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
                    @Override
                    public void on(TickSignal s) {
                    }
                }));
                track(trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
                    @Override
                    public void on(TickSignal s) {
                    }
                }));
            }
        };

        chip.setEnabled(true);
        assertEquals(3, trace.subscriberCount());
        chip.setEnabled(false);
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    public void nullTrackIsIgnoredAndReturnedAsIs() {
        Chip chip = new Chip() {
        };
        // track(null) must be a safe no-op returning null — no NPE on disable.
        chip.setEnabled(true);
        chip.setEnabled(false);
        assertFalse(chip.isEnabled());
    }

    @Test
    public void throwingOnDisableStillCancelsTrackedSubscriptions() {
        // A chip whose onDisable() throws must NOT leak its tracked subscriptions:
        // cancelTracked runs in a finally, so enabled == subscribed holds even when
        // the author's onDisable faults. On the pre-fix code cancelTracked was the
        // last statement in the try, so a throwing onDisable skipped it and left the
        // bag live — this asserts leak == 0.
        final Trace trace = new Trace();
        Chip chip = new Chip() {
            @Override
            protected void onEnable() {
                track(trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
                    @Override
                    public void on(TickSignal s) {
                    }
                }));
            }

            @Override
            protected void onDisable() {
                throw new RuntimeException("author's onDisable blew up");
            }
        };

        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());

        // onDisable throws; the outer guard swallows it, but the tracked
        // subscription must still be cancelled — zero live subscriptions.
        chip.setEnabled(false);
        assertEquals("throwing onDisable must not leak tracked subscriptions",
                0, trace.subscriberCount());
        assertFalse(chip.isEnabled());
        // handler stays silent after disable
        trace.publish(new TickSignal());
    }

    @Test
    public void authorWhoAlsoCancelsInOnDisableDoesNotDoubleCancel() {
        // Belt-and-suspenders: a diligent author cancels in onDisable AND the bag
        // cancels too. cancel() is idempotent, so this must not blow up and must
        // still end with zero live subscriptions.
        final Trace trace = new Trace();
        final Trace.Subscription[] held = new Trace.Subscription[1];
        Chip chip = new Chip() {
            @Override
            protected void onEnable() {
                held[0] = track(trace.subscribe(TickSignal.class,
                        new Trace.Listener<TickSignal>() {
                            @Override
                            public void on(TickSignal s) {
                            }
                        }));
            }

            @Override
            protected void onDisable() {
                held[0].cancel(); // author cancels first
            }
        };

        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());
        chip.setEnabled(false);
        assertEquals(0, trace.subscriberCount());
        assertFalse(held[0].isActive());
    }
}
