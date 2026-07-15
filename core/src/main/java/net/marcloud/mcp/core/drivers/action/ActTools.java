package net.marcloud.mcp.core.drivers.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.ActStatus;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.LookIntent;
import net.marcloud.mcp.core.drivers.act.MoveIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.se.Ring;

/**
 * The PHASE A.7 MCP surface over the {@link ActRuntime} act layer: three tools that
 * let an AI drive the live player's three orthogonal actuation channels
 * ({@link ActSlot#MOVE}/{@link ActSlot#LOOK}/{@link ActSlot#INTERACT}) and read
 * back what each channel is doing.
 *
 * <ul>
 *   <li>{@code act_set} (R1, write) — submit one intent per named slot; missing
 *       slots are left untouched. Each accepted intent becomes eligible at the
 *       next clean tick boundary ({@code effectiveTick = tickNow + 1}).</li>
 *   <li>{@code act_cancel} (R1, write) — cancel named slots, or all of them.</li>
 *   <li>{@code act_status} (R3, read) — a reference-free snapshot of every slot's
 *       phase / activity for "what am I doing right now".</li>
 * </ul>
 *
 * <p><b>Reference-free by construction.</b> Every value returned is a primitive,
 * String, Map or List built off the runtime's plain-data {@link SlotRecord} /
 * {@link ActStatus} snapshots — no live {@code net.minecraft} object ever crosses
 * the tool boundary, so the handlers run headlessly in tests over a bare
 * {@link ActRuntime}.
 *
 * <p>Registration follows the supervised built-in pattern (see {@code ObserveTools}
 * / {@code GuiTools}): each tool is gated via {@link Ring#forBuiltin} with the
 * declared ring as the fallback (R1 for the two writers, R3 for the reader).
 */
public final class ActTools {

    private final ActRuntime runtime;

    /** Uses the process-wide {@link ActRuntime#INSTANCE}. */
    public ActTools() {
        this(ActRuntime.INSTANCE);
    }

    /** Test/DI constructor with an explicit runtime. */
    public ActTools(ActRuntime runtime) {
        this.runtime = runtime == null ? ActRuntime.INSTANCE : runtime;
    }

    /** Register all act tools into the supervised registry with their true rings. */
    public void registerAll(IoManager registry) {
        register(registry, actSet(), Ring.R1);
        register(registry, actCancel(), Ring.R1);
        register(registry, actStatus(), Ring.R3);
    }

    private static void register(IoManager registry, SyncToolSpecification spec, Ring fallback) {
        Tool t = spec.tool();
        registry.register(t.name(), spec, null, t.description(), true,
                Ring.forBuiltin(t.name(), fallback));
    }

    // ===== small local helpers (mirror ObserveTools/GuiTools shapes) =====

    private static CallToolResult ok(String s) {
        return CallToolResult.builder().addTextContent(s).isError(false).build();
    }

    private static CallToolResult error(String s) {
        return CallToolResult.builder().addTextContent(s).isError(true).build();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> prop(String type, String desc) {
        return Map.of("type", type, "description", desc);
    }

    /** The Map under key {@code k}, or null if absent / not a map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return (v instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    private static float floatArg(Map<String, Object> a, String k, float fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Number n) {
            return n.floatValue();
        }
        if (v != null) {
            try {
                return Float.parseFloat(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static int intArg(Map<String, Object> a, String k, int fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static boolean boolArg(Map<String, Object> a, String k, boolean fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    private static String strArg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return v == null ? null : v.toString();
    }

    /** Parse an int triple from a {@code [x,y,z]} list; null if absent/malformed. */
    private static int[] intTriple(Object v) {
        if (!(v instanceof List<?> l) || l.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) {
                return null;
            }
            out[i] = n.intValue();
        }
        return out;
    }

    // ===== act_set =====

    SyncToolSpecification actSet() {
        Tool tool = Tool.builder()
                .name("act_set")
                .title("Set actuation intents")
                .description("Drive the live player's three orthogonal channels. Supply any of "
                        + "'move', 'look', 'interact'; each present slot gets a fresh intent that "
                        + "REPLACES whatever that slot held, and becomes eligible at the next clean "
                        + "tick boundary (effectiveTick = current tick + 1). Missing slots are left "
                        + "running. move:{forward,strafe (-1..1, vanilla sign +ahead/+left), "
                        + "jump,sneak,sprint (bool), durationTicks (<=0 = hold until cancelled)}. "
                        + "look:{mode 'set'|'look_at', yaw/pitch (SET degrees), block:[x,y,z] or "
                        + "entityId (LOOK_AT), slewDegPerTick (<=0 = instant snap)}. "
                        + "interact:{kind 'dig'|'use'|'place'|'attack'|'hotbar', block:[x,y,z], "
                        + "face 0-5, entityId, hotbarSlot 0-8, mode}. Returns per-slot effectiveTick "
                        + "and phase. Read act_status to see how each intent progresses.")
                .inputSchema(objectSchema(Map.of(
                        "move", Map.of("type", "object",
                                "description", "locomotion: forward,strafe,jump,sneak,sprint,durationTicks"),
                        "look", Map.of("type", "object",
                                "description", "camera aim: mode set|look_at, yaw,pitch,block,entityId,slewDegPerTick"),
                        "interact", Map.of("type", "object",
                                "description", "world interaction: kind dig|use|place|attack|hotbar, block,face,entityId,hotbarSlot")),
                        List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Set actuation intents")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            Map<String, Object> effectiveTick = new LinkedHashMap<>();
            Map<String, Object> perSlot = new LinkedHashMap<>();
            int accepted = 0;

            Map<String, Object> move = mapArg(args, "move");
            if (move != null) {
                SlotRecord r = runtime.submitMove(parseMove(move));
                effectiveTick.put("move", r.effectiveTick());
                perSlot.put("move", r.phase().name());
                accepted++;
            }

            Map<String, Object> look = mapArg(args, "look");
            if (look != null) {
                LookIntent li;
                try {
                    li = parseLook(look);
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
                }
                SlotRecord r = runtime.submitLook(li);
                effectiveTick.put("look", r.effectiveTick());
                perSlot.put("look", r.phase().name());
                accepted++;
            }

            Map<String, Object> interact = mapArg(args, "interact");
            if (interact != null) {
                InteractIntent ii;
                try {
                    ii = parseInteract(interact);
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
                }
                SlotRecord r = runtime.submitInteract(ii);
                effectiveTick.put("interact", r.effectiveTick());
                perSlot.put("interact", r.phase().name());
                accepted++;
            }

            if (accepted == 0) {
                return error("act_set: supply at least one of 'move', 'look', 'interact'");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("tickNow", runtime.status().tickNow());
            out.put("effectiveTick", effectiveTick);
            out.put("perSlot", perSlot);
            return ok(Json.write(out));
        });
    }

    private static MoveIntent parseMove(Map<String, Object> m) {
        return new MoveIntent(
                floatArg(m, "forward", 0f),
                floatArg(m, "strafe", 0f),
                boolArg(m, "jump", false),
                boolArg(m, "sneak", false),
                boolArg(m, "sprint", false),
                intArg(m, "durationTicks", 0));
    }

    private static LookIntent parseLook(Map<String, Object> m) {
        String modeStr = strArg(m, "mode");
        String mode = modeStr == null ? "set" : modeStr.trim().toLowerCase(Locale.ROOT);
        float slew = floatArg(m, "slewDegPerTick", 0f);
        switch (mode) {
            case "set":
                return LookIntent.set(floatArg(m, "yaw", 0f), floatArg(m, "pitch", 0f), slew);
            case "look_at": {
                int[] block = intTriple(m.get("block"));
                if (block != null) {
                    return LookIntent.lookAtBlock(block[0], block[1], block[2], slew);
                }
                int entityId = intArg(m, "entityId", -1);
                if (entityId >= 0) {
                    return LookIntent.lookAtEntity(entityId, slew);
                }
                throw new IllegalArgumentException(
                        "act_set look mode 'look_at' needs a 'block':[x,y,z] or a non-negative 'entityId'");
            }
            default:
                throw new IllegalArgumentException(
                        "act_set look 'mode' must be 'set' or 'look_at', got '" + modeStr + "'");
        }
    }

    private static InteractIntent parseInteract(Map<String, Object> m) {
        String kindStr = strArg(m, "kind");
        if (kindStr == null) {
            throw new IllegalArgumentException("act_set interact needs a 'kind'");
        }
        String kind = kindStr.trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "dig": {
                int[] b = requireBlock(m, "dig");
                return InteractIntent.dig(b[0], b[1], b[2], intArg(m, "face", -1));
            }
            case "use":
                return InteractIntent.useInAir();
            case "place": {
                int[] b = requireBlock(m, "place");
                return InteractIntent.place(b[0], b[1], b[2], intArg(m, "face", -1),
                        floatArg(m, "hitX", 0f), floatArg(m, "hitY", 0f), floatArg(m, "hitZ", 0f));
            }
            case "attack": {
                int entityId = intArg(m, "entityId", -1);
                if (entityId < 0) {
                    throw new IllegalArgumentException("act_set interact 'attack' needs a non-negative 'entityId'");
                }
                return InteractIntent.attack(entityId);
            }
            case "hotbar": {
                int slot = intArg(m, "hotbarSlot", -1);
                if (slot < 0 || slot > 8) {
                    throw new IllegalArgumentException("act_set interact 'hotbar' needs 'hotbarSlot' 0-8");
                }
                return InteractIntent.hotbar(slot);
            }
            default:
                throw new IllegalArgumentException(
                        "act_set interact 'kind' must be one of dig|use|place|attack|hotbar, got '"
                                + kindStr + "'");
        }
    }

    private static int[] requireBlock(Map<String, Object> m, String kind) {
        int[] b = intTriple(m.get("block"));
        if (b == null) {
            throw new IllegalArgumentException(
                    "act_set interact '" + kind + "' needs a 'block':[x,y,z]");
        }
        return b;
    }

    // ===== act_cancel =====

    SyncToolSpecification actCancel() {
        Tool tool = Tool.builder()
                .name("act_cancel")
                .title("Cancel actuation intents")
                .description("Cancel actuation channels. 'slots' is the string \"all\" (cancel every "
                        + "slot) or an array of slot names ('move'|'look'|'interact'). Omitting 'slots' "
                        + "cancels all. A live intent is flagged for a clean teardown on its next game "
                        + "tick before it ends CANCELLED; an idle/terminal slot is reset. Returns the "
                        + "list of slots for which a LIVE intent was flagged.")
                .inputSchema(objectSchema(Map.of(
                        "slots", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "slot names to cancel, or the string \"all\" (default all)")),
                        List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Cancel actuation intents")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(true)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            Object slotsArg = args == null ? null : args.get("slots");

            List<String> cancelled = new ArrayList<>();
            if (slotsArg == null || isAll(slotsArg)) {
                for (ActSlot slot : ActSlot.values()) {
                    if (runtime.cancel(slot)) {
                        cancelled.add(slot.name().toLowerCase(Locale.ROOT));
                    }
                }
            } else if (slotsArg instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) {
                        continue;
                    }
                    ActSlot slot = parseSlot(o.toString());
                    if (slot == null) {
                        return error("act_cancel: unknown slot '" + o
                                + "' (want move|look|interact or \"all\")");
                    }
                    if (runtime.cancel(slot)) {
                        cancelled.add(slot.name().toLowerCase(Locale.ROOT));
                    }
                }
            } else {
                return error("act_cancel: 'slots' must be \"all\" or an array of slot names");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cancelled", cancelled);
            return ok(Json.write(out));
        });
    }

    private static boolean isAll(Object v) {
        return v instanceof String s && s.trim().equalsIgnoreCase("all");
    }

    private static ActSlot parseSlot(String s) {
        if (s == null) {
            return null;
        }
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "move":
                return ActSlot.MOVE;
            case "look":
                return ActSlot.LOOK;
            case "interact":
                return ActSlot.INTERACT;
            default:
                return null;
        }
    }

    // ===== act_status =====

    SyncToolSpecification actStatus() {
        Tool tool = Tool.builder()
                .name("act_status")
                .title("Actuation status")
                .description("Read-only: a snapshot of all three actuation slots — the current tick "
                        + "plus, per slot, {slot, phase (IDLE|ACTIVE|COMPLETE|FAILED|CANCELLED), "
                        + "intentKind, ticksActive, message}. Reference-free. Use it to see 'what am I "
                        + "doing right now and did the last thing finish' without a screenshot.")
                .inputSchema(objectSchema(Map.of(), List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Actuation status")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            ActStatus st = runtime.status();
            List<Object> slots = new ArrayList<>();
            for (ActStatus.SlotStatus s : st.slots()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slot", s.slot().name().toLowerCase(Locale.ROOT));
                row.put("phase", s.phase().name());
                row.put("hasIntent", s.hasIntent());
                row.put("intentKind", s.intentKind());
                row.put("ticksActive", s.ticksActive());
                row.put("message", s.message());
                slots.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tickNow", st.tickNow());
            out.put("slots", slots);
            return ok(Json.write(out));
        });
    }
}
