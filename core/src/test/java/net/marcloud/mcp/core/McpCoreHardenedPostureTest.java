package net.marcloud.mcp.core;

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
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeReferenceMonitor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * HEADLESS. Proves the opt-in {@code -Dmcp.core.hardened=true} flag wires a
 * subject through {@link McpCore#buildEngine} that BITES at the less-tested L4 and
 * L5 layers while the shipped wide-open default (flag unset) still runs a
 * hypervisor tool. Everything is driven through the SUPERVISED
 * {@link IoManager#invoke} boundary a real client / REST facade uses, so this
 * proves the flag reaches the actual gate, not just a factory in isolation.
 *
 * <p>Non-vacuous + FAILS ON OLD CODE: the stub handler returns the literal
 * {@code "ran"} with {@code isError=false}, so an {@code isError==true} result is
 * itself proof the handler never executed. On the pre-hardening code there is no
 * hardened branch (and no {@code hardenedSubject()} factory), so {@code
 * buildEngine} would fall through to {@link net.marcloud.mcp.core.se.SeToken#wideOpen()}
 * and eval_java would RUN — failing the L4 deny assertion (and the file would not
 * even compile against the old API, which is the stronger regression signal).
 */
public class McpCoreHardenedPostureTest {

    private static final String HARDENED = "mcp.core.hardened";

    private String savedHardened;
    private String savedPsecure;
    private String savedCaps;

    @Before
    public void saveProps() {
        savedHardened = System.getProperty(HARDENED);
        // buildEngine consults these two before the hardened branch; pin them off
        // so this test is hermetic regardless of the ambient environment.
        savedPsecure = System.getProperty("mcp.core.psecure");
        savedCaps = System.getProperty("mcp.core.caps");
        System.clearProperty("mcp.core.psecure");
        System.clearProperty("mcp.core.caps");
    }

    @After
    public void restoreProps() {
        restore(HARDENED, savedHardened);
        restore("mcp.core.psecure", savedPsecure);
        restore("mcp.core.caps", savedCaps);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static SyncToolSpecification tool(String name) {
        Tool t = Tool.builder().name(name).description("t " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t, McpCoreHardenedPostureTest::ranTool);
    }

    private static CallToolResult ranTool(McpSyncServerExchange e, CallToolRequest r) {
        return CallToolResult.builder().addTextContent("ran").isError(false).build();
    }

    /**
     * With the flag on, buildEngine builds the hardened subject: eval_java is
     * denied at L4 (SE_CREATE_TOOL granted-but-disabled), recent_packets is denied
     * at L5 (CAP_NETWORK_RECV_TAP not held), and a benign R3/no-capability tool
     * (memory_search) still runs — all through the supervised invoke boundary.
     */
    @Test
    public void hardenedFlagDeniesDangerousVerbsButRunsBenignTool() {
        System.setProperty(HARDENED, "true");
        SeReferenceMonitor engine = McpCore.buildEngine(
                new SeClearancePolicy(Ring.R_MINUS_1, "t"), null);
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        IoManager reg = new IoManager(exec, engine);
        reg.register("eval_java", tool("eval_java"), "src", "d", true, Ring.R_MINUS_1);
        reg.register("recent_packets", tool("recent_packets"), null, "d", true, Ring.R3);
        reg.register("memory_search", tool("memory_search"), null, "d", true, Ring.R3);

        CallToolResult evalDenied = reg.invoke("eval_java", Map.of());
        assertTrue("eval_java denied under hardened posture",
                Boolean.TRUE.equals(evalDenied.isError()));
        assertTrue("eval_java deny names the privilege layer: " + evalDenied.content(),
                evalDenied.content().toString().contains("L4 privilege"));

        CallToolResult packetsDenied = reg.invoke("recent_packets", Map.of());
        assertTrue("recent_packets denied under hardened posture",
                Boolean.TRUE.equals(packetsDenied.isError()));
        assertTrue("recent_packets deny names the capability layer: " + packetsDenied.content(),
                packetsDenied.content().toString().contains("L5 capability"));

        CallToolResult benign = reg.invoke("memory_search", Map.of());
        assertFalse("benign R3 no-capability tool still runs under hardened posture",
                Boolean.TRUE.equals(benign.isError()));
        assertTrue("benign handler actually ran", benign.content().toString().contains("ran"));
        exec.shutdown();
    }

    /**
     * Guards "defaults unchanged": with the flag cleared, buildEngine returns the
     * shipped wide-open engine, and eval_java RUNS through the same supervised
     * boundary. This is the assertion the old code path (which has no hardened
     * branch) also satisfies — pinning the default so the hardened branch can never
     * silently become the default.
     */
    @Test
    public void defaultPostureIsWideOpenAndRunsEvalJava() {
        System.clearProperty(HARDENED);
        SeReferenceMonitor engine = McpCore.buildEngine(
                new SeClearancePolicy(Ring.R_MINUS_1, "t"), null);
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        IoManager reg = new IoManager(exec, engine);
        reg.register("eval_java", tool("eval_java"), "src", "d", true, Ring.R_MINUS_1);

        CallToolResult ran = reg.invoke("eval_java", Map.of());
        assertFalse("default posture runs eval_java wide open: " + ran.content(),
                Boolean.TRUE.equals(ran.isError()));
        assertTrue("eval_java handler actually ran under the default", ran.content().toString().contains("ran"));
        exec.shutdown();
    }
}
