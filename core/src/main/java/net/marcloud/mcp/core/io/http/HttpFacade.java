package net.marcloud.mcp.core.io.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.io.Capability;
import net.marcloud.mcp.core.io.IoManager;

/**
 * A plain-HTTP "concretization" of the MCP tool surface, on top of the JDK's
 * built-in {@link HttpServer} (module jdk.httpserver — zero external deps). It is
 * a SECOND front door beside the MCP socket: everything routes through the same
 * {@link IoManager#invoke} supervised path, so the privilege rings,
 * circuit breaker, and timeouts apply identically — HTTP cannot bypass them.
 *
 * <p>Endpoints (self-describing, IDA-Pro-MCP style — the AI/human can discover
 * exactly what exists and how to call it):
 * <ul>
 *   <li>{@code GET  /v1/models}       — OpenAI-ish list: one entry per tool.</li>
 *   <li>{@code GET  /v1/tools}        — full tool catalog: ring, description,
 *       input schema, version, health.</li>
 *   <li>{@code GET  /v1/permissions}  — current clearance + each tool's ring/allow.</li>
 *   <li>{@code POST /v1/tools/{name}} — call a tool; JSON body = arguments.</li>
 *   <li>{@code GET  /v1/screen}       — convenience: raw PNG of the game view.</li>
 *   <li>{@code GET  /}                — a short human index.</li>
 * </ul>
 */
public final class HttpFacade {

    public static final int DEFAULT_PORT = 1337;

    private final IoManager registry;
    private final String bindHost;
    private final int port;
    /** Shared secret; when non-blank, every request must carry {@code Authorization: Bearer <token>}. Blank = open (loopback dev default). */
    private final String authToken;
    private volatile HttpServer server;

    /** Open facade (no auth) — the loopback dev default. */
    public HttpFacade(IoManager registry, String bindHost, int port) {
        this(registry, bindHost, port, null);
    }

    /**
     * @param authToken when non-blank, requests without a matching
     *     {@code Authorization: Bearer <token>} header are rejected 401 before
     *     any route runs. Required by {@code McpCore} for non-loopback binds
     *     (SECURITY.md: R-1 arbitrary code execution must not reach the network
     *     unauthenticated).
     */
    public HttpFacade(IoManager registry, String bindHost, int port, String authToken) {
        this.registry = registry;
        this.bindHost = bindHost;
        this.port = port;
        this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
    }

    /** Start the HTTP server on a daemon-threaded executor. */
    public void start() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        s.createContext("/", this::handle);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-http");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        this.server = s;
        System.err.println("[MCP Core] REST facade on http://" + bindHost + ":" + port
                + "/  (try /v1/models)");
    }

    public void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
        }
    }

    /** Actual bound port (useful when constructed with port 0 for tests), or -1 if not started. */
    public int boundPort() {
        HttpServer s = server;
        return s == null ? -1 : s.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendJson(ex, 401, Map.of("error", "unauthorized: Authorization: Bearer <token> required"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.equals("/") || path.isEmpty()) {
                sendText(ex, 200, indexPage());
            } else if (path.equals("/v1/models") && method.equals("GET")) {
                sendJson(ex, 200, models());
            } else if (path.equals("/v1/tools") && method.equals("GET")) {
                sendJson(ex, 200, tools());
            } else if (path.equals("/v1/permissions") && method.equals("GET")) {
                sendJson(ex, 200, permissions());
            } else if (path.equals("/v1/screen") && method.equals("GET")) {
                sendScreen(ex);
            } else if (path.startsWith("/v1/tools/") && method.equals("POST")) {
                callTool(ex, path.substring("/v1/tools/".length()));
            } else {
                sendJson(ex, 404, Map.of("error", "no route: " + method + " " + path));
            }
        } catch (Throwable t) {
            try {
                sendJson(ex, 500, Map.of("error", String.valueOf(t)));
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    /**
     * True when the request may proceed. No configured token = always true (the
     * frictionless loopback dev posture). With a token, require an exact
     * {@code Authorization: Bearer <token>} match, compared in constant time so a
     * network attacker cannot time-probe the secret byte by byte.
     */
    private boolean authorized(HttpExchange ex) {
        if (authToken == null) {
            return true;
        }
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        String prefix = "Bearer ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        String presented = header.substring(prefix.length()).trim();
        return java.security.MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                authToken.getBytes(StandardCharsets.UTF_8));
    }

    // ---- endpoints ---------------------------------------------------------

    /** OpenAI-style: {"object":"list","data":[{"id":name,"object":"tool",...}]}. */
    private Map<String, Object> models() {
        List<Object> data = new ArrayList<>();
        for (Capability c : registry.capabilities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.name());
            m.put("object", "tool");
            m.put("ring", c.ring().tag());
            m.put("allowed", registry.isAllowed(c));
            m.put("description", c.description());
            data.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "list");
        out.put("data", data);
        return out;
    }

    /** Full catalog: everything the AI needs to know HOW to call each tool. */
    private Map<String, Object> tools() {
        List<Object> list = new ArrayList<>();
        for (Capability c : registry.capabilities()) {
            Tool t = c.spec().tool();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("ring", c.ring().tag());
            m.put("allowed", registry.isAllowed(c));
            m.put("builtin", c.builtIn());
            m.put("version", c.version());
            m.put("description", t.description());
            m.put("inputSchema", t.inputSchema());
            m.put("health", Map.of(
                    "circuit", c.stats().circuit().name(),
                    "calls", c.stats().calls(),
                    "failures", c.stats().failures()));
            m.put("invoke", "POST /v1/tools/" + c.name() + "  (JSON body = arguments)");
            list.add(m);
        }
        return Map.of("object", "list", "count", list.size(), "tools", list);
    }

    private Map<String, Object> permissions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clearance", registry.engine().clearance().tag());
        out.put("restorable", registry.engine().restorable());
        List<Object> tools = new ArrayList<>();
        for (Capability c : registry.capabilities()) {
            tools.add(Map.of("name", c.name(), "ring", c.ring().tag(),
                    "allowed", registry.isAllowed(c)));
        }
        out.put("tools", tools);
        return out;
    }

    private void callTool(HttpExchange ex, String name) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> args = Json.readObject(body);
        CallToolResult r = registry.invoke(name, args);
        if (r == null) {
            sendJson(ex, 404, Map.of("error", "no such tool: " + name));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", name);
        out.put("isError", Boolean.TRUE.equals(r.isError()));
        out.put("content", renderContent(r));
        sendJson(ex, Boolean.TRUE.equals(r.isError()) ? 400 : 200, out);
    }

    /** GET /v1/screen — call capture_screen and stream the raw PNG. */
    private void sendScreen(HttpExchange ex) throws IOException {
        CallToolResult r = registry.invoke("capture_screen", Map.of());
        if (r == null || Boolean.TRUE.equals(r.isError())) {
            sendJson(ex, 400, Map.of("error", r == null ? "capture_screen missing"
                    : firstText(r)));
            return;
        }
        for (Content c : r.content()) {
            if (c instanceof ImageContent img) {
                byte[] png = Base64.getDecoder().decode(img.data());
                ex.getResponseHeaders().set("Content-Type", img.mimeType());
                ex.sendResponseHeaders(200, png.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(png);
                }
                return;
            }
        }
        sendJson(ex, 400, Map.of("error", "no image content returned"));
    }

    // ---- content rendering / io -------------------------------------------

    /** Turn tool result content into JSON-friendly entries (text inline, image as data-uri note). */
    private static List<Object> renderContent(CallToolResult r) {
        List<Object> out = new ArrayList<>();
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                out.add(Map.of("type", "text", "text", t.text()));
            } else if (c instanceof ImageContent img) {
                out.add(Map.of("type", "image", "mimeType", img.mimeType(),
                        "bytes", Base64.getDecoder().decode(img.data()).length,
                        "note", "base64 omitted here; GET /v1/screen for the raw PNG"));
            } else {
                out.add(Map.of("type", c.type()));
            }
        }
        return out;
    }

    private static String firstText(CallToolResult r) {
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                return t.text();
            }
        }
        return "(error)";
    }

    private static void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private String indexPage() {
        return """
               MCP Core REST facade
               ====================
               GET  /v1/models          list tools (OpenAI-style)
               GET  /v1/tools           full catalog: ring, description, input schema, health
               GET  /v1/permissions     current clearance + per-tool ring/allow
               POST /v1/tools/{name}    call a tool; JSON body = arguments
               GET  /v1/screen          raw PNG of the current game view

               example: curl -s http://%s:%d/v1/models
                        curl -s -X POST http://%s:%d/v1/tools/scan_surroundings -d '{"radius":8}'
                        curl -s http://%s:%d/v1/screen -o game.png
               """.formatted(bindHost, port, bindHost, port, bindHost, port);
    }
}
