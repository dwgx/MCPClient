package net.marcloud.mcp.core.hook;

import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.DisconnectedEvent;
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
        } catch (RuntimeException ignored) {
            // never let observation break the game thread
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
        } catch (RuntimeException ignored) {
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
        } catch (RuntimeException ignored) {
        }
    }
}
