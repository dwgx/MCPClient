package net.marcloud.mcp.core.drivers.observe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.ChatLog;
import net.marcloud.mcp.core.se.Ring;

/**
 * {@code chat_read} — the inbound chat reader the kernel never had.
 *
 * <p>{@code send_chat} (R1) made the agent able to SPEAK. Nothing made it able to
 * HEAR. That asymmetry is the single most damaging gap for 自动完成对局: the agent
 * cannot see a server message, a player's reply, a death message naming what killed
 * it, a join/leave, a plugin warning, or a quest prompt. Everything downstream —
 * reacting to a bet, retreating after a death, answering a question — is unreachable
 * while the agent is deaf.
 *
 * <p><b>Polling cost is the design constraint.</b> This runs every turn, so it must
 * not return the whole history each time. Two modes, both cheap:
 * <ul>
 *   <li><b>unread (default, no args)</b> — returns only what arrived since the last
 *       read and advances the cursor. An idle turn costs one empty list.</li>
 *   <li><b>stateless replay</b> — pass {@code sinceSeq} to re-read a range without
 *       rewinding the cursor.</li>
 * </ul>
 *
 * <p><b>Kind, not prose.</b> Every entry carries {@code kind} (DEATH / PLAYER /
 * SYSTEM / RAW) and, for a death, a machine-readable {@code cause} plus killer and
 * item, because "you died to a creeper" and "you died to the void" demand completely
 * different next actions and a model must not have to parse a sentence to tell them
 * apart. The translation {@code key} is locale-independent; {@code text} is not — see
 * {@link ChatLog.DeathCause} for what the cause enum can and cannot distinguish.
 *
 * <p><b>Honesty boundary.</b> Every row carries {@code authority}:
 * {@code SERVER_FACT} (the server computed it), {@code PLAYER_CLAIM} (a person typed
 * it; the transmission is server-side but the content is an assertion), or
 * {@code UNKNOWN} (a keyless literal — a plugin, a proxy, or anything else, not
 * attributable from the wire). And {@code selfEcho} names the client-side echo: an
 * inbound row that is the agent hearing its own {@code send_chat} words back. It is
 * {@code null} — NOT {@code false} — when the local player name could not be read,
 * because "not an echo" is a claim we cannot support without it.
 *
 * <p>Registered at {@link Ring#R3} (local, read-only) — it reads the same Netty-tap
 * feed as {@code packet_view}, so it carries {@code CAP_NETWORK_RECV_TAP}.
 */
public final class ChatTools {

    private final ChatLog log;
    private final SeamController seams;
    /** Local player name for self-echo detection; may be null or return null. */
    private final Supplier<String> localName;

    /** @param localName supplies the local player name, or null when unavailable. */
    public ChatTools(ChatLog log, SeamController seams, Supplier<String> localName) {
        this.log = log;
        this.seams = seams;
        this.localName = localName;
    }

    /** No self-echo detection (no way to read the local name). */
    public ChatTools(ChatLog log, SeamController seams) {
        this(log, seams, null);
    }

    /** Register {@code chat_read} into the supervised registry. */
    public void registerAll(IoManager registry) {
        SyncToolSpecification spec = chatRead();
        Tool t = spec.tool();
        registry.register(t.name(), spec, null, t.description(), true,
                Ring.forBuiltin(t.name(), Ring.R3));
    }

    /** Package-private for direct handler testing (mirrors ObserveTools.packetView). */
    SyncToolSpecification chatRead() {
        Tool tool = Tool.builder()
                .name("chat_read")
                .title("Read inbound chat")
                .description("[requires: seam_netty_install] Read-only: the server's inbound chat — server "
                        + "notices, other players' lines, join/leave, plugin warnings, and DEATH "
                        + "messages. This is how you hear. Each entry: {seq, tickId, kind, channel, "
                        + "key, text, args, death:{cause,killer,item}, authority, selfEcho}.\n"
                        + "BRANCH ON kind AND death.cause, NOT on text: a death is a machine-readable "
                        + "type ('outOfWorld' means the void, 'fall' means a fall, 'explosion' with "
                        + "killer 'Creeper' means a creeper — note vanilla reports a creeper kill as "
                        + "cause=explosion, so read killer too), and text is locale-dependent prose.\n"
                        + "honesty: authority=SERVER_FACT means the server computed it from world "
                        + "state; PLAYER_CLAIM means a PLAYER typed it, so it is an assertion, not "
                        + "evidence; UNKNOWN means a keyless literal (plugin/proxy) that cannot be "
                        + "attributed. selfEcho=true means this is your OWN send_chat coming back — "
                        + "no new information; it is null when the local name was unreadable, so do "
                        + "not read null as false.\n"
                        + "cost: called with no arguments this returns ONLY what arrived since your "
                        + "last call (and advances the cursor), so an idle turn is cheap. Pass "
                        + "sinceSeq to replay a range without rewinding the cursor. Filter with "
                        + "'kinds' (DEATH|PLAYER|SYSTEM|RAW) and cap with 'limit' (default 20).")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "sinceSeq", Map.of("type", "integer",
                                        "description", "replay entries with seq greater than this "
                                                + "instead of the unread set; the unread cursor is "
                                                + "still advanced to the newest seq returned"),
                                "limit", Map.of("type", "integer",
                                        "description", "max entries, most recent kept (default 20)"),
                                "kinds", Map.of("type", "array",
                                        "items", Map.of("type", "string",
                                                "enum", List.of("DEATH", "PLAYER", "SYSTEM", "RAW")),
                                        "description", "keep only these kinds (default all)")),
                        "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Read inbound chat")
                        .readOnlyHint(true).destructiveHint(false)
                        // NOT idempotent: the default mode advances the unread cursor, so a
                        // second identical call returns nothing. Declared honestly.
                        .idempotentHint(false).openWorldHint(false).build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> handle(request));
    }

    private CallToolResult handle(io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        int limit = 20;
        boolean replay = false;
        long sinceSeq = -1L;
        Set<String> kinds = null;
        if (args != null) {
            if (args.get("limit") instanceof Number n) {
                limit = Math.max(0, n.intValue());
            }
            if (args.get("sinceSeq") instanceof Number n) {
                replay = true;
                sinceSeq = n.longValue();
            }
            kinds = kindsOf(args.get("kinds"));
        }

        boolean tap = tapInstalled();
        long oldest = log.oldestSeq();
        long newest = log.newestSeq();

        // Honest gap detection BEFORE reading: a cursor below the ring floor has lost
        // messages. Silently returning the surviving tail would read as "that was all
        // of it", which is a different and wrong claim.
        long effectiveSince = replay ? sinceSeq : log.readCursor();
        long missed = Math.max(0L, oldest - 1L - effectiveSince);
        if (effectiveSince < 0L) {
            // Default unread mode on a fresh log: everything ever seen.
            effectiveSince = -1L;
        }

        List<ChatLog.Entry> entries = replay
                ? log.sinceAndMarkRead(effectiveSince, limit)
                : log.unread(limit);
        List<Object> rows = new ArrayList<>(entries.size());
        String name = localName();
        for (ChatLog.Entry e : entries) {
            if (kinds != null && !kinds.contains(e.kind().name())) {
                continue;
            }
            rows.add(row(e, name));
        }

        if (rows.isEmpty() && log.size() == 0 && !tap) {
            return CallToolResult.builder().addTextContent(
                    "packet tap not installed — this is NOT an authoritative 'nobody spoke'. "
                    + "Install it with seam_netty_install first.").isError(true).build();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("cursor", log.readCursor());
        out.put("newestSeq", newest);
        out.put("oldestSeq", oldest);
        out.put("unread", log.peekUnread(limit).size());
        out.put("tapInstalled", tap);
        if (missed > 0) {
            out.put("missed", missed);
        }
        out.put("entries", rows);
        return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
    }

    private static Map<String, Object> row(ChatLog.Entry e, String localName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("tickId", e.tickId());
        m.put("kind", e.kind().name());
        m.put("channel", e.channel().name());
        if (e.key() != null) {
            m.put("key", e.key());
        }
        m.put("text", e.text());
        if (!e.args().isEmpty()) {
            m.put("args", e.args());
        }
        if (e.isDeath()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cause", e.deathCause().name());
            if (e.deathCause().vanillaKeySuffix() != null) {
                d.put("vanillaSuffix", e.deathCause().vanillaKeySuffix());
            }
            d.put("killer", e.deathKiller());
            d.put("item", e.deathItem());
            m.put("death", d);
        }
        m.put("authority", e.authority().name());
        // Tri-state on purpose: null means "could not tell", not "no".
        m.put("selfEcho", ChatLog.selfEcho(e,
                e.args().isEmpty() ? null : e.args().get(0), localName));
        return m;
    }

    private String localName() {
        if (localName == null) {
            return null;
        }
        try {
            return localName.get();
        } catch (Throwable t) {
            // A name we cannot read must leave selfEcho null, not become false.
            return null;
        }
    }

    private boolean tapInstalled() {
        try {
            return seams != null && seams.isNettyTapInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Set<String> kindsOf(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    String s = o.toString().trim().toUpperCase(java.util.Locale.ROOT);
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } else if (v instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                String t = part.trim().toUpperCase(java.util.Locale.ROOT);
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }
}