import static org.junit.Assert.assertEquals;

import net.marcloud.mcp.core.io.transport.SocketTransportServer;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Ring;
import org.junit.Test;

/**
 * GAP-3 regression: after a socket client disconnects, SocketTransportServer.
 * closeCurrent() now calls {@code registry.bindServer(null)}. This test pins the
 * registry contract that fix relies on — with no bound server, register() must
 * take the in-memory-only commit path and still commit, rather than throwing.
 *
 * <p>Before the fix, closeCurrent() left the registry pointing at a CLOSED
 * McpSyncServer, so a create_tool/register between disconnect and the next
 * connect called addTool on a closed server, threw, and committed nothing. We
 * can't cheaply stand up a real closed McpSyncServer here, so we assert the
 * load-bearing invariant directly: register with a null server commits.
 */
public class StaleServerRebindTest {

    private static SyncToolSpecification tool(String name) {
        Tool t = Tool.builder().name(name).description("t " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t,
                (McpSyncServerExchange e, CallToolRequest r) ->
                        CallToolResult.builder().addTextContent("ran").isError(false).build());
    }

    @Test
    public void registerCommitsInMemoryWhenNoServerBound() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));

        // Simulate the post-disconnect state: no server bound (bindServer(null)).
        reg.bindServer(null);

        // A generated tool registers with no live server → in-memory-only commit.
        reg.register("gen.after_disconnect", tool("gen.after_disconnect"),
                "public class X {}", "generated", false, Ring.DEFAULT_GENERATED);

        assertNotNull("tool committed in-memory despite no bound server",
                reg.get("gen.after_disconnect"));
        assertEquals(1, reg.get("gen.after_disconnect").version());

        // And it is invocable through the supervised gate (at R-1 wide-open).
        CallToolResult r = reg.invoke("gen.after_disconnect", Map.of());
        assertFalse("invocable after in-memory commit", Boolean.TRUE.equals(r.isError()));
        exec.shutdown();
    }

    @Test
    public void rebindAfterNullStillWorks() {
        // bindServer(null) then a later bindServer(realServer) must not corrupt
        // state — mirrors disconnect → reconnect. We only exercise the null leg +
        // re-null here (no real server), proving repeated unbinds are safe.
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
        reg.bindServer(null);
        reg.register("gen.one", tool("gen.one"), "src", "d", false, Ring.DEFAULT_GENERATED);
        reg.bindServer(null); // idempotent unbind (a second disconnect)
        reg.register("gen.two", tool("gen.two"), "src", "d", false, Ring.DEFAULT_GENERATED);
        assertNotNull(reg.get("gen.one"));
        assertNotNull(reg.get("gen.two"));
        exec.shutdown();
    }
}
