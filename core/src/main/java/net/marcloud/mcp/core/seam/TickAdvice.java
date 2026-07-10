package net.marcloud.mcp.core.seam;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice body inlined into {@code Minecraft.runTick}. Captures tick
 * entry and forwards to {@link TickBridge} on method entry. Runs on the game
 * thread; observes only.
 */
public final class TickAdvice {

    private TickAdvice() {
    }

    /** Inlined at the entry of {@code Minecraft.runTick()}. */
    @Advice.OnMethodEnter
    static void enter() {
        TickBridge.onTick();
    }
}
