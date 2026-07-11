import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.PrivilegeToken;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToken;
import org.junit.Test;

/**
 * AUTOMATABLE. Drives the L3/L4/L5 layers through the <b>supervised</b>
 * {@code registry.invoke(...)} call path — proving {@code
 * IoManager.supervise()} runs the FULL 7-layer decision, not just the L2
 * ring. {@code SecurityKernelTest} exercises these layers against the engine
 * directly; {@code SupervisedGateIntegrationTest} exercises L2-drop + L7 through
 * the registry. This closes the remaining gap: L3/L4/L5 denials must also be
 * enforced end-to-end at the {@code invoke} boundary a client / REST facade uses.
 *
 * <p>Every test is non-vacuous: the registered handler returns the literal
 * {@code "ran"} if it ever executes, so a supervised gate that only checked the
 * ring (all these tools are R2, clearance is R-1) would let the call through and
 * the assertion on {@code isError} would fail.
 */
public class SupervisedGateL4L5DenyTest {

    private static SyncToolSpecification tool(String name) {
        Tool t = Tool.builder().name(name).description("t " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t, SupervisedGateL4L5DenyTest::ranTool);
    }

    private static CallToolResult ranTool(McpSyncServerExchange e, CallToolRequest r) {
        return CallToolResult.builder().addTextContent("ran").isError(false).build();
    }

    /**
     * L5: a strict subject holding only CAP_WORLD_READ is denied capture_screen
     * (requires CAP_SCREEN_CAP) at the supervised boundary, while scan_surroundings
     * (requires only CAP_WORLD_READ) still runs. Both tools are R2, clearance is
     * R-1 — so a ring-only gate would run both.
     */
    @Test
    public void l5CapabilityDenyIsEnforcedThroughSupervisedInvoke() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        SeToken strict = SeLocalMonitor.strictSubject(
                java.util.Set.of(CapabilitySid.CAP_WORLD_READ));
        IoManager reg = new IoManager(exec, new SeLocalMonitor(p, strict));
        reg.register("capture_screen", tool("capture_screen"), null, "d", true, Ring.R2);
        reg.register("scan_surroundings", tool("scan_surroundings"), null, "d", true, Ring.R2);

        CallToolResult denied = reg.invoke("capture_screen", Map.of());
        // isError==true is itself proof the handler never ran: the handler returns
        // isError=false with body "ran", so a passed-through call could not be an error.
        assertTrue("capture_screen denied at L5 (missing CAP_SCREEN_CAP)",
                Boolean.TRUE.equals(denied.isError()));
        assertTrue("deny names the capability layer",
                denied.content().toString().contains("L5 capability"));

        CallToolResult allowed = reg.invoke("scan_surroundings", Map.of());
        assertFalse("scan_surroundings runs under the held CAP_WORLD_READ",
                Boolean.TRUE.equals(allowed.isError()));
        assertTrue("allowed handler actually ran", allowed.content().toString().contains("ran"));
        exec.shutdown();
    }

    /**
     * L4: a subject with wildcard capabilities and SYSTEM integrity but the
     * SE_SCREEN_CAP privilege GRANTED-yet-DISABLED is denied capture_screen at L4.
     * Isolates the privilege layer from L5 (caps wildcard) and L3 (SYSTEM writes
     * everything) and L2 (R-1 clearance clears the R2 ring).
     */
    @Test
    public void l4PrivilegeDisabledDenyIsEnforcedThroughSupervisedInvoke() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        // Grant every privilege but leave SE_SCREEN_CAP disabled.
        java.util.Map<Privilege, Boolean> grants = new java.util.EnumMap<>(Privilege.class);
        for (Privilege pr : Privilege.values()) {
            grants.put(pr, true);
        }
        grants.put(Privilege.SE_SCREEN_CAP, false);
        SeToken subj = new SeToken("t", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                new PrivilegeToken(grants), null); // null caps = wildcard, so L5 passes
        IoManager reg = new IoManager(exec, new SeLocalMonitor(p, subj));
        reg.register("capture_screen", tool("capture_screen"), null, "d", true, Ring.R2);

        CallToolResult denied = reg.invoke("capture_screen", Map.of());
        // isError==true proves the handler (isError=false) never ran.
        assertTrue("capture_screen denied at L4 (SE_SCREEN_CAP disabled)",
                Boolean.TRUE.equals(denied.isError()));
        assertTrue("deny names the privilege layer",
                denied.content().toString().contains("L4 privilege"));
        exec.shutdown();
    }

    /**
     * L3: a MEDIUM-integrity subject cannot run send_chat (writes a HIGH-integrity
     * resource) even at R-1 with all privileges + wildcard caps — no-write-up
     * catches it through the supervised boundary.
     */
    @Test
    public void l3IntegrityDenyIsEnforcedThroughSupervisedInvoke() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        SeToken medium = new SeToken("t", Ring.R_MINUS_1, IntegrityLevel.MEDIUM,
                PrivilegeToken.wideOpen(), null);
        IoManager reg = new IoManager(exec, new SeLocalMonitor(p, medium));
        reg.register("send_chat", tool("send_chat"), null, "d", true, Ring.R1);

        CallToolResult denied = reg.invoke("send_chat", Map.of());
        // isError==true proves the handler (isError=false) never ran.
        assertTrue("send_chat denied at L3 (MEDIUM cannot write HIGH)",
                Boolean.TRUE.equals(denied.isError()));
        assertTrue("deny names the integrity layer",
                denied.content().toString().contains("L3 integrity"));
        exec.shutdown();
    }
}
