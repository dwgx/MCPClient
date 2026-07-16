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
        // L4/L5 self-management (GAP-2): enable/disable_privilege + grant/revoke_capability.
        // Mutations bite only when engine is SeLocalMonitor; under P-SECURE the
        // interface defaults return false and the tools report "not locally owned".
        new net.marcloud.mcp.core.se.PrivilegeControlTools(engine).registerAll(registry);

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
        CmQuery introspect = new CmQuery(
                getClass().getClassLoader(), java.util.List.of(hooks, dynHooks));
        new IntrospectionTools(introspect).registerAll(registry);

        // C3 INTERCEPT: runtime install/uninstall/reset of ByteBuddy hooks.
        new HookTools(dynHooks, gate).registerAll(registry);

        // C5 MUTATE-STATE: read/write any field, invoke private methods, open
        // modules. Instrumentation reached through the gated AgentAccess seam.
        MmAccess deep = new MmAccess(game, gate,
                net.marcloud.mcp.core.boot.AgentAccess::instrumentation);
        // Invalidate MmAccess's layout-dependent caches after any redefine
        // (a DCEVM structural redefine can move field offsets → stale VarHandle).
        hotLoad.setOnRedefined(deep::invalidate);
        new MutateStateTools(deep, game).registerAll(registry);

        // C7 SYNTHESIZE: one-shot GC-able hidden-class tools.
        new SynthTools(new PsSynthesizer()).registerAll(registry);

        // C8 SEAM: Netty pipeline MITM / GLFW input / tick injection. Kept as a
        // field so stop() can tear the seams down.
        seams = new SeamController(bus, game);
        new SeamTools(seams).registerAll(registry);

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

        // PHASE T: Timeline ring — fold every EventBus event onto the GameClock as a
        // safe {tickId, kind, summary} entry, and expose clock_now / timeline_tail.
        // Attach BEFORE nothing-else-matters ordering; subscribing to the GameEvent
        // base type captures all present + future event subclasses in one hook.
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

        new net.marcloud.mcp.core.drivers.observe.ObserveTools(
                net.marcloud.mcp.core.ke.GameClock.INSTANCE, timeline, packetJournal,
                seams).registerAll(registry);

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
        actRuntime.registerApplier(net.marcloud.mcp.core.drivers.act.ActSlot.MOVE,
                new net.marcloud.mcp.core.drivers.act.MoveApplier());
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
        new net.marcloud.mcp.core.drivers.action.ActTools(actRuntime).registerAll(registry);

        // PHASE E: fan whitelisted world GameEvents out to board Signals (disconnect,
        // inbound chat, block-change). Board absent ⇒ silent no-op, like the clock bridge.
        new net.marcloud.mcp.core.link.BoardWorldEventBridge(bus).attach();

        // DWM overlay (OPT-IN via -Dmcp.core.overlay=true). Reflectively discovers
        // whichever optional overlay backend jar is on the game classpath (pure-Java
        // dwm-gl, or imgui); if present, installs the render-frame seam
        // (EntityRenderer.updateCameraAndRender exit) and drives one overlay frame per
        // game frame. Absent jar / off flag / any fault => silent no-op, game unaffected
        // (detachable-auxiliary contract). The overlay resolves its own GLFW window
        // handle, so 0 is passed here.
        net.marcloud.mcp.core.flt.seam.RenderOverlayCoordinator.tryInstall(
                net.marcloud.mcp.core.boot.AgentAccess.instrumentation(), 0L);

        // C6 CONTROL-EXEC: native JVMTI debugger. Graceful no-op without
        // -agentpath:core-jvmti.dll — the debug_* tools still register and report
        // honestly (no dead tools). Debug events also flow onto the EventBus.
        net.marcloud.mcp.core.kd.DebugEventQueue.INSTANCE.addListener(bus::publish);
        if (objects != null) {
            // L6 wired: debug ops can bind to a frozen thread handle; the subject
            // supplier ties a minted handle's owner to the gate's principal.
            new net.marcloud.mcp.core.kd.DebugTools(gate, objects, engine::currentSubject)
                    .registerAll(registry);
        } else {
            new net.marcloud.mcp.core.kd.DebugTools(gate).registerAll(registry);
        }
        if (!net.marcloud.mcp.core.kd.KdBridge.isAvailable()) {
            System.err.println("[MCP Core] JVMTI debugger absent — "
                    + net.marcloud.mcp.core.kd.KdBridge.unavailableReason()
                    + " (debug_* tools registered, return isError until the agent is present).");
        }

        // Structured GUI interaction: expose the whole clickable GUI (buttons,
        // slots, text fields) to the LLM as addressable elements and drive the
        // real vanilla handlers by element id. gui_snapshot is R2 (game-thread
        // read); the action tools are R1 (server-visible effects) + SE_GUI_INTERACT.
        new net.marcloud.mcp.core.drivers.gui.GuiTools(game, new net.marcloud.mcp.core.drivers.gui.GuiSnapshotService())
                .registerAll(registry);

        // dev_probe (R2 read-only): one-call live-game diagnostic — connection/world
        // presence + GL context (version/vendor/profile) — marshalled onto the game
        // thread. The development-time "what is the running game actually doing" probe
        // and the carrier for KI-1's GL-context evidence. Degrades to absent headless.
        new net.marcloud.mcp.core.drivers.video.DevTools(game).registerAll(registry);

        // Compat patch observability (R3 read-only): list_compat_patches reports the
        // startup patches armed by the engine at premain (NT AppCompat analogue).
        // Application is kernel-automatic at premain; only the read-only state view
        // passes through the tool gate. The engine/database are null-safe here (a
        // headless run without -javaagent reports an empty catalog).
        new net.marcloud.mcp.core.compat.CompatTools(
                net.marcloud.mcp.core.compat.Compat.database(),
                net.marcloud.mcp.core.compat.Compat.engine())
                .registerAll(registry);

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
                httpFacade = new HttpFacade(registry, bind, httpPort, httpToken);
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
