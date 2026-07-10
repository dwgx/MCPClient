package net.marcloud.mcp.core.registry;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Supervises every tool invocation so one bad/slow/crashing tool can never take
 * down the MCP server or the game (Erlang "let it crash" + Resilience4j
 * bulkhead/timeout/circuit-breaker, implemented with plain JDK to stay
 * dependency-light).
 *
 * <p>Each call: consult the tool's circuit breaker (fail fast if OPEN) → run the
 * handler on a bounded worker pool with a hard timeout (a hung tool is abandoned,
 * not allowed to freeze the server) → catch every {@link Throwable} at the
 * boundary and convert to a structured MCP error → record success/failure in
 * {@link ToolStats} (which trips the breaker after repeated failures).
 *
 * <p>Honest limitation: an in-JVM thread pool bounds concurrency and wall-time,
 * not heap. A generated tool that allocates unboundedly can still pressure memory;
 * true resource isolation needs a child process (a later hardening step).
 */
public final class SafeToolExecutor {

    /** Shared bounded pool = bulkhead (caps concurrent tool work). Daemon threads. */
    private final ExecutorService pool;
    private final long defaultTimeoutMillis;

    public SafeToolExecutor(int maxConcurrent, long defaultTimeoutMillis) {
        this.pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), r -> {
            Thread t = new Thread(r, "mcp-tool-worker");
            t.setDaemon(true);
            return t;
        });
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    /**
     * Run a tool handler under supervision.
     *
     * @param stats   the tool's health record (breaker + counters)
     * @param handler the actual tool logic
     * @param exchange MCP exchange (passed through)
     * @param request MCP call request (passed through)
     * @param timeoutMillis per-call timeout, or <=0 for the default
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
        long timeout = timeoutMillis > 0 ? timeoutMillis : defaultTimeoutMillis;
        Callable<CallToolResult> task = () -> handler.apply(exchange, request);
        Future<CallToolResult> future = pool.submit(task);
        try {
            CallToolResult result = future.get(timeout, TimeUnit.MILLISECONDS);
            // A returned isError result is a DOMAIN rejection (bad args, compile
            // error, "not connected", ...), NOT a tool fault. It must NOT trip the
            // breaker — otherwise the AI iterating on create_tool source would
            // quarantine create_tool after 3 compile errors, breaking the whole
            // self-extension loop. Only thrown exceptions / timeouts are faults.
            stats.recordSuccess();
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            stats.recordFailure("timeout after " + timeout + "ms", true);
            return errorResult("tool '" + stats.toolName() + "' timed out after " + timeout + "ms");
        } catch (Throwable t) {
            // Includes ExecutionException wrapping whatever the tool threw.
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            stats.recordFailure(cause.toString(), false);
            return errorResult("tool '" + stats.toolName() + "' failed: " + cause);
        }
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
