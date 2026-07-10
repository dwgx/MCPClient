package net.marcloud.mcp.core.hook;

import java.util.concurrent.ConcurrentHashMap;

import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.DisconnectedEvent;
import net.marcloud.mcp.core.event.events.HookFiredEvent;
import net.marcloud.mcp.core.event.events.PacketReceivedEvent;
import net.marcloud.mcp.core.event.events.PacketSentEvent;
import net.minecraft.network.Packet;
import net.minecraft.util.IChatComponent;

/**
 * Static bridge that injected advice calls into. Byte Buddy {@code @Advice}
 * bodies are inlined into MC methods, so they can only call publicly-reachable
 * static members — this class is that surface. It forwards to the live {@link
 * EventBus} set at hook-install time.
 *
 * <p>Every method is defensive (null-checks, swallows its own errors): advice
 * runs inside the game's own network/main threads, and a fault here must never
 * disturb the game. All arguments are treated as read-only observations.
 */
public final class HookBridge {

    private static volatile EventBus bus;

    private HookBridge() {
    }

    /** Wire the bus that hook callbacks publish to. */
    public static void setBus(EventBus eventBus) {
        bus = eventBus;
    }

    /** Called from injected advice at NetworkManager.channelRead0 (inbound). */
    public static void onPacketReceived(Object packet) {
        EventBus b = bus;
        if (b == null || !(packet instanceof Packet)) {
            return;
        }
        try {
            b.publish(new PacketReceivedEvent((Packet<?>) packet));
        } catch (Throwable ignored) {
            // This runs inlined inside MC's Netty code — never let observation
            // (even an Error) break the game thread.
        }
    }

    /** Called from injected advice at NetworkManager.sendPacket (outbound). */
    public static void onPacketSent(Object packet) {
        EventBus b = bus;
        if (b == null || !(packet instanceof Packet)) {
            return;
        }
        try {
            b.publish(new PacketSentEvent((Packet<?>) packet));
        } catch (Throwable ignored) {
        }
    }

    /** Called from injected advice at NetworkManager.closeChannel (disconnect). */
    public static void onDisconnect(Object reason) {
        EventBus b = bus;
        if (b == null) {
            return;
        }
        try {
            b.publish(new DisconnectedEvent(reason instanceof IChatComponent ic ? ic : null));
        } catch (Throwable ignored) {
        }
    }

    // ---- generic dynamic-hook routing (C3 DynamicHookManager) --------------
    // Each dynamically-installed hook carries its own routeKey (bound constant in
    // GenericEntryAdvice). The route table maps routeKey -> the target metadata so
    // one shared advice body can serve arbitrarily many hooks. Kept here (not a
    // separate class) so all inlined-advice statics live on one system-loader
    // surface reachable from MC bytecode.

    private record Route(EventBus bus, String targetClass, String method) {
    }

    private static final ConcurrentHashMap<Integer, Route> ROUTES = new ConcurrentHashMap<>();

    /** Register a hook route BEFORE installOn, so advice can fire immediately. */
    public static void registerRoute(int key, EventBus eventBus, String targetClass, String method) {
        ROUTES.put(key, new Route(eventBus, targetClass, method));
    }

    /** Unregister a hook route after transformer.reset() succeeds. */
    public static void unregisterRoute(int key) {
        ROUTES.remove(key);
    }

    /**
     * Dispatch a dynamic-hook fire to the EventBus. Called from inlined
     * {@link GenericEntryAdvice} on whatever thread the hooked method runs on.
     * Defensive: never throws into the game.
     */
    public static void dispatch(int key, String method, Object[] args) {
        Route r = ROUTES.get(key);
        if (r == null) {
            return;
        }
        try {
            r.bus().publish(new HookFiredEvent(key, r.targetClass(), r.method(), args));
        } catch (Throwable ignored) {
        }
    }
}
