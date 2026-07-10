package net.marcloud.mcp.core;

import net.marcloud.mcp.core.action.ActionManager;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.PacketReceivedEvent;
import net.marcloud.mcp.core.event.events.PacketSentEvent;
import net.marcloud.mcp.core.hook.HookManager;
import net.marcloud.mcp.core.hotload.HotLoadEngine;
import net.marcloud.mcp.core.http.HttpFacade;
import net.marcloud.mcp.core.memory.MemoryStore;
import net.marcloud.mcp.core.memory.MemoryTools;
import net.marcloud.mcp.core.narrative.GoalStack;
import net.marcloud.mcp.core.narrative.NarrativeTools;
import net.marcloud.mcp.core.mcp.SocketTransportServer;
import net.marcloud.mcp.core.mcp.ToolContext;
import net.marcloud.mcp.core.mcp.ToolRegistry;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.registry.DynamicToolFactory;
import net.marcloud.mcp.core.registry.MetaTools;
import net.marcloud.mcp.core.registry.SafeToolExecutor;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.PermissionTools;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.state.DisconnectTracker;
import net.marcloud.mcp.core.state.PacketLog;
import net.marcloud.mcp.core.thread.MainThreadExecutor;

/**
 * MCP Core entry point — assembles the whole stack and exposes the running game
 * over MCP.
 *
 * <p>Wiring order:
 * <ol>
 *   <li>{@link GameAccess} + {@link MainThreadExecutor} (game façade + thread marshal),</li>
 *   <li>{@link EventBus} + {@link HookManager} (observe MC networking at runtime),</li>
 *   <li>{@link PacketLog} fed from packet events,</li>
 *   <li>{@link HotLoadEngine} + {@link ActionManager} (control + live code),</li>
 *   <li>{@link CapabilityRegistry} + {@link SocketTransportServer} (expose it all
 *       as supervised, runtime-extensible MCP tools over a loopback socket).</li>
 * </ol>
 *
 * <p>Call {@link #start()} once the game is initialized (see the launcher hook).
 */
public final class McpCore {

    private static final int PACKET_LOG_CAPACITY = 256;

    private final EventBus bus = new EventBus();
    private final GameAccess game = new GameAccess();
    private final PacketLog packetLog = new PacketLog(PACKET_LOG_CAPACITY);
    private SocketTransportServer socketServer;
    private HttpFacade httpFacade;

    /**
     * Assemble and start Core. Installs runtime hooks (if Instrumentation is
     * present), begins recording packets, and starts the loopback-socket MCP
     * server on 127.0.0.1:25599 (not stdio — the game owns the console).
     */
    public void start() {
        MainThreadExecutor exec = new MainThreadExecutor(game.mc());
        ActionManager actions = new ActionManager(game, exec);
        HotLoadEngine hotLoad = new HotLoadEngine(getClass().getClassLoader());

        // Expose the game thread + façade statically so AI-authored tools and
        // eval_java snippets (which run on worker threads) can safely marshal
        // game access via GameBridge.onGameThread(...).
        GameBridge.init(exec, game);

        // Feed the packet log from the event stream.
        bus.subscribe(PacketReceivedEvent.class, e -> packetLog.recordInbound(e.packetType()));
        bus.subscribe(PacketSentEvent.class, e -> packetLog.recordOutbound(e.packetType()));

        // Track disconnects for the "why was I kicked" report.
        DisconnectTracker disconnects = new DisconnectTracker(bus, packetLog);

        // Install runtime network hooks (needs -javaagent). Non-fatal if absent:
        // the MCP server still serves state/chat/eval; only live packet
        // observation requires the agent.
        HookManager hooks = new HookManager(bus);
        try {
            if (hooks.canInstall()) {
                hooks.install();
            } else {
                System.err.println("[MCP Core] Instrumentation absent — packet hooks disabled. "
                        + "Start with -javaagent:core-<ver>.jar to enable them.");
            }
        } catch (Throwable t) {
            // A ByteBuddy retransform failure must only disable packet
            // observation, NOT take down the whole MCP endpoint (the rest of
            // start() — registry, tools, socket — must still come up).
            System.err.println("[MCP Core] hook install failed; packet observation "
                    + "disabled, server continues: " + t);
        }

        ToolContext ctx = new ToolContext(game, actions, hotLoad, packetLog, disconnects);

        // Build the capability registry: every tool is supervised (timeout +
        // circuit breaker + exception boundary) so one bad tool can't crash the
        // system, and new tools can be grown at runtime via create_tool.
        // Privilege policy: dev default = wide open (R-1). The restore token
        // (from -Dmcp.core.restoreToken or a random one) gates re-escalation, so
        // drop_privilege is a real kill-switch. Pin lower via -Dmcp.core.clearance.
        PermissionPolicy policy = buildPolicy();
        SafeToolExecutor executor = new SafeToolExecutor(8, 5000L);
        CapabilityRegistry registry = new CapabilityRegistry(executor, policy);

        // Register the built-in game tools through the registry (supervised).
        ToolRegistry builtins = new ToolRegistry(ctx);
        builtins.registerAll(registry);

        // Register the self-referential meta-tools (introspect + self-extend +
        // redefine_class hypervisor tool).
        DynamicToolFactory factory = new DynamicToolFactory(hotLoad);
        MetaTools meta = new MetaTools(registry, factory, hotLoad);
        meta.registerAll(registry);

        // Privilege tools (CPU-ring model): drop/restore/list clearance.
        new PermissionTools(policy, registry).registerAll(registry);

        // Durable memory (persists across restarts) — the knowledge counterpart
        // to create_tool's capabilities. Stored under the game working dir.
        MemoryStore memory = new MemoryStore(java.nio.file.Path.of("mcp_memory.json"));
        new MemoryTools(memory).registerAll(registry);

        // Narrative/intent (the "fable" layer): goal stack + story log.
        GoalStack goalStack = new GoalStack(200);
        new NarrativeTools(goalStack).registerAll(registry);

        // Socket transport (not stdio): the game owns the console, so a stdio
        // MCP server would corrupt the JSON-RPC stream. An AI client connects to
        // the loopback port. The registry binds the live server for runtime
        // create_tool / rollback propagation.
        socketServer = new SocketTransportServer(registry);
        try {
            socketServer.start();
        } catch (java.io.IOException e) {
            System.err.println("[MCP Core] could not start socket transport: " + e);
        }

        // REST facade (concretization): a plain-HTTP front door beside the socket
        // so tools can be listed/called with curl/a browser. Default on; routes
        // through the same supervised registry, so rings/breaker still apply.
        // -Dmcp.core.http=false disables it; -Dmcp.core.httpPort / -Dmcp.core.httpBind configure it.
        if (!"false".equalsIgnoreCase(System.getProperty("mcp.core.http", "true"))) {
            String bind = System.getProperty("mcp.core.httpBind", "127.0.0.1");
            int httpPort = Integer.getInteger("mcp.core.httpPort", HttpFacade.DEFAULT_PORT);
            httpFacade = new HttpFacade(registry, bind, httpPort);
            try {
                httpFacade.start();
            } catch (java.io.IOException e) {
                System.err.println("[MCP Core] could not start REST facade: " + e);
            }
        }
    }

    /** Stop the MCP server + REST facade. */
    public void stop() {
        if (socketServer != null) {
            socketServer.close();
        }
        if (httpFacade != null) {
            httpFacade.stop();
        }
    }

    /**
     * Build the privilege policy from system properties (dev-friendly defaults):
     * <ul>
     *   <li>{@code -Dmcp.core.clearance=R2} pins the initial clearance (default
     *       R-1 = wide open).</li>
     *   <li>{@code -Dmcp.core.restoreToken=...} sets the token that gates raising
     *       privilege again after a drop_privilege. If unset, a random token is
     *       generated and printed once, so drop_privilege stays a real kill-switch
     *       (only someone who saw the log can restore).</li>
     * </ul>
     */
    private static PermissionPolicy buildPolicy() {
        Ring clearance = Ring.R_MINUS_1;
        String c = System.getProperty("mcp.core.clearance");
        if (c != null) {
            for (Ring r : Ring.values()) {
                if (c.trim().equalsIgnoreCase("R" + r.level()) || c.trim().equalsIgnoreCase(r.label())) {
                    clearance = r;
                    break;
                }
            }
        }
        String token = System.getProperty("mcp.core.restoreToken");
        if (token == null || token.isBlank()) {
            token = Long.toHexString(new java.security.SecureRandom().nextLong());
            System.err.println("[MCP Core] restore token (needed to raise privilege after "
                    + "drop_privilege): " + token);
        }
        System.err.println("[MCP Core] initial clearance: " + clearance.tag());
        return new PermissionPolicy(clearance, token);
    }

    /** Marker so callers/tests can confirm the module loaded and its Java level. */
    public static String banner() {
        return """
               MCP Core initialized (Java 25 module, running on JDK %s)
               """.formatted(Runtime.version().feature());
    }
}
