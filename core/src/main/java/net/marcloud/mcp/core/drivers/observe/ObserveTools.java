package net.marcloud.mcp.core.drivers.observe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.Timeline;
import net.marcloud.mcp.core.se.Ring;

/**
 * Read-only windows onto the PHASE T timeline spine: {@code clock_now} (the
 * current {@link GameClock} tickId / phase / monotonic time) and
 * {@code timeline_tail} (the last N observations folded onto that clock). Both are
 * R3 (local, read-only) — they expose the ordered story of the session so an AI
 * can reason about "what happened in what tick order" without polling live state
 * or taking a screenshot.
 *
 * <p>Registration follows the supervised built-in pattern (see {@code CompatTools}
 * / {@code NarrativeTools}): each tool is gated at {@link Ring#forBuiltin} (R3).
 */
public final class ObserveTools {

    private final GameClock clock;
    private final Timeline timeline;

    /**
     * @param clock    the single game clock (usually {@link GameClock#INSTANCE})
     * @param timeline the attached timeline ring (may be null → timeline_tail
     *                 reports empty, honestly)
     */
    public ObserveTools(GameClock clock, Timeline timeline) {
        this.clock = clock == null ? GameClock.INSTANCE : clock;
        this.timeline = timeline;
    }

    /** Register {@code clock_now} + {@code timeline_tail} into the supervised registry. */
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : List.of(clockNow(), timelineTail())) {
            Tool t = spec.tool();
            registry.register(t.name(), spec, null, t.description(), true,
                    Ring.forBuiltin(t.name(), Ring.R3));
        }
    }

    private SyncToolSpecification clockNow() {
        Tool tool = Tool.builder()
                .name("clock_now")
                .title("Game clock now")
                .description("Read-only: the single game clock's current tickId (monotonic, 0 before the "
                        + "first tick / if the tick seam is not armed), last phase, and the monotonic "
                        + "nanotime of the last tick. The one authoritative time source every observation "
                        + "is stamped against.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Game clock now")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tickId", clock.tickId());
            out.put("phase", clock.lastPhase().name());
            out.put("lastTickMonoNs", clock.lastTickMonoNs());
            out.put("armed", clock.tickId() > 0L);
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }

    private SyncToolSpecification timelineTail() {
        Tool tool = Tool.builder()
                .name("timeline_tail")
                .title("Timeline tail")
                .description("Read-only: the most recent observations placed on the game-clock timeline, "
                        + "oldest first. Each entry is {tickId, arrivalMono, kind, summary} — a safe "
                        + "projection (no live game object). Optional 'limit' (default 50) caps how many "
                        + "are returned. Reconstruct the ordered story of the session without a screenshot.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("limit", Map.of(
                                "type", "integer",
                                "description", "max entries to return (default 50)")),
                        "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Timeline tail")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int limit = 50;
            Map<String, Object> args = request.arguments();
            if (args != null && args.get("limit") instanceof Number n) {
                limit = n.intValue();
            }
            List<Object> entries = new ArrayList<>();
            if (timeline != null) {
                for (Timeline.Entry e : timeline.tail(limit)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tickId", e.tickId());
                    row.put("arrivalMono", e.arrivalMono());
                    row.put("kind", e.kind());
                    row.put("summary", e.summary());
                    entries.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", entries.size());
            out.put("tickNow", clock.tickId());
            out.put("entries", entries);
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }
}
