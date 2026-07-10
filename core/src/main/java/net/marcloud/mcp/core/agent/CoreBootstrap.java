package net.marcloud.mcp.core.agent;

import java.util.concurrent.atomic.AtomicBoolean;

import net.marcloud.mcp.core.McpCore;

/**
 * Ignites MCP Core once the game is initialized. Called from {@link
 * StartupAdvice} (woven into {@code Minecraft.startGame()}'s exit).
 *
 * <p><b>Self-containment</b>: this runs on the game thread at a critical moment,
 * so every failure is caught and logged — Core failing to start must NEVER crash
 * the game. Fires exactly once (guarded by an atomic flag), because startGame is
 * only called once but we defend against re-entry / re-transformation anyway.
 */
public final class CoreBootstrap {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile McpCore core;

    private CoreBootstrap() {
    }

    /** Invoked by advice when {@code Minecraft.startGame()} returns. */
    public static void onGameInitialized() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            McpCore c = new McpCore();
            c.start();
            core = c;
            System.err.println("[MCP Core] started after game initialization.");
        } catch (Throwable t) {
            // Never let Core startup take down the game.
            System.err.println("[MCP Core] failed to start (game continues): " + t);
            t.printStackTrace();
        }
    }

    /** The running Core instance, or null if not started / failed. */
    public static McpCore core() {
        return core;
    }
}
