package net.marcloud.mcp.core.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;

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

    /** Set once the server is built; enables live add/notify. Null before that. */
    private volatile McpSyncServer server;

    public CapabilityRegistry(SafeToolExecutor executor) {
        this.executor = executor;
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
    private SyncToolSpecification supervise(SyncToolSpecification raw, ToolStats stats) {
        var rawHandler = raw.callHandler();
        return new SyncToolSpecification(raw.tool(),
                (exchange, request) -> executor.run(stats, rawHandler, exchange, request, 0));
    }

    /**
     * Register (or replace) a capability. If the server is bound, the change is
     * pushed live and clients are notified. Old version is archived.
     */
    public synchronized void register(String name, SyncToolSpecification rawSpec,
                                      String source, String description, boolean builtIn) {
        ToolStats stats = statsByName.computeIfAbsent(name, ToolStats::new);
        Capability previous = current.get(name);
        int version = (previous == null) ? 1 : previous.version() + 1;

        SyncToolSpecification supervised = supervise(rawSpec, stats);
        Capability cap = new Capability(name, supervised, source, description, version, stats, builtIn);

        if (previous != null) {
            archive.computeIfAbsent(name, k -> new ArrayList<>()).add(previous);
        }
        current.put(name, cap);

        McpSyncServer s = server;
        if (s != null) {
            if (previous != null) {
                try {
                    s.removeTool(name);
                } catch (RuntimeException ignored) {
                    // not registered yet on server; fine
                }
                stats.reset(); // a redefined tool gets a clean breaker
            }
            s.addTool(supervised);
            s.notifyToolsListChanged();
        }
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
        Capability prev = history.remove(history.size() - 1);
        current.put(name, prev);
        McpSyncServer s = server;
        if (s != null) {
            try {
                s.removeTool(name);
            } catch (RuntimeException ignored) {
            }
            s.addTool(prev.spec());
            s.notifyToolsListChanged();
        }
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
}
