package net.marcloud.mcp.core.io.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;

import org.junit.Test;

/**
 * Teeth for the A.10 SSE feed ({@code GET /v1/stream}). Proves a live event
 * published on the EventBus is pushed to a connected client as an SSE frame, that
 * the {@code kinds} filter excludes non-matching events, that a disconnect removes
 * the bus subscription (no leak), and that the route inherits the facade's bearer
 * auth. All fail on a codebase without the stream route/wiring.
 */
public class SseStreamTest {

    private static IoManager registry(IoSupervisor exec) {
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
        reg.bindServer(null);
        return reg;
    }

    @Test
    public void publishedEventIsPushedAsSseFrameAndUnsubscribedOnClose() throws Exception {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        EventBus bus = new EventBus();
        HttpFacade facade = new HttpFacade(registry(exec), "127.0.0.1", 0, null, bus);
        facade.start();
        int port = facade.boundPort();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<java.io.InputStream> resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/stream"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, resp.statusCode());
            assertTrue("SSE content type",
                    resp.headers().firstValue("content-type").orElse("").contains("text/event-stream"));

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8));

            // wait until the subscription is actually registered before publishing,
            // else the event fires before the serving thread subscribed (race).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (bus.subscriberCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("stream subscribed to the bus", 1, bus.subscriberCount());

            bus.publish(new TickEvent(4242L));

            // read frames until we see the tick (skip the hello frame / keep-alives)
            String tickData = readDataContaining(r, "4242", 5000);
            assertTrue("tick event pushed as a frame carrying its tickId: " + tickData,
                    tickData != null && tickData.contains("4242")
                            && tickData.contains("\"kind\":\"tick\""));

            // closing the client stream must remove the subscription (no leak)
            resp.body().close();
            r.close();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (bus.subscriberCount() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals("disconnect unsubscribed the stream from the bus",
                    0, bus.subscriberCount());
        } finally {
            facade.stop();
            exec.shutdown();
        }
    }

    @Test
    public void kindsFilterExcludesNonMatchingEvents() throws Exception {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        EventBus bus = new EventBus();
        HttpFacade facade = new HttpFacade(registry(exec), "127.0.0.1", 0, null, bus);
        facade.start();
        int port = facade.boundPort();
        try {
            HttpClient client = HttpClient.newHttpClient();
            // ask ONLY for packet kind; a TickEvent must not come through
            HttpResponse<java.io.InputStream> resp = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/v1/stream?kinds=packet")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (bus.subscriberCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            bus.publish(new TickEvent(999L));
            // should NOT see the tick within a short window (filter drops it)
            String tick = readDataContaining(r, "999", 1500);
            assertTrue("tick must be filtered out when kinds=packet", tick == null);
            resp.body().close();
            r.close();
        } finally {
            facade.stop();
            exec.shutdown();
        }
    }

    /** Read SSE {@code data:} lines until one contains {@code needle} or timeout; null on timeout. */
    private static String readDataContaining(BufferedReader r, String needle, long timeoutMs)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (!r.ready()) {
                Thread.sleep(20);
                continue;
            }
            String line = r.readLine();
            if (line == null) {
                return null;
            }
            if (line.startsWith("data:") && line.contains(needle)) {
                return line;
            }
        }
        return null;
    }
}
