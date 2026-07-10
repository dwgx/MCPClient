package net.marcloud.mcp.core.memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.registry.CapabilityRegistry;

/**
 * MCP tools over the {@link MemoryStore}: let the AI accumulate and recall
 * durable experiences across sessions. The knowledge counterpart to create_tool
 * (which accumulates capabilities).
 */
public final class MemoryTools {

    private final MemoryStore store;

    public MemoryTools(MemoryStore store) {
        this.store = store;
    }

    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : List.of(write(), search(), delete())) {
            var t = spec.tool();
            registry.register(t.name(), spec, null, t.description(), true);
        }
    }

    private static CallToolResult ok(String s) {
        return CallToolResult.builder().addTextContent(s).isError(false).build();
    }

    private static CallToolResult err(String s) {
        return CallToolResult.builder().addTextContent(s).isError(true).build();
    }

    private static String arg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return v == null ? null : v.toString();
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String d) {
        return Map.of("type", "string", "description", d);
    }

    private SyncToolSpecification write() {
        Tool tool = Tool.builder()
                .name("memory_write")
                .description("Save a durable experience/lesson/fact you want to recall later "
                        + "(persists across restarts). Use for things like why you got kicked, "
                        + "how a server behaves, a working strategy, a useful location.")
                .inputSchema(schema(Map.of(
                        "title", str("short label / the situation"),
                        "content", str("the lesson, plan, or fact to remember"),
                        "tags", str("comma-separated tags for later filtering (optional)")),
                        List.of("title", "content")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String title = arg(request.arguments(), "title");
            String content = arg(request.arguments(), "content");
            String tagsRaw = arg(request.arguments(), "tags");
            if (title == null || content == null) {
                return err("title and content are required");
            }
            List<String> tags = new ArrayList<>();
            if (tagsRaw != null && !tagsRaw.isBlank()) {
                for (String t : Arrays.asList(tagsRaw.split(","))) {
                    if (!t.isBlank()) {
                        tags.add(t.trim());
                    }
                }
            }
            String id = store.write(title, content, tags);
            return ok("remembered as " + id + " (" + store.size() + " total)");
        });
    }

    private SyncToolSpecification search() {
        Tool tool = Tool.builder()
                .name("memory_search")
                .description("Recall saved experiences matching a query (searches title, "
                        + "content, and tags; empty query lists the most recent). Newest first.")
                .inputSchema(schema(Map.of(
                        "query", str("text to match (optional; empty = most recent)"),
                        "limit", Map.of("type", "integer", "description", "max results (default 10)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String query = arg(request.arguments(), "query");
            int limit = 10;
            Object v = request.arguments() == null ? null : request.arguments().get("limit");
            if (v instanceof Number num) {
                limit = Math.max(1, num.intValue());
            }
            List<MemoryEntry> hits = store.search(query, limit);
            if (hits.isEmpty()) {
                return ok("(no matching memories)");
            }
            StringBuilder sb = new StringBuilder();
            for (MemoryEntry e : hits) {
                sb.append(e.toLine()).append(System.lineSeparator());
            }
            return ok(sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification delete() {
        Tool tool = Tool.builder()
                .name("memory_delete")
                .description("Delete a saved memory by its id (e.g. 'm3').")
                .inputSchema(schema(Map.of("id", str("memory id to delete")), List.of("id")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String id = arg(request.arguments(), "id");
            if (id == null) {
                return err("id is required");
            }
            return store.delete(id) ? ok("deleted " + id) : err("no memory with id " + id);
        });
    }
}
