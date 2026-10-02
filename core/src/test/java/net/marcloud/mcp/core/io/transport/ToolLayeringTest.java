package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.Test;

/**
 * The built-in surface is in two layers, and the model-facing one is the only one a model can
 * reach. This file drives production's own {@link McpCore#registerBuiltins} — the single
 * registration site — and then asks the two registries different questions.
 *
 * <p><b>Why two registries rather than one filtered list.</b> The audited registry keeps every
 * built-in, so the gate tables ({@link Ring} BUILTIN_RINGS, {@code SeToolRequirement} L3/L4),
 * {@code McpCore.reportGateGaps} and {@code RegisteredBuiltinGateCoverageTest} still see the
 * complete surface. Hiding a tool by unregistering it would make all of them report kernel tools
 * as stale gate rows, i.e. turn a product decision into a security warning. The model-facing
 * registry is a second {@link IoManager} over the same executor and the same reference monitor,
 * and it is the one the MCP socket and the REST facade are handed.
 *
 * <p><b>What each test is for, and the two mutation directions.</b> The split is only worth
 * anything if BOTH halves are pinned, so the directions are separate tests rather than one
 * equality assertion:
 * <ul>
 *   <li>making a kernel tool visible by default must redden
 *       {@link #theModelSurfaceCarriesNoKernelLayeredTool()} and
 *       {@link #aKernelLayeredToolIsUnreachableThroughTheModelSurface()};</li>
 *   <li>making a model-facing tool invisible must redden
 *       {@link #theModelSurfaceCarriesEveryModelFacingTool()}.</li>
 * </ul>
 * The red sets are disjoint, which is the point: a single "surface == expected set" assertion
 * would go red for both mutations and could not say which half broke.
 *
 * <p><b>Non-vacuity.</b> {@link #everyRegisteredBuiltinDeclaresALayer()} pins the registered
 * count against the MEASURED surface for both production states, and every other test names
 * concrete tools on both sides of the split, so an empty or near-empty surface fails rather
 * than passing quietly.
 */
public final class ToolLayeringTest {

    /** One driven {@link McpCore}: its audited registry and its model-facing surface. */
    private record Driven(McpCore core, IoManager audited, IoManager surface, IoSupervisor exec) {
    }

    /**
     * Drive {@code registerBuiltins} with the trivial stand-ins production itself passes when
     * the game is not up — building a tool spec never dereferences a collaborator.
     *
     * <p>Both real production states are driven, not a merge of them: L6 off (the shipped
     * default) and L6 on, which registers the extra gated {@code debug_handle}.
     */
    private static Driven drive(boolean l6Wired, Ring clearance) {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(clearance, "tok"), SeToken.wideOpen());
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        IoManager audited = new IoManager(exec, engine);
        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(audited, engine,
                l6Wired ? new ObManager(null, 8, 60_000L) : null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(ToolLayeringTest.class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return new Driven(core, audited, core.modelSurface(), exec);
    }

    private static Set<String> auditedNames(Driven d) {
        return new TreeSet<>(d.audited().names());
    }

    private static Set<String> surfaceNames(Driven d) {
        return new TreeSet<>(d.surface().names());
    }

    /**
     * Every registered built-in carries an explicit row in {@link ToolRegistry}'s layer table.
     *
     * <p>This is the anti-drift guard, and it is the reason the table can safely default to
     * KERNEL: a provider wired into {@code registerBuiltins} whose tools nobody classified would
     * silently become invisible, so this test requires the row to exist. The counts are pinned to
     * the MEASURED surface per state (the same numbers {@code RegisteredBuiltinGateCoverageTest}
     * pins), so a silently dropped provider fails here rather than vanishing from the split.
     */
    @Test
    public void everyRegisteredBuiltinDeclaresALayer() {
        for (boolean l6 : new boolean[] {false, true}) {
            Driven d = drive(l6, Ring.R_MINUS_1);
            try {
                Set<String> registered = auditedNames(d);
                assertEquals("the " + (l6 ? "L6-wired" : "L6-off")
                                + " audited registry must be the complete surface: " + registered,
                        l6 ? 85 : 84, registered.size());
                for (String name : registered) {
                    assertTrue("'" + name + "' is registered but has no row in ToolRegistry.LAYERS, "
                                    + "so the rule would classify it by the deny-by-default "
                                    + "fallback rather than by a decision anyone made. Registered: "
                                    + registered,
                            ToolRegistry.declaredNames().contains(name));
                }
            } finally {
                d.exec().shutdown();
            }
        }
    }

    /**
     * DIRECTION B. Every model-facing registered tool is on the model-facing surface.
     *
     * <p>Reddens when a game tool is made invisible: one missing name is named, not a count.
     */
    @Test
    public void theModelSurfaceCarriesEveryModelFacingTool() {
        for (boolean l6 : new boolean[] {false, true}) {
            Driven d = drive(l6, Ring.R_MINUS_1);
            try {
                Set<String> expected = new TreeSet<>();
                for (String name : auditedNames(d)) {
                    if (!ToolRegistry.isKernelLayered(name)) {
                        expected.add(name);
                    }
                }
                Set<String> missing = new TreeSet<>(expected);
                missing.removeAll(surfaceNames(d));
                assertTrue("model-facing tools the surface does NOT carry: " + missing
                        + " — a model would be told it has no such tool. Surface: "
                        + surfaceNames(d),
                        missing.isEmpty());
                assertTrue("the non-vacuity guard: the expected set must be substantial, or an "
                                + "almost-empty surface would satisfy this vacuously. Expected ("
                                + expected.size() + "): " + expected,
                        expected.size() >= 40);
            } finally {
                d.exec().shutdown();
            }
        }
    }

    /**
     * DIRECTION A. No kernel-layered tool is on the model-facing surface.
     *
     * <p>The negative half, kept as its own test so its red set is disjoint from
     * {@link #theModelSurfaceCarriesEveryModelFacingTool()}'s.
     */
    @Test
    public void theModelSurfaceCarriesNoKernelLayeredTool() {
        for (boolean l6 : new boolean[] {false, true}) {
            Driven d = drive(l6, Ring.R_MINUS_1);
            try {
                Set<String> leaked = new TreeSet<>();
                for (String name : surfaceNames(d)) {
                    if (ToolRegistry.isKernelLayered(name)) {
                        leaked.add(name);
                    }
                }
                assertTrue("kernel-layered tools the model surface is ADVERTISING: " + leaked
                        + " — they are exactly the legible names a weak model reaches for first",
                        leaked.isEmpty());
            } finally {
                d.exec().shutdown();
            }
        }
    }

    /**
     * A kernel-layered tool is not merely un-advertised: it cannot be called by name through the
     * surface at all.
     *
     * <p>Non-vacuous in both directions: {@code eval_java} is called through the audited registry
     * (where the gate answers it) and through the surface, where there is no such capability, so
     * a null from the surface cannot be confused with a handler that happens to fail.
     */
    @Test
    public void aKernelLayeredToolIsUnreachableThroughTheModelSurface() {
        Driven d = drive(false, Ring.R_MINUS_1);
        try {
            CallToolResult viaAudited = d.audited().invoke("eval_java", Map.of());
            assertNotNull("eval_java must be callable through the audited registry", viaAudited);
            assertFalse("the gate must not deny eval_java here (this subject is R-1 and eval_java "
                            + "is R-1), so the audited path really does reach it: "
                            + viaAudited.content(),
                    viaAudited.content().toString().contains("L2 ring"));

            assertNull("eval_java must NOT be reachable through the model surface",
                    d.surface().get("eval_java"));
            assertNull("invoke on a name the surface does not carry must return null, not an "
                    + "empty success: a model would otherwise read silence as 'it ran'",
                    d.surface().invoke("eval_java", Map.of()));

            for (String name : new String[] {"install_hook", "read_field", "debug_manage",
                    "create_tool", "list_capabilities", "drop_privilege", "seam_netty_install",
                    "list_classes", "redefine_class", "send_raw_packet", "dev_probe"}) {
                assertNull("'" + name + "' is kernel-layered and must not be on the surface",
                        d.surface().get(name));
            }
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * The two registries share one reference monitor, so hiding a tool changes what a model can
     * see and nothing about what a subject may do.
     */
    @Test
    public void bothRegistriesAreGatedByTheSameLiveMonitor() {
        Driven d = drive(false, Ring.R_MINUS_1);
        try {
            assertSame("the surface must be gated by the SAME monitor instance, or a drop_privilege "
                            + "or revoke_capability would bite on one registry and not the other",
                    d.audited().engine(), d.surface().engine());
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * Promotion puts a kernel-layered tool on the surface, and it arrives still gated: the
     * promoted copy of an R-1 tool is refused to a subject dropped to R3, by the ring layer.
     *
     * <p>Non-vacuous because the R-3 subject is the only thing that can produce the denial — the
     * handler is the real one, and the same promoted name IS permitted to an R-1 subject.
     */
    @Test
    public void promotionPutsAKernelToolOnTheSurfaceStillGated() {
        Driven low = drive(false, Ring.R3);
        try {
            assertFalse("precondition: eval_java must be hidden before the promotion, or this "
                            + "proves nothing", low.surface().names().contains("eval_java"));
            assertTrue("promote must report success for a registered kernel-layered tool",
                    low.core().promote("eval_java"));
            assertNotNull("after promotion eval_java must be on the model surface",
                    low.surface().get("eval_java"));
            assertEquals("promotion must be recorded so a reader can see what was promoted",
                    java.util.List.of("eval_java"), low.core().promotedNames());

            CallToolResult denied = low.surface().invoke("eval_java", Map.of());
            assertTrue("a PROMOTED R-1 tool must still be refused to an R-3 subject — promotion "
                            + "changes what a model can see, never what a subject may do: "
                            + denied.content(),
                    Boolean.TRUE.equals(denied.isError()));
            assertTrue("the denial must come from the ring layer, not from a missing tool: "
                    + denied.content(), denied.content().toString().contains("L2 ring"));
        } finally {
            low.exec().shutdown();
        }

        Driven wide = drive(false, Ring.R_MINUS_1);
        try {
            assertTrue(wide.core().promote("list_compat_patches"));
            assertNotNull(wide.surface().get("list_compat_patches"));
            // No arguments, so whatever comes back is the GATE's answer, not a schema refusal:
            // that is what distinguishes "promoted and permitted" from "promoted but locked".
            CallToolResult res = wide.surface().invoke("list_compat_patches", Map.of());
            assertNotNull("a promoted tool must be reachable on the surface", res);
            assertFalse("a promoted tool must not be denied by the gate at a clearance that "
                            + "already permitted it: " + res.content(),
                    res.content().toString().contains("L2 ring"));
        } finally {
            wide.exec().shutdown();
        }
    }

    /**
     * The promotion reader refuses a name nothing registered, and refuses politely — a typo in an
     * operator's file must not keep the game from starting.
     */
    @Test
    public void promotionRefusesAnUnknownNameWithoutThrowing() {
        Driven d = drive(false, Ring.R_MINUS_1);
        try {
            assertFalse("promoting a name no provider registered must report false",
                    d.core().promote("no_such_tool_anywhere"));
            assertNull("and must not have put anything on the surface",
                    d.surface().get("no_such_tool_anywhere"));
            assertTrue("a refused promotion must not be recorded as promoted",
                    d.core().promotedNames().isEmpty());
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * {@code create_tool} is kernel-layered, so a model has no verb that manufactures one — the
     * answer to "a model that cannot see a verb can still be told to make one".
     *
     * <p>Non-vacuous on both sides: the surface really does refuse the name, and the audited
     * registry really does hold the same spec under the same name, so this is a layering
     * decision and not a tool that failed to register.
     */
    @Test
    public void createToolIsNotOnTheModelSurface() {
        Driven d = drive(false, Ring.R_MINUS_1);
        try {
            for (String name : new String[] {"create_tool", "rollback_tool", "get_tool_source",
                    "list_capabilities", "redefine_class"}) {
                assertNotNull("'" + name + "' must still be registered — hiding a tool by not "
                        + "registering it would turn it into a stale gate row",
                        d.audited().get(name));
                assertNull("'" + name + "' must not be on the model surface", d.surface().get(name));
            }
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * The folded JVMTI action names are kernel-layered even though ADR-0004 unregistered them, so
     * the whole kernel vocabulary is classified rather than only the handful that happens to be
     * live.
     *
     * <p>The second half is the non-vacuity guard: if one of these ever became a registered tool,
     * this test would fail rather than let the table quietly grow a row nothing backs.
     */
    @Test
    public void theFoldedDebugActionsAreDeclaredButNotRegistered() {
        Driven d = drive(false, Ring.R_MINUS_1);
        try {
            for (String action : DebugTools.MANAGE_ACTIONS) {
                assertTrue(action + " must be kernel-layered: it is a JVMTI operation",
                        ToolRegistry.isKernelLayered(action));
                assertNull(action + " is not a registered tool name (ADR-0004 folded it), so a "
                        + "surface entry for it would be a phantom",
                        d.surface().get(action));
            }
            for (String action : DebugTools.HANDLE_ACTIONS) {
                assertTrue(action + " must be kernel-layered",
                        ToolRegistry.isKernelLayered(action));
            }
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * The rule denies by default, and the declared rows are the whole vocabulary.
     */
    @Test
    public void anUndeclaredNameIsKernelLayered() {
        assertEquals("a name the table has never heard of must default to KERNEL, not to GAME: "
                        + "that is what makes a new provider safe before anyone classifies it",
                ToolRegistry.Layer.KERNEL, ToolRegistry.layerOf("some_tool_from_the_future"));
        assertEquals(ToolRegistry.Layer.KERNEL, ToolRegistry.layerOf("eval_java"));
        assertEquals(ToolRegistry.Layer.GAME, ToolRegistry.layerOf("world_view"));
        assertTrue(ToolRegistry.modelFacingNames().contains("world_view"));
        assertFalse(ToolRegistry.kernelLayeredNames().contains("world_view"));
        assertEquals("every declared name is on exactly one side",
                ToolRegistry.declaredNames().size(),
                ToolRegistry.modelFacingNames().size() + ToolRegistry.kernelLayeredNames().size());
    }

    /**
     * The split is a RULING, so the ruling is pinned — one representative name per provider
     * family, on both sides, with the question-1 subject that puts it there.
     *
     * <p><b>Why this is not the duplicate list the acceptance warns about.</b>
     * {@link #theModelSurfaceCarriesEveryModelFacingTool()} derives its expectation from
     * {@link ToolRegistry#layerOf}, so it catches a ROUTING fault (a family fed to the wrong
     * registry, the mixed-provider filter dropping a name) and would stay green if somebody
     * edited the table. This is the other half: it fails when the DECISION changes, which is the
     * only way a game tool becomes invisible without a routing bug. Fifty-odd names rather than
     * ninety-six, because a family-wide flip is the realistic edit and the whole table is already
     * the readable artifact the Owner reviews.
     */
    @Test
    public void theSplitIsTheRulingAndNotAnAccident() {
        // GAME: the subject is the Minecraft session (question 2).
        game("read_player_state", "the local player's own state");
        game("world_view", "the world around the player");
        game("scan_surroundings", "one symbolic snapshot of the player's situation");
        game("craft_plan", "the recipe table plus the live inventory; changes nothing");
        game("capture_screen", "a rendered frame of the game window");
        game("find_block", "where a block type is, in the world");
        game("inspect_block", "one world cell");
        game("server_info", "the identity of the server the client is connected to");
        game("send_chat", "an outward chat/command effect on the session");
        game("do_place_block", "a typed C08 into the session's own protocol");
        game("do_dig", "a typed C07 into the session's own protocol");
        game("do_click_slot", "a typed C0E into the session's own protocol");
        game("transfer_item", "moves items between the session's own container regions");
        game("do_enchant_item", "spends the player's lapis at their own enchant table");
        game("act_set", "drives the live player through the movement/look/interact channels");
        game("act_status", "a read of the actuation runtime's own state");
        game("press_key_binding", "vanilla's own KeyBinding dispatch");
        game("gui_snapshot", "the open GUI as addressable elements");
        game("gui_click_element", "drives a real vanilla GUI handler");
        game("open_pause_menu", "the ESC tree root; changes what is on screen, sends nothing");
        game("open_overlay", "the project's own overlay panel; changes what is on screen");
        game("chat_read", "the server's inbound chat, read from the tap's typed projection");
        game("packets_tail", "a read of the journal the tap feeds");
        game("packet_get", "a read of one journaled packet");
        game("packet_view", "a read of typed packet projections");
        game("clock_now", "a read of the single game clock");
        game("timeline_tail", "a read of the timeline spine");
        game("disconnect_report", "explains the last kick from what was observed");
        game("recent_packets", "a read of the packet-log ring");
        game("memory_write", "the agent's own notes, in a JSON file");
        game("memory_search", "the agent's own notes, read back");
        game("set_goal", "the agent's own goal stack");
        game("get_story", "the agent's own goal stack and story log");

        // KERNEL: the subject is the Kernel itself (question 1).
        kernel("eval_java", "compiles and loads Java into the running game JVM");
        kernel("send_raw_packet", "compiles and reflectively runs caller-supplied Java (Ring:91-97)");
        kernel("redefine_class", "replaces the bytecode of an already-loaded class");
        kernel("eval_ephemeral", "compiles and executes AI Java as a hidden class");
        kernel("create_tool", "compiles AI Java into a new registered tool");
        kernel("rollback_tool", "replaces a registered tool with an archived version");
        kernel("get_tool_source", "reads the registry's own source store");
        kernel("list_capabilities", "enumerates the capability registry itself");
        kernel("install_hook", "ByteBuddy advice onto any loaded method");
        kernel("uninstall_hook", "reverts ByteBuddy advice");
        kernel("read_field", "reads a private field off a live object");
        kernel("write_field", "writes a private/final field on a live object");
        kernel("invoke_method", "invokes a private method on a live object");
        kernel("open_module", "redefineModule on a platform module");
        kernel("list_classes", "the JVM's loaded classes");
        kernel("describe_class", "reflection over a loaded class");
        kernel("find_method", "reflection across loaded classes");
        kernel("list_hooks", "the runtime hook set");
        kernel("seam_netty_install", "installs a MITM into the Netty pipeline");
        kernel("seam_tick_enable", "retransforms Minecraft.runTick");
        kernel("debug_manage", "native JVMTI thread control");
        kernel("debug_handle", "the L6 handle lifecycle over a live thread");
        kernel("list_permissions", "the 7-layer reference monitor's own posture");
        kernel("drop_privilege", "lowers the subject's clearance in the monitor");
        kernel("restore_privilege", "raises it back, token-gated");
        kernel("enable_privilege", "enables an L4 privilege in the monitor");
        kernel("disable_privilege", "disables an L4 privilege in the monitor");
        kernel("grant_capability", "grants an L5 capability SID");
        kernel("revoke_capability", "revokes an L5 capability SID");
        kernel("dev_probe", "reports the GL context as well as the connection");
        kernel("list_compat_patches", "reports the agent's own startup patch catalog");
    }

    private static void game(String name, String why) {
        assertEquals("'" + name + "' must be MODEL-FACING: " + why,
                ToolRegistry.Layer.GAME, ToolRegistry.layerOf(name));
    }

    private static void kernel(String name, String why) {
        assertEquals("'" + name + "' must be KERNEL-LAYERED: " + why,
                ToolRegistry.Layer.KERNEL, ToolRegistry.layerOf(name));
    }
}
