package net.marcloud.mcp.core.drivers.video;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;

/**
 * The {@code dev_probe} MCP tool: a single read-only call that returns a
 * structured diagnostic of the LIVE game (connection/world presence + GL context),
 * marshalled onto the game thread. This is the development-time "what is the
 * running game actually doing right now" instrument for the LLM — the first rung
 * across the live gap and the carrier for KI-1's GL-context evidence.
 *
 * <p>R2 read-only (game-thread read, like {@code gui_snapshot}); no mutation, so
 * no privilege beyond the ring. Registered through the supervised {@link IoManager}
 * so the 7-layer monitor gates it like every other tool.
 */
public final class DevTools {

    private final GameAccess game;

    public DevTools(GameAccess game) {
        this.game = game;
    }

    /** Register the dev_probe tool into the supervised registry. */
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    net.marcloud.mcp.core.se.Ring.forBuiltin(tool.name(),
                            net.marcloud.mcp.core.se.Ring.R2));
        }
    }

    public List<SyncToolSpecification> all() {
        return List.of(devProbe());
    }

    private SyncToolSpecification devProbe() {
        Tool tool = Tool.builder()
                .name("dev_probe")
                .title("Probe live game + GL context")
                .description("DEV DIAGNOSTIC (read-only): one call returns a structured JSON "
                        + "snapshot of the LIVE game right now — game liveness/connection/world "
                        + "presence, and the OpenGL context (version, vendor, renderer, profile "
                        + "mask, core-vs-compatibility). Runs on the game thread. Every section "
                        + "degrades to 'absent' when the game or a subsystem is not up, so it "
                        + "never errors just because you're not in a world yet. Use it to see "
                        + "what the running client actually is (not what a headless test asserts) "
                        + "and to read the GL context for the KI-1 mipmap investigation.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Probe live game + GL context")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                // Marshal onto the game thread: GL + live game reads are not
                // thread-safe off it. If the bridge isn't wired (Core not fully up)
                // or we're headless, fall back to a direct capture so the tool still
                // returns a well-formed (absent) snapshot rather than erroring.
                Map<String, Object> result;
                try {
                    result = GameBridge.onGameThread(() -> DevProbe.capture(game), 5000L);
                } catch (IllegalStateException notWired) {
                    result = DevProbe.capture(game);
                }
                return CallToolResult.builder()
                        .addTextContent(Json.write(result))
                        .isError(false)
                        .build();
            } catch (Throwable t) {
                return CallToolResult.builder()
                        .addTextContent("dev_probe failed: " + t)
                        .isError(true)
                        .build();
            }
        });
    }
}
