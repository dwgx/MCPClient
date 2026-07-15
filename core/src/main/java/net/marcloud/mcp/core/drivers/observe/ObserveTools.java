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
import net.marcloud.mcp.core.ke.PacketFilter;
import net.marcloud.mcp.core.ke.PacketJournal;
import net.marcloud.mcp.core.ke.Timeline;
import net.marcloud.mcp.core.flt.seam.SeamController;
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
    private final PacketJournal journal;
    private final SeamController seams;

    /** Back-compat ctor (no packet journal): packets_tail/packet_get are not registered. */
    public ObserveTools(GameClock clock, Timeline timeline) {
        this(clock, timeline, null, null);
    }

    /**
     * @param clock    the single game clock (usually {@link GameClock#INSTANCE})
     * @param timeline the attached timeline ring (may be null → timeline_tail
     *                 reports empty, honestly)
     * @param journal  the attached packet journal (may be null → packet tools not registered)
     * @param seams    the seam controller, for the honest "tap installed?" guard (may be null)
     */
    public ObserveTools(GameClock clock, Timeline timeline, PacketJournal journal,
                        SeamController seams) {
        this.clock = clock == null ? GameClock.INSTANCE : clock;
        this.timeline = timeline;
        this.journal = journal;
        this.seams = seams;
    }

    /** Register the observe tools into the supervised registry. */
    public void registerAll(IoManager registry) {
        List<SyncToolSpecification> specs = new ArrayList<>(List.of(clockNow(), timelineTail()));
        if (journal != null) {
            specs.add(packetsTail());
            specs.add(packetGet());
            specs.add(packetView());
        }
        for (SyncToolSpecification spec : specs) {
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

    private boolean tapInstalled() {
        try {
            return seams != null && seams.isNettyTapInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Map<String, Object> row(PacketJournal.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.seq());
        m.put("tickId", e.tickId());
        m.put("dir", e.dir().name());
        m.put("class", e.packetClass());
        m.put("simpleName", e.simpleName());
        if (e.byteLen() >= 0) {
            m.put("byteLen", e.byteLen());
        }
        m.put("summary", e.summary());
        return m;
    }

    /** Structured row for packet_view: the typed field map instead of a summary String. */
    private static Map<String, Object> fieldsRow(PacketJournal.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("tickId", e.tickId());
        m.put("dir", e.dir().name());
        m.put("class", e.packetClass());
        m.put("simpleName", e.simpleName());
        m.put("fields", e.fields());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s);
        }
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        return List.of();
    }

    private static PacketFilter filterFromArgs(Map<String, Object> args) {
        if (args == null) {
            return PacketFilter.of(PacketFilter.Dir.ANY, null, null, true);
        }
        PacketFilter.Dir dir = PacketFilter.Dir.ANY;
        Object d = args.get("dir");
        if (d instanceof String ds) {
            String u = ds.trim().toUpperCase(java.util.Locale.ROOT);
            if (u.equals("IN")) dir = PacketFilter.Dir.IN;
            else if (u.equals("OUT")) dir = PacketFilter.Dir.OUT;
        }
        List<String> include = asStringList(args.get("include"));
        List<String> exclude = asStringList(args.get("exclude"));
        // default noise-drop only when the caller didn't narrow with an explicit include
        boolean dropNoise = include.isEmpty()
                && !Boolean.FALSE.equals(args.get("includeNoise"));
        return PacketFilter.of(dir, include, exclude, dropNoise);
    }

    private SyncToolSpecification packetsTail() {
        Tool tool = Tool.builder()
                .name("packets_tail")
                .title("Recent packets")
                .description("[requires: netty-tap] Read-only: the most recent network packets on the "
                        + "game-clock timeline, oldest first. Each: {id, tickId, dir(IN/OUT), class, "
                        + "simpleName, summary}. Filter with 'dir' (IN|OUT), 'include'/'exclude' "
                        + "(class-name substrings or globs), and 'limit' (default 50). High-noise "
                        + "packets (keepalive/time/velocity) are dropped unless you set includeNoise=true "
                        + "or an explicit include. Supersedes recent_packets with tick-stamped, "
                        + "addressable entries. Use packet_get for one packet's full detail.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "max entries (default 50)"),
                                "dir", Map.of("type", "string", "description", "IN | OUT (default any)"),
                                "include", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "keep only classes matching these substrings/globs"),
                                "exclude", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "drop classes matching these substrings/globs"),
                                "includeNoise", Map.of("type", "boolean",
                                        "description", "include high-noise packets (default false)")),
                        "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Recent packets")
                        .readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(false).openWorldHint(false).build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            int limit = 50;
            if (args != null && args.get("limit") instanceof Number n) {
                limit = Math.max(0, n.intValue());
            }
            PacketFilter filter = filterFromArgs(args);
            List<Object> entries = new ArrayList<>();
            for (PacketJournal.Entry e : journal.tail()) {
                if (filter.accepts(e.simpleName(), e.packetClass(), e.dir())) {
                    entries.add(row(e));
                }
            }
            // keep most-recent `limit`, preserving oldest-first order
            if (entries.size() > limit) {
                entries = new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
            }
            if (entries.isEmpty() && journal.size() == 0 && !tapInstalled()) {
                return CallToolResult.builder().addTextContent(
                        "packet tap not installed — this is NOT an authoritative 'no packets'. "
                        + "Install it via the seam netty-tap tool first.").isError(true).build();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", entries.size());
            out.put("tickNow", clock.tickId());
            out.put("tapInstalled", tapInstalled());
            out.put("entries", entries);
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }

    private SyncToolSpecification packetGet() {
        Tool tool = Tool.builder()
                .name("packet_get")
                .title("Get one packet")
                .description("[requires: netty-tap] Read-only: fetch one journaled packet by its 'id' "
                        + "(the stable seq from packets_tail). Returns {id, tickId, dir, class, "
                        + "simpleName, byteLen, summary} or an error if that id was evicted from the ring "
                        + "or never existed.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("id", Map.of("type", "integer",
                                "description", "the packet seq id from packets_tail")),
                        "required", List.of("id")))
                .annotations(ToolAnnotations.builder()
                        .title("Get one packet")
                        .readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            if (args == null || !(args.get("id") instanceof Number idn)) {
                return CallToolResult.builder().addTextContent("id (integer) is required")
                        .isError(true).build();
            }
            long id = idn.longValue();
            var found = journal.byId(id);
            if (found.isEmpty()) {
                String why = tapInstalled()
                        ? "no packet with id " + id + " (evicted from the ring or never existed)"
                        : "packet tap not installed; no journal to search";
                return CallToolResult.builder().addTextContent(why).isError(true).build();
            }
            return CallToolResult.builder()
                    .addTextContent(Json.write(row(found.get()))).isError(false).build();
        });
    }

    private SyncToolSpecification packetView() {
        Tool tool = Tool.builder()
                .name("packet_view")
                .title("Structured packet view")
                .description("[requires: netty-tap] Read-only: recent packets that carry a STRUCTURED, "
                        + "typed projection (the high-value A-tier packets — position, health, world "
                        + "edits, inventory, entity spawns, etc.), oldest first. Each: {seq, tickId, "
                        + "dir(IN/OUT), class, simpleName, fields:{...typed key/values...}}. Unlike "
                        + "packets_tail (a String summary per packet), this hands you parsed fields you "
                        + "can read directly — no string parsing. Filter with 'dir' (IN|OUT), 'class' "
                        + "(class-name substring), 'sinceSeq' (only seq > this), and 'limit' (default 50). "
                        + "Packets with no typed projection (low-value / not yet modeled) are omitted; "
                        + "use packets_tail to see those.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "max entries (default 50)"),
                                "dir", Map.of("type", "string", "description", "IN | OUT (default any)"),
                                "class", Map.of("type", "string",
                                        "description", "keep only classes whose name contains this substring"),
                                "sinceSeq", Map.of("type", "integer",
                                        "description", "only packets with seq greater than this (incremental polling)")),
                        "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Structured packet view")
                        .readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(false).openWorldHint(false).build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            int limit = 50;
            long sinceSeq = 0L;
            String classSub = null;
            if (args != null) {
                if (args.get("limit") instanceof Number n) {
                    limit = Math.max(0, n.intValue());
                }
                if (args.get("sinceSeq") instanceof Number n) {
                    sinceSeq = n.longValue();
                }
                if (args.get("class") instanceof String s && !s.isBlank()) {
                    classSub = s;
                }
            }
            PacketFilter filter = filterFromArgs(args);
            List<Object> entries = new ArrayList<>();
            for (PacketJournal.Entry e : journal.tail()) {
                // only entries that actually carry typed fields (A-tier)
                if (e.fields() == null || e.fields().isEmpty()) {
                    continue;
                }
                if (e.seq() <= sinceSeq) {
                    continue;
                }
                if (classSub != null && (e.packetClass() == null || !e.packetClass().contains(classSub))) {
                    continue;
                }
                if (filter.accepts(e.simpleName(), e.packetClass(), e.dir())) {
                    entries.add(fieldsRow(e));
                }
            }
            if (entries.size() > limit) {
                entries = new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
            }
            if (entries.isEmpty() && journal.size() == 0 && !tapInstalled()) {
                return CallToolResult.builder().addTextContent(
                        "packet tap not installed — this is NOT an authoritative 'no packets'. "
                        + "Install it via the seam netty-tap tool first.").isError(true).build();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", entries.size());
            out.put("tickNow", clock.tickId());
            out.put("tapInstalled", tapInstalled());
            out.put("entries", entries);
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }
}
