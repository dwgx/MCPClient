package net.marcloud.mcp.core.boot;

import net.bytebuddy.asm.Advice;

/**
 * Advice inlined into {@code Minecraft.startGame()}. When startGame returns, the
 * client is fully initialized (window, resources, sound) but the game loop has
 * not started — the ideal moment to ignite MCP Core. Fires exactly once.
 */
public final class StartupAdvice {

    private StartupAdvice() {
    }

    @Advice.OnMethodExit
    static void onExit() {
        CoreBootstrap.onGameInitialized();
    }
}
