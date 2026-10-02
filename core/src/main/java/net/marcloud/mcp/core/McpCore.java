package net.marcloud.mcp.core;

import net.marcloud.mcp.core.drivers.action.ActionManager;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.PacketReceivedEvent;
import net.marcloud.mcp.core.ke.event.events.PacketSentEvent;
import net.marcloud.mcp.core.mm.MmAccess;
import net.marcloud.mcp.core.mm.MutateStateTools;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.HookTools;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.cm.CmQuery;
import net.marcloud.mcp.core.cm.IntrospectionTools;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.flt.seam.SeamTools;
import net.marcloud.mcp.core.se.AccessGate;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.MonitorAccessGate;
import net.marcloud.mcp.core.ps.PsSynthesizer;
import net.marcloud.mcp.core.ps.SynthTools;
import net.marcloud.mcp.core.io.http.HttpFacade;
import net.marcloud.mcp.core.drivers.store.MemoryStore;
import net.marcloud.mcp.core.drivers.store.MemoryTools;
import net.marcloud.mcp.core.drivers.narrative.GoalStack;
import net.marcloud.mcp.core.drivers.narrative.NarrativeTools;
import net.marcloud.mcp.core.io.transport.SocketTransportServer;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.DynamicToolFactory;
import net.marcloud.mcp.core.io.MetaTools;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.PermissionTools;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToken;
import net.marcloud.mcp.core.drivers.world.DisconnectTracker;
import net.marcloud.mcp.core.drivers.world.PacketLog;
import net.marcloud.mcp.core.ke.KeGameDispatcher;

/**
 * MCP Core entry point — assembles the whole stack and exposes the running game
 * over MCP.
 *
 * <p>Wiring order:
 * <ol>
 *   <li>{@link GameAccess} + {@link KeGameDispatcher} (game façade + thread marshal),</li>
 *   <li>{@link EventBus} + {@link FltManager} (observe MC networking at runtime),</li>
 *   <li>{@link PacketLog} fed from packet events,</li>
 *   <li>{@link LdrEngine} + {@link ActionManager} (control + live code),</li>
 *   <li>{@link IoManager} + {@link SocketTransportServer} (expose it all
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
    private net.marcloud.mcp.core.flt.seam.SeamController seams;
    // MEDIUM#8: kept as a field so stop() can revert dynamic hooks before the
    // transports go down, instead of letting installed advice outlive the server.
    private net.marcloud.mcp.core.flt.FltDynamicManager dynHooks;

    /**
     * The MODEL-FACING registry: the {@link IoManager} handed to {@link SocketTransportServer}
     * and {@link HttpFacade}, so {@code tools/list} and every model-reachable route carry the
     * model-facing tools and nothing else.
     *
     * <p>Distinct from the audited registry {@link #registerBuiltins} fills. That one keeps
     * every built-in so the gate tables, {@link #reportGateGaps} and
     * {@code RegisteredBuiltinGateCoverageTest} still see the complete surface; hiding a tool
     * must never be implemented by unregistering it, or the security tables would start
     * reporting kernel tools as stale rows. Both registries share one {@link IoSupervisor} and
     * one {@link SeReferenceMonitor}, so a promoted tool is gated exactly like the built-in it
     * was copied from. Null until {@link #registerBuiltins} runs.
     */
    private IoManager modelSurface;

    /** The executor {@link #registerBuiltins} created for the surface, when it had to make one. */
    private IoSupervisor ownedSurfaceExecutor;

    /** Promoted kernel-layered names, in promotion order. Read by {@link #promotedNames()}. */
    private final java.util.List<String> promotedNames = new java.util.ArrayList<>();

    /**
     * The audited registry, kept so {@link #promote} can copy a capability out of it.
     * Set by {@link #registerBuiltins}; null before that.
     */
    private IoManager auditedRegistry;

    /**
     * The model-facing registry — the one the MCP socket and the REST facade serve.
     *
     * <p>Non-null only after {@link #registerBuiltins} has run. A caller that wants to know
     * what a model can actually reach reads THIS, not the audited registry.
     */
    public IoManager modelSurface() {
        return modelSurface;
    }

    /** Kernel-layered names promoted so far, in promotion order. */
    public java.util.List<String> promotedNames() {
        return java.util.List.copyOf(promotedNames);
    }

    /**
     * THE PROMOTION READER — the one that is not the model.
     *
     * <p>Reads two operator-controlled sources and nothing else:
     * <ul>
     *   <li>{@code -Dmcp.core.promote=eval_java,install_hook} — a launch argument, which the
     *       model cannot set and cannot observe.</li>
     *   <li>{@code mcp_promote.txt} in the game working directory — one name per line, {@code #}
     *       comments and blank lines ignored. A file on the operator's disk.</li>
     * </ul>
     *
     * <p><b>Why there is deliberately no model-reachable route to this.</b> A promotion route
     * the model can call is not a promotion path: the whole point of layering is that a weak
     * model does not choose what the kernel surface contains. So there is no tool for it, and
     * {@code mcp_promote.txt} is not written by any tool either — the only verb that writes code
     * is {@code create_tool}, which is itself kernel-layered. This is the answer to "invisible
     * by default and visible only to the component that needs it": the component that needs it
     * is the operator, and the operator's reader is this method.
     *
     * <p>Called once at the end of {@link #registerBuiltins}, and callable again at any time to
     * pick up an edit to the file without a restart. Returns the names actually promoted by this
     * call. Never throws: a bad entry is reported on stderr and skipped, because a typo in an
     * operator's file must not keep the game from starting.
     */
    public java.util.List<String> applyPromotions() {
        java.util.List<String> requested = new java.util.ArrayList<>();
        String prop = System.getProperty("mcp.core.promote", "");
        for (String s : prop.split(",")) {
            if (!s.isBlank()) {
                requested.add(s.trim());
            }
        }
        java.nio.file.Path file = java.nio.file.Path.of("mcp_promote.txt");
        if (java.nio.file.Files.isReadable(file)) {
            try {
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    requested.add(s);
                }
            } catch (java.io.IOException e) {
                System.err.println("[MCP Core] could not read " + file
                        + " (nothing promoted from it): " + e);
            }
        }
        java.util.List<String> done = new java.util.ArrayList<>();
        for (String name : requested) {
            if (promote(name)) {
                done.add(name);
            }
        }
        return done;
    }

    /**
     * Put one kernel-layered tool on the model-facing surface.
     *
     * <p><b>Promotion changes what a model can SEE, never what a subject may DO.</b> The
     * promoted capability is gated by the same {@link SeReferenceMonitor} instance at the same
     * declared {@link Ring} it always had, so a subject whose clearance was dropped below that
     * ring is still refused — and the refusal is the monitor's own message. Nothing here grants
     * a privilege.
     *
     * @return true if {@code name} is on the model-facing surface afterwards (including when it
     *         was already model-facing, or was already promoted).
     */
    public boolean promote(String name) {
        IoManager surface = this.modelSurface;
        if (surface == null || name == null) {
            return false;
        }
        if (surface.get(name) != null) {
            return true;                       // already model-facing, or already promoted
        }
        net.marcloud.mcp.core.io.Capability cap =
                auditedRegistry == null ? null : auditedRegistry.get(name);
        if (cap == null) {
            System.err.println("[MCP Core] promotion refused: '" + name
                    + "' is not a registered tool (typo, or a tool this build does not have)");
            return false;
        }
        // Copied, not re-derived: the promoted capability keeps its description, schema,
        // builtIn flag and declared ring, so every surface that reads the ring (the REST
        // catalog, list_permissions) reports the promoted tool exactly as it reported it
        // while it was hidden.
        surface.register(name, cap.spec(), cap.source(), cap.description(), cap.builtIn(),
                cap.ring());
        if (!promotedNames.contains(name)) {
            promotedNames.add(name);
        }
        System.err.println("[MCP Core] PROMOTED kernel-layered tool '" + name
                + "' onto the model-facing surface (still gated at " + cap.ring().tag() + ")");
        return true;
    }

    /**
     * The game façade this Core is wired around — the SAME instance
     * {@link #registerBuiltins} hands to every tool provider.
     *
     * <p>Exists so a caller driving {@link #registerBuiltins} outside {@link #start()} (the
     * gate-coverage test) passes the real collaborators rather than substituting stand-ins
 * and hoping the providers treat them alike. Reading it is safe with no game running:
     * {@link GameAccess} resolves the singleton per call and every accessor is null-tolerant.
     */
    public GameAccess gameAccess() {
        return game;
    }

    /**
     * Assemble and start Core. Installs runtime hooks (if Instrumentation is
     * present), begins recording packets, and starts the loopback-socket MCP
     * server on 127.0.0.1:25599 (not stdio — the game owns the console).
     */
    public void start() {
        KeGameDispatcher exec = new KeGameDispatcher(game.mc());
        ActionManager actions = new ActionManager(game, exec);
        LdrEngine hotLoad = new LdrEngine(getClass().getClassLoader());

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
        FltManager hooks = new FltManager(bus);
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
        // captured Instrumentation. Coexists with the fixed FltManager above.
        // Assigned to the field so stop() can tear its hooks down (MEDIUM#8).
        dynHooks = new FltDynamicManager(
                net.marcloud.mcp.core.boot.AgentAccess.instrumentation(), bus);

        ToolContext ctx = new ToolContext(game, actions, hotLoad, packetLog, disconnects);

        // Build the capability registry: every tool is supervised (timeout +
        // circuit breaker + exception boundary) so one bad tool can't crash the
        // system, and new tools can be grown at runtime via create_tool.
        // Privilege policy: dev default = wide open (R-1). The restore token
        // (from -Dmcp.core.restoreToken or a random one) gates re-escalation, so
        // drop_privilege is a real kill-switch. Pin lower via -Dmcp.core.clearance.
        SeClearancePolicy policy = buildPolicy();
        // L6 object-handle layer. Off by default (objects == null → L6 is a pure
        // no-op and every existing tool is gated exactly as before). -Dmcp.core.handles=true
        // wires it: the engine runs the L6 subset check for any request that carries
        // a "handle" arg, and DebugTools exposes debug_open_thread / debug_close_handle
        // so a C6 op can bind to a frozen, TOCTOU-safe thread target.
        net.marcloud.mcp.core.ob.ObManager objects = buildObjectManager();
        SeReferenceMonitor engine = buildEngine(policy, objects);
        IoSupervisor executor = new IoSupervisor(8, 5000L);
        IoManager registry = new IoManager(executor, engine);

        // Register the built-in game tools through the registry (supervised).
        //
        // The whole built-in surface is ONE call into registerBuiltins(...) at the end of
        // this method. There is deliberately no second registration site: what a test can
        // drive is then exactly what production registers, which is the invariant
        // RegisteredBuiltinGateCoverageTest exists to police.

        // C8 SEAM: Netty pipeline MITM / GLFW input / tick injection. Built before the
        // tick-injector arming below and before registerBuiltins(...), which hands the same
        // controller to SeamTools/ChatTools/ObserveTools. Kept as a field so stop() can
        // tear the seams down.
        seams = new SeamController(bus, game);

        // PHASE T: arm the tick injector by DEFAULT so the single GameClock actually
        // advances (tickId is the spine every observation stamps itself with). Opt-OUT
        // with -Dmcp.core.tick=false. Needs Instrumentation (-javaagent); absent or any
        // fault => the clock simply never advances (tickId stays 0), MCP still serves —
        // never fatal. Was opt-in via the seam_tick_enable tool only; now on by default
        // because the timeline is core infrastructure, not an optional experiment.
        if (!"false".equalsIgnoreCase(System.getProperty("mcp.core.tick", "true"))) {
            try {
                if (seams.canInstall()) {
                    seams.installTickInjector();
                    System.err.println("[MCP Core] tick clock armed (GameClock advancing on runTick).");
                } else {
                    System.err.println("[MCP Core] tick clock NOT armed — Instrumentation absent "
                            + "(start with -javaagent:core-<ver>.jar). GameClock stays at tick 0.");
                }
            } catch (Throwable t) {
                System.err.println("[MCP Core] tick injector install failed (clock disabled, "
                        + "game unaffected): " + t);
            }
        }


        // PHASE T (T.8): fan the ONE clock out to board's TickSignal by reflection —
        // zero compile-time board dependency (core never imports board). Board present
        // ⇒ its tick chips run off the same GameClock; board absent ⇒ silent no-op.
        // (Lighting Board.init + installing default chips is PHASE E's job, not here.)
        new net.marcloud.mcp.core.link.BoardClockBridge(bus).attach();

        // PHASE A: streaming real control. ActRuntime holds per-slot target state,
        // applied on the game thread by ActTickLoop (TickEvent). LivePlayerActuator is
        // the sole net.minecraft-touching impl; the three appliers bridge controllers
        // into the runtime. MovementInputInstaller swaps EntityPlayerSP.movementInput
        // for a synthetic subclass (client zero-diff). act_set/act_cancel/act_status
        // are the RPC face. Nothing arms until act_set is called.
        net.marcloud.mcp.core.drivers.act.ActRuntime actRuntime =
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE;
        net.marcloud.mcp.core.drivers.act.ActActuator actuator =
                new net.marcloud.mcp.core.drivers.act.LivePlayerActuator(game);
        // MOVE takes the actuator too, so act_status can report distance travelled and call out a
        // jam instead of saying "moving" for a player pressed against a wall.
        // The route factory is supplied HERE, not inside the applier: the planner package depends on
        // drivers.act, so an applier naming the executor would close a package cycle. This is the one
        // place that can see both, and it is also the only place that knows which world a plan should
        // be built against -- the SERVER world, because it validates and reverts what the client
        // predicts, so a plan built on the client's belief can be rubber-banded away.
        actRuntime.registerApplier(net.marcloud.mcp.core.drivers.act.ActSlot.MOVE,
                new net.marcloud.mcp.core.drivers.act.MoveApplier(actuator, actRuntime,
                        ri -> net.marcloud.mcp.core.drivers.plan.RoutePlanning.executorFor(
                                game, ri.targetX(), ri.targetY(), ri.targetZ(), ri.blockBudget())));
        actRuntime.registerApplier(net.marcloud.mcp.core.drivers.act.ActSlot.LOOK,
                new net.marcloud.mcp.core.drivers.act.LookApplier(actuator));
        actRuntime.registerApplier(net.marcloud.mcp.core.drivers.act.ActSlot.INTERACT,
                new net.marcloud.mcp.core.drivers.act.InteractApplier(actuator));
        new net.marcloud.mcp.core.drivers.act.ActTickLoop(actRuntime).attach(bus);
        net.marcloud.mcp.core.drivers.act.MovementInputInstaller moveInstaller =
                new net.marcloud.mcp.core.drivers.act.MovementInputInstaller(
                        new net.marcloud.mcp.core.drivers.act.GameAccessInputSlot(game), actRuntime);
        moveInstaller.attach(bus);
        // Arm permanently: the ActMovementInput wrapper delegates 100% to vanilla input
        // UNLESS a MOVE intent is active (view.moveActive()), so a resident swap is
        // transparent until act_set{move} arrives. Without this, act_set{move} would
        // report ACTIVE while the player never actually moved (fake success). The
        // installer no-ops until the player exists and re-swaps across world-join/respawn.
        moveInstaller.arm();

        // PHASE E: fan whitelisted world GameEvents out to board Signals (disconnect,
        // inbound chat, block-change). Board absent ⇒ silent no-op, like the clock bridge.
        new net.marcloud.mcp.core.link.BoardWorldEventBridge(bus).attach();

        // Publish a LIVE kernel-state snapshot supplier to the board Backplane so the DWM
        // overlay can render the 7-layer posture without importing a single core type.
        // Reflective register (core never imports board): board absent ⇒ ClassNotFound ⇒
        // silent no-op, exactly like BoardClockBridge. The supplier re-reads currentSubject
        // on every call, so runtime disable_privilege/revoke_capability show up next frame.
        publishKernelState(engine);

        // C6 CONTROL-EXEC: native JVMTI debugger. Graceful no-op without
        // -agentpath:core-jvmti.dll — the debug_* tools still register and report
        // honestly (no dead tools). Debug events also flow onto the EventBus.
        net.marcloud.mcp.core.kd.DebugEventQueue.INSTANCE.addListener(bus::publish);
        if (!net.marcloud.mcp.core.kd.KdBridge.isAvailable()) {
            System.err.println("[MCP Core] JVMTI debugger absent — "
                    + net.marcloud.mcp.core.kd.KdBridge.unavailableReason()
                    + " (debug_* tools registered, return isError until the agent is present).");
        }

        // ---- THE built-in surface ----
        // SeamController must exist before this call: SeamTools, ChatTools and ObserveTools
        // all take it, and so does the tick injector above.
        //
        // `executor` is passed so the model-facing surface shares it: the two registries
        // supervise independently but must not double the thread budget of a running game.
        registerBuiltins(registry, engine, objects, ctx, hotLoad, hooks, dynHooks, seams,
                actRuntime, executor);

        // The MODEL-FACING registry, built by the call above. Everything below this line —
        // the MCP socket and the REST facade — is handed THIS and not `registry`, so a
        // kernel-layered tool is registered, gated and callable in-process while being
        // absent from tools/list and from every model-reachable route.
        IoManager surface = modelSurface;

        // Startup self-check: cross-check the LIVE registry against the gate tables and
        // say so out loud. This is the same audit the test suite runs
        // (net.marcloud.mcp.core.se.BuiltinGateAudit), so the runtime diagnostic and the CI
        // gate cannot drift apart — but it runs where the truth is: on the registry that is
        // about to be served. A builtin wired into a provider that start() never reaches
        // cannot hide here, because start() IS the only registration site.
        //
        // Warn-only by design: a gate-table gap must never keep the game from starting.
        // Set -Dmcp.core.gateAudit=fail to make it abort startup instead, for a hardened
        // deployment that would rather refuse to serve than serve an ungated tool.
        reportGateGaps(registry);

        // Socket transport (not stdio): the game owns the console, so a stdio
        // MCP server would corrupt the JSON-RPC stream. An AI client connects to
        // the loopback port. The MODEL-FACING registry binds the live server, so the
        // advertised tool list is the layered one and a runtime create_tool / rollback
        // pushes live to this client exactly as before.
        socketServer = new SocketTransportServer(surface);
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
            String httpToken = System.getProperty("mcp.core.httpToken", "");
            // SECURITY.md invariant: R-1 arbitrary code execution (eval_java,
            // redefine_class, C5 field write, C6 JVMTI) must not reach the network
            // unauthenticated. On loopback the no-auth dev posture is intentional;
            // on any non-loopback bind, refuse to start without a token.
            if (isNonLoopback(bind) && httpToken.isBlank()) {
                System.err.println("[MCP Core] REFUSING REST facade on non-loopback bind '" + bind
                        + "' without auth. Set -Dmcp.core.httpToken=<secret> (Authorization: Bearer). "
                        + "See SECURITY.md.");
            } else {
                // Pass the EventBus so GET /v1/stream (A.10 SSE feed) can push live
                // events; it routes through the same authorized handle as every route.
                httpFacade = new HttpFacade(surface, bind, httpPort, httpToken, bus);
                if (!httpToken.isBlank()) {
                    System.err.println("[MCP Core] REST facade auth ENABLED (Authorization: Bearer <token>).");
                }
                try {
                    httpFacade.start();
                } catch (java.io.IOException e) {
                    System.err.println("[MCP Core] could not start REST facade: " + e);
                }
            }
        }
    }

    /**
     * THE built-in surface — the one and only place a built-in tool is registered.
     *
     * <p><b>Why this is a method and not inline in {@link #start()}.</b> The gate tables
     * ({@link Ring} BUILTIN_RINGS, {@link SeToolRequirement} L3_WRITES/L4_PRIVILEGE) have to
     * cover every tool that actually exists at runtime, and the only readable truth about
     * that is the live registry. When the registrations were inline in {@code start()},
     * {@code RegisteredBuiltinGateCoverageTest} had to keep its own hand-written copy of the
     * provider list — and that copy drifts: a provider wired into the test but never into
     * {@code start()} leaves the suite green on a build where the tool does not exist.
     * {@code chat_read} sat in exactly that state. With one registration method there is no
     * copy to drift, so the gap cannot reopen.
     *
     * <p>Every collaborator here is the one production passes. Constructing a provider and
     * calling its {@code registerAll} builds tool specs and never dereferences the
     * collaborators, so this is drivable headless with trivial stand-ins.
     *
     * <p><b>Two registries, not one.</b> {@code registry} receives every built-in and stays the
     * audited truth — the gate tables are checked against it, and {@link #reportGateGaps} reads
     * it. {@code modelSurface} receives only the tools {@link ToolRegistry#layerOf} calls
     * model-facing, and it is the registry the MCP socket and the REST facade are handed, so
     * kernel-layered tools are registered, gated and invocable in-process while being absent
     * from {@code tools/list} and from every model-reachable route.
     *
     * <p>This overload exists for callers that already have an {@link IoSupervisor} to share
     * ({@link #start()} does). The 8-argument form creates one for the surface and records it so
     * {@link #stop()} can shut it down.
     */
    public void registerBuiltins(IoManager registry, SeReferenceMonitor engine,
                                 net.marcloud.mcp.core.ob.ObManager objects, ToolContext ctx,
                                 LdrEngine hotLoad, FltManager hooks,
                                 net.marcloud.mcp.core.flt.FltDynamicManager dynHooks,
                                 net.marcloud.mcp.core.flt.seam.SeamController seams,
                                 net.marcloud.mcp.core.drivers.act.ActRuntime actRuntime) {
        IoSupervisor surfaceExec = new IoSupervisor(8, 5000L);
        this.ownedSurfaceExecutor = surfaceExec;
        registerBuiltins(registry, engine, objects, ctx, hotLoad, hooks, dynHooks, seams,
                actRuntime, surfaceExec);
    }

    /** As above, with the model-facing surface sharing {@code surfaceExec}. */
    public void registerBuiltins(IoManager registry, SeReferenceMonitor engine,
                                 net.marcloud.mcp.core.ob.ObManager objects, ToolContext ctx,
                                 LdrEngine hotLoad, FltManager hooks,
                                 net.marcloud.mcp.core.flt.FltDynamicManager dynHooks,
                                 net.marcloud.mcp.core.flt.seam.SeamController seams,
                                 net.marcloud.mcp.core.drivers.act.ActRuntime actRuntime,
                                 IoSupervisor surfaceExec) {
        // L4/L5 defense-in-depth against the LIVE monitor (was new AllowAllGate(), an
        // empty method body, so the documented second term of the AND did not exist).
        AccessGate gate = new MonitorAccessGate(engine);

        // THE MODEL-FACING SURFACE. Same executor, same reference monitor, same supervisor —
        // so a promoted tool is gated exactly like the built-in it was copied from — and a
        // different registry, so only the model-facing names are advertised.
        IoManager surface = new IoManager(surfaceExec, engine);
        this.modelSurface = surface;
        this.auditedRegistry = registry;

        // ---- THE PROVIDERS ----
        // Every provider registers into `registry` (the audited, complete surface) and,
        // when every tool it contributed is model-facing, into `surface` as well. Which of
        // those happens is DERIVED, never written down here: wireProvider diffs the names a
        // provider added and consults ToolRegistry.layerOf. A provider nobody classified
        // therefore defaults to kernel-layered, i.e. hidden.
        ToolRegistry builtins = new ToolRegistry(ctx);
        wireProvider(registry, surface, builtins::registerAll, builtins::registerModelFacing);
        // C11 (the enchant-table lapis spend). Separate from ToolRegistry because that file is
        // under concurrent edit; same ToolContext, same veto-guarded send path, same ring.
        net.marcloud.mcp.core.io.transport.EnchantTools enchant =
                new net.marcloud.mcp.core.io.transport.EnchantTools(ctx);
        wireProvider(registry, surface, enchant::registerAll, enchant::registerAll);

        // KI-12: the ESC door. Separate file and separate registration because the ESC key is a
        // raw LWJGL event handled inside Minecraft.runTick's keyboard loop, not a KeyBinding, so
        // no existing tool can deliver it -- see EscMenu, which calls
        // Minecraft.displayInGameMenu(), the same method the key calls. R3, like open_overlay: it
        // changes what is on screen and nothing else (no packet leaves the client).
        net.marcloud.mcp.core.io.transport.EscPanelTools esc = new net.marcloud.mcp.core.io.transport.EscPanelTools();
        wireProvider(registry, surface, esc::registerAll, esc::registerAll);

        // Register the self-referential meta-tools (introspect + self-extend +
        // redefine_class hypervisor tool). MetaTools is handed the MODEL-FACING registry,
        // not the audited one, and that is load-bearing rather than cosmetic: create_tool
        // registers what it builds into the registry it holds, so handing it the audited one
        // would put every AI-authored tool somewhere the model can never call it, and
        // create_tool's own success text ("It is now callable") would be false.
        //
        // The provider STRADDLES both layers as of the 2026-10-02 ruling: list_capabilities is
        // model-facing, the other four are kernel-layered. wireProvider's rule is all-or-nothing
        // per PROVIDER, so the second argument must be MetaTools.registerModelFacing and not
        // registerAll — passing registerAll puts create_tool on the model surface, which is the
        // exact defect the ruling was about. See
        // .ai-notes/docs/audits/2026-10-02-wave19-layer-filter.md.
        DynamicToolFactory factory = new DynamicToolFactory(hotLoad);
        MetaTools meta = new MetaTools(surface, factory, hotLoad);
        wireProvider(registry, surface, meta::registerAll, meta::registerModelFacing);

        // Privilege tools (7-layer model): drop/restore/list clearance. Driven
        // through the same engine the gate reads, so a drop takes effect at once.
        PermissionTools permission = new PermissionTools(engine, registry);
        wireProvider(registry, surface, permission::registerAll, permission::registerAll);
        // L4/L5 self-management (GAP-2): enable/disable_privilege + grant/revoke_capability.
        // Mutations bite only when engine is SeLocalMonitor; under P-SECURE the
        // interface defaults return false and the tools report "not locally owned".
        net.marcloud.mcp.core.se.PrivilegeControlTools privilege =
                new net.marcloud.mcp.core.se.PrivilegeControlTools(engine);
        wireProvider(registry, surface, privilege::registerAll, privilege::registerAll);

        // Durable memory (persists across restarts) — the knowledge counterpart
        // to create_tool's capabilities. Stored under the game working dir.
        MemoryStore memory = new MemoryStore(java.nio.file.Path.of("mcp_memory.json"));
        MemoryTools memoryTools = new MemoryTools(memory);
        wireProvider(registry, surface, memoryTools::registerAll, memoryTools::registerAll);

        // Narrative/intent (the "fable" layer): goal stack + story log.
        GoalStack goalStack = new GoalStack(200);
        NarrativeTools narrative = new NarrativeTools(goalStack);
        wireProvider(registry, surface, narrative::registerAll, narrative::registerAll);

        // ---- Phase 2 capability layers (C1/C3/C5/C7/C8) ----
        // Each tool is registered through the same supervised registry, so the
        // 7-layer reference monitor gates it exactly like every other tool.

        // C1 INTROSPECT: read-only self-model. list_hooks aggregates both the
        // fixed network hooks and the dynamic ones via the HookSource SPI.
        CmQuery introspect = new CmQuery(
                getClass().getClassLoader(), java.util.List.of(hooks, dynHooks));
        IntrospectionTools introspection = new IntrospectionTools(introspect);
        wireProvider(registry, surface, introspection::registerAll, introspection::registerAll);

        // C3 INTERCEPT: runtime install/uninstall/reset of ByteBuddy hooks.
        HookTools hookTools = new HookTools(dynHooks, gate);
        wireProvider(registry, surface, hookTools::registerAll, hookTools::registerAll);

        // C5 MUTATE-STATE: read/write any field, invoke private methods, open
        // modules. Instrumentation reached through the gated AgentAccess seam.
        MmAccess deep = new MmAccess(game, gate,
                net.marcloud.mcp.core.boot.AgentAccess::instrumentation);
        // Invalidate MmAccess's layout-dependent caches after any redefine
        // (a DCEVM structural redefine can move field offsets → stale VarHandle).
        hotLoad.setOnRedefined(deep::invalidate);
        MutateStateTools mutate = new MutateStateTools(deep, game);
        wireProvider(registry, surface, mutate::registerAll, mutate::registerAll);

        // C7 SYNTHESIZE: one-shot GC-able hidden-class tools.
        SynthTools synth = new SynthTools(new PsSynthesizer());
        wireProvider(registry, surface, synth::registerAll, synth::registerAll);

        // C8 SEAM: Netty pipeline MITM / GLFW input / tick injection.
        net.marcloud.mcp.core.flt.seam.SeamTools seamTools =
                new net.marcloud.mcp.core.flt.seam.SeamTools(seams);
        wireProvider(registry, surface, seamTools::registerAll, seamTools::registerAll);

        // PHASE T: Timeline ring — fold every EventBus event onto the GameClock as a
        // safe {tickId, kind, summary} entry. Subscribe to the GameEvent base type to
        // capture all present + future event subclasses in one hook.
        net.marcloud.mcp.core.ke.Timeline timeline =
                new net.marcloud.mcp.core.ke.Timeline(
                        Integer.getInteger("mcp.core.timelineCap", 512));
        timeline.attach(bus);

        // PHASE P: PacketJournal — a packet-only ring fed from the Netty-tap
        // Seam packet events, addressable per-packet (seq) for packet_get.
        net.marcloud.mcp.core.ke.PacketJournal packetJournal =
                new net.marcloud.mcp.core.ke.PacketJournal(
                        Integer.getInteger("mcp.core.packetJournalCap", 1024));
        packetJournal.attach(bus);

        // Inbound chat. Fed from the SAME SeamPacketInboundEvent as PacketJournal so
        // there is one place the wire is observed, and reference-free so the ring
        // cannot pin objects.
        net.marcloud.mcp.core.ke.ChatLog chatLog =
                new net.marcloud.mcp.core.ke.ChatLog(
                        Integer.getInteger("mcp.core.chatLogCap", 256));
        chatLog.attach(bus);
        net.marcloud.mcp.core.drivers.observe.ChatTools chat =
                new net.marcloud.mcp.core.drivers.observe.ChatTools(chatLog, seams,
                        () -> game.player() == null ? null : game.player().getName());
        wireProvider(registry, surface, chat::registerAll, chat::registerAll);

        net.marcloud.mcp.core.drivers.observe.ObserveTools observe =
                new net.marcloud.mcp.core.drivers.observe.ObserveTools(
                        net.marcloud.mcp.core.ke.GameClock.INSTANCE, timeline, packetJournal,
                        seams);
        wireProvider(registry, surface, observe::registerAll, observe::registerAll);

        net.marcloud.mcp.core.drivers.action.ActTools act =
                new net.marcloud.mcp.core.drivers.action.ActTools(actRuntime);
        wireProvider(registry, surface, act::registerAll, act::registerAll);

        // C6 CONTROL-EXEC: native JVMTI debugger. Graceful no-op without
        // -agentpath:core-jvmti.dll — the debug_* tools still register and report
        // honestly (no dead tools).
        if (objects != null) {
            // L6 wired: debug ops can bind to a frozen thread handle; the subject
            // supplier ties a minted handle's owner to the gate's principal.
            net.marcloud.mcp.core.kd.DebugTools debug =
                    new net.marcloud.mcp.core.kd.DebugTools(gate, objects, engine::currentSubject);
            wireProvider(registry, surface, debug::registerAll, debug::registerAll);
        } else {
            net.marcloud.mcp.core.kd.DebugTools debug = new net.marcloud.mcp.core.kd.DebugTools(gate);
            wireProvider(registry, surface, debug::registerAll, debug::registerAll);
        }

        // Structured GUI interaction: expose the whole clickable GUI (buttons,
        // slots, text fields) to the LLM as addressable elements and drive the
        // real vanilla handlers by element id. gui_snapshot is R2 (game-thread
        // read); the action tools are R1 (server-visible effects) + SE_GUI_INTERACT.
        net.marcloud.mcp.core.drivers.gui.GuiTools gui =
                new net.marcloud.mcp.core.drivers.gui.GuiTools(game,
                        new net.marcloud.mcp.core.drivers.gui.GuiSnapshotService());
        wireProvider(registry, surface, gui::registerAll, gui::registerAll);

        // dev_probe (R2 read-only): one-call live-game diagnostic — connection/world
        // presence + GL context (version/vendor/profile) — marshalled onto the game
        // thread. Degrades to absent headless.
        net.marcloud.mcp.core.drivers.video.DevTools dev =
                new net.marcloud.mcp.core.drivers.video.DevTools(game);
        wireProvider(registry, surface, dev::registerAll, dev::registerAll);

        // Compat patch observability (R3 read-only): list_compat_patches reports the
        // startup patches armed by the engine at premain. The engine/database are
        // null-safe (a headless run without -javaagent reports an empty catalog).
        net.marcloud.mcp.core.compat.CompatTools compat =
                new net.marcloud.mcp.core.compat.CompatTools(
                        net.marcloud.mcp.core.compat.Compat.database(),
                        net.marcloud.mcp.core.compat.Compat.engine());
        wireProvider(registry, surface, compat::registerAll, compat::registerAll);

        // The non-model promotion reader runs LAST, so a name it promotes is one the
        // audited registry already holds and the model surface can therefore serve.
        java.util.List<String> promoted = applyPromotions();
        System.err.println("[MCP Core] tool layers: " + surface.names().size()
                + " model-facing, " + ToolRegistry.kernelLayeredNames().size()
                + " kernel-layered (of " + registry.names().size()
                + " registered built-ins)"
                + (promoted.isEmpty() ? "" : "; promoted: " + promoted));
    }

    /**
     * Register one provider into the audited registry, and into the model-facing surface
     * when — and only when — every tool it contributed is model-facing.
     *
     * <p><b>The layer decision is derived here, not written down.</b> The names the provider
     * added are read back off the live registry and fed to {@link ToolRegistry#layerOf}. That
     * is why there is no second list of providers anywhere in the tree: a provider wired in
     * and nobody classified lands on the kernel side by default, and a provider whose tools
     * were reclassified needs no edit here at all.
     *
     * <p><b>The contract this method cannot enforce, and why it matters.</b> The rule above is
     * a PROVIDER-level decision. It is right when every name a provider contributed lands on
     * the same side, and for every provider except two that is true. A provider that STRADDLES
     * both layers must therefore pass a {@code registerModelFacing} that filters per name
     * against {@link ToolRegistry#layerOf(String)}: passing {@code registerAll} as that
     * argument does not mean "no filtering needed", it means WHOLESALE PROMOTION, and this loop
     * has already decided to call it. Not hypothetical — {@code MetaTools} straddled the moment
     * {@code list_capabilities} was ruled model-facing, and passing {@code meta::registerAll}
     * carried {@code create_tool} onto the surface with it. See
     * .ai-notes/docs/audits/2026-10-02-wave19-layer-filter.md.
     *
     * <p>So the rule for a provider is: all names on one side ⇒ pass the same method twice;
     * names on both sides ⇒ pass a method that filters. The two straddling providers are
     * {@link ToolRegistry} and {@code MetaTools}, and each says so on its own method.
     */
    private static void wireProvider(IoManager registry, IoManager surface,
                                     java.util.function.Consumer<IoManager> registerAll,
                                     java.util.function.Consumer<IoManager> registerModelFacing) {
        java.util.Set<String> before = new java.util.HashSet<>(registry.names());
        registerAll.accept(registry);
        java.util.Set<String> added = new java.util.HashSet<>(registry.names());
        added.removeAll(before);
        if (added.isEmpty()) {
            return;
        }
        for (String name : added) {
            if (!ToolRegistry.isKernelLayered(name)) {
                registerModelFacing.accept(surface);
                return;
            }
        }
    }

    /**
     * Cross-check the LIVE registry against the three gate tables and report loudly, naming
     * the specific tools, when a registered built-in has no gate row or a declared row names
     * nothing that was registered.
     *
     * <p>This is a runtime assertion, not a second copy of the inventory: it reads the
     * registry {@link #registerBuiltins} just filled. That is the whole point — before the
     * registrations were factored into one method, a provider could be wired into the TEST's
     * copy of the list and never into {@code start()}, and the suite stayed green on a build
     * where the tool did not exist ({@code chat_read} did exactly this).
     *
     * <p>Warn-only by default: a missing gate row is a security defect to fix, not a reason to
     * leave the player without an MCP endpoint. {@code -Dmcp.core.gateAudit=fail} promotes it
     * to a startup abort for hardened deployments.
     */
    static void reportGateGaps(IoManager registry) {
        net.marcloud.mcp.core.se.BuiltinGateAudit.Report report =
                net.marcloud.mcp.core.se.BuiltinGateAudit.audit(registry);
        if (report.clean()) {
            return;
        }
        String detail = report.message();
        if ("fail".equalsIgnoreCase(System.getProperty("mcp.core.gateAudit", "warn"))) {
            throw new IllegalStateException("[SECURITY] built-in gate coverage is incomplete, "
                    + "refusing to serve (-Dmcp.core.gateAudit=fail): " + detail);
        }
        System.err.println("[SECURITY] built-in gate coverage is INCOMPLETE. A registered tool "
                + "with no gate row is enforced at the R3 fallback with no L3/L4 gate while every "
                + "visible surface reports the ring it was registered with: " + detail);
    }

    /**
     * Stop the MCP server + REST facade, and tear down every runtime modification
     * this Core installed (dynamic hooks + seams) so nothing outlives the server
     * (MEDIUM#8). Dynamic hooks and seams are reverted BEFORE the transports close,
     * so installed advice stops firing into a half-torn-down system.
     */
    public void stop() {
        if (dynHooks != null) {
            dynHooks.close();  // revert all dynamic ByteBuddy advice (MEDIUM#8)
        }
        if (seams != null) {
            seams.uninstallAll();
        }
        if (socketServer != null) {
            socketServer.close();
        }
        if (httpFacade != null) {
            httpFacade.stop();
        }
        if (ownedSurfaceExecutor != null) {
            // Only an executor the 8-arg registerBuiltins created is ours to close; start()
            // shares the main one, which the caller owns.
            ownedSurfaceExecutor.shutdown();
            ownedSurfaceExecutor = null;
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
    /**
     * Register a live kernel-state snapshot supplier on the board {@code Backplane} under
     * {@link net.marcloud.mcp.core.link.KernelStatePort#KEY}, so the DWM overlay can render
     * the 7-layer posture with zero compile-time coupling. Done by reflection — core never
     * imports board — so board's absence (ClassNotFound) or any fault is a silent no-op,
     * exactly like {@code BoardClockBridge}. The published value is a
     * {@code Supplier<Map<String,String>>} (pure JDK types the overlay already has).
     */
    private static void publishKernelState(SeReferenceMonitor engine) {
        try {
            net.marcloud.mcp.core.link.KernelStatePort port =
                    new net.marcloud.mcp.core.link.KernelStatePort(engine);
            java.util.function.Supplier<java.util.Map<String, String>> supplier = port::snapshot;
            Class<?> backplane = Class.forName("net.marcloud.mcp.board.Backplane");
            backplane.getMethod("register", String.class, Object.class)
                    .invoke(null, net.marcloud.mcp.core.link.KernelStatePort.KEY, supplier);
        } catch (ClassNotFoundException e) {
            // board not on the classpath — overlay/kernel-state feature simply absent.
        } catch (Throwable t) {
            System.err.println("[MCP Core] kernel-state publish failed (overlay shows no live state): " + t);
        }
    }

    private static SeClearancePolicy buildPolicy() {
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
        return new SeClearancePolicy(clearance, token);
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
    static SeReferenceMonitor buildEngine(SeClearancePolicy policy,
                                            net.marcloud.mcp.core.ob.ObManager objects) {
        // L1 VTL: if enabled, defer L1-L5 decisions to the separate P-SECURE
        // process over a loopback socket (fail-closed). This is the only real
        // wall for those layers — a rogue in-JVM hook cannot reach that address
        // space. L6 handles, however, freeze an IN-JVM object snapshot the remote
        // process cannot resolve (it lives in THIS address space), so L6 must stay
        // LOCAL: when the object manager is wired we splice a local L6 gate in
        // FRONT of the remote authority (KI-8 — otherwise the ObManager was silently
        // dropped and L6 strict-handle TOCTOU protection became a no-op under
        // psecure).
        if ("true".equalsIgnoreCase(System.getProperty(
                net.marcloud.mcp.core.alpc.AlpcProtocol.ENABLE_PROPERTY, "false"))) {
            String host = System.getProperty("mcp.core.psecureHost", "127.0.0.1");
            int port = Integer.getInteger("mcp.core.psecurePort",
                    net.marcloud.mcp.core.alpc.AlpcProtocol.DEFAULT_PORT);
            String token = System.getProperty(
                    net.marcloud.mcp.core.alpc.AlpcProtocol.TOKEN_PROPERTY, "");
            System.err.println("[MCP Core] L1 VTL ENABLED: L1-L5 decisions deferred to P-SECURE at "
                    + host + ":" + port + " (fail-closed).");
            net.marcloud.mcp.core.se.SeRemoteMonitor remote =
                    new net.marcloud.mcp.core.se.SeRemoteMonitor(host, port, token, 2000);
            // Posture-split probe (A): if THIS game JVM was launched hardened but the
            // separate authority process came up wide-open, the L4/L5 kill switches are
            // silent no-ops and dangerous verbs are permitted across the wall. Warn
            // loudly (a null posture just means unreachable — evaluate() already fails
            // closed for that, so it is not a split). Warn-only for now, per owner.
            if ("true".equalsIgnoreCase(System.getProperty("mcp.core.hardened", "false"))) {
                String posture = remote.posture();
                if (net.marcloud.mcp.core.alpc.AlpcProtocol.POSTURE_WIDE_OPEN.equals(posture)) {
                    System.err.println("[SECURITY] POSTURE SPLIT: this game JVM is HARDENED "
                            + "(-Dmcp.core.hardened=true) but the P-SECURE authority reports a "
                            + "WIDE-OPEN posture. The authority owns the L4/L5 decision, so "
                            + "dangerous verbs are PERMITTED across the wall and "
                            + "disable_privilege / revoke_capability CANNOT tighten what it never "
                            + "restricted. Launch the P-SECURE process (AlpcMain) with the SAME "
                            + "-Dmcp.core.hardened=true.");
                }
            }
            if (objects != null) {
                System.err.println("[MCP Core] L6 object-handles enforced LOCALLY in front of "
                        + "P-SECURE (in-JVM handle snapshots cannot cross the wall).");
                return new net.marcloud.mcp.core.se.SeHandleGatedMonitor(remote, objects);
            }
            return remote;
        }

        // Hardened opt-in (additive, default OFF): -Dmcp.core.hardened=true wires a
        // subject that PASSES L3 (SYSTEM integrity) but DENIES at L4 (every
        // privilege granted-but-disabled) and L5 (empty capability set), so a
        // dangerous verb is refused while a benign R3/no-cap tool still runs. The
        // existing R0 enable_privilege / grant_capability tools remain live
        // in-session levers (privileges are granted, just disabled). This does NOT
        // change the shipped wide-open default below.
        if ("true".equalsIgnoreCase(System.getProperty("mcp.core.hardened", "false"))) {
            System.err.println("[MCP Core] HARDENED posture ENABLED (-Dmcp.core.hardened=true): "
                    + "L4 privileges granted-but-disabled, L5 capabilities empty (default-deny). "
                    + "Use enable_privilege / grant_capability to open specific verbs in-session.");
            return new SeLocalMonitor(policy, SeLocalMonitor.hardenedSubject(), objects);
        }

        String caps = System.getProperty("mcp.core.caps", "wildcard");
        if ("strict".equalsIgnoreCase(caps.trim())) {
            System.err.println("[MCP Core] L5 capabilities: STRICT default-deny "
                    + "(grant SIDs explicitly to use gated tools)");
            SeToken strict = SeLocalMonitor.strictSubject(
                    java.util.EnumSet.noneOf(CapabilitySid.class));
            return new SeLocalMonitor(policy, strict, objects);
        }
        return new SeLocalMonitor(policy, SeToken.wideOpen(), objects);
    }

    /**
     * Build the L6 object-handle manager, or null when the layer is off (the
     * default). {@code -Dmcp.core.handles=true} enables it. Off ⇒ the engine's L6
     * branch is a pure no-op and no handle tools are registered, so the default
     * behavior (and the whole headless test suite) is unchanged.
     */
    private static net.marcloud.mcp.core.ob.ObManager buildObjectManager() {
        if (!"true".equalsIgnoreCase(System.getProperty("mcp.core.handles", "false"))) {
            return null;
        }
        int cap = Integer.getInteger("mcp.core.handlesCap", 32);
        long idleMillis = Long.getLong("mcp.core.handlesIdleMs", 300_000L); // 5 min idle reap
        // Under the hardened posture, run L6 in STRICT-handle mode: a handle-op tool
        // invoked without a "handle" arg is denied rather than falling back to the
        // name-based TOCTOU path. Default (dev) posture keeps the voluntary behavior.
        boolean strictHandles =
                "true".equalsIgnoreCase(System.getProperty("mcp.core.hardened", "false"));
        System.err.println("[MCP Core] L6 object-handles ENABLED (cap " + cap + "/subject, idle "
                + idleMillis + "ms, strictHandles=" + strictHandles
                + "). debug_open_thread / debug_close_handle registered.");
        // Resolver: THREAD refs → the live thread by name. debug_open_thread passes
        // its own already-resolved thread, so this is the fallback for other callers.
        return new net.marcloud.mcp.core.ob.ObManager(
                McpCore::resolveThreadRef, cap, idleMillis, strictHandles);
    }

    /** Default L6 TargetResolver: resolve a {@code thread:<name>} ref to the live Thread. */
    private static Object resolveThreadRef(net.marcloud.mcp.core.ob.ObRef ref) {
        if (ref.scheme() == net.marcloud.mcp.core.ob.ObRef.Scheme.THREAD) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals(ref.target())) {
                    return t;
                }
            }
            throw new IllegalArgumentException("no live thread named '" + ref.target() + "'");
        }
        throw new IllegalArgumentException("unsupported L6 scheme for this resolver: " + ref.scheme());
    }

    /**
     * True if {@code bind} is anything other than a pure loopback address (so the
     * facade would be reachable off-host). A blank/unset bind is treated as
     * loopback. Wildcards ({@code 0.0.0.0}, {@code ::}) and any resolvable
     * non-loopback host count as non-loopback; an unresolvable host is treated as
     * non-loopback (fail-safe — we would rather refuse than expose).
     */
    static boolean isNonLoopback(String bind) {
        if (bind == null || bind.isBlank()) {
            return false;
        }
        String host = bind.trim();
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("*")) {
            return true;
        }
        try {
            return !java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return true; // fail-safe: cannot prove it is loopback → treat as exposed
        }
    }

    /** Marker so callers/tests can confirm the module loaded and its Java level. */
    public static String banner() {
        return """
               MCP Core initialized (Java 25 module, running on JDK %s)
               """.formatted(Runtime.version().feature());
    }
}
