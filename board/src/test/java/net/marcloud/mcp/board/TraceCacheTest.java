package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Regression tests for task A3: the per-runtime-class dispatch cache added to
 * {@link Trace#publish} (replacing the per-call allocate-collect-sort) plus the
 * new {@link Trace#hasSubscribers(Class)} guard.
 *
 * <p>Each test pins a behaviour that a naive cache would break: stale cache
 * after a late subscribe, delivery to an already-cancelled subscriber (cancel
 * does NOT invalidate the cache, so publish must skip {@code !active} entries),
 * lost subtype fan-out, or ordering that drifts once the sorted list is reused.
 * {@code hasSubscribers} itself does not exist on the pre-A3 code, so those
 * assertions fail to even compile against it.
 */
public class TraceCacheTest {

    static class BaseSignal extends Signal {
    }

    static final class SubSignal extends BaseSignal {
    }

    static final class OtherSignal extends Signal {
    }

    private static <E extends Signal> Trace.Listener<E> tally(final int[] counter) {
        return new Trace.Listener<E>() {
            @Override
            public void on(E s) {
                counter[0]++;
            }
        };
    }

    /**
     * The whole point of A3: after a runtime class has been published once (its
     * matcher list now cached), a freshly-subscribed listener must still receive
     * the next publish. A cache that never invalidates on subscribe would drop it.
     */
    @Test
    public void lateSubscribeAfterCacheWarmupStillReceives() {
        Trace trace = new Trace();
        final int[] first = {0};
        final int[] late = {0};
        trace.subscribe(BaseSignal.class, tally(first));

        trace.publish(new BaseSignal()); // warms the cache for BaseSignal
        assertEquals(1, first[0]);

        // Subscribe AFTER the cache was built for this runtime class.
        trace.subscribe(BaseSignal.class, tally(late));
        trace.publish(new BaseSignal());

        assertEquals("late subscriber must see the post-warmup publish", 1, late[0]);
        assertEquals(2, first[0]);
    }

    /**
     * A cancelled subscriber must not be dispatched, whether the cache was warm or cold.
     * Cancel clears the cache, so the next publish rebuilds without it — and a publish that
     * had already grabbed the old list skips it on the {@code !active} guard.
     */
    @Test
    public void cancelledSubscriberInWarmCacheIsSkipped() {
        Trace trace = new Trace();
        final int[] stay = {0};
        final int[] doomed = {0};
        trace.subscribe(BaseSignal.class, tally(stay));
        Trace.Subscription sub = trace.subscribe(BaseSignal.class, tally(doomed));

        trace.publish(new BaseSignal()); // warm cache
        assertEquals(1, stay[0]);
        assertEquals(1, doomed[0]);

        sub.cancel();
        trace.publish(new BaseSignal());

        assertEquals("cancelled subscriber must not be dispatched from a warm cache", 1, doomed[0]);
        assertEquals(2, stay[0]);
    }

    /**
     * The retention half, which the assertion above cannot see: the dispatch cache must not
     * keep a cancelled subscription — and through it its listener — reachable. A warm cache
     * built before the cancel is exactly the reference that outlived it, and the
     * {@link Subscription} contract advertises this handle as the leak-proof path.
     *
     * <p>White-box on purpose. Retention is not observable through the public API — the
     * cancelled listener is correctly not delivered either way — so the assertion reads the
     * private cache. What it pins is a real property, not a spelling.
     */
    @Test
    public void cancellingReleasesTheCachedReferenceToTheListener() throws Exception {
        Trace trace = new Trace();
        trace.subscribe(BaseSignal.class, tally(new int[1]));
        Trace.Subscription doomed = trace.subscribe(BaseSignal.class, tally(new int[1]));

        trace.publish(new BaseSignal()); // warm the cache: it now holds BOTH subscriptions

        java.lang.reflect.Field f = Trace.class.getDeclaredField("dispatchCache");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Class<? extends Signal>, List<?>> cache =
                (java.util.Map<Class<? extends Signal>, List<?>>) f.get(trace);
        assertTrue("the warm cache must contain the doomed subscription, or this test is "
                + "asserting nothing", cache.get(BaseSignal.class).contains(doomed));

        doomed.cancel();

        boolean stillCached = false;
        for (List<?> list : cache.values()) {
            stillCached |= list.contains(doomed);
        }
        assertFalse("a cancelled subscription must not stay reachable from the dispatch "
                + "cache; it holds the listener, and through an anonymous listener the chip",
                stillCached);
    }

    /** unsubscribe(Listener) on the cold path must also stop delivery from a warm cache. */
    @Test
    public void unsubscribeAfterWarmupStopsDelivery() {
        Trace trace = new Trace();
        final int[] hits = {0};
        Trace.Listener<BaseSignal> l = tally(hits);
        trace.subscribe(BaseSignal.class, l);

        trace.publish(new BaseSignal());
        assertEquals(1, hits[0]);

        trace.unsubscribe(l);
        trace.publish(new BaseSignal());
        assertEquals("unsubscribe must invalidate the cache", 1, hits[0]);
    }

    /** Subtype fan-out (old s.type.isInstance) must survive the class-keyed cache. */
    @Test
    public void subtypeFanOutPreservedAcrossCachedPublishes() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(BaseSignal.class, tally(hits));

        trace.publish(new SubSignal()); // first publish of SubSignal -> builds cache
        trace.publish(new SubSignal()); // second publish -> uses cache
        assertEquals("base-type subscriber must catch subtype both times", 2, hits[0]);
    }

    /**
     * The cached list is built sorted once; reusing it must not scramble Clock
     * priority + insertion order. Assert identical order on the warm publish.
     */
    @Test
    public void orderingIsStableAcrossWarmAndCachedPublishes() {
        Trace trace = new Trace();
        final List<String> log = new ArrayList<String>();
        // Subscribe out of priority order on purpose.
        trace.subscribe(BaseSignal.class, rec(log, "normal-a"), Clock.NORMAL);
        trace.subscribe(BaseSignal.class, rec(log, "lowest"), Clock.LOWEST);
        trace.subscribe(BaseSignal.class, rec(log, "highest"), Clock.HIGHEST);
        trace.subscribe(BaseSignal.class, rec(log, "normal-b"), Clock.NORMAL);

        trace.publish(new BaseSignal()); // builds + sorts cache
        trace.publish(new BaseSignal()); // reuses cache

        List<String> expected = new ArrayList<String>();
        // highest, then the two NORMALs in insertion order, then lowest — twice.
        expected.add("highest");
        expected.add("normal-a");
        expected.add("normal-b");
        expected.add("lowest");
        expected.add("highest");
        expected.add("normal-a");
        expected.add("normal-b");
        expected.add("lowest");
        assertEquals(expected, log);
    }

    /** Two distinct runtime classes must each get their own correct matcher set. */
    @Test
    public void distinctRuntimeClassesGetIndependentMatchers() {
        Trace trace = new Trace();
        final int[] base = {0};
        final int[] other = {0};
        trace.subscribe(BaseSignal.class, tally(base));
        trace.subscribe(OtherSignal.class, tally(other));

        trace.publish(new BaseSignal());
        trace.publish(new OtherSignal());
        trace.publish(new BaseSignal());

        assertEquals(2, base[0]);
        assertEquals(1, other[0]);
    }

    // ---- hasSubscribers ----------------------------------------------------

    @Test
    public void hasSubscribersFalseWhenNobodyListening() {
        Trace trace = new Trace();
        assertFalse(trace.hasSubscribers(BaseSignal.class));
    }

    @Test
    public void hasSubscribersTrueForExactAndSubtype() {
        Trace trace = new Trace();
        trace.subscribe(BaseSignal.class, tally(new int[1]));
        // exact type
        assertTrue(trace.hasSubscribers(BaseSignal.class));
        // a subtype the base subscriber would receive via fan-out
        assertTrue(trace.hasSubscribers(SubSignal.class));
    }

    @Test
    public void hasSubscribersFalseForUnrelatedAndForSupertypeOfSubOnlySubscriber() {
        Trace trace = new Trace();
        trace.subscribe(SubSignal.class, tally(new int[1]));
        // unrelated type: no match
        assertFalse(trace.hasSubscribers(OtherSignal.class));
        // a SubSignal-only subscriber would NOT get a plain BaseSignal, so guard is false
        assertFalse(trace.hasSubscribers(BaseSignal.class));
    }

    @Test
    public void hasSubscribersFalseAfterCancelAndAfterClear() {
        Trace trace = new Trace();
        Trace.Subscription sub = trace.subscribe(BaseSignal.class, tally(new int[1]));
        assertTrue(trace.hasSubscribers(BaseSignal.class));
        sub.cancel();
        assertFalse("cancelled subscription must not count as a listener", trace.hasSubscribers(BaseSignal.class));

        trace.subscribe(BaseSignal.class, tally(new int[1]));
        assertTrue(trace.hasSubscribers(BaseSignal.class));
        trace.clear();
        assertFalse(trace.hasSubscribers(BaseSignal.class));
    }

    @Test
    public void hasSubscribersNullTypeIsFalse() {
        Trace trace = new Trace();
        trace.subscribe(Signal.class, tally(new int[1]));
        assertFalse(trace.hasSubscribers(null));
    }

    // ---- concurrency -------------------------------------------------------

    /**
     * Concurrent subscribe + publish (which reads/rebuilds the cache) must not
     * throw — COW list for storage, CHM for the cache. All publishes land on a
     * class that has a base-Signal subscriber, so no signal is silently dropped.
     */
    @Test
    public void concurrentSubscribeAndPublishDoesNotThrow() throws InterruptedException {
        final Trace trace = new Trace();
        final int threads = 8;
        final int iters = 500;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final List<Thread> pool = new CopyOnWriteArrayList<Thread>();

        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < iters; i++) {
                            trace.subscribe(BaseSignal.class, tally(new int[1]));
                            trace.publish(new SubSignal());
                            trace.publish(new BaseSignal());
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }
            });
            pool.add(th);
            th.start();
        }
        start.countDown();
        assertTrue("threads should finish", done.await(30, TimeUnit.SECONDS));

        assertNull("no exception under concurrent subscribe+publish", failure.get());
        assertEquals(threads * iters, trace.subscriberCount());
    }

    private static Trace.Listener<BaseSignal> rec(final List<String> log, final String tag) {
        return new Trace.Listener<BaseSignal>() {
            @Override
            public void on(BaseSignal s) {
                log.add(tag);
            }
        };
    }
}
