package net.marcloud.mcp.core.thread;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.util.concurrent.ListenableFuture;

import net.minecraft.client.Minecraft;

/**
 * Marshals work onto the Minecraft client (game) thread and returns the result.
 *
 * <p>This is the load-bearing safety primitive of Core: MCP requests arrive on
 * network/IO threads, but touching game state or sending packets must happen on
 * the game thread. We reuse the game's own scheduler ({@link
 * Minecraft#addScheduledTask(Callable)}), which drains {@code scheduledTasks}
 * once per frame — the same mechanism vanilla uses for cross-thread work.
 *
 * <p>Guards learned from mature host-app MCP integrations (IDA/Unity):
 * <ul>
 *   <li><b>Timeout</b> — a wedged task must not freeze the caller forever; every
 *       blocking call takes a deadline and throws {@link TimeoutException}.</li>
 *   <li><b>Reentrancy</b> — if we are already ON the game thread, run inline
 *       instead of scheduling (which would deadlock waiting for a frame that
 *       cannot advance).</li>
 * </ul>
 *
 * <p>Note: forcing the game thread to advance is not our job — if the game loop
 * is paused, tasks queue until it resumes; the timeout bounds the wait.
 */
public final class MainThreadExecutor {

    private final Minecraft mc;

    public MainThreadExecutor(Minecraft mc) {
        this.mc = mc;
    }

    /** True if the current thread is the game thread. */
    public boolean onGameThread() {
        return mc.isCallingFromMinecraftThread();
    }

    /**
     * Schedule {@code task} on the game thread without waiting for the result.
     * If already on the game thread, the game runs it inline (per vanilla).
     */
    public <V> ListenableFuture<V> submit(Callable<V> task) {
        return mc.addScheduledTask(task);
    }

    /** Fire-and-forget a Runnable on the game thread. */
    public ListenableFuture<Object> submit(Runnable task) {
        return mc.addScheduledTask(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Run {@code task} on the game thread and block for its result up to
     * {@code timeoutMillis}. Reentrancy-safe: runs inline if already on-thread.
     *
     * @throws TimeoutException   if the game thread did not run it in time
     * @throws ExecutionException if the task threw
     */
    public <V> V invokeAndWait(Callable<V> task, long timeoutMillis)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (onGameThread()) {
            // Reentrancy guard: scheduling here would wait for a frame we are
            // currently blocking, i.e. deadlock. Run directly.
            try {
                return task.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }
        ListenableFuture<V> future = mc.addScheduledTask(task);
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
