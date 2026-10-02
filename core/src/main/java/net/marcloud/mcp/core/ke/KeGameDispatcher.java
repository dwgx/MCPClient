package net.marcloud.mcp.core.ke;

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
public final class KeGameDispatcher {

    private final Minecraft mc;

    public KeGameDispatcher(Minecraft mc) {
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
        return awaitOrCancel(mc.addScheduledTask(task), timeoutMillis);
    }

    /**
     * Block on {@code future} up to {@code timeoutMillis}, cancelling the queued
     * task on ANY non-normal exit (timeout OR interrupt) so it doesn't run on a
     * later frame after the caller was already told it failed. A not-yet-started
     * {@link ListenableFuture} no-ops once cancelled; one already draining cannot
     * be stopped — game-thread work should be kept bounded.
     *
     * <p>Package-visible and Minecraft-free so the timeout contract is unit-testable without a
     * live game. It deliberately does NOT cancel: see the comment in the body, which is the
     * reason a change here should not be made casually.
     */
    static <V> V awaitOrCancel(ListenableFuture<V> future, long timeoutMillis)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            // DELIBERATELY NOT CANCELLED.
            //
            // This future is a vanilla `MinecraftFuture`: addScheduledTask wraps the callable and
            // pushes it onto Minecraft.scheduledTasks, and there is no way to take it back off.
            // Cancelling it therefore leaves a CANCELLED FutureTask sitting in the game's own
            // queue, which the next runGameLoop drains through Util.runTask ->
            // FutureTask.get() -- and vanilla does not catch CancellationException there, so the
            // client dies with "Unreported exception thrown: java.util.concurrent.
            // CancellationException" at Minecraft.java:1105.
            //
            // That crash was observed live three times before this was understood, and it is
            // strictly worse than the timeout it was trying to avoid: a slow tool call took the
            // whole client down. The task still runs; we simply stop waiting for it and report
            // the timeout. Its result is discarded by the caller, which is the honest outcome.
            throw e;
        }
    }
}
