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

import net.marcloud.mcp.core.drivers.act.ActIntent;
import net.marcloud.mcp.core.drivers.act.ActIntentParser;
import net.marcloud.mcp.core.drivers.act.ActPlan;
import net.marcloud.mcp.core.drivers.act.ActPlanStatus;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.ActStatus;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.LookIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.se.Ring;

/**
 * The PHASE A.7 MCP surface over the {@link ActRuntime} act layer: four tools that
 * let an AI drive the live player's three orthogonal actuation channels
 * ({@link ActSlot#MOVE}/{@link ActSlot#LOOK}/{@link ActSlot#INTERACT}) and read
 * back what each channel is doing.
 *
 * <ul>
 *   <li>{@code act_set} (R1, write) — submit one intent per named slot; missing
 *       slots are left untouched. Each accepted intent becomes eligible at the
 *       next clean tick boundary ({@code effectiveTick = tickNow + 1}).</li>
 *   <li>{@code act_plan} (R1, write) — sidecar sequencer: an ordered list of
 *       {@code act_set}-shaped steps, advanced at 20Hz after the slot loop.
 *       Not a fourth slot and not a {@code plan:} key on {@code act_set}.</li>
 *   <li>{@code act_cancel} (R1, write) — cancel named slots, or all of them
 *       (which also cancels a running plan).</li>
 *   <li>{@code act_status} (R3, read) — a reference-free snapshot of every slot's
 *       phase / activity for "what am I doing right now", plus {@code plan}.</li>
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
        register(registry, actPlan(), Ring.R1);
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

    // ===== act_set =====

    SyncToolSpecification actSet() {
        Tool tool = Tool.builder()
                .name("act_set")
                .title("Set actuation intents")
                .description("[requires: in-world, -javaagent] Drive the live player's three orthogonal "
                        + "channels. NOTHING here happens off the tick seam: intents are stepped once "
                        + "per Minecraft.runTick, so if that seam is not armed every intent below is "
                        + "accepted and then sits at IDLE forever. Confirm act_status.tickNow is "
                        + "advancing before concluding an intent was wrong. Supply any of "
                        + "'move', 'look', 'interact'; each present slot gets a fresh intent that "
                        + "REPLACES whatever that slot held, and becomes eligible at the next clean "
                        + "tick boundary (effectiveTick = current tick + 1). Missing slots are left "
                        + "running. move: EITHER to:[x,y,z] (+ timeoutTicks) to WALK THERE over many "
                        + "ticks in ONE call -- it corrects heading every tick and act_status "
                        + "reports arrived / stuck against a wall / gave up; STRAIGHT LINE ONLY, "
                        + "there is no pathfinding, so an obstacle is an honest failure and the "
                        + "caller reroutes. The y you pass is RECORDED BUT NEVER STEERED TOWARD -- "
                        + "this walks, it neither flies nor climbs -- so 'arrived' means the "
                        + "HORIZONTAL distance closed and says nothing about your height; you may "
                        + "arrive many blocks above or below the y you named. Pass a full block "
                        + "position from find_block freely, but read your real y back from "
                        + "world_view rather than assuming it -- OR raw axes forward,strafe (-1..1, "
                        + "vanilla sign +ahead/+left), "
                        + "jump,sneak,sprint (bool), durationTicks (<=0 = hold until cancelled)}. "
                        + "look:{mode 'set'|'look_at', yaw/pitch (SET degrees), block:[x,y,z] or "
                        + "entityId (LOOK_AT), slewDegPerTick (<=0 = instant snap), track (bool), "
                        + "durationTicks}. By default an aim ENDS THE MOMENT IT LANDS: the slot goes "
                        + "COMPLETE and nothing corrects it afterwards, so aiming at a mob that then "
                        + "walks leaves you pointed where it USED to be. 'track':true is the "
                        + "following mode -- it re-aims every tick and does NOT stop on arrival, "
                        + "ending only when you act_cancel it, when a new look intent replaces it, "
                        + "when a tracked entityId is gone (FAILED, and for a mob that reads as died "
                        + "or left render distance), or after 'durationTicks' if you gave one. "
                        + "durationTicks defaults to 0 = track until cancelled, and is REJECTED "
                        + "without track:true rather than ignored. A track holds the look slot for "
                        + "its whole life and rewrites rotation every tick, so it overrides a human "
                        + "moving the mouse and overrides the server's own rotation packets -- give a "
                        + "duration unless you really mean indefinitely. If the slew cap is too slow "
                        + "to catch the target, a bounded track ends FAILED saying the crosshair "
                        + "never arrived, so 'tracked for N ticks' never means 'aimed' unless it "
                        + "says so. "
                        + "interact:{kind 'dig'|'use'|'place'|'attack'|'hotbar'|'hold', block:[x,y,z], "
                        + "face 0-5, entityId, hotbarSlot 0-8, holdTicks, hitX/hitY/hitZ (place "
                        + "only: where on the face, 0..1 each)}. 'use' is a SINGLE "
                        + "right-click, which vanilla cancels a couple of ticks later -- so it CANNOT "
                        + "eat, draw a bow or block. 'hold' is the sustained one: it keeps vanilla's "
                        + "use key asserted every tick. Omit holdTicks to hold until the game itself "
                        + "ends the use (eating: act_status reports whether the food was actually "
                        + "consumed or the hold was interrupted); give holdTicks to hold that long "
                        + "then let go, which for a bow is what FIRES the arrow -- a draw shorter than "
                        + "3 ticks shoots nothing. A hold ends FAILED if a screen opens (chat, the "
                        + "pause menu, a chest), and READ THAT MESSAGE rather than assuming the use "
                        + "stopped: a screen clears the key AND gates off vanilla's own code for "
                        + "ending a use, so the item usually keeps being used with nobody driving it "
                        + "and a drawn bow fires whenever the screen closes. act_status distinguishes "
                        + "the two endings. But a screen only CLEARS the key when the game had "
                        + "in-game focus, so on an unfocused client (the normal state when a script "
                        + "drives it) the hold keeps running instead, and what happens then depends "
                        + "on WHICH screen: a PAUSING one (the pause menu, a chest, a furnace -- the "
                        + "default) stops the world, so the use freezes and act_status reports that "
                        + "the count stopped moving rather than blaming the server, while CHAT does "
                        + "not pause and a meal finishes normally behind it. All three measured on a "
                        + "live client. "
                        + "While a use is held vanilla also scales walking to 0.2x (unless riding), "
                        + "so a MOVE running at the same time will travel far less than its own "
                        + "report suggests. "
                        + "Returns accepted, tickNow, per-slot effectiveTick, and per-slot phase "
                        + "under 'perSlot'. perSlot is the phase BEFORE the intent has run -- it is "
                        + "read at SUBMIT time, so on a successful submit it is ALWAYS IDLE and says "
                        + "NOTHING about the seam's health or the intent's fate. The seam signal is "
                        + "the 'tickNow' in this same reply: the one game clock, the same value "
                        + "clock_now reports (monotonic, 0 before the first tick / if the tick seam "
                        + "is not armed), so a tickNow of 0, or one that does not grow between two "
                        + "calls, is a dead seam rather than a wrong intent. For the outcome read "
                        + "act_status one or more ticks later; its phase is the one that moves.")
                .inputSchema(objectSchema(Map.of(
                        "move", Map.of("type", "object",
                                "description", "ONE of three, cheapest first. "
                                        + "route:[x,y,z] (+ optional blockBudget, default "
                                        + RouteIntent.DEFAULT_BLOCK_BUDGET + ") = REACH that block: "
                                        + "a path is computed around obstacles, and blocks are PLACED "
                                        + "to cross gaps when walking round would be longer, so the "
                                        + "caller does not need to know the terrain. It consumes "
                                        + "placeable blocks from the held stack, up to blockBudget; "
                                        + "pass 0 to forbid building. Fails naming where the player "
                                        + "stopped rather than reporting a crossing it did not make. "
                                        + "to:[x,y,z] (+ optional timeoutTicks) = walk STRAIGHT "
                                        + "toward a point and give up if blocked; it never builds and "
                                        + "never routes around anything. "
                                        + "Or raw axes forward,strafe,jump,sneak,sprint,durationTicks "
                                        + "for direct input. Giving both 'route' and 'to' is an error: "
                                        + "the MOVE slot holds one intent"),
                        "look", Map.of("type", "object",
                                "description", "camera aim: mode set|look_at, yaw,pitch,block,"
                                        + "entityId,slewDegPerTick, track (keep aiming after "
                                        + "arrival), durationTicks (track only; 0 = until cancelled)"),
                        "interact", Map.of("type", "object",
                                "description", "world interaction: kind dig|use|place|attack|hotbar|hold, "
                                        + "block,face,entityId,hotbarSlot,holdTicks,hitX,hitY,hitZ "
                                        + "(hold: omit holdTicks to eat until done, give it to "
                                        + "draw-then-release)")),
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

            Map<String, Object> move = ActIntentParser.mapArg(args, "move");
            if (move != null) {
                ActIntent intent;
                try {
                    intent = ActIntentParser.parseMoveSlot(move);
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
                }
                SlotRecord r = runtime.submit(intent);
                effectiveTick.put("move", r.effectiveTick());
                perSlot.put("move", r.phase().name());
                accepted++;
            }

            Map<String, Object> look = ActIntentParser.mapArg(args, "look");
            if (look != null) {
                LookIntent li;
                try {
                    li = ActIntentParser.parseLook(look);
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
                }
                SlotRecord r = runtime.submitLook(li);
                effectiveTick.put("look", r.effectiveTick());
                perSlot.put("look", r.phase().name());
                accepted++;
            }

            Map<String, Object> interact = ActIntentParser.mapArg(args, "interact");
            if (interact != null) {
                InteractIntent ii;
                try {
                    ii = ActIntentParser.parseInteract(interact);
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

    // ===== act_plan =====

    SyncToolSpecification actPlan() {
        Tool tool = Tool.builder()
                .name("act_plan")
                .title("Run an actuation plan")
                .description("[requires: in-world, -javaagent] Submit an ordered sequence of "
                        + "act_set-shaped steps that the runtime advances on the tick seam. This is "
                        + "NOT a fourth slot and NOT a 'plan' key on act_set: each step is a "
                        + "non-empty subset of move/look/interact with the same inner keys as "
                        + "act_set, turned into 1-3 intents on the existing channels. The next step "
                        + "is submitted only when every slot that step touched is COMPLETE with the "
                        + "same intent identity; FAILED fails the plan and does not submit the next; "
                        + "CANCELLED or a racing act_set (identity mismatch) aborts naming "
                        + "supersession. A new act_plan replaces the previous. "
                        + "Refuse empty steps, a step with no move/look/interact, unknown keys "
                        + "(wait/eval/craft/skill), route+to together, raw axes with "
                        + "durationTicks<=0, and look track with durationTicks<=0 (KEEP that never "
                        + "completes). look.durationTicks without track is rejected as on act_set. "
                        + "Confirm act_status.tickNow is advancing; watch progress on "
                        + "act_status.plan {phase (IDLE|RUNNING|COMPLETE|FAILED|CANCELLED), index "
                        + "(0-based current step), size, waitingOn, message}.")
                .inputSchema(objectSchema(Map.of(
                        "steps", Map.of("type", "array",
                                "description", "ordered act_set argument objects; each supplies any "
                                        + "of move, look, interact",
                                "items", Map.of("type", "object"))),
                        List.of("steps")))
                .annotations(ToolAnnotations.builder()
                        .title("Run an actuation plan")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            Object stepsArg = args == null ? null : args.get("steps");
            if (!(stepsArg instanceof List<?> steps)) {
                return error("act_plan: 'steps' must be a non-empty array of act_set-shaped objects");
            }
            ActPlan plan;
            try {
                plan = ActPlan.parse(steps);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            runtime.submitPlan(plan);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("tickNow", runtime.status().tickNow());
            out.put("plan", planObject(runtime.planStatus()));
            return ok(Json.write(out));
        });
    }

    // ===== act_cancel =====

    SyncToolSpecification actCancel() {
        Tool tool = Tool.builder()
                .name("act_cancel")
                .title("Cancel actuation intents")
                .description("[requires: in-world, -javaagent] Cancel actuation channels. The teardown "
                        + "runs on the tick seam like everything else here, so without the seam armed "
                        + "a cancel cannot complete either -- check act_status.tickNow. "
                        + "'slots' is the string \"all\" (cancel every "
                        + "slot, and cancel a running act_plan) or an array of slot names "
                        + "('move'|'look'|'interact'). Omitting 'slots' "
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
                runtime.cancelPlan();
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
                .description("Read-only: a snapshot of all three actuation slots — 'tickNow' plus, per "
                        + "slot, {slot, phase (IDLE|ACTIVE|COMPLETE|FAILED|CANCELLED), hasIntent, "
                        + "intentKind, ticksActive, message} — plus 'plan', the sidecar sequencer "
                        + "(not a fourth slot): {phase (IDLE|RUNNING|COMPLETE|FAILED|CANCELLED), "
                        + "index (0-based current step), size, waitingOn (slot names the current "
                        + "step still needs COMPLETE), message}. IDLE plan means none is bound. The "
                        + "next step is submitted only once every slot in waitingOn is COMPLETE with "
                        + "the same intent identity. Reference-free. Use it to see 'what am I "
                        + "doing right now and did the last thing finish' without a screenshot. "
                        + "'tickNow' is the one game clock's current tickId, the same value clock_now "
                        + "reports: monotonic, and 0 before the first tick / if the tick seam is not "
                        + "armed. Read it FIRST, because the act layer only steps on that seam: a "
                        + "tickNow of 0, or one that does not grow between two calls, means nothing is "
                        + "driving the appliers and every intent you submit will sit at IDLE forever "
                        + "no matter how correct it was. That is a dead act layer, not a slow one, and "
                        + "seam_tick_enable is what arms it. "
                        + "'hasIntent' says only that the slot HOLDS an intent record, not that it is "
                        + "doing anything: a slot keeps its intent after reaching a terminal phase, so "
                        + "hasIntent stays true once COMPLETE, FAILED or CANCELLED and only a slot "
                        + "never used since startup reports false. For 'is this channel busy' read "
                        + "hasIntent AND a non-terminal phase; phase is the field that answers it.")
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
            out.put("plan", planObject(runtime.planStatus()));
            return ok(Json.write(out));
        });
    }

    private static Map<String, Object> planObject(ActPlanStatus plan) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", plan.phase().name());
        row.put("index", plan.index());
        row.put("size", plan.size());
        row.put("waitingOn", plan.waitingOn());
        row.put("message", plan.message());
        return row;
    }
}
