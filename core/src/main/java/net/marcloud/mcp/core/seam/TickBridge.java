package net.marcloud.mcp.core.seam;

import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.TickEvent;

/**
 * Static bridge that injected tick advice calls into. Mirrors {@link
 * net.marcloud.mcp.core.hook.HookBridge}: advice is inlined into
 * {@code Minecraft.runTick}, so it can only call publicly-reachable static
 * members. This forwards to the live {@link EventBus} wired at install time.
 *
 * <p>Defensive: null-checks, swallows errors. Advice runs on the game thread;
 * a fault here must never disturb the game loop.
 */
public final class TickBridge {

    private static volatile EventBus bus;
    private static volatile long tickCounter;

    private TickBridge() {
    }

    /** Wire the bus that tick callbacks publish to. */
    public static void setBus(EventBus eventBus) {
        bus = eventBus;
    }

    /** Called from injected advice at Minecraft.runTick entry. */
    public static void onTick() {
        tickCounter++;
        EventBus b = bus;
        if (b == null) {
            return;
        }
        try {
            b.publish(new TickEvent(tickCounter));
        } catch (Throwable ignored) {
            // Runs inlined inside the game loop — never let observation fault
            // (even an Error) break the game thread.
        }
    }

    /** Current tick count (for diagnostics/tests). */
    public static long tickCounter() {
        return tickCounter;
    }

    /** Reset counter (for tests). */
    public static void resetCounter() {
        tickCounter = 0;
    }
}
