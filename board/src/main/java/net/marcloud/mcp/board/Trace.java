package net.marcloud.mcp.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The event bus — signals travel down the board's copper traces to the chips
 * that listen. Self-built (no external library), typed by explicit generic
 * subscription, ordered by {@link Clock} priority, and exception-isolated:
 * publishing runs synchronously on the caller (game) thread, and a throwing
 * subscriber is swallowed so it can never break the publisher.
 *
 * <p>Style mirrors mcp-core's {@code ke.event.EventBus} — copy-on-write storage
 * so publishing from the game thread never blocks on concurrent
 * subscribe/unsubscribe. Subscribers see events of their subscribed type and its
 * subtypes.
 *
 * <p>FROZEN framework contract (design doc 06 §7).
 */
public final class Trace {

    /** A subscriber callback for signals of type {@code E}. */
    @FunctionalInterface
    public interface Listener<E extends Signal> {
        void on(E signal);
    }

    /**
     * A handle to one registration, returned by {@code subscribe}. Call
     * {@link #cancel()} (or use try-with-resources) to remove exactly this
     * subscription — the leak-proof path that works even when the listener was a
     * method reference or lambda (which {@link Trace#unsubscribe(Listener)} cannot
     * match by identity). {@link #isActive()} reports whether it is still live.
     */
    public interface Subscription extends AutoCloseable {
        /** Remove this subscription. Idempotent. */
        void cancel();

        /** {@code true} until {@link #cancel()} removes it. */
        boolean isActive();

        /** {@link AutoCloseable} bridge — same as {@link #cancel()}. */
        @Override
        default void close() {
            cancel();
        }
    }

    private final CopyOnWriteArrayList<Subscription0<?>> subscriptions = new CopyOnWriteArrayList<Subscription0<?>>();

    /** Monotonic sequence to keep same-priority subscribers in insertion order. */
    private final AtomicLong seq = new AtomicLong();

    /**
     * Hot-path cache: for each concrete runtime {@link Signal} class ever
     * published, the pre-sorted list of subscriptions that match it (by
     * {@code type.isInstance}), in {@link #BY_PRIORITY} order. Built lazily on the
     * first publish of a given runtime class so the steady-state publish loop
     * avoids the per-call allocate-collect-sort. Invalidated wholesale on any
     * cold-path mutation ({@code subscribe}/{@code unsubscribe}/{@code clear}) —
     * simple and correct over clever incremental patching.
     *
     * <p>Cached lists may contain entries whose {@link Subscription0#active} flag
     * flipped to {@code false} via {@link Subscription#cancel()} after the list was
     * built; the publish loop skips those, so a cancel need not invalidate the
     * cache to be correct (though {@code unsubscribe}/{@code clear} do).
     */
    private final ConcurrentHashMap<Class<? extends Signal>, List<Subscription0<?>>> dispatchCache =
            new ConcurrentHashMap<Class<? extends Signal>, List<Subscription0<?>>>();

    private final class Subscription0<E extends Signal> implements Subscription {
        final Class<E> type;
        final Listener<E> listener;
        final Clock clock;
        final long order;
        volatile boolean active = true;

        Subscription0(Class<E> type, Listener<E> listener, Clock clock, long order) {
            this.type = type;
            this.listener = listener;
            this.clock = clock;
            this.order = order;
        }

        @Override
        public void cancel() {
            if (active) {
                active = false;
                subscriptions.remove(this);
            }
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    /** Comparator: higher {@link Clock} first (enum ordinal ascending = HIGHEST first), then insertion order. */
    private static final Comparator<Subscription0<?>> BY_PRIORITY =
            new Comparator<Subscription0<?>>() {
                @Override
                public int compare(Subscription0<?> a, Subscription0<?> b) {
                    int c = Integer.compare(a.clock.ordinal(), b.clock.ordinal());
                    return c != 0 ? c : Long.compare(a.order, b.order);
                }
            };

    /**
     * Register {@code listener} for signals of {@code type} at {@link Clock#DEFAULT}
     * priority. Returns a {@link Subscription} handle — the leak-proof way to
     * later remove exactly this registration (works for method-ref/lambda
     * listeners, which {@link #unsubscribe(Listener)} cannot match by identity).
     */
    public <E extends Signal> Subscription subscribe(Class<E> type, Listener<E> listener) {
        return subscribe(type, listener, Clock.DEFAULT);
    }

    /**
     * Register {@code listener} for signals of {@code type} (and subtypes) at the
     * given priority. Returns a {@link Subscription} handle for leak-proof removal.
     */
    public <E extends Signal> Subscription subscribe(Class<E> type, Listener<E> listener, Clock clock) {
        if (type == null || listener == null) {
            throw new IllegalArgumentException("type and listener must not be null");
        }
        Clock tier = clock == null ? Clock.DEFAULT : clock;
        Subscription0<E> sub = new Subscription0<E>(type, listener, tier, seq.getAndIncrement());
        subscriptions.add(sub);
        // Cold path: a new subscriber may match already-cached runtime classes —
        // rebuild everything lazily on next publish.
        dispatchCache.clear();
        return sub;
    }

    /**
     * Remove a previously-registered listener <em>instance</em> (all its
     * subscriptions). Convenience path; matches by reference identity, so it
     * CANNOT remove a listener passed as a fresh method-ref/lambda — for those,
     * keep the {@link Subscription} handle from {@code subscribe} and call
     * {@link Subscription#cancel()}.
     */
    public void unsubscribe(Listener<?> listener) {
        boolean removed = false;
        for (Subscription0<?> s : subscriptions) {
            if (s.listener == listener) {
                s.active = false;
                subscriptions.remove(s);
                removed = true;
            }
        }
        if (removed) {
            // Cold path: drop cached lists so they no longer reference the removed
            // subscription (active=false would skip it anyway, but keep the cache
            // honest about live membership).
            dispatchCache.clear();
        }
    }

    /**
     * Publish {@code signal} to every matching subscriber, in {@link Clock}
     * priority order, synchronously on the calling thread. Exceptions from one
     * subscriber are isolated (logged to stderr) so a bad chip cannot take down
     * the publisher. Returns the signal for call-site convenience (e.g. to read
     * a {@link Signal.Cancellable}'s cancelled flag).
     */
    public <E extends Signal> E publish(E signal) {
        if (signal == null) {
            return null;
        }
        List<Subscription0<?>> matching = matchersFor(signal.getClass());
        for (Subscription0<?> s : matching) {
            // A subscription cached before its cancel() flipped active=false must
            // not receive the signal — the cache is not invalidated on cancel.
            if (!s.active) {
                continue;
            }
            try {
                dispatch(s, signal);
            } catch (Throwable e) {
                // Publish often runs on the game thread; a subscriber fault
                // (even an Error) must not propagate into the game.
                System.err.println("[Trace] subscriber for "
                        + s.type.getSimpleName() + " threw: " + e);
            }
        }
        return signal;
    }

    /**
     * The pre-sorted matcher list for a concrete runtime signal class, built once
     * per class and reused. {@code computeIfAbsent} walks {@link #subscriptions}
     * (collecting every {@code type.isInstance}-matching subscription — the
     * subtype fan-out semantic) and sorts by {@link #BY_PRIORITY} exactly once;
     * subsequent publishes of the same runtime class iterate the cached list with
     * no allocation or sort. The returned list is treated as immutable by callers.
     */
    private List<Subscription0<?>> matchersFor(Class<? extends Signal> runtimeType) {
        return dispatchCache.computeIfAbsent(runtimeType, new java.util.function.Function<Class<? extends Signal>, List<Subscription0<?>>>() {
            @Override
            public List<Subscription0<?>> apply(Class<? extends Signal> rt) {
                List<Subscription0<?>> matching = new ArrayList<Subscription0<?>>();
                for (Subscription0<?> s : subscriptions) {
                    // Class-level equivalent of the old s.type.isInstance(signal):
                    // a subscription for type T matches runtime class rt iff T is a
                    // supertype of rt. Preserves the subtype fan-out semantic.
                    if (s.type.isAssignableFrom(rt)) {
                        matching.add(s);
                    }
                }
                matching.sort(BY_PRIORITY);
                return matching;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void dispatch(Subscription0<?> s, Signal signal) {
        ((Listener<Signal>) s.listener).on(signal);
    }

    /**
     * Whether any live subscriber would receive a signal of {@code type} (or a
     * supertype it is registered under). Lets an emission site skip constructing a
     * {@link Signal} when nobody is listening — the "is anyone listening?" guard
     * mirrored from established event buses. Pure addition to the frozen contract.
     *
     * @param type the concrete signal class an emitter is about to publish
     * @return {@code true} iff at least one active subscription matches it
     */
    public boolean hasSubscribers(Class<? extends Signal> type) {
        if (type == null) {
            return false;
        }
        for (Subscription0<?> s : subscriptions) {
            if (s.active && s.type.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /** Current subscription count (for diagnostics/tests). */
    public int subscriberCount() {
        return subscriptions.size();
    }

    /** Remove every subscription (marks each cancelled first). */
    public void clear() {
        for (Subscription0<?> s : subscriptions) {
            s.active = false;
        }
        subscriptions.clear();
        // Cold path: nothing matches anything now — drop the whole cache.
        dispatchCache.clear();
    }
}
