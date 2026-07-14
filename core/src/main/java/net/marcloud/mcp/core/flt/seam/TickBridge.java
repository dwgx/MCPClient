package net.marcloud.mcp.core.flt.seam;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * Static bridge that injected tick advice calls into. Mirrors {@link
 * net.marcloud.mcp.core.flt.HookBridge}: advice is inlined into
 * {@code Minecraft.runTick}, so it can only call publicly-reachable static
 * members. This forwards to the live {@link EventBus} wired at install time.
 *
 * <p><b>PHASE T:</b> the tick number is no longer a private counter here — the
 * bridge advances the single {@link GameClock} on every {@code runTick} entry, so
 * the whole kernel shares one authoritative tickId. The {@link TickEvent} it
 * publishes carries that same id, and off-thread observers (packets, hooks) stamp
 * themselves with {@link GameClock#lastCompletedTick()}. There is exactly one tick
 * counter in the system, and it lives in {@link GameClock}.
 *
 * <p>Defensive: null-checks, swallows errors. Advice runs on the game thread;
 * a fault here must never disturb the game loop.
 */
public final class TickBridge {

    private static volatile EventBus bus;

    private TickBridge() {
    }

    /** Wire the bus that tick callbacks publish to. */
    public static void setBus(EventBus eventBus) {
        bus = eventBus;
    }

    /** Called from injected advice at Minecraft.runTick entry. */
    public static void onTick() {
        // Advance the ONE global clock (this is the sole tick source).
        long tickId = GameClock.INSTANCE.advance();
        EventBus b = bus;
        if (b == null) {
            return;
        }
        try {
            b.publish(new TickEvent(tickId, GameClock.Phase.START));
        } catch (Throwable ignored) {
            // Runs inlined inside the game loop — never let observation fault
            // (even an Error) break the game thread.
        }
    }

    /** Current tick count (for diagnostics/tests) — reads the single {@link GameClock}. */
    public static long tickCounter() {
        return GameClock.INSTANCE.tickId();
    }

    /** Reset the shared clock (for tests). */
    public static void resetCounter() {
        GameClock.INSTANCE.reset();
    }
}
