package net.marcloud.mcp.core;

import net.marcloud.mcp.core.action.ActionManager;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.PacketReceivedEvent;
import net.marcloud.mcp.core.event.events.PacketSentEvent;
import net.marcloud.mcp.core.deepaccess.DeepAccess;
import net.marcloud.mcp.core.deepaccess.MutateStateTools;
import net.marcloud.mcp.core.hook.DynamicHookManager;
import net.marcloud.mcp.core.hook.HookManager;
import net.marcloud.mcp.core.hook.HookTools;
import net.marcloud.mcp.core.hotload.HotLoadEngine;
import net.marcloud.mcp.core.introspect.IntrospectionService;
import net.marcloud.mcp.core.introspect.IntrospectionTools;
import net.marcloud.mcp.core.seam.SeamController;
import net.marcloud.mcp.core.seam.SeamTools;
import net.marcloud.mcp.core.security.AccessGate;
import net.marcloud.mcp.core.security.AllowAllGate;
import net.marcloud.mcp.core.synth.EphemeralSynthesizer;
import net.marcloud.mcp.core.synth.SynthTools;
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
import net.marcloud.mcp.core.security.CapabilitySid;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.PermissionTools;
import net.marcloud.mcp.core.security.PolicyEngine;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.SecurityContext;
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
    private net.marcloud.mcp.core.seam.SeamController seams;

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

        // C3 INTERCEPT: dynamic (install/uninstall/reset) hooks, sharing the
        // captured Instrumentation. Coexists with the fixed HookManager above.
        DynamicHookManager dynHooks = new DynamicHookManager(
                net.marcloud.mcp.core.agent.AgentAccess.instrumentation(), bus);

        ToolContext ctx = new ToolContext(game, actions, hotLoad, packetLog, disconnects);

        // Build the capability registry: every tool is supervised (timeout +
        // circuit breaker + exception boundary) so one bad tool can't crash the
        // system, and new tools can be grown at runtime via create_tool.
        // Privilege policy: dev default = wide open (R-1). The restore token
        // (from -Dmcp.core.restoreToken or a random one) gates re-escalation, so
        // drop_privilege is a real kill-switch. Pin lower via -Dmcp.core.clearance.
        PermissionPolicy policy = buildPolicy();
        PolicyEngine engine = buildEngine(policy);
        SafeToolExecutor executor = new SafeToolExecutor(8, 5000L);
        CapabilityRegistry registry = new CapabilityRegistry(executor, engine);

        // Register the built-in game tools through the registry (supervised).
        ToolRegistry builtins = new ToolRegistry(ctx);
        builtins.registerAll(registry);

        // Register the self-referential meta-tools (introspect + self-extend +
        // redefine_class hypervisor tool).
        DynamicToolFactory factory = new DynamicToolFactory(hotLoad);
        MetaTools meta = new MetaTools(registry, factory, hotLoad);
        meta.registerAll(registry);

        // Privilege tools (7-layer model): drop/restore/list clearance. Driven
        // through the same engine the gate reads, so a drop takes effect at once.
        new PermissionTools(engine, registry).registerAll(registry);

        // Durable memory (persists across restarts) — the knowledge counterpart
        // to create_tool's capabilities. Stored under the game working dir.
        MemoryStore memory = new MemoryStore(java.nio.file.Path.of("mcp_memory.json"));
        new MemoryTools(memory).registerAll(registry);

        // Narrative/intent (the "fable" layer): goal stack + story log.
        GoalStack goalStack = new GoalStack(200);
        new NarrativeTools(goalStack).registerAll(registry);

        // ---- Phase 2 capability layers (C1/C3/C5/C7/C8) ----
        // Each tool is registered through the same supervised registry, so the
        // 7-layer reference monitor gates it exactly like every other tool.
        AccessGate gate = new AllowAllGate();

        // C1 INTROSPECT: read-only self-model. list_hooks aggregates both the
        // fixed network hooks and the dynamic ones via the HookSource SPI.
        IntrospectionService introspect = new IntrospectionService(
                getClass().getClassLoader(), java.util.List.of(hooks, dynHooks));
        new IntrospectionTools(introspect).registerAll(registry);

        // C3 INTERCEPT: runtime install/uninstall/reset of ByteBuddy hooks.
        new HookTools(dynHooks, gate).registerAll(registry);

        // C5 MUTATE-STATE: read/write any field, invoke private methods, open
        // modules. Instrumentation reached through the gated AgentAccess seam.
        DeepAccess deep = new DeepAccess(game, gate,
                net.marcloud.mcp.core.agent.AgentAccess::instrumentation);
        // Invalidate DeepAccess's layout-dependent caches after any redefine
        // (a DCEVM structural redefine can move field offsets → stale VarHandle).
        hotLoad.setOnRedefined(deep::invalidate);
        new MutateStateTools(deep, game).registerAll(registry);

        // C7 SYNTHESIZE: one-shot GC-able hidden-class tools.
        new SynthTools(new EphemeralSynthesizer()).registerAll(registry);

        // C8 SEAM: Netty pipeline MITM / GLFW input / tick injection. Kept as a
        // field so stop() can tear the seams down.
        seams = new SeamController(bus, game);
        new SeamTools(seams).registerAll(registry);

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

    /** Stop the MCP server + REST facade, and tear down any installed seams. */
    public void stop() {
        if (socketServer != null) {
            socketServer.close();
        }
        if (httpFacade != null) {
            httpFacade.stop();
        }
        if (seams != null) {
            seams.uninstallAll();
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

    /**
     * Build the reference monitor (7-layer decision authority). Dev default =
     * wide open: SYSTEM integrity, all privileges enabled, wildcard capabilities
     * (only the ring dimension bites, matching the pre-Phase-2 behavior).
     *
     * <p>{@code -Dmcp.core.caps=strict} switches L5 to true default-deny: the
     * subject starts with an EMPTY capability set, so every tool that touches a
     * gated resource class must be granted its SID first. Use for hardened runs.
     */
    private static PolicyEngine buildEngine(PermissionPolicy policy) {
        // L1 VTL: if enabled, defer EVERY decision to the separate P-SECURE
        // process over a loopback socket (fail-closed). This is the only real
        // wall — a rogue in-JVM hook cannot reach that address space.
        if ("true".equalsIgnoreCase(System.getProperty(
                net.marcloud.mcp.core.secure.PSecureProtocol.ENABLE_PROPERTY, "false"))) {
            String host = System.getProperty("mcp.core.psecureHost", "127.0.0.1");
            int port = Integer.getInteger("mcp.core.psecurePort",
                    net.marcloud.mcp.core.secure.PSecureProtocol.DEFAULT_PORT);
            String token = System.getProperty(
                    net.marcloud.mcp.core.secure.PSecureProtocol.TOKEN_PROPERTY, "");
            System.err.println("[MCP Core] L1 VTL ENABLED: decisions deferred to P-SECURE at "
                    + host + ":" + port + " (fail-closed).");
            return new net.marcloud.mcp.core.security.RemotePolicyEngine(host, port, token, 2000);
        }

        String caps = System.getProperty("mcp.core.caps", "wildcard");
        if ("strict".equalsIgnoreCase(caps.trim())) {
            System.err.println("[MCP Core] L5 capabilities: STRICT default-deny "
                    + "(grant SIDs explicitly to use gated tools)");
            SecurityContext strict = InProcessPolicyEngine.strictSubject(
                    java.util.EnumSet.noneOf(CapabilitySid.class));
            return new InProcessPolicyEngine(policy, strict);
        }
        return new InProcessPolicyEngine(policy);
    }

    /** Marker so callers/tests can confirm the module loaded and its Java level. */
    public static String banner() {
        return """
               MCP Core initialized (Java 25 module, running on JDK %s)
               """.formatted(Runtime.version().feature());
    }
}
