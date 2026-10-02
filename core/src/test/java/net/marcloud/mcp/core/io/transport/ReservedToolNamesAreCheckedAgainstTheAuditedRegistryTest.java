package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.Test;

/**
 * {@code create_tool} must not install an AI-authored handler under a name the Kernel owns.
 *
 * <p><b>What was wrong.</b> {@code MetaTools.isReserved} read {@code IoManager.isBuiltin} on the
 * registry it had been <em>constructed</em> with, and that registry is the model-facing surface.
 * Kernel-layered names are absent from the surface by construction, so the guard answered "not
 * reserved" for every hidden tool: measured on the driven surface, 33 registered kernel-layered
 * names were squattable, including {@code eval_java}, {@code install_hook},
 * {@code invoke_method}, {@code write_field} and {@code send_raw_packet}.
 *
 * <p><b>Why the obvious backstop does not close it.</b> {@code IoManager.register} refuses a
 * generated tool replacing a built-in — {@code !builtIn && previous != null &&
 * previous.builtIn()} — but {@code previous} is read from the same surface, where {@code eval_java}
 * does not exist, so {@code previous} is null and the guard is skipped. The refusal asserted below
 * is therefore the reservation check and not that backstop, and the test proves which one fired by
 * refusing a name that is not on the surface at all (so the backstop <em>cannot</em> fire) and by
 * checking the refusal text.
 *
 * <p><b>Reachability, so this is not a latent bug.</b> Promotion is a supported feature
 * ({@code -Dmcp.core.promote}, {@code mcp_promote.txt}, {@code McpCore.promote}), so
 * {@code promote("create_tool")} puts the verb in the model's hands. Every test here promotes it
 * first: without that step the tool is not on the surface and the test would be asserting against
 * a verb no model can reach — the exact non-vacuity trap the reachability finding was.
 *
 * <p><b>Both directions, disjoint red sets.</b> A guard asserted only in the deny direction
 * passes against a guard that refuses everything, which would be a worse defect than the one it
 * replaced. So:
 * <ul>
 *   <li>{@link #aBuiltInNameIsRefused()} — built-in names are reserved (the deny direction).</li>
 *   <li>{@link #aGenuinelyNewNameIsAccepted()} — an undeclared, unregistered name is installed
 *       (the allow direction).</li>
 * </ul>
 * The membership pin, {@link #theReservedSetIsExactlyTheDeclaredVocabulary()}, is deliberately
 * <b>neither</b>: it is the counterweight that makes each of the two above meaningful, and it is
 * written so that widening the guard to "refuse everything" reddens the allow direction while
 * narrowing it to "refuse nothing" reddens the deny direction.
 */
public final class ReservedToolNamesAreCheckedAgainstTheAuditedRegistryTest {

    private record Driven(McpCore core, IoManager audited, IoManager surface, IoSupervisor exec) {
    }

    /**
     * Drive production's own {@code registerBuiltins}, then promote {@code create_tool} onto the
     * model surface so the verb is reachable exactly the way an operator's
     * {@code mcp_promote.txt} line makes it reachable.
     */
    private static Driven drivenWithCreateToolPromoted() {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        IoManager audited = new IoManager(exec, engine);
        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(audited, engine, null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(ReservedToolNamesAreCheckedAgainstTheAuditedRegistryTest
                        .class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        assertTrue("precondition: create_tool must be kernel-layered and OFF the surface before "
                        + "promotion, or promoting it proves nothing",
                !core.modelSurface().names().contains("create_tool"));
        assertTrue("create_tool must be registered in the audited registry, or promote cannot "
                        + "find it to copy", core.promote("create_tool"));
        assertNotNull("create_tool must now be reachable through the model surface",
                core.modelSurface().get("create_tool"));
        return new Driven(core, audited, core.modelSurface(), exec);
    }

    /** Arguments for {@code create_tool}, with source that compiles into a trivial handler. */
    private static Map<String, Object> createArgs(String toolName, String className) {
        String source = "package gen;\n"
                + "public class " + className.substring(className.lastIndexOf('.') + 1) + " {\n"
                + "  public String handle(java.util.Map<String,Object> args) {\n"
                + "    return \"ai-authored\";\n"
                + "  }\n"
                + "}\n";
        return Map.of("toolName", toolName, "className", className,
                "description", "AI-authored probe tool", "source", source);
    }

    private static boolean refusedAsReserved(CallToolResult res) {
        return res != null
                && Boolean.TRUE.equals(res.isError())
                && res.content().toString().contains("is a reserved core tool");
    }

    /**
     * THE DENY DIRECTION. A registered built-in name — model-facing or kernel-layered — is refused.
     *
     * <p>The kernel names are the ones the measurement called out, and the model-facing one
     * ({@code world_view}) is in the list as the control: it is reserved for the same reason, and
     * an implementation that reserved only the hidden half would pass a kernel-only test.
     *
     * <p><b>The refusal must come from the reservation check.</b> Every one of these names is
     * absent from the surface except the two promoted-in meta verbs, so {@code IoManager}'s
     * built-in-replacement guard cannot fire for them — {@code previous} is null. The refusal text
     * is asserted rather than merely {@code isError}, because a compile failure or a schema
     * rejection is also an error and would otherwise satisfy this test for the wrong reason.
     */
    @Test
    public void aBuiltInNameIsRefused() {
        Driven d = drivenWithCreateToolPromoted();
        try {
            for (String name : new String[] {
                    "eval_java",          // the R-1 hypervisor verb the audit named first
                    "install_hook",
                    "invoke_method",
                    "write_field",
                    "send_raw_packet",
                    "debug_manage",
                    "list_classes",
                    "redefine_class",
                    "world_view",         // model-facing, still reserved: the control
                    "read_player_state"}) {
                CallToolResult res = d.surface().invoke("create_tool", createArgs(name, "gen.Squat"));
                assertTrue("create_tool must REFUSE the built-in name '" + name + "' as reserved; "
                                + "it answered: " + (res == null ? "null" : res.content()),
                        refusedAsReserved(res));
            }
            // And nothing was installed under any of them. A refusal that still registered would
            // satisfy the assertions above while leaving the squat in place. The two states are
            // different and both are asserted: a kernel-layered name must not appear on the
            // surface at all, while a model-facing one is legitimately there — as the BUILT-IN it
            // already was, since a squat would replace it with an AI-authored handler.
            for (String name : new String[] {"eval_java", "install_hook"}) {
                assertNull("'" + name + "' is kernel-layered, so a refused create_tool must have "
                        + "left it absent from the model surface entirely",
                        d.surface().get(name));
            }
            for (String name : new String[] {"world_view", "read_player_state"}) {
                assertNotNull("'" + name + "' is model-facing and stays on the surface", 
                        d.surface().get(name));
                assertTrue("'" + name + "' must still be the BUILT-IN after a refused create_tool: "
                                + "a squat would leave an AI-authored handler running under the "
                                + "name an operator reads as the hypervisor's tool",
                        d.surface().get(name).builtIn());
            }
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * THE ALLOW DIRECTION. A genuinely new name is accepted, compiled, registered on the SURFACE,
     * and callable.
     *
     * <p>This is what makes the guard a guard. {@code ToolRegistry.layerOf} denies by default, so
     * an implementation that used the wrong predicate would refuse every name the model could ever
     * invent and turn {@code create_tool} into a no-op that fails with a gate error — while passing
     * every deny-direction assertion. The undeclared name below is exactly that case.
     *
     * <p>It also pins that the install target is the surface and not the audited registry, which
     * is the other half of the two-registry contract: a tool the model cannot call is not a tool.
     */
    @Test
    public void aGenuinelyNewNameIsAccepted() {
        Driven d = drivenWithCreateToolPromoted();
        try {
            String name = "probe_new_tool_" + System.nanoTime();
            assertFalse("precondition: a genuinely new name must be UNDECLARED, or this test is "
                            + "not testing the undeclared path. layerOf(" + name + ")="
                            + ToolRegistry.layerOf(name),
                    ToolRegistry.declaredNames().contains(name));

            CallToolResult res = d.surface().invoke("create_tool", createArgs(name, "gen.ProbeNew"));
            assertNotNull("create_tool must answer for a new name", res);
            assertFalse("create_tool must ACCEPT a genuinely new name; it answered: "
                            + res.content(), Boolean.TRUE.equals(res.isError()));

            assertNotNull("the new tool must be installed on the MODEL SURFACE, not the audited "
                            + "registry — an AI-authored tool the model cannot call is not a tool",
                    d.surface().get(name));
            assertNull("and it must NOT be in the audited registry, which is the complete BUILT-IN "
                            + "set: an AI-authored handler there would be reported as a built-in by "
                            + "the gate audit",
                    d.audited().get(name));
            assertFalse("and it must be recorded as AI-authored, not built-in",
                    d.surface().get(name).builtIn());

            CallToolResult called = d.surface().invoke(name, Map.of());
            assertNotNull("the newly created tool must be callable through the surface", called);
            assertTrue("and it must actually run its handler: " + called.content(),
                    called.content().toString().contains("ai-authored"));
        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * THE MEMBERSHIP PIN — every name {@link ToolRegistry} declares is reserved, and the set is
     * exactly the declared vocabulary.
     *
     * <p><b>What the reserved set contains and why, including the twelve {@code debug_*} folds.</b>
     * {@code MetaTools.isReserved} is the union of two live terms: every <em>registered</em>
     * built-in in the audited registry, and every <em>declared</em> kernel-layered name. The second
     * term alone would leave them squattable. They are reserved because (a) a name-shaped probe
     * could otherwise install its own handler under a name an operator reads in
     * {@code list_capabilities} as the JVMTI verb, which is the impersonation this guard exists to
     * prevent, one ADR away from being live; and (b) {@code ToolRegistry}'s own javadoc already
     * states the rule — those names are declared precisely so "the reservation authority covers the
     * whole kernel vocabulary rather than only the handful that happens to be live". Reserving an
     * unregistered name costs one refused tool name; not reserving it costs a forged verb.
     *
     * <p>Membership is asserted <b>against {@link ToolRegistry#declaredNames()}</b>, not against a
     * list typed here, so adding a tool to the table extends the reserved set with no edit to this
     * file — and so removing one cannot leave this test asserting a name the table no longer knows.
     */
    @Test
    public void theReservedSetIsExactlyTheDeclaredVocabulary() {
        Driven d = drivenWithCreateToolPromoted();
        try {
            Set<String> declared = new TreeSet<>(ToolRegistry.declaredNames());
            Set<String> registeredNotReserved = new TreeSet<>();

            for (String name : declared) {
                CallToolResult res = d.surface().invoke("create_tool", createArgs(name, "gen.Pin"));
                if (!refusedAsReserved(res)) {
                    registeredNotReserved.add(name);
                }
            }
            assertTrue("every name ToolRegistry declares must be reserved, or a model can install "
                            + "its own handler under a name the hypervisor owns. NOT reserved: "
                            + registeredNotReserved + ". Declared: " + declared,
                    registeredNotReserved.isEmpty());

            // The counterweight: the reserved set must not be "everything". Names nobody declared
            // are the whole point of create_tool, so a set that grew to include them would be a
            // guard that refuses all work.
            for (String fresh : new String[] {"totally_new_tool_a", "another_fresh_tool_b",
                    "unheard_of_tool_c"}) {
                assertFalse("precondition: '" + fresh + "' must be undeclared for the allow "
                                + "direction to be testing anything",
                        declared.contains(fresh));
                CallToolResult res = d.surface().invoke("create_tool",
                        createArgs(fresh, "gen.Fresh" + Math.abs(fresh.hashCode())));
                assertFalse("'" + fresh + "' is a genuinely new name and must NOT be reserved; "
                                + "create_tool answered: " + (res == null ? "null" : res.content()),
                        refusedAsReserved(res));
            }
            assertTrue("the fresh names above must actually have been installed, or this test "
                            + "never exercised the allow path",
                    d.surface().get("totally_new_tool_a") != null);

        } finally {
            d.exec().shutdown();
        }
    }

    /**
     * THE TWELVE FOLDS, named and reasoned rather than left inside a set union.
     *
     * <p>Separate from the membership pin because the reasoning is a decision, not a derivation:
     * these names are reserved even though <em>nothing registers them</em>. That is a behaviour
     * change beyond a name filter — before it, {@code create_tool} would have accepted
     * {@code toolName:"debug_suspend_thread"} — and it is pinned here so the decision cannot be
     * reversed silently by somebody tidying the union down to "registered built-ins only".
     *
     * <p>The list is read from {@link DebugTools}'s own constants rather than typed here, so it
     * cannot drift from the folds ADR-0004 actually defines.
     */
    @Test
    public void theUnregisteredDebugFoldsAreReserved() {
        Driven d = drivenWithCreateToolPromoted();
        try {
            // Precondition, and the reason this is a decision rather than a derivation: with L6
            // off, these eleven names are declared kernel-layered and registered by NOTHING, so
            // the audited-registry term alone cannot see them.
            for (String action : DebugTools.MANAGE_ACTIONS) {
                assertNull(action + " is an ADR-0004 fold: declared kernel-layered but registered "
                                + "by nothing, which is why the reserved set cannot be "
                                + "'every audited built-in'",
                        d.audited().get(action));
            }
            for (String action : DebugTools.MANAGE_ACTIONS) {
                CallToolResult res = d.surface().invoke("create_tool",
                        createArgs(action, "gen.Fold" + Math.abs(action.hashCode())));
                assertTrue(action + " is declared kernel-layered and must be reserved even though "
                                + "nothing registers it: a squat here would be a tool an operator "
                                + "reads in list_capabilities as the JVMTI verb. create_tool "
                                + "answered: " + (res == null ? "null" : res.content()),
                        refusedAsReserved(res));
            }
            for (String action : DebugTools.HANDLE_ACTIONS) {
                assertNull(action + " is folded behind debug_handle and registered by nothing here",
                        d.audited().get(action));
                CallToolResult res = d.surface().invoke("create_tool",
                        createArgs(action, "gen.Handle" + Math.abs(action.hashCode())));
                assertTrue(action + " is a declared kernel-layered fold and must be reserved; "
                                + "create_tool answered: " + (res == null ? "null" : res.content()),
                        refusedAsReserved(res));
            }
        } finally {
            d.exec().shutdown();
        }
    }
}