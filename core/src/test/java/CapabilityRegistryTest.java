import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.ToolStats;
import org.junit.Test;

/**
 * Tests the self-heal core: the supervised executor's circuit breaker and the
 * registry's versioning/rollback. These run without a game or a live MCP server
 * (server is null → registry works in "local" mode). Guards the "one bad tool
 * can't crash the system" invariant.
 */
public class CapabilityRegistryTest {

    private static SyncToolSpecification tool(String name,
            java.util.function.BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
                    io.modelcontextprotocol.spec.McpSchema.CallToolRequest, CallToolResult> handler) {
        Tool t = Tool.builder().name(name).description("test " + name)
                .inputSchema(java.util.Map.of("type", "object", "properties", java.util.Map.of()))
                .build();
        return new SyncToolSpecification(t, handler);
    }

    @Test
    public void circuitBreakerTripsAfterRepeatedFailures() {
        ToolStats stats = new ToolStats("boom");
        assertEquals(ToolStats.Circuit.CLOSED, stats.circuit());
        stats.recordFailure("e1", false);
        stats.recordFailure("e2", false);
        assertEquals("still closed after 2", ToolStats.Circuit.CLOSED, stats.circuit());
        stats.recordFailure("e3", false);
        assertEquals("open after 3", ToolStats.Circuit.OPEN, stats.circuit());
        assertFalse("open breaker blocks calls", stats.allowCall());
    }

    @Test
    public void successResetsConsecutiveFailures() {
        ToolStats stats = new ToolStats("flaky");
        stats.recordFailure("e1", false);
        stats.recordFailure("e2", false);
        stats.recordSuccess();
        stats.recordFailure("e3", false);
        stats.recordFailure("e4", false);
        // 2 fresh failures after the reset — should still be closed.
        assertEquals(ToolStats.Circuit.CLOSED, stats.circuit());
    }

    @Test
    public void thrownToolFaultTripsBreaker_butRunawayRejectionIsFailFast() {
        // A tool that THROWS (not returns isError) must count as a fault so the
        // breaker can eventually quarantine it — the self-heal for AI tools.
        IoSupervisor exec = new IoSupervisor(4, 1000L);
        ToolStats stats = new ToolStats("throws");
        for (int i = 0; i < 3; i++) {
            exec.run(stats, (ex, req) -> { throw new IllegalStateException("boom"); }, null, null, 0);
        }
        assertEquals("3 thrown faults trip the breaker", ToolStats.Circuit.OPEN, stats.circuit());
        exec.shutdown();
    }

    @Test
    public void negativeCountDoesNotThrow() {
        // Finding #6: negative n must not throw (would be mis-counted as a fault).
        net.marcloud.mcp.core.drivers.world.PacketLog log = new net.marcloud.mcp.core.drivers.world.PacketLog(8);
        log.recordInbound("A");
        log.recordOutbound("B");
        assertTrue("negative n yields empty, not an exception", log.recent(-5).isEmpty());
        assertEquals(2, log.recent(50).size());
    }

    @Test
    public void executorContainsAThrowingTool() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        ToolStats stats = new ToolStats("thrower");
        CallToolResult r = exec.run(stats,
                (ex, req) -> { throw new RuntimeException("boom"); },
                null, null, 0);
        assertTrue("throwing tool yields an error result, not a crash", Boolean.TRUE.equals(r.isError()));
        assertEquals(1, stats.failures());
        exec.shutdown();
    }

    @Test
    public void executorTimesOutAHangingTool() {
        IoSupervisor exec = new IoSupervisor(2, 300L);
        ToolStats stats = new ToolStats("hang");
        CallToolResult r = exec.run(stats, (ex, req) -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            return CallToolResult.builder().addTextContent("never").build();
        }, null, null, 0);
        assertTrue("hanging tool times out to an error", Boolean.TRUE.equals(r.isError()));
        assertEquals(1, stats.timeouts());
        exec.shutdown();
    }

    @Test
    public void domainErrorResultDoesNotTripBreaker() {
        // Critical invariant: a tool returning isError=true (validation/compile
        // rejection, e.g. create_tool on bad source) must NOT trip the breaker,
        // else the AI's self-extension loop would quarantine create_tool after a
        // few compile errors. Only thrown exceptions / timeouts are faults.
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        ToolStats stats = new ToolStats("validator");
        for (int i = 0; i < 5; i++) {
            CallToolResult r = exec.run(stats,
                    (ex, req) -> CallToolResult.builder().addTextContent("bad args").isError(true).build(),
                    null, null, 0);
            assertTrue(Boolean.TRUE.equals(r.isError()));
        }
        assertEquals("domain-error results must not count as failures", 0, stats.failures());
        assertEquals("breaker stays closed", ToolStats.Circuit.CLOSED, stats.circuit());
        assertTrue(stats.allowCall());
        exec.shutdown();
    }

    @Test
    public void registryVersionsAndRollsBack() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        IoManager reg = new IoManager(exec,
                new net.marcloud.mcp.core.se.SeClearancePolicy(
                        net.marcloud.mcp.core.se.Ring.R_MINUS_1, "t"));
        reg.register("t", tool("t", (e, r) -> CallToolResult.builder().addTextContent("v1").build()),
                "src-v1", "d1", false, net.marcloud.mcp.core.se.Ring.R2);
        assertEquals(1, reg.get("t").version());
        reg.register("t", tool("t", (e, r) -> CallToolResult.builder().addTextContent("v2").build()),
                "src-v2", "d2", false, net.marcloud.mcp.core.se.Ring.R2);
        assertEquals(2, reg.get("t").version());
        assertEquals("src-v2", reg.get("t").source());
        assertTrue(reg.rollback("t"));
        assertEquals("rolled back to v1 source", "src-v1", reg.get("t").source());
        exec.shutdown();
    }
}
