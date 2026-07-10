package net.marcloud.mcp.core.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.security.AccessDecision;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.PolicyEngine;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.ToolRequest;

/**
 * The living catalog of the神器's capabilities — the neural-network-like core.
 *
 * <p>Each tool is a "neuron" that can be added at runtime (grow the network);
 * every invocation is supervised by {@link SafeToolExecutor} so a misfiring
 * neuron self-quarantines instead of killing the system (graceful degradation).
 * Old versions are archived for rollback (Darwin Gödel Machine "stepping-stones").
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Wrap every tool handler in the supervised executor before registering.</li>
 *   <li>Register/replace tools on a live {@link McpSyncServer} and emit
 *       {@code notifyToolsListChanged} so the AI client sees new capabilities.</li>
 *   <li>Keep source + description + stats so the system can describe itself
 *       (introspection tools read this).</li>
 * </ul>
 */
public final class CapabilityRegistry {

    private final Map<String, Capability> current = new ConcurrentHashMap<>();
    private final Map<String, List<Capability>> archive = new ConcurrentHashMap<>();
    private final Map<String, ToolStats> statsByName = new ConcurrentHashMap<>();
    private final SafeToolExecutor executor;
    private final PolicyEngine engine;

    /** Cap on archived versions kept per tool (bounds long-session growth). */
    private static final int MAX_ARCHIVE_PER_TOOL = 10;

    /** Set once the server is built; enables live add/notify. Null before that. */
    private volatile McpSyncServer server;

    /**
     * Back-compat constructor: wraps a bare {@link PermissionPolicy} in the
     * default in-process reference monitor. Existing callers/tests that only know
     * about ring clearance keep working; the full 7-layer gate still runs (with
     * safe defaults, so behavior is identical for tools with no extra
     * requirements).
     */
    public CapabilityRegistry(SafeToolExecutor executor, PermissionPolicy policy) {
        this(executor, new InProcessPolicyEngine(policy));
    }

    /** Primary constructor: the reference monitor is the single decision authority. */
    public CapabilityRegistry(SafeToolExecutor executor, PolicyEngine engine) {
        this.executor = executor;
        this.engine = engine;
    }

    /** The policy engine (reference monitor) governing tool invocation. */
    public PolicyEngine engine() {
        return engine;
    }

    /**
     * Whether a capability would be permitted right now for the current subject —
     * the FULL 7-layer decision (ring + integrity + privilege + capability), not
     * just the ring. Used by the REST facade's "allowed" display so it reflects
     * the real gate. Evaluated with an empty argument map (schema/boundary layers
     * are argument-dependent and enforced at call time).
     */
    public boolean isAllowed(Capability c) {
        ToolRequest req = new ToolRequest(c.name(), java.util.Map.of(), c.builtIn());
        return engine.evaluate(engine.currentSubject(), req).allow();
    }

    /** Attach the live server so subsequent registrations propagate to clients. */
    public void bindServer(McpSyncServer server) {
        this.server = server;
    }

    /**
     * Wrap a raw tool spec's handler in the supervised executor. The returned
     * spec is what actually gets registered — its handler consults the circuit
     * breaker, enforces a timeout, and catches every throwable.
     */
    private SyncToolSpecification supervise(SyncToolSpecification raw, ToolStats stats,
                                            boolean builtIn) {
        var rawHandler = raw.callHandler();
        String toolName = raw.tool().name();
        return new SyncToolSpecification(raw.tool(), (exchange, request) -> {
            // Reference-monitor gate FIRST: run the full 7-layer decision (L2 ring
            // + L3 integrity + L4 privilege + L5 capability, AND-composed) before
            // the breaker/executor. A deny fails fast and does NOT touch the
            // circuit breaker (a permission denial is not a tool fault). Safe
            // defaults mean a tool with no extra requirements is gated by its ring
            // alone — identical to the pre-Phase-2 behavior.
            ToolRequest toolReq = new ToolRequest(toolName, request.arguments(), builtIn);
            AccessDecision decision = engine.evaluate(engine.currentSubject(), toolReq);
            if (!decision.allow()) {
                return CallToolResult.builder()
                        .addTextContent(decision.message())
                        .isError(true)
                        .build();
            }
            return executor.run(stats, rawHandler, exchange, request, 0);
        });
    }

    /**
     * Register (or replace) a capability. If the server is bound, the change is
     * pushed live and clients are notified. Old version is archived.
     */
    public synchronized void register(String name, SyncToolSpecification rawSpec,
                                      String source, String description, boolean builtIn, Ring ring) {
        ToolStats stats = statsByName.computeIfAbsent(name, ToolStats::new);
        Capability previous = current.get(name);
        int version = (previous == null) ? 1 : previous.version() + 1;

        SyncToolSpecification supervised = supervise(rawSpec, stats, builtIn);
        Capability cap = new Capability(name, supervised, source, description, version, stats, builtIn, ring);

        // Do the FALLIBLE live-server mutation FIRST. If addTool/notify throws, we
        // must not have already committed current/archive — otherwise the manifest
        // would advertise a tool the server can't call. On success, commit.
        McpSyncServer s = server;
        if (s != null) {
            if (previous != null) {
                try {
                    s.removeTool(name);
                } catch (RuntimeException ignored) {
                    // not registered on the server yet; fine
                }
            }
            s.addTool(supervised);       // may throw — nothing committed yet
            s.notifyToolsListChanged();
        }

        // Server mutation succeeded (or no server bound): commit in-memory state.
        if (previous != null) {
            List<Capability> history = archive.computeIfAbsent(name, k -> new ArrayList<>());
            history.add(previous);
            // Bound archive depth so a long create_tool session doesn't grow
            // unbounded. Keep the most recent versions.
            while (history.size() > MAX_ARCHIVE_PER_TOOL) {
                history.remove(0);
            }
            stats.reset(); // a redefined tool gets a clean breaker
        }
        current.put(name, cap);
    }

    /** All current capabilities' supervised specs, for initial server build. */
    public synchronized List<SyncToolSpecification> currentSpecs() {
        List<SyncToolSpecification> out = new ArrayList<>();
        for (Capability c : current.values()) {
            out.add(c.spec());
        }
        return out;
    }

    /** Snapshot of current capabilities (for introspection). */
    public List<Capability> capabilities() {
        return new ArrayList<>(current.values());
    }

    public Capability get(String name) {
        return current.get(name);
    }

    /** Roll a tool back to its previous archived version (DGM stepping-stone). */
    public synchronized boolean rollback(String name) {
        List<Capability> history = archive.get(name);
        if (history == null || history.isEmpty()) {
            return false;
        }
        // Peek (don't remove yet): only drop the archived backup once the live
        // server has actually accepted the rollback, so a throw can't lose it.
        Capability prev = history.get(history.size() - 1);
        McpSyncServer s = server;
        if (s != null) {
            try {
                s.removeTool(name);
            } catch (RuntimeException ignored) {
            }
            s.addTool(prev.spec());      // may throw — backup still in archive
            s.notifyToolsListChanged();
        }
        history.remove(history.size() - 1);
        current.put(name, prev);
        prev.stats().reset();
        return true;
    }

    /** Tool names currently registered. */
    public List<String> names() {
        return new ArrayList<>(current.keySet());
    }

    /** Health summaries for the manifest. */
    public Map<String, ToolStats> allStats() {
        return Map.copyOf(statsByName);
    }

    /** Convenience: a Tool's name from its spec. */
    public static String nameOf(SyncToolSpecification spec) {
        Tool t = spec.tool();
        return t.name();
    }

    /**
     * Invoke a tool by name with an argument map, going through its SUPERVISED
     * handler — so the privilege ring gate, circuit breaker, and timeout all
     * apply exactly as they do for an MCP client. Used by the REST facade so the
     * HTTP front door can't bypass the security model. Returns null if no such
     * tool.
     */
    public CallToolResult invoke(String name, Map<String, Object> args) {
        Capability c = current.get(name);
        if (c == null) {
            return null;
        }
        var req = new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                name, args == null ? Map.of() : args);
        // exchange is null: our tool handlers don't use it (only sampling would).
        return c.spec().callHandler().apply(null, req);
    }
}
