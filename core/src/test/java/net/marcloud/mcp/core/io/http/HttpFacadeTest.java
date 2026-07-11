package net.marcloud.mcp.core.io.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
 * Security regression for the REST facade auth gate (repo-gap-survey finding #1):
 * the HTTP facade routes to the same supervised registry that can run R-1
 * arbitrary code (eval_java, redefine_class, C5/C6), so it must not answer
 * unauthenticated requests once a token is configured, and it must gate ALL
 * routes — including the read-only ones — not just tool invocation.
 *
 * <p>These would all FAIL on the pre-fix HttpFacade, which had no Authorization
 * check on any route.
 */
public class HttpFacadeTest {

    private static SyncToolSpecification tool(String name) {
        Tool t = Tool.builder().name(name).description("t " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
        return new SyncToolSpecification(t,
                (McpSyncServerExchange e, CallToolRequest r) ->
                        CallToolResult.builder().addTextContent("ran").isError(false).build());
    }

    private static IoManager registry(IoSupervisor exec) {
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
        reg.bindServer(null);
        reg.register("gen.ping", tool("gen.ping"), "src", "d", false, Ring.DEFAULT_GENERATED);
        return reg;
    }

    private static HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void withTokenSetEveryRouteIs401WithoutBearer() throws Exception {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        HttpFacade f = new HttpFacade(registry(exec), "127.0.0.1", 0, "s3cr3t");
        f.start();
        try {
            int port = f.boundPort();
            // Read routes AND the invoke route must all be gated.
            for (String path : new String[] {"/", "/v1/models", "/v1/tools", "/v1/permissions"}) {
                assertEquals("unauthenticated " + path + " must be 401",
                        401, get(port, path, null).statusCode());
                assertEquals("wrong token on " + path + " must be 401",
                        401, get(port, path, "nope").statusCode());
            }
        } finally {
            f.stop();
            exec.shutdown();
        }
    }

    @Test
    public void withTokenSetCorrectBearerPasses() throws Exception {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        HttpFacade f = new HttpFacade(registry(exec), "127.0.0.1", 0, "s3cr3t");
        f.start();
        try {
            HttpResponse<String> r = get(f.boundPort(), "/v1/models", "s3cr3t");
            assertEquals(200, r.statusCode());
            assertTrue("catalog should list our tool", r.body().contains("gen.ping"));
        } finally {
            f.stop();
            exec.shutdown();
        }
    }

    @Test
    public void noTokenMeansOpenLoopbackPosture() throws Exception {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        HttpFacade f = new HttpFacade(registry(exec), "127.0.0.1", 0); // no token → dev default
        f.start();
        try {
            assertEquals("loopback dev posture: no auth required",
                    200, get(f.boundPort(), "/v1/models", null).statusCode());
        } finally {
            f.stop();
            exec.shutdown();
        }
    }
}
