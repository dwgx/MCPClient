package net.marcloud.mcp.core.ke.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A minimal thread-safe publish/subscribe bus for {@link GameEvent}s.
 *
 * <p>Publishers are game-thread hooks (packet in/out, disconnect, tick);
 * subscribers are MCP-layer components (packet log, disconnect reporter, state
 * trackers) that may live on other threads. Delivery is synchronous on the
 * publishing thread, so subscribers must be cheap and non-blocking — anything
 * heavy should hand off to its own executor. A throwing subscriber is isolated
 * so it cannot break the publisher (the game thread).
 *
 * <p><b>PHASE T hot path (T.7):</b> {@link #publish} is on the per-tick / per-packet
 * game-thread path, so it must not scan every subscription and {@code isInstance}
 * each one on every event. Instead it resolves the matching handler list ONCE per
 * concrete event class and caches it ({@link #dispatchCache}); subsequent events of
 * that class reuse the cached list with no per-event type filtering or allocation.
 * The cache is invalidated on subscribe/unsubscribe (cold path). Mirrors the board
 * {@code Trace} dispatch-cache design.
 */
public final class EventBus {

    /**
     * All subscriptions. The source of truth; the per-class {@link #dispatchCache}
     * is derived from it. Copy-on-write so publishing never blocks on concurrent
     * subscribe/unsubscribe.
     */
    private final CopyOnWriteArrayList<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * Concrete-event-class → the subscription handlers that match it, resolved once
     * and reused. Cleared whenever the subscription set changes.
     */
    private final Map<Class<?>, List<Subscription<?>>> dispatchCache = new ConcurrentHashMap<>();

    private record Subscription<T extends GameEvent>(Class<T> type, Consumer<T> handler) {
    }

    /** Register a handler for events of {@code type} (and its subtypes). */
    public <T extends GameEvent> void subscribe(Class<T> type, Consumer<T> handler) {
        subscriptions.add(new Subscription<>(type, handler));
        dispatchCache.clear(); // cold path: a new subscriber may match cached classes
    }

    /** Remove a previously-registered handler instance. */
    public <T extends GameEvent> void unsubscribe(Consumer<T> handler) {
        if (subscriptions.removeIf(s -> s.handler() == handler)) {
            dispatchCache.clear();
        }
    }

    /**
     * Publish {@code event} to all matching subscribers. The matching list is
     * resolved once per concrete event class and cached, so the hot path does no
     * per-event {@code isInstance} scan or list allocation once warm. Exceptions
     * from one subscriber are swallowed (logged to stderr) so a bad handler cannot
     * take down the game thread.
     */
    @SuppressWarnings("unchecked")
    public void publish(GameEvent event) {
        if (event == null) {
            return;
        }
        List<Subscription<?>> matching = matchersFor(event.getClass());
        for (Subscription<?> s : matching) {
            try {
                ((Consumer<GameEvent>) s.handler()).accept(event);
            } catch (Throwable e) {
                // Publish often runs on the game/Netty thread; a subscriber
                // fault (even an Error) must not propagate into the game.
                System.err.println("[EventBus] subscriber for "
                        + s.type().getSimpleName() + " threw: " + e);
            }
        }
    }

    /**
     * The subscription handlers matching a concrete event class, built once and
     * cached. On a cache miss (first event of a class, or after a subscription
     * change cleared the cache) it does the O(subscribers) {@code isInstance} scan
     * and memoizes the result. {@code computeIfAbsent} makes the build idempotent
     * under concurrent first-publishes.
     */
    private List<Subscription<?>> matchersFor(Class<?> eventClass) {
        return dispatchCache.computeIfAbsent(eventClass, ec -> {
            List<Subscription<?>> out = new ArrayList<>();
            for (Subscription<?> s : subscriptions) {
                if (s.type().isAssignableFrom(ec)) {
                    out.add(s);
                }
            }
            return out;
        });
    }

    /** Current subscriber count (for diagnostics/tests). */
    public int subscriberCount() {
        return subscriptions.size();
    }

    /** Snapshot of registered event type names (for diagnostics). */
    public List<String> subscribedTypeNames() {
        return subscriptions.stream().map(s -> s.type().getName()).toList();
    }
}
