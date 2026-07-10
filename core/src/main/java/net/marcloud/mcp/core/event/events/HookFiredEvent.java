package net.marcloud.mcp.core.event.events;

import java.util.Arrays;
import java.util.List;

import net.marcloud.mcp.core.event.GameEvent;

/**
 * Fired whenever a dynamically-installed hook's advice body executes. Carries
 * the routeKey (identifying which hook fired), the target class+method, and a
 * read-only snapshot of the method's arguments. Subscribers can observe
 * arbitrary method calls without editing MC source.
 *
 * <p><b>L7 boundary caution:</b> {@link #args()} references live game objects.
 * Subscribers must treat them as read-only observations; mutating them is
 * undefined behavior (the advice uses {@code readOnly = true}, but that only
 * prevents reassigning the array itself, not mutating object fields). For safe
 * logging/inspection without leaking object values, use {@link #argTypes()}.
 * This mirrors the contract of {@link PacketReceivedEvent}.
 */
public final class HookFiredEvent extends GameEvent {

    private final int routeKey;
    private final String targetClass;
    private final String method;
    private final Object[] args;

    public HookFiredEvent(int routeKey, String targetClass, String method, Object[] args) {
        this.routeKey = routeKey;
        this.targetClass = targetClass;
        this.method = method;
        this.args = args;
    }

    /** The per-hook identifier assigned by DynamicHookManager at install time. */
    public int routeKey() {
        return routeKey;
    }

    /** Fully-qualified target class name (e.g. "net.minecraft.network.NetworkManager"). */
    public String targetClass() {
        return targetClass;
    }

    /** Target method name (e.g. "channelRead0"). */
    public String method() {
        return method;
    }

    /**
     * Read-only snapshot of the method's arguments at entry. The array itself
     * and its elements reference live game objects — treat them as read-only
     * observations. Never empty (nullIfEmpty = false in the advice).
     */
    public Object[] args() {
        return args;
    }

    /**
     * The simple class names of the arguments (or "null" for null elements),
     * for safe logging without leaking object values (L7 boundary). For
     * example, {@code ["NetworkManager", "Packet", "null"]}.
     */
    public List<String> argTypes() {
        return Arrays.stream(args)
                .map(a -> a == null ? "null" : a.getClass().getSimpleName())
                .toList();
    }
}
