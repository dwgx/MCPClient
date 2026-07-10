import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.registry.SafeToolExecutor;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.ToolPolicy;
import org.junit.Test;

/**
 * Regression guards for the two CRITICAL audit findings on generated tools:
 *
 * <ul>
 *   <li>CRITICAL#1 — a generated (non-builtIn) tool must carry the maximal gate
 *       (R-1 + SYSTEM integrity + SE_RUN_GENERATED privilege), UNCONDITIONALLY,
 *       so a lowered clearance actually locks it out. Before the fix it ran at
 *       R2 with no privilege gate.</li>
 *   <li>CRITICAL#2 — a generated tool must never be able to replace a built-in
 *       (name-based policy would otherwise run attacker code under the built-in's
 *       gate).</li>
 * </ul>
 *
 * These are LIVE tests through the real registry gate, not vacuous stubs.
 */
public class GeneratedToolGateTest {

    private static SyncToolSpecification tool(String name,
            java.util.function.BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> h) {
        Tool t = Tool.builder().name(name).description("t " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t, h);
    }

    private static CallToolResult okTool(McpSyncServerExchange e, CallToolRequest r) {
        return CallToolResult.builder().addTextContent("ran").isError(false).build();
    }

    /**
     * CRITICAL#1 — the policy for ANY non-builtIn tool is the maximal gate,
     * regardless of the name (even an unlisted name that no side table knows).
     */
    @Test
    public void generatedToolPolicyIsAlwaysMaximalGate() {
        ToolPolicy tp = ToolPolicy.forTool("some_generated_name_not_in_any_table", false);
        assertEquals("generated tool sits at hypervisor ring",
                Ring.R_MINUS_1, tp.requiredRing());
        assertEquals("generated tool writes at SYSTEM integrity",
                net.marcloud.mcp.core.security.IntegrityLevel.SYSTEM, tp.writesResourceAt());
        assertEquals("generated tool requires SE_RUN_GENERATED",
                net.marcloud.mcp.core.security.Privilege.SE_RUN_GENERATED, tp.requiredPrivilege());
        assertTrue("generated tool requires the tool-create capability",
                tp.requiredCaps().contains(
                        net.marcloud.mcp.core.security.CapabilitySid.CAP_TOOL_CREATE));
    }

    /**
     * CRITICAL#1 — end-to-end: a generated tool that would have run at R2 is
     * DENIED once clearance drops below R-1, through the real supervised gate.
     */
    @Test
    public void generatedToolDeniedAfterClearanceDrop() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "secret");
        InProcessPolicyEngine engine = new InProcessPolicyEngine(p);
        CapabilityRegistry reg = new CapabilityRegistry(exec, engine);
        // A generated (builtIn=false) tool, registered at the generated default ring.
        reg.register("gen.evil", tool("gen.evil", GeneratedToolGateTest::okTool),
                "public class Evil {}", "generated", false, Ring.DEFAULT_GENERATED);

        // At wide-open R-1 with all privileges enabled, it runs.
        assertFalse("generated tool runs at R-1",
                Boolean.TRUE.equals(reg.invoke("gen.evil", Map.of()).isError()));

        // Drop to R2 — the ring a generated tool used to run at. Now denied.
        engine.dropTo(Ring.R2);
        CallToolResult denied = reg.invoke("gen.evil", Map.of());
        assertTrue("generated tool denied at R2 clearance",
                Boolean.TRUE.equals(denied.isError()));
    }

    /**
     * CRITICAL#2 — register() rejects a generated tool trying to squat a built-in
     * name. The built-in stays intact.
     */
    @Test
    public void generatedToolCannotReplaceBuiltin() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        CapabilityRegistry reg = new CapabilityRegistry(exec,
                new InProcessPolicyEngine(new PermissionPolicy(Ring.R_MINUS_1, "tok")));
        // A built-in occupies the name (as the real registrars do at startup).
        reg.register("memory_search", tool("memory_search", GeneratedToolGateTest::okTool),
                null, "builtin", true, Ring.R3);

        try {
            reg.register("memory_search", tool("memory_search", GeneratedToolGateTest::okTool),
                    "public class Evil {}", "generated", false, Ring.DEFAULT_GENERATED);
            org.junit.Assert.fail("expected IllegalArgumentException replacing a built-in");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("built-in"));
        }
        // The built-in is untouched (still built-in, v1).
        assertTrue("built-in survived", reg.isBuiltin("memory_search"));
        assertEquals(1, reg.get("memory_search").version());
    }

    /**
     * A built-in may still be replaced by another built-in (redefine/upgrade of a
     * core tool) — the guard only blocks generated-over-builtin.
     */
    @Test
    public void builtinCanBeReplacedByBuiltin() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        CapabilityRegistry reg = new CapabilityRegistry(exec,
                new InProcessPolicyEngine(new PermissionPolicy(Ring.R_MINUS_1, "tok")));
        reg.register("list_hooks", tool("list_hooks", GeneratedToolGateTest::okTool),
                null, "v1", true, Ring.R3);
        reg.register("list_hooks", tool("list_hooks", GeneratedToolGateTest::okTool),
                null, "v2", true, Ring.R3);
        assertEquals(2, reg.get("list_hooks").version());
    }

    /**
     * HIGH#4 — eval_java now carries an L4 privilege (SE_CREATE_TOOL). A subject
     * that keeps R-1 + integrity but has that privilege disabled is denied.
     */
    @Test
    public void evalJavaRequiresPrivilege() {
        ToolPolicy tp = ToolPolicy.forTool("eval_java", true);
        assertEquals("eval_java requires SE_CREATE_TOOL",
                net.marcloud.mcp.core.security.Privilege.SE_CREATE_TOOL, tp.requiredPrivilege());
    }

    /**
     * LOW#16 — the four narrative mutators now write at LOW integrity, so an
     * UNTRUSTED subject can no longer modify story/goal state.
     */
    @Test
    public void narrativeMutatorsWriteAtLowIntegrity() {
        for (String name : new String[] {"set_goal", "push_subgoal", "complete_goal", "narrate"}) {
            ToolPolicy tp = ToolPolicy.forTool(name, true);
            assertEquals(name + " writes at LOW integrity",
                    net.marcloud.mcp.core.security.IntegrityLevel.LOW, tp.writesResourceAt());
        }
    }
}

