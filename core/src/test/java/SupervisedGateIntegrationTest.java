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
import org.junit.Test;

/**
 * End-to-end through the SUPERVISED gate: confirms the reference-monitor
 * decision is what actually runs in CapabilityRegistry.supervise() (not just the
 * standalone engine), and that the drop/restore clearance path still gates a real
 * tool invocation via registry.invoke(). This is the keystone-migration
 * regression guard.
 */
public class SupervisedGateIntegrationTest {

    private static SyncToolSpecification tool(String name,
            java.util.function.BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> h) {
        Tool t = Tool.builder().name(name).description("t " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t, h);
    }

    private static CallToolResult okTool(McpSyncServerExchange e, CallToolRequest r) {
        return CallToolResult.builder().addTextContent("ran").isError(false).build();
    }

    @Test
    public void wideOpenClearanceRunsAHypervisorTool() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "tok");
        CapabilityRegistry reg = new CapabilityRegistry(exec, new InProcessPolicyEngine(p));
        // Register a tool under the eval_java name (R-1) — must run at R-1 clearance.
        reg.register("eval_java", tool("eval_java", SupervisedGateIntegrationTest::okTool),
                null, "d", true, Ring.R_MINUS_1);
        CallToolResult r = reg.invoke("eval_java", Map.of());
        assertFalse("allowed at R-1", Boolean.TRUE.equals(r.isError()));
        exec.shutdown();
    }

    @Test
    public void droppedClearanceDeniesThroughSupervisedGate() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "secret");
        InProcessPolicyEngine engine = new InProcessPolicyEngine(p);
        CapabilityRegistry reg = new CapabilityRegistry(exec, engine);
        reg.register("eval_java", tool("eval_java", SupervisedGateIntegrationTest::okTool),
                null, "d", true, Ring.R_MINUS_1);
        reg.register("scan_surroundings",
                tool("scan_surroundings", SupervisedGateIntegrationTest::okTool),
                null, "d", true, Ring.R2);

        // Drop to R2 through the engine (same authority the gate reads).
        engine.dropTo(Ring.R2);

        CallToolResult denied = reg.invoke("eval_java", Map.of());
        assertTrue("eval_java denied after drop", Boolean.TRUE.equals(denied.isError()));
        assertTrue("names the layer", denied.content().toString().contains("L2 ring")
                || denied.content().toString().toLowerCase().contains("permission denied"));

        CallToolResult allowed = reg.invoke("scan_surroundings", Map.of());
        assertFalse("observe tool still allowed at R2", Boolean.TRUE.equals(allowed.isError()));

        // Restore with the token → eval_java works again.
        assertTrue(engine.tryRestore(Ring.R_MINUS_1, "secret"));
        CallToolResult back = reg.invoke("eval_java", Map.of());
        assertFalse("eval_java allowed after restore", Boolean.TRUE.equals(back.isError()));
        exec.shutdown();
    }

    @Test
    public void boundaryValidationRejectsMissingRequiredArgThroughGate() {
        // Confirms L7 is LIVE end-to-end: the SDK Tool's inputSchema is recovered
        // as a Map and a missing required arg is rejected as a domain error before
        // the handler runs (handler would have returned "ran").
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        CapabilityRegistry reg = new CapabilityRegistry(exec,
                new InProcessPolicyEngine(new PermissionPolicy(Ring.R_MINUS_1, "tok")));
        Tool t = Tool.builder().name("needs_name").description("d")
                .inputSchema(Map.of("type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", java.util.List.of("name")))
                .build();
        reg.register("needs_name",
                new SyncToolSpecification(t, SupervisedGateIntegrationTest::okTool),
                null, "d", true, Ring.R3);

        CallToolResult missing = reg.invoke("needs_name", Map.of());
        assertTrue("missing required arg rejected", Boolean.TRUE.equals(missing.isError()));
        assertTrue(missing.content().toString().contains("name"));

        CallToolResult okCall = reg.invoke("needs_name", Map.of("name", "x"));
        assertFalse("valid args pass", Boolean.TRUE.equals(okCall.isError()));
        exec.shutdown();
    }

    @Test
    public void isAllowedReflectsFullDecision() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        PermissionPolicy p = new PermissionPolicy(Ring.R2, "tok");
        CapabilityRegistry reg = new CapabilityRegistry(exec, new InProcessPolicyEngine(p));
        reg.register("eval_java", tool("eval_java", SupervisedGateIntegrationTest::okTool),
                null, "d", true, Ring.R_MINUS_1);
        reg.register("recent_packets",
                tool("recent_packets", SupervisedGateIntegrationTest::okTool),
                null, "d", true, Ring.R3);
        assertFalse("R-1 tool not allowed at R2 clearance",
                reg.isAllowed(reg.get("eval_java")));
        assertTrue("R3 tool allowed at R2 clearance",
                reg.isAllowed(reg.get("recent_packets")));
        exec.shutdown();
    }
}
