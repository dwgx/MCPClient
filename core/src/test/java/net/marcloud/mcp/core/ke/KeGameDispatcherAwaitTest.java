package net.marcloud.mcp.core.ke;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

/**
 * Regression for finding H7: {@code KeGameDispatcher.invokeAndWait} must cancel
 * the queued game-thread task on ANY non-normal exit — not just TimeoutException.
 *
 * <p>Before the fix, only {@code catch (TimeoutException)} called
 * {@code future.cancel(false)}. When the supervisor interrupts a timed-out worker
 * thread, {@code future.get} throws InterruptedException, which propagated WITHOUT
 * cancelling — so a mutating tool the AI was already told "timed out" still ran on
 * a later frame. These tests drive the extracted, Minecraft-free
 * {@link KeGameDispatcher#awaitOrCancel} seam with a fake future.
 *
 * <p>Non-vacuous: on the pre-fix code {@code interruptCancelsTheQueuedTask} FAILS
 * (cancel never called on the interrupt path).
 */
public class KeGameDispatcherAwaitTest {

    /** Minimal ListenableFuture that throws a chosen exception from get(t,u) and records cancel(). */
    private static final class FakeFuture<V> implements ListenableFuture<V> {
        private final Exception toThrow;
        volatile boolean cancelled = false;

        FakeFuture(Exception toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (toThrow instanceof InterruptedException) throw (InterruptedException) toThrow;
            if (toThrow instanceof TimeoutException) throw (TimeoutException) toThrow;
            if (toThrow instanceof ExecutionException) throw (ExecutionException) toThrow;
            throw new AssertionError("unexpected");
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return true; }
        @Override public V get() { throw new UnsupportedOperationException(); }
        @Override public void addListener(Runnable listener, Executor executor) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A timeout must NOT cancel the task.
     *
     * <p>This test previously asserted the opposite, and it was wrong. The future passed to
     * {@code awaitOrCancel} is a vanilla {@code MinecraftFuture} from
     * {@code Minecraft.addScheduledTask}, which pushes the task onto {@code scheduledTasks} with
     * no way to take it back off. Cancelling leaves a cancelled FutureTask in the game's own
     * queue; the next {@code runGameLoop} drains it through {@code Util.runTask} ->
     * {@code FutureTask.get()}, and vanilla does not catch CancellationException there. Observed
     * live three times before it was understood: a tool call that timed out took the entire
     * client down with "Unreported exception thrown".
     *
     * <p>So the correct contract is: report the timeout, let the task run, discard its result. The
     * test now pins that, because the failure it prevents is a crash rather than a slow call.
     */
    @Test
    public void timeoutDoesNotCancelTheQueuedTask() {
        FakeFuture<String> f = new FakeFuture<>(new TimeoutException("deadline"));
        try {
            KeGameDispatcher.awaitOrCancel(f, 5000L);
            fail("expected TimeoutException");
        } catch (TimeoutException expected) {
            // good
        } catch (InterruptedException | ExecutionException other) {
            fail("wrong exception: " + other);
        }
        assertFalse("a timed-out game task must be left in the queue, not cancelled: cancelling "
                        + "it poisons Minecraft.scheduledTasks and crashes the client on the next "
                        + "tick", f.cancelled);
    }

    /** Same contract on the interrupt path, for the same reason. */
    @Test
    public void interruptDoesNotCancelTheQueuedTask() {
        FakeFuture<String> f = new FakeFuture<>(new InterruptedException("worker interrupted"));
        try {
            KeGameDispatcher.awaitOrCancel(f, 5000L);
            fail("expected InterruptedException to propagate");
        } catch (InterruptedException expected) {
            // good
        } catch (ExecutionException | TimeoutException other) {
            fail("wrong exception: " + other);
        }
        assertFalse("an interrupted worker must also leave the game queue intact", f.cancelled);
    }

    @Test
    public void executionExceptionDoesNotCancel() {
        // The task already ran and threw; cancelling is meaningless and we must not.
        FakeFuture<String> f = new FakeFuture<>(new ExecutionException(new RuntimeException("boom")));
        try {
            KeGameDispatcher.awaitOrCancel(f, 5000L);
            fail("expected ExecutionException");
        } catch (ExecutionException expected) {
            // good
        } catch (InterruptedException | TimeoutException other) {
            fail("wrong exception: " + other);
        }
        assertFalse("execution-exception path should not cancel (task already ran)", f.cancelled);
    }
}
