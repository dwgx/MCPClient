package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;

import org.junit.Test;

/**
 * The gate tables must cover every built-in the registry actually registers — the invariant that
 * was missing when {@code dev_probe} shipped (audit H8).
 *
 * <p><b>The defect this file exists for.</b> A tool's requirement is composed by NAME from three
 * independent tables: {@link Ring} {@code BUILTIN_RINGS} (L2), {@link SeToolRequirement}
 * {@code L3_WRITES} (L3) and {@code L4_PRIVILEGE} (L4). A name in none of them silently falls back
 * to R3 with no L3/L4 gate. {@code dev_probe} was registered by {@code McpCore.start()} with an R2
 * fallback and reported as R2 by every visible surface (registration, REST catalog, docs) while
 * enforcement re-derived it as R3 — so a subject dropped to the lowest clearance could still probe
 * the live game, and nothing failed. {@code PolicySideTableDriftTest} cannot see that shape: it
 * walks L3→Ring and L4→Ring, so a tool in NO table is invisible to it.
 *
 * <p><b>Why the inventory is the production one.</b> The names come from
 * {@link McpCore#registerBuiltins} — the single method {@code McpCore.start()} calls to build
 * the whole built-in surface. This file used to keep its OWN hand-copied provider list, and
 * that copy was the defect: it looked like production's wiring but could drift from it. A
 * provider added here and never wired into {@code start()} left every assertion below green on
 * a build where the tool did not exist at runtime — exactly the state {@code chat_read} sat in
 * until {@code PacketCoverage} caught it independently. There is now nothing to keep in sync,
 * so a name only has to be declared once, in the tables, where the gate reads it.
 *
 * <p><b>Three directions are pinned, and none pushes toward weakening a declaration.</b> (1) Every
 * registered name declares at least one row — the {@code dev_probe} shape; (2) the ring a tool is
 * REGISTERED with equals the ring the gate ENFORCES for it, which is that defect stated directly;
 * (3) every declared row names a tool that is actually registered, so a stale row cannot hide and
 * "declare everything" is not a fix. A tool that declares a STRICTER ring than the fallback it was
 * registered with stays legal — declarations are read through {@link Ring#forBuiltin}, so a
 * declaration is what BOTH sides see and only a registration that bypasses the table can disagree
 * with the gate.
 *
 * <p>The audit logic itself lives in {@link BuiltinGateAudit}, which production
 * ({@code McpCore.reportGateGaps}) also calls at startup — one implementation, so the runtime
 * diagnostic and this gate cannot disagree about what "covered" means.
 */
public final class RegisteredBuiltinGateCoverageTest {

    /**
     * The registry built by {@link McpCore#registerBuiltins} — the ONE production
     * registration site — driven into a throwaway {@link IoManager}.
     *
     * <p><b>This method replaced a hand-copied provider list, and that copy was the bug.</b>
     * It looked like production's wiring and could drift from it in both directions: a
     * provider added here but never wired into {@code start()} left every assertion below
     * green on a build where the tool did not exist at runtime. {@code chat_read} sat in
     * exactly that state for about ten minutes; {@code PacketCoverage} caught it
     * independently. Now there is nothing to keep in sync — the test drives the same method
     * {@code start()} calls.
     *
     * <p>Collaborators are the trivial stand-ins production itself passes when the game is
     * not up: constructing a provider and registering its specs never dereferences one, so
     * this is drivable headless.
     *
     * <p><b>Both real production states are driven, not a merge of them.</b> Production is in
     * exactly one of two: L6 off (the shipped default, {@code objects == null}) registers 84
     * tools, L6 on registers 85 because {@code debug_handle} joins. This file used to register
     * BOTH {@link DebugTools} variants into ONE registry, which is a state production never
     * occupies — so it could not tell a conditionally-registered tool from a missing one. Now
     * each audit runs against a state that genuinely exists.
     */
    private static IoManager registerEveryBuiltin(IoSupervisor exec, boolean l6Wired) {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
        IoManager reg = new IoManager(exec, engine);

        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(reg, engine,
                l6Wired ? new ObManager(null, 8, 60_000L) : null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(RegisteredBuiltinGateCoverageTest.class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return reg;
    }

    /**
     * The two production states differ by exactly one name, and it is a gated one.
     *
     * <p>{@code debug_handle} exists only when the L6 object-handle layer is wired
     * ({@code -Dmcp.core.handles=true}); ADR-0004 folded the eleven concrete JVMTI ops behind
     * {@code debug_manage}/{@code debug_handle}. So the L6-off registry must NOT carry
     * {@code debug_handle}, the L6-on one MUST, and every concrete action name must stay
     * gated in both. Pinned here so the audit's widening by DebugTools' own lists is honest
     * rather than a loophole that would hide a genuinely missing tool.
     */
    @Test
    public void theTwoProductionStatesDifferOnlyByTheGatedHandleTool() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            Set<String> off = new TreeSet<>(registerEveryBuiltin(exec, false).names());
            Set<String> on = new TreeSet<>(registerEveryBuiltin(exec, true).names());

            assertFalse("L6 off must not register debug_handle — it would be a tool with no "
                    + "handle layer behind it", off.contains("debug_handle"));
            assertTrue("L6 on must register debug_handle: " + on,
                    on.contains("debug_handle"));

            Set<String> delta = new TreeSet<>(on);
            delta.removeAll(off);
            assertEquals("the two states must differ by exactly the one gated handle tool, or "
                            + "the conditional wiring has drifted: " + delta,
                    Set.of("debug_handle"), delta);

            for (String name : DebugTools.HANDLE_ACTIONS) {
                assertTrue("handle action " + name + " must declare an L2 ring row even though "
                                + "it is never registered — it is dispatched behind debug_handle",
                        Ring.forBuiltin(name, null) != null);
            }
        } finally {
            exec.shutdown();
        }
    }

    private static SyncToolSpecification stub(String name) {
        Tool t = Tool.builder().name(name).description("stub " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t, (ex, req) ->
                CallToolResult.builder().addTextContent("ran").isError(false).build());
    }

    /**
     * {@code dev_probe} is enforced at the ring it declares (R2). The subject is dropped to R3 —
     * the lowest clearance, where the pre-fix code let the call through because enforcement fell
     * back to R3 — while everything else on the subject is wide open, so only the ring can deny.
     *
     * <p>Non-vacuous: the handler returns {@code isError=false} with body "ran", so an
     * {@code isError=true} result is itself proof it never executed. The second half drives the
     * same call at R2 and requires it to RUN, which is what distinguishes "enforced at R2" from
     * "denied for some other reason".
     */
    @Test
    public void aSubjectDroppedToUserRingCannotCallDevProbe() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            // Enforcement is by name, so the spec registered here does not matter; registering
            // the name through the real registry is what puts it behind the real gate.
            IoManager atR3 = new IoManager(exec, new SeLocalMonitor(
                    new SeClearancePolicy(Ring.R3, "tok"), SeToken.wideOpen()));
            atR3.register("dev_probe", stub("dev_probe"), null, "d", true,
                    Ring.forBuiltin("dev_probe", Ring.R2));
            CallToolResult denied = atR3.invoke("dev_probe", Map.of());
            assertTrue("dev_probe must be denied to an R3 subject — it is declared R2, and the "
                            + "pre-fix gate fell back to R3 and ran it",
                    Boolean.TRUE.equals(denied.isError()));
            assertTrue("the deny must come from the ring layer: " + denied.content(),
                    denied.content().toString().contains("L2 ring"));

            IoManager atR2 = new IoManager(exec, new SeLocalMonitor(
                    new SeClearancePolicy(Ring.R2, "tok"), SeToken.wideOpen()));
            atR2.register("dev_probe", stub("dev_probe"), null, "d", true,
                    Ring.forBuiltin("dev_probe", Ring.R2));
            CallToolResult allowed = atR2.invoke("dev_probe", Map.of());
            assertFalse("at the declared ring the call must run, or the deny above proves "
                            + "nothing: " + allowed.content(),
                    Boolean.TRUE.equals(allowed.isError()));
            assertTrue("the allowed handler actually ran", allowed.content().toString().contains("ran"));
        } finally {
            exec.shutdown();
        }
    }

    /**
     * FORWARD invariant: every registered built-in declares at least one gate row.
     *
     * <p>Driven, not read: the names come from the live registry {@link #registerEveryBuiltin}
     * fills — which is {@link McpCore#registerBuiltins}, the same call {@code start()} makes —
     * and the "declared?" answer comes from the gate's OWN resolver.
     */
    @Test
    public void everyRegisteredBuiltinDeclaresAGateRow() {
        assertAuditClean("forward: registered-but-ungated");
    }

    /**
     * The ring a tool is REGISTERED with must be the ring the gate ENFORCES for it. This is the
     * H8 defect stated directly: {@code dev_probe} was registered with an R2 fallback while
     * enforcement re-derived R3, so every visible surface (registration, REST catalog, docs) said
     * R2 while a subject dropped to R3 was let through.
     *
     * <p>Declaring a STRICTER ring is never a violation, and this assertion must not push anyone
     * toward weakening one: providers resolve through {@link Ring#forBuiltin}, so a declared ring
     * is what BOTH sides see. Only a registration that bypasses the table can disagree with the
     * gate — which is the drift worth failing on.
     */
    @Test
    public void theRingAToolIsRegisteredWithIsTheRingTheGateEnforces() {
        assertAuditClean("ring: registered/enforced divergence");
    }

    /**
     * REVERSE invariant: every declared row names a tool that is actually registered. A row for a
     * name nothing registers is a stale declaration the display surfaces keep repeating — and it
     * is what makes the forward invariant above unfakeable ("declare everything" is not a fix).
     */
    @Test
    public void everyDeclaredGateRowNamesARegisteredBuiltin() {
        assertAuditClean("reverse: stale gate rows");
    }

    /**
     * Drive production's own registration and require the shared audit to come back clean.
     *
     * <p>Runs against BOTH real production states (L6 off and L6 on), because a gate row can be
     * satisfied in one and violated in the other.
     *
     * <p>The non-vacuity guard runs on every call, not just the forward test: an empty or
     * near-empty registry would satisfy all three assertions at once, so a
     * {@link McpCore#registerBuiltins} that silently registered a handful of tools would
     * masquerade as coverage of the whole surface. The sentinels are one per provider family,
     * so losing any single provider fails here rather than passing quietly.
     */
    private static void assertAuditClean(String direction) {
        for (boolean l6Wired : new boolean[] {false, true}) {
            IoSupervisor exec = new IoSupervisor(4, 2000L);
            try {
                IoManager reg = registerEveryBuiltin(exec, l6Wired);
                Set<String> names = new TreeSet<>(reg.names());
                String state = l6Wired ? "L6-wired" : "L6-off";
                // The audit runs FIRST so its message names the offending tool. A missing
                // provider surfaces as "gate rows naming tools nothing registers:
                // [chat_read]" — which points at the exact registration that was dropped —
                // rather than as the count guard's vaguer "82 != 83".
                BuiltinGateAudit.Report report = BuiltinGateAudit.audit(reg);
                assertTrue("gate coverage broken (" + direction + ", " + state + "): "
                        + report.message(), report.clean());

                assertTrue("the " + state + " inventory must be real — a near-empty registry "
                                + "would satisfy every assertion vacuously. Registered ("
                                + names.size() + "): " + names,
                        names.size() == (l6Wired ? 85 : 84) && names.containsAll(Set.of(
                                // One VERIFIED sentinel per provider family registerBuiltins
                                // wires. Losing any single provider fails HERE rather than
                                // quietly shrinking the audited surface. The count is pinned to
                                // the MEASURED surface per state, so a silent shrinkage or an
                                // unexpected extra name fails here too.
                                "eval_java",            // ToolRegistry
                                "capture_screen",       // ToolRegistry
                                "redefine_class",       // MetaTools
                                "create_tool",          // MetaTools (DynamicToolFactory)
                                "rollback_tool",        // MetaTools
                                "get_tool_source",      // MetaTools
                                "do_enchant_item",      // EnchantTools
                                "dev_probe",            // DevTools
                                "chat_read",            // ChatTools — the tool this file exists for
                                "packet_view",          // ObserveTools
                                "world_view",           // ObserveTools
                                "scan_surroundings",    // ObserveTools
                                "gui_snapshot",         // GuiTools
                                "act_set",              // ActTools
                                "open_pause_menu",       // EscPanelTools (KI-12, the ESC door)
                                "debug_manage",         // DebugTools (ADR-0004 folded)
                                "timeline_tail",        // ObserveTools/Clock
                                "list_compat_patches",  // CompatTools
                                "memory_search",        // MemoryTools
                                "get_story",            // NarrativeTools
                                "set_goal",             // NarrativeTools
                                "install_hook",         // HookTools
                                "uninstall_hook",       // HookTools
                                "list_hooks",           // IntrospectionTools
                                "eval_ephemeral",       // SynthTools
                                "seam_netty_install",   // SeamTools
                                "drop_privilege",       // PermissionTools
                                "grant_capability",     // PrivilegeControlTools
                                "read_field",           // MutateStateTools (C5)
                                "invoke_method",        // MutateStateTools (C5)
                                "open_module")));       // MutateStateTools (C5)
            } finally {
                exec.shutdown();
            }
        }
    }
}
