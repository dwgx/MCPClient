package net.marcloud.mcp.core.registry;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Supervises every tool invocation so one bad/slow/crashing tool can never take
 * down the MCP server or the game (Erlang "let it crash" + Resilience4j
 * bulkhead/timeout/circuit-breaker, plain JDK).
 *
 * <p>Each call: consult the tool's circuit breaker (fail fast if OPEN) → run the
 * handler on a worker with a hard timeout → catch every {@link Throwable} at the
 * boundary → record success/failure in {@link ToolStats}.
 *
 * <p><b>Runaway handling (honest).</b> A timeout {@code cancel(true)} only
 * <i>interrupts</i> the worker; AI-authored code (eval_java / create_tool'd
 * tools) that ignores interrupts (e.g. {@code while(true){}}) keeps running and
 * pins a thread. Two mitigations so this can't brick the tool surface:
 * <ul>
 *   <li>The general pool is a <b>cached</b> pool, not a fixed one — an abandoned
 *       worker never blocks a new call; a fresh thread is spawned. We cap the
 *       number of concurrently-abandoned (still-running-after-timeout) workers so
 *       runaways degrade gracefully (fail fast) instead of spawning unbounded
 *       threads and pinning every core.</li>
 *   <li>Recovery/meta tools (create_tool, rollback_tool, list_capabilities,
 *       get_tool_source) run on a <b>separate</b> executor, so no amount of
 *       runaway general tools can starve the ability to inspect/fix/roll back.</li>
 * </ul>
 * True CPU/heap isolation would need a child process — noted, not attempted.
 */
public final class SafeToolExecutor {

    /** Tool names that get the reserved recovery lane (never starved by runaways). */
    private static final java.util.Set<String> RECOVERY_TOOLS = java.util.Set.of(
            "create_tool", "rollback_tool", "list_capabilities", "get_tool_source");

    private final ExecutorService generalPool;
    private final ExecutorService recoveryPool;
    private final long defaultTimeoutMillis;

    /** Workers still running after their call timed out (leaked/abandoned). */
    private final AtomicInteger abandoned = new AtomicInteger();
    private final int maxAbandoned;

    public SafeToolExecutor(int maxConcurrent, long defaultTimeoutMillis) {
        this.generalPool = Executors.newCachedThreadPool(daemon("mcp-tool-worker"));
        this.recoveryPool = Executors.newCachedThreadPool(daemon("mcp-recovery-worker"));
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        // Beyond this many simultaneously-wedged runaway tools, fail fast rather
        // than keep spawning threads that pin cores.
        this.maxAbandoned = Math.max(2, maxConcurrent);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Run a tool handler under supervision. Recovery/meta tools use a reserved
     * pool so they can't be starved by runaway general tools.
     */
    public CallToolResult run(ToolStats stats,
                              java.util.function.BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler,
                              McpSyncServerExchange exchange,
                              CallToolRequest request,
                              long timeoutMillis) {
        if (!stats.allowCall()) {
            return errorResult("tool '" + stats.toolName()
                    + "' is quarantined (circuit OPEN after repeated failures); "
                    + "last error: " + stats.lastError());
        }

        boolean recovery = RECOVERY_TOOLS.contains(stats.toolName());
        if (!recovery && abandoned.get() >= maxAbandoned) {
            // Too many runaway tools are still pinning threads; fail fast to
            // protect the JVM instead of spawning yet another doomed worker.
            stats.recordFailure("too many runaway tools in flight (" + abandoned.get() + ")", true);
            return errorResult("tool '" + stats.toolName() + "' rejected: "
                    + abandoned.get() + " runaway tool(s) still running after timeout; "
                    + "recovery tools remain available.");
        }

        long timeout = timeoutMillis > 0 ? timeoutMillis : defaultTimeoutMillis;
        ExecutorService pool = recovery ? recoveryPool : generalPool;
        TaskState state = new TaskState();
        Callable<CallToolResult> task = () -> {
            state.enter();
            try {
                return handler.apply(exchange, request);
            } finally {
                state.exit(abandoned);
            }
        };
        Future<CallToolResult> future = pool.submit(task);
        try {
            CallToolResult result = future.get(timeout, TimeUnit.MILLISECONDS);
            // A returned isError result is a DOMAIN rejection (bad args, compile
            // error, "not connected", ...), NOT a tool fault. Only thrown
            // exceptions / timeouts trip the breaker.
            stats.recordSuccess();
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            state.markAbandonedIfRunning(abandoned);
            stats.recordFailure("timeout after " + timeout + "ms", true);
            return errorResult("tool '" + stats.toolName() + "' timed out after " + timeout
                    + "ms (interrupt requested; CPU-bound code may keep running)");
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            stats.recordFailure(cause.toString(), false);
            return errorResult("tool '" + stats.toolName() + "' failed: " + cause);
        }
    }

    private static final class TaskState {
        private boolean entered;
        private boolean exited;
        private boolean abandoned;

        synchronized void enter() {
            entered = true;
        }

        synchronized void markAbandonedIfRunning(AtomicInteger count) {
            if (entered && !exited && !abandoned) {
                abandoned = true;
                count.incrementAndGet();
            }
        }

        synchronized void exit(AtomicInteger count) {
            exited = true;
            if (abandoned) {
                abandoned = false;
                count.decrementAndGet();
            }
        }
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    public void shutdown() {
        generalPool.shutdownNow();
        recoveryPool.shutdownNow();
    }
}
