package net.marcloud.mcp.core;

import java.util.concurrent.Callable;

import net.marcloud.mcp.core.ke.KeGameDispatcher;

/**
 * A process-wide static handle to the game thread and game façade, so that
 * AI-authored tools ({@code create_tool}) and {@code eval_java} snippets — which
 * run on MCP worker threads — have a way to safely touch game state.
 *
 * <p>Minecraft 1.8.9 world/entity state is NOT thread-safe: reading it off the
 * game thread risks torn/inconsistent values, and mutating or iterating it off
 * the game thread can crash the game (ConcurrentModificationException, corrupted
 * state). Generated code must route game access through {@link #onGameThread}.
 *
 * <p>Set once by {@code McpCore.start()}. Static because generated code compiled
 * at runtime has no other way to reach the wiring.
 */
public final class GameBridge {

    private static volatile KeGameDispatcher executor;
    private static volatile GameAccess game;

    private GameBridge() {
    }

    /** Wire the bridge (called once during Core startup). */
    public static void init(KeGameDispatcher exec, GameAccess gameAccess) {
        executor = exec;
        game = gameAccess;
    }

    /** The game façade. May be null before Core has started. */
    public static GameAccess game() {
        return game;
    }

    /** True if the calling thread is the game thread. */
    public static boolean onGameThread() {
        KeGameDispatcher e = executor;
        return e != null && e.onGameThread();
    }

    /**
     * Run {@code task} on the game thread and return its result, blocking up to
     * {@code timeoutMillis}. Use this from any tool/snippet that reads or mutates
     * live world/entity/player state.
     *
     * @throws IllegalStateException if the bridge isn't initialized
     */
    public static <V> V onGameThread(Callable<V> task, long timeoutMillis) throws Exception {
        KeGameDispatcher e = executor;
        if (e == null) {
            throw new IllegalStateException("GameBridge not initialized (Core not started)");
        }
        return e.invokeAndWait(task, timeoutMillis);
    }

    /** Convenience with a 5s default timeout. */
    public static <V> V onGameThread(Callable<V> task) throws Exception {
        return onGameThread(task, 5000L);
    }
}
