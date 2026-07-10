package net.marcloud.mcp.core.event;

import java.util.List;
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
 */
public final class EventBus {

    /** One subscriber list per event type, keyed by class for O(1) dispatch. */
    private final CopyOnWriteArrayList<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    private record Subscription<T extends GameEvent>(Class<T> type, Consumer<T> handler) {
    }

    /** Register a handler for events of {@code type} (and its subtypes). */
    public <T extends GameEvent> void subscribe(Class<T> type, Consumer<T> handler) {
        subscriptions.add(new Subscription<>(type, handler));
    }

    /** Remove a previously-registered handler instance. */
    public <T extends GameEvent> void unsubscribe(Consumer<T> handler) {
        subscriptions.removeIf(s -> s.handler() == handler);
    }

    /**
     * Publish {@code event} to all matching subscribers. Exceptions from one
     * subscriber are swallowed (logged to stderr) so a bad handler cannot take
     * down the game thread.
     */
    @SuppressWarnings("unchecked")
    public void publish(GameEvent event) {
        for (Subscription<?> s : subscriptions) {
            if (s.type().isInstance(event)) {
                try {
                    ((Consumer<GameEvent>) s.handler()).accept(event);
                } catch (RuntimeException e) {
                    System.err.println("[EventBus] subscriber for "
                            + s.type().getSimpleName() + " threw: " + e);
                }
            }
        }
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
