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
import net.marcloud.mcp.core.drivers.act.NavHazard;
import net.marcloud.mcp.core.drivers.act.MoveTactic;
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
 *       next clean tick boundary ({@code effectiveTick = tickNow + 1}). Every
 *       named channel is validated BEFORE any is submitted, so an error reply
 *       means nothing was started.</li>
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
        register(registry, pressKeyBinding(), Ring.R1);
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

    // ===== schema construction =====
    //
    // Every object below used to be {"type":"object","description":"<a wall of prose>"} with NO
    // properties, which is why `{"move":{"to":{"x":..,"y":..,"z":..}}}` was accepted silently:
    // the schema had nothing to reject, and the handler's own reader treated a Map `to` as "not
    // supplied" (ActIntentParser.coordArg). A live client caught it — the call returned
    // accepted:true, the MOVE slot reported ACTIVE, and the player never moved. A schema that
    // declares nothing is not a weaker check, it is NO check, and the prose was describing
    // constraints the machine could not see.
    //
    // The prose is not deleted here; it is MOVED onto the fields it is about, and every closed set
    // becomes a JSON `enum` rather than a sentence. The two are not equal for a model: an enum is
    // validated, offered by IDE completion, and cannot be misread, while a sentence is prose the
    // model has to parse correctly every time it is read.

    private static Map<String, Object> obj(String description, Map<String, Object> props,
                                           List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("description", description);
        m.put("properties", props);
        m.put("required", required);
        return m;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> field(String type, String description, Object... extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        for (int i = 0; i < extra.length; i += 2) {
            m.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> enumField(String description, String... values) {
        return field("string", description, "enum", List.of(values));
    }

    private static Map<String, Object> num(String description, double min, double max) {
        return field("number", description, "minimum", min, "maximum", max);
    }

    /**
     * A coordinate that accepts BOTH shapes, because both are natural and models produce both.
     *
     * <p>Documented form is the array; the object form is what every other tool in this surface
     * uses for a position. Declaring only one of them is what let the other through unchecked, and
     * the one that got through was the one that broke a live client.
     *
     * <p><b>The description is emitted ONCE, on this node -- not on the node and both
     * {@code oneOf} branches.</b> It used to be written into all three, so every coordinate in the
     * surface paid three copies: {@code go_to}'s 298-char description and {@code walk_straight}'s
     * 555-char one were each in the tools/list payload three times over. That is pure waste --
     * JSON Schema's {@code description} on the node a property is DECLARED at is what a reader
     * resolving that property is shown, and the two copies under {@code oneOf} said nothing the
     * first did not.
     *
     * <p>The branches carry no description at all, rather than a shortened one, because they
     * already say which shape each accepts in a form that cannot be misread: {@code type} plus
     * {@code minItems}/{@code maxItems}/{@code items} for the array, and {@code type} plus
     * {@code required}/{@code properties} for the object. A branch repeating even a short label
     * would be a second copy of a string this surface then emits once per coordinate -- the same
     * waste in miniature, which is what the first version of this fix did and what
     * {@code DescriptionsNameToolsThatExistTest} caught. Nothing is lost: the whole prose is still
     * emitted, once, on the node the property is declared at.
     */
    private static Map<String, Object> coord(String description) {
        Map<String, Object> array = new LinkedHashMap<>();
        array.put("type", "array");
        array.put("minItems", 3);
        array.put("maxItems", 3);
        array.put("items", Map.of("type", "number"));

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("required", List.of("x", "y", "z"));
        object.put("properties", props(
                "x", field("number", "block x"),
                "y", field("number", "block y"),
                "z", field("number", "block z")));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("description", description);
        m.put("oneOf", List.of(array, object));
        return m;
    }

    /**
     * The act_set input schema.
     *
     * <p>Every channel used to be {"type":"object","description":"&lt;a wall of prose&gt;"} with NO
     * properties, which is why {@code {"move":{"to":{"x":..,"y":..,"z":..}}}} was accepted
     * silently: the schema had nothing to reject, and the handler read a Map {@code to} as "not
     * supplied". A live client caught it -- accepted:true, slot ACTIVE, player never moved. A
     * schema that declares nothing is not a weaker check, it is NO check, and the prose was
     * describing constraints the machine could not see.
     *
     * <p>The prose is not deleted here; it is MOVED onto the field it is about, and every closed
     * set becomes a JSON enum rather than a sentence. For a model those are not equal: an enum is
     * validated and offered by completion, a sentence has to be re-parsed on every read.
     *
     * <p>Assembled from locals rather than nested inline. The first version was one 100-line
     * expression inside a builder chain and cost an hour to a missing paren; a flat method is
     * reviewable and the nesting is gone.
     */
    private static Map<String, Object> actSetInputSchema() {
        Map<String, Object> move = obj(
                "ONE of: 'go_to' (REACH a block, pathfinding around obstacles and placing blocks to "
                        + "cross gaps), 'walk_straight' (walk a STRAIGHT LINE to a point, no "
                        + "pathfinding, no building), or raw axes. Giving both 'go_to' and "
                        + "'walk_straight' is an error: the MOVE slot holds one intent. A move whose "
                        + "axes are all at rest AND that has no durationTicks is REFUSED: it would "
                        + "report itself as moving forever without moving.",
                props(
                        "go_to", coord("destination BLOCK to reach. A path is computed around "
                                + "obstacles, and blocks are PLACED to cross gaps when walking round "
                                + "would be longer. Consumes placeable blocks from the held stack, up "
                                + "to blockBudget; pass 0 to forbid building. Fails naming where the "
                                + "player stopped. THIS IS THE ONE TO REACH FOR."),
                        "blockBudget", field("integer", "how many blocks 'go_to' may place to cross a "
                                + "gap (default " + RouteIntent.DEFAULT_BLOCK_BUDGET
                                + ", 0 = never build)."),
                        "walk_straight", coord("use go_to unless you know the line is clear. Point to "
                                + "WALK STRAIGHT toward. STRAIGHT LINE ONLY: no pathfinding, no "
                                + "building, so an obstacle is an honest failure. It does not steer "
                                + "y at all, so 'arrived' means the horizontal distance closed and "
                                + "says nothing about height -- read your real y from world_view. "
                                + "'walk_straight' walks the player into water, off a cliff, or into "
                                + "anything else between here and there; on a live client it walked "
                                + "one off a 30-block cliff and drowned them, which is why the "
                                + "dangerous option no longer has the short reassuring name."),
                        "timeoutTicks", field("integer", "how many ticks 'walk_straight' may take "
                                + "before giving up (default 0 = no limit)."),
                        "forward", num("raw axis: -1 ahead, +1 back (vanilla's sign). Raw axes only.",
                                -1, 1),
                        "strafe", num("raw axis: -1 left, +1 right. Raw axes only.", -1, 1),
                        "jump", field("boolean", "raw axis: hold jump."),
                        "sneak", field("boolean", "hold sneak. With raw axes it is just a key; with "
                                + "'go_to' or 'walk_straight' it is what keeps you ALIVE on a ledge: "
                                + "vanilla's own guard stops a sneaking body at a brink instead of "
                                + "walking it off, and it costs about a third of your speed. "
                                + "'walk_straight' with sneak:true toward a point past a cliff is how "
                                + "you cross an edge without dying. A 'go_to' route creeps the moves "
                                + "that end at an edge by itself, so pass this only to force a creep on "
                                + "flat ground too."),
                        "sprint", field("boolean", "raw axis: hold sprint."),
                        "durationTicks", field("integer", "raw axes: how long to hold them "
                                + "(<=0 = until cancelled). A raw move with no axes and no duration is "
                                + "refused.")),
                List.of());

        Map<String, Object> look = obj(
                "camera aim. An aim ENDS THE MOMENT IT LANDS by default: the slot goes COMPLETE and "
                        + "nothing corrects it afterwards, so aiming at a mob that then walks leaves "
                        + "you pointed where it USED to be. 'track':true is the following mode -- it "
                        + "re-aims every tick and does not stop on arrival.",
                props(
                        "mode", enumField("how to aim.", "set", "look_at"),
                        "yaw", num("absolute yaw in degrees (mode 'set').", -180, 180),
                        "pitch", num("absolute pitch in degrees (mode 'set'); vanilla clamps to "
                                + "-90..90.", -90, 90),
                        "block", coord("block to look at (mode 'look_at')."),
                        "entityId", field("integer", "entity to look at (mode 'look_at')."),
                        "slewDegPerTick", field("number", "cap on degrees turned per tick; <=0 = "
                                + "instant snap. A bounded track that never arrives ends FAILED saying "
                                + "the crosshair never landed."),
                        "track", field("boolean", "keep re-aiming after arrival. Overrides a human "
                                + "moving the mouse AND the server's own rotation packets, so give a "
                                + "duration unless you mean indefinitely."),
                        "durationTicks", field("integer", "track only; 0 = until cancelled. REJECTED "
                                + "without track:true rather than ignored.")),
                List.of());

        Map<String, Object> interact = obj(
                "world interaction, chosen by 'kind'. A block-target argument supplied to a kind "
                        + "that reads none is REFUSED, never dropped: block/face/hitX/hitY/hitZ on "
                        + "'use', 'hold', 'attack', 'block', 'release', 'hotbar', 'drop' or "
                        + "'craft' (right-click a named block face with 'place'), and "
                        + "hitX/hitY/hitZ on 'dig' (they are how a PLACEMENT aims). 'drop' is the "
                        + "one kind that acts on the PLAYER rather than the world: it throws the "
                        + "whole stack in the named 'slot' out onto the floor, and confirms "
                        + "afterwards by re-reading that slot rather than reporting the click as "
                        + "sent. 'craft' is the other: it spends ingredients from your bag and puts "
                        + "the output there, clicking the open crafting window for you -- see the "
                        + "'item' property, and craft_plan for what to ask it. 'face' is OPTIONAL "
                        + "on 'dig' and REQUIRED on 'place', and a value outside 0-5 is REFUSED on "
                        + "both rather than rounded to the nearest side -- see the 'face' property, "
                        + "which says why one and not the other.",
                props(
                        "kind", enumField("which interaction.", "dig", "use", "place", "attack",
                                "hotbar", "drop", "craft", "hold", "block", "release"),
                        "block", coord("the target block. Read by 'dig' and 'place' only."),
                        "face", field("integer", "which side of the block, 0=down 1=up 2=north(z-) "
                                + "3=south(z+) 4=west(x-) 5=east(x+). Read by 'dig' and 'place' only. "
                                + "A value outside 0-5 is REFUSED, not rounded: for 'place' the face "
                                + "decides WHICH CELL the new block goes in, so a guess puts a block "
                                + "where nobody asked. OMITTABLE on 'dig' (it breaks the block it "
                                + "names whichever side you approach from, and the reply tells you "
                                + "when a default was used) and REQUIRED on 'place', where no side "
                                + "is a safe default.",
                                "minimum", 0, "maximum", 5),
                        "entityId", field("integer", "target entity. Read by 'attack' only."),
                        "attack", enumField("'attack' only, optional: 'plain' (default, swing "
                                + "now) or 'crit' (WAIT for the falling instant a critical hit needs "
                                + "-- off the ground, fallDistance > 0, not on a ladder, not in "
                                + "water -- then swing there, and REFUSE rather than swing on flat "
                                + "ground). The 1.5x is the server's to apply (EntityPlayer:1335) and "
                                + "no packet reports it back, so 'crit' is a timed swing, NOT a "
                                + "confirmed crit. To use it, jump on the MOVE slot and attack as the "
                                + "body starts down.", "plain", "crit"),
                        "hotbarSlot", field("integer", "slot 0-8. Read by 'hotbar' only.",
                                "minimum", 0, "maximum", 8),
                        "slot", field("integer", "'drop' only: the PLAYER inventory slot 0-35 to "
                                + "empty, in vanilla's mainInventory order, so 0-8 is the hotbar "
                                + "and 9-35 the pack above it. Bounded here rather than in the "
                                + "parser so a slot outside it is REFUSED at the boundary: "
                                + "ContainerPlayer numbers its own rows differently (result 0, "
                                + "2x2 at 1-4, ARMOUR at 5-8, pack at 9-35, hotbar LAST at "
                                + "36-44), so 'slot 36' names the first row of the crafting grid "
                                + "rather than an inventory stack, and a silently clamped 35 would "
                                + "throw away a stack the caller did not name.",
                                "minimum", 0, "maximum", 35),
                        "item", field("string", "'craft' only: the OUTPUT registry name to make, "
                                + "namespace optional ('stick' or 'minecraft:crafting_table'), so a "
                                + "name read out of world_view, find_block or craft_plan can be fed "
                                + "straight in. REQUIRED for 'craft' and refused on every other "
                                + "kind. Run craft_plan on it first: that names the grid, tells you "
                                + "what you are short, and is the only thing that can say whether "
                                + "there is a recipe at all. The craft runs over several ticks -- it "
                                + "waits out a server round trip before it believes its own "
                                + "placements -- so it finishes after this call returns; read "
                                + "act_status for the outcome, and act_cancel to stop it (which "
                                + "returns anything left in the grid to your bag). It needs a "
                                + "crafting window ALREADY OPEN: right-click a bench with "
                                + "kind='place' first, or use your own 2x2 grid. A 3x3 recipe in a "
                                + "2x2 grid is REFUSED by name rather than half-filled. A craft "
                                + "never walks anywhere or opens anything on its own."),
                        "holdTicks", field("integer", "'hold' only: how long to hold the use. Omit to "
                                + "hold until the game ends it; give it for a bow, which fires on "
                                + "release and shoots nothing under a 3-tick draw."),
                        "hitX", num("'place' only: where on the face, 0..1 each.", 0, 1),
                        "hitY", num("'place' only: where on the face, 0..1 each.", 0, 1),
                        "hitZ", num("'place' only: where on the face, 0..1 each.", 0, 1)),
                List.of("kind"));

        return objectSchema(props("move", move, "look", look, "interact", interact), List.of());
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
                        + "running. move: EITHER go_to:[x,y,z] to REACH A BLOCK -- a path is computed "
                        + "around obstacles and blocks are PLACED to cross gaps (blockBudget, default "
                        + "8, 0 = never build), and it fails naming where the player stopped. Use it "
                        + "unless you know there is nothing to go around. OR walk_straight:[x,y,z] (+ "
                        + "timeoutTicks) to WALK THERE in a STRAIGHT LINE over many ticks in ONE call "
                        + "-- it corrects heading every tick and act_status reports arrived / stuck "
                        + "against a wall / gave up; STRAIGHT LINE ONLY, there is no pathfinding and "
                        + "no building, so an obstacle is an honest failure and the caller reroutes. "
                        + "It walked a live player off a 30-block cliff and drowned them with no "
                        + "warning anywhere, which is why it no longer wears the short reassuring "
                        + "name. The y you pass is RECORDED BUT NEVER STEERED TOWARD -- this walks, it "
                        + "neither flies nor climbs -- so 'arrived' means the HORIZONTAL distance "
                        + "closed and says nothing about your height; you may arrive many blocks above "
                        + "or below the y you named. Pass a full block position from find_block "
                        + "freely, but read your real y back from world_view rather than assuming it "
                        + "-- OR raw axes forward,strafe (-1..1, vanilla sign +ahead/+left), "
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
                        + "interact:{kind 'dig'|'use'|'place'|'attack'|'hotbar'|'drop'|'craft'|"
                        + "'hold'|'block'|'release', block:[x,y,z], face 0-5, entityId, "
                        + "hotbarSlot 0-8, slot 0-35 (drop only), item (craft only), holdTicks, "
                        + "hitX/hitY/hitZ (place only: where on the face, 0..1 each)}. "
                        + "'use' is a SINGLE right-click, which vanilla cancels a couple of ticks "
                        + "later -- so it CANNOT "
                        + "eat, draw a bow or block. 'use' is also the IN-AIR click and takes no block "
                        + "target: 'block', 'face' or 'hitX/hitY/hitZ' with kind 'use' is REFUSED (the "
                        + "reply names the key that could not be honoured) rather than quietly clicked "
                        + "into open air -- right-clicking a named block face is 'place', which "
                        + "carries the face and the hit offset this kind has nowhere to put. Every "
                        + "block-target argument is refused on a kind that has nowhere to put it, never "
                        + "dropped: 'hold' names no block (it holds the use in the air), 'dig' names "
                        + "a block and a face but takes no hitX/hitY/hitZ (the within-block hit point "
                        + "id or a slot number and no block at all. "
                        + "'craft' spends ingredients from your bag and puts the output there, by "
                        + "clicking the crafting window that is ALREADY OPEN -- give it 'item', the "
                        + "registry name of what to make, and run craft_plan on that name FIRST: "
                        + "craft_plan is the read-only half (grid, bill of what you are short, "
                        + "whether a recipe exists at all) and act_set kind='craft' is the half "
                        + "that spends things. It is multi-tick like a dig or a hold -- it waits "
                        + "out a server round trip before it will believe its own placements, "
                        + "because a click the server rejects is dropped in silence and a craft "
                        + "that kept clicking would report itself finished having done nothing -- "
                        + "so this call returns while it is still RUNNING, and act_status carries "
                        + "the outcome a tick or more later. Open the window first (right-click a "
                        + "bench with kind='place', or use your own 2x2 grid); a 3x3 recipe in a 2x2 "
                        + "grid is REFUSED by name rather than half-placed. act_cancel stops a "
                        + "running craft and returns anything left in the grid to your bag, which "
                        + "matters because vanilla DROPS a grid's contents on window close and "
                        + "world_view cannot see them. A craft never walks anywhere, opens nothing, "
                        + "and never picks what to make: an unmakeable item, a missing ingredient "
                        + "and a window that is not a crafting grid are each refused by name "
                        + "rather than attempted. "
                        + "'drop' is the one kind that acts on YOU rather than the world: it throws "
                        + "the WHOLE stack in the named 'slot' 0-35 onto the floor, which is what "
                        + "makes room in a full bag -- and it RE-READS that slot and confirms it "
                        + "before reporting success, so a slot outside 0-35 is refused at the "
                        + "boundary rather than clamped onto somebody else's stack. "
                        + "'craft' is the other kind that acts on YOU rather than the world, and it "
                        + "is the only one that ADDS to your bag rather than emptying it. "
                        + "'hold' is the sustained one: it keeps vanilla's "
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
                        + "Returns accepted, tickNow, seamArmed, runnable, assumptions, per-slot "
                        + "effectiveTick, and per-slot phase under 'perSlot'. READ seamArmed FIRST: "
                        + "false means the tick seam has never run a single tick, so EVERY intent in "
                        + "this reply is stored and will sit at IDLE forever no matter how correct it "
                        + "was. That is a dead act layer, not a slow one, and seam_tick_enable is "
                        + "what arms it. seamArmed is false exactly when tickNow is 0 -- the one game "
                        + "clock, the same value clock_now reports (monotonic, 0 before the first tick "
                        + "/ if the tick seam is not armed) -- so you do not have to compare two calls "
                        + "to find out. 'runnable' is that same fact per slot and is a PREDICTION, not "
                        + "an observation: true means the seam should step it, not that it has. "
                        + "'assumptions' lists in words anything the tool decided on your behalf "
                        + "(today: the facing of a dig sent without a 'face'); an empty list means it "
                        + "chose nothing for you. With seamArmed true, accepted:true then means "
                        + "exactly what it says -- the intent is in the slot -- and for the outcome "
                        + "read act_status one or more ticks later; its phase is the one that moves. "
                        + "'perSlot' is the phase BEFORE the intent has run -- it is read at SUBMIT "
                        + "time, so on a successful submit it is ALWAYS IDLE and says NOTHING about "
                        + "the seam's health or the intent's fate.")
                .inputSchema(actSetInputSchema())
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

            // Parse EVERY present channel before submitting ANY of them. Submitting as each channel
            // was parsed meant a later channel's validation error arrived AFTER an earlier channel had
            // already started: {"move":{...},"look":{"mode":"spin"}} began the walk and then reported a
            // clean error, so the caller's model (an error means nothing happened) and the live player
            // disagreed, and only act_status would ever say so. ActPlanStep.parse has always validated
            // a whole step before the interpreter submits it; act_set now matches it.
            Map<String, Object> moveArg = ActIntentParser.mapArg(args, "move");
            Map<String, Object> lookArg = ActIntentParser.mapArg(args, "look");
            Map<String, Object> interactArg = ActIntentParser.mapArg(args, "interact");

            ActIntent move;
            try {
                move = moveArg == null ? null : ActIntentParser.parseMoveSlot(moveArg);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            LookIntent look;
            try {
                look = lookArg == null ? null : ActIntentParser.parseLook(lookArg);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            InteractIntent interact;
            try {
                interact = interactArg == null ? null : ActIntentParser.parseInteract(interactArg);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
            if (move == null && look == null && interact == null) {
                return error("act_set: supply at least one of 'move', 'look', 'interact'");
            }

            // Every named channel validated, so the submissions below cannot fail. A submit takes the
            // tick at the moment it happens, which is why they are still done one at a time rather than
            // as one batch: each slot's effectiveTick is stamped when its intent really lands.
            Map<String, Object> effectiveTick = new LinkedHashMap<>();
            Map<String, Object> perSlot = new LinkedHashMap<>();

            if (move != null) {
                SlotRecord r = runtime.submit(move);
                effectiveTick.put("move", r.effectiveTick());
                perSlot.put("move", r.phase().name());
            }
            if (look != null) {
                SlotRecord r = runtime.submitLook(look);
                effectiveTick.put("look", r.effectiveTick());
                perSlot.put("look", r.phase().name());
            }
            if (interact != null) {
                SlotRecord r = runtime.submitInteract(interact);
                effectiveTick.put("interact", r.effectiveTick());
                perSlot.put("interact", r.phase().name());
            }

            long tickNow = runtime.status().tickNow();
            // F4. What the reply says about whether the intent WILL run.
            //
            // The three outcomes -- accepted and running, refused, and accepted into a slot nothing
            // is stepping -- used to share one byte string, because `accepted` is true for two of them
            // and `perSlot` is read at SUBMIT time so it is IDLE on both. A model reading `accepted:
            // true` believed the player was walking, and the player was not: the tick seam had never
            // been armed, so the intent sat in its slot forever.
            //
            // Two fields, and the split is deliberate. `seamArmed` is the ONE thing submit can
            // actually observe: the game clock is at 0, so no tick has ever completed and nothing
            // will step these slots. It is a READ of a value that already exists in this reply, not
            // an inference across two calls, which is what the old description asked the model to
            // do. And it is reported as its own boolean rather than left for a reader to derive from
            // `tickNow == 0`, because deriving it is exactly the step a weak model skips.
            //
            // `runnable` is per slot and honest about being a PREDICTION: false means this seam will
            // not step it, true means it should and has NOT been observed to. It is deliberately not
            // named `ok` or `active`, because a slot at effectiveTick N+1 genuinely has not run yet
            // and a field claiming otherwise would be the same defect one level down.
            boolean seamArmed = tickNow > 0;
            Map<String, Object> runnable = new LinkedHashMap<>();
            for (String slot : perSlot.keySet()) {
                runnable.put(slot, seamArmed);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("tickNow", tickNow);
            // Only ever false, and that is the point: a model can branch on this without comparing
            // two calls. A refusal never reaches this reply (it is isError with a reason), so
            // `accepted: true` now means exactly one thing -- the intent is in the slot.
            out.put("seamArmed", seamArmed);
            out.put("runnable", runnable);
            // Whatever the tool decided on the caller's behalf, in words. Absent-on-purpose as an
            // empty list rather than omitted: an empty list says "we looked and chose nothing",
            // which is a different answer from a field that is not there at all.
            out.put("assumptions", ActIntentParser.defaultsApplied(interactArg));
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
                        + "A step whose channel is already held by a live act_set is BLOCKED, not "
                        + "failed: the plan stays on that index, names the holder in message, lists "
                        + "the slot in waitingOn, and retries every tick until the holder finishes, "
                        + "you cancel that channel, or the holder's lease goes idle for ~40 ticks and "
                        + "is reclaimed. Before this existed a step silently overwrote whatever was "
                        + "in the slot and reported itself as running. A blocked step submits NOTHING "
                        + "for the slot it is blocked on. "
                        + "Refuse empty steps, a step with no move/look/interact, unknown keys "
                        + "(wait/eval/craft/skill), go_to+walk_straight together, the pre-rename "
                        + "'to'/'route' move keys (both refused, each naming its replacement), raw axes "
                        + "with durationTicks<=0, and look track with durationTicks<=0 (KEEP that never "
                        + "completes). look.durationTicks without track is rejected as on act_set. "
                        + "interact arguments a kind cannot carry are rejected as on act_set -- a "
                        + "block target on 'use', 'hold', 'attack', 'hotbar' or 'drop', and "
                        + "hitX/hitY/hitZ on 'dig'; 'place' is the kind that takes a block, and "
                        + "'drop' is the kind that takes a player inventory slot. "
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


    /**
     * Press or release a raw {@code KeyBinding}, the way a physical key does.
     *
     * <p><b>Why this exists.</b> Without it the agent cannot open its own inventory. The 'E' key
     * is a {@code KeyBinding} read inside {@code Minecraft.runTick}, not a character delivered to
     * {@code GuiScreen.keyTyped}, which is all {@code gui_press_key} drives. And
     * {@code do_click_slot} on windowId 0 needs the inventory screen to already be open. So the
     * 2x2 crafting grid was unreachable, and with it the whole chain
     * log to planks to sticks to crafting table to torch to pickaxe to bed. The first measured
     * run of the round had to shelter bareshand for that reason alone.
     *
     * <p><b>This is not a back door around the UI.</b> Vanilla itself consumes a real key in
     * exactly two calls, at {@code Minecraft.java:1899-1907}:
     *
     * <pre>
     *   int k = getEventKey() == 0 ? getEventCharacter() + 256 : getEventKey();
     *   KeyBinding.setKeyBindState(k, getEventKeyState());
     *   if (getEventKeyState()) KeyBinding.onTick(k);
     * </pre>
     *
     * <p>This tool issues those same calls in the same order, on the game thread, so
     * {@code KeyBinding.onTick} dispatches to the same handlers a person's keystroke reaches and
     * the inventory opens the ordinary way. What is absent is the LWJGL event itself, and that is
     * stated in the reply rather than hidden: {@code seam_glfw_key_hook} stays a pure observer
     * (GLFW to EventBus), so nothing downstream can claim to have seen a hardware event.
     *
     * <p>PAIR EVERY PRESS WITH A RELEASE. {@code setKeyBindState(k, true)} leaves the binding
     * held, and a held movement key is a player walking into a wall forever.
     */
    SyncToolSpecification pressKeyBinding() {
        Tool tool = Tool.builder()
                .name("press_key_binding")
                .title("Press or release a raw game key binding")
                .description("[requires: in-world, connected-to-server] Press (phase=PRESS) or "
                        + "release (phase=RELEASE) a raw KeyBinding by name ('E', 'Escape', "
                        + "'F3', 'W', ...) or numeric LWJGL key code. This is the path the "
                        + "inventory key ('E') lives on: a KeyBinding read in Minecraft.runTick, "
                        + "which no GuiScreen key event reaches, so gui_press_key cannot open the "
                        + "inventory. It issues exactly the two calls vanilla issues for a real "
                        + "keystroke (KeyBinding.setKeyBindState, then KeyBinding.onTick on "
                        + "press), on the game thread, so the game treats it as a normal key "
                        + "press. NO LWJGL EVENT IS SYNTHESISED and no key hook sees this; no GL "
                        + "happens here, so it is safe from any thread. PAIR EVERY PRESS WITH A "
                        + "RELEASE -- a binding left pressed stays held. An unknown key name is "
                        + "REFUSED with the numeric escape hatch, never guessed.")
                .inputSchema(objectSchema(Map.of(
                        "key", Map.of("type", "string",
                                "description", "key name ('E','Escape','F3','W','space') or a "
                                        + "numeric LWJGL key code"),
                        "phase", Map.of("type", "string",
                                "enum", java.util.List.of("PRESS", "RELEASE"),
                                "description", "PRESS or RELEASE. Defaults to PRESS.")),
                        List.of("key")))
                .annotations(ToolAnnotations.builder()
                        .title("Press or release a raw game key binding")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(true)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            Object kobj = args == null ? null : args.get("key");
            if (kobj == null) {
                return error("key is required: a key name or a numeric LWJGL key code");
            }
            Integer code = keyBindingCode(String.valueOf(kobj));
            if (code == null) {
                return error("unknown key: " + kobj + " -- pass a single character ('E'), a "
                        + "known name ('Escape', 'Return', 'Tab', 'Space', 'Backspace', "
                        + "'Shift', 'Control', 'F1'..'F12', 'Up', 'Down', 'Left', 'Right'), "
                        + "or a numeric LWJGL key code");
            }
            String phase = String.valueOf(args.getOrDefault("phase", "PRESS"));
            boolean press = "PRESS".equalsIgnoreCase(phase);
            final int k = code;
            try {
                return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                    // Vanilla's own pair, in vanilla's own order.
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(k, press);
                    if (press) {
                        net.minecraft.client.settings.KeyBinding.onTick(k);
                    }
                    return ok("key=" + kobj + " code=" + k + " phase="
                            + (press ? "PRESS" : "RELEASE") + " bindingClaimed="
                            + bindingClaims(k));
                });
            } catch (Exception e) {
                return error("press_key_binding failed: " + e.getMessage());
            }
        });
    }

    /**
     * Name or character to the key code this build actually uses, or {@code null} if unknown.
     *
     * <p><b>These are the shim's constants, referenced, never literals.</b> The first version
     * of this method computed letters as {@code 48 + (c - 'a')} and function keys as
     * {@code 289 + n}, on the assumption that LWJGL key codes were contiguous starting at
     * KEY_A = 48. They are not. {@code lwjgl2-shim} assigns USB HID usage codes, so KEY_A = 0x1E
     * (30) and KEY_Z = 0x2C (44) -- fifteen apart, not twenty-five, and not a contiguous range
     * at all. Guessing produced a mapper that silently sent 'A' to the wrong key. Two tests
     * caught it, which is the only reason it was not shipped.
     *
     * <p>Referencing the constants also means the mapping follows the shim if it ever changes,
     * instead of becoming a second source of truth about key codes that quietly diverges.
     *
     * <p>Refusal rather than a guess is deliberate: an unknown name resolving to some nearby key
     * would open a menu nobody asked for while the run record showed a successful call.
     */
    private static java.lang.reflect.Field shimKeyMap;

    static Integer keyBindingCode(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT).trim();
        if (k.isEmpty()) {
            return null;
        }
        switch (k) {
            case "escape": case "esc":
                return org.lwjgl.input.Keyboard.KEY_ESCAPE;
            case "return": case "enter":
                return org.lwjgl.input.Keyboard.KEY_RETURN;
            case "tab":
                return org.lwjgl.input.Keyboard.KEY_TAB;
            case "space":
                return org.lwjgl.input.Keyboard.KEY_SPACE;
            case "backspace": case "back":
                return org.lwjgl.input.Keyboard.KEY_BACK;
            case "shift": case "lshift": case "rshift":
                return org.lwjgl.input.Keyboard.KEY_LSHIFT;
            case "control": case "ctrl": case "lcontrol":
                return org.lwjgl.input.Keyboard.KEY_LCONTROL;
            case "up":    return org.lwjgl.input.Keyboard.KEY_UP;
            case "down":  return org.lwjgl.input.Keyboard.KEY_DOWN;
            case "left":  return org.lwjgl.input.Keyboard.KEY_LEFT;
            case "right": return org.lwjgl.input.Keyboard.KEY_RIGHT;
            default:
                break;
        }
        if (k.matches("f([1-9]|1[0-2])")) {
            int n = Integer.parseInt(k.substring(1));
            int[] codes = {
                org.lwjgl.input.Keyboard.KEY_F1,  org.lwjgl.input.Keyboard.KEY_F2,
                org.lwjgl.input.Keyboard.KEY_F3,  org.lwjgl.input.Keyboard.KEY_F4,
                org.lwjgl.input.Keyboard.KEY_F5,  org.lwjgl.input.Keyboard.KEY_F6,
                org.lwjgl.input.Keyboard.KEY_F7,  org.lwjgl.input.Keyboard.KEY_F8,
                org.lwjgl.input.Keyboard.KEY_F9,  org.lwjgl.input.Keyboard.KEY_F10,
                org.lwjgl.input.Keyboard.KEY_F11, org.lwjgl.input.Keyboard.KEY_F12,
            };
            return codes[n - 1];
        }
        if (k.length() == 1) {
            // A single character that the shim NAMES must resolve by name. The numeric escape
            // hatch below would otherwise swallow it: Integer.parseInt("0") is 0, so pressing
            // the digit row sent KEY_NONE and silently did nothing. A test caught this by
            // expecting KEY_0 and getting 0 -- the two look identical in a log.
            Integer named = shimKeyNamed(k.toUpperCase(java.util.Locale.ROOT));
            if (named != null) {
                return named;
            }
        }

        // Only now does the numeric form apply, and it is the escape hatch for a key this build
        // does not name under any spelling we know.
        try {
            int n = Integer.parseInt(k);
            return n >= 0 && n <= 0xFFFF ? n : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * The shim's own name to code map, read reflectively, or {@code null} if absent.
     *
     * <p>Reading the map rather than an offset means a character cannot drift from the constant
     * that names it, and it means this file carries no second opinion about key codes.
     */
    private static Integer shimKeyNamed(String name) {
        try {
            if (shimKeyMap == null) {
                shimKeyMap = org.lwjgl.input.Keyboard.class.getDeclaredField("keyMap");
                shimKeyMap.setAccessible(true);
            }
            Object v = shimKeyMap.get(null);
            if (v instanceof java.util.Map<?, ?> m) {
                Object hit = m.get(name);
                if (hit instanceof Integer i) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
            // If the field ever moves, the caller falls through to the numeric form.
        }
        return null;
    }

    /**
     * Whether any registered binding claims this code, read from vanilla's own binding array.
     *
     * <p>Reporting this is the difference between "pressed E" and "pressed a code nothing is
     * bound to", which look identical from outside and mean very different things in a run
     * record. The array is walked rather than queried because 1.8.9's KeyBinding has no public
     * lookup by code.
     */
    private static java.lang.reflect.Field bindingArray;

    private static boolean bindingClaims(int code) {
        try {
            if (bindingArray == null) {
                // The field is keybindArray, not "keybinds". Reading the wrong name made this
                // answer false for EVERY key -- including 'E', which demonstrably opened the
                // inventory on the live client. A report field that is always wrong is worse
                // than no report field: it teaches the reader to ignore it.
                bindingArray = net.minecraft.client.settings.KeyBinding.class
                        .getDeclaredField("keybindArray");
                bindingArray.setAccessible(true);
            }
            Object v = bindingArray.get(null);
            if (!(v instanceof List<?> list)) {
                return false;
            }
            for (Object o : list) {
                if (o instanceof net.minecraft.client.settings.KeyBinding b
                        && b.getKeyCode() == code) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // If the field ever moves, this answers "no" rather than throwing at the caller.
        }
        return false;
    }

    SyncToolSpecification actCancel() {
        Tool tool = Tool.builder()
                .name("act_cancel")
                .title("Cancel actuation intents")
                .description("[requires: in-world, -javaagent] Cancel actuation channels. The teardown "
                        + "runs on the tick seam like everything else here, so without the seam armed "
                        + "a cancel cannot complete either -- check act_status.tickNow. "
                        + "'slots' is EITHER the string \"all\" -- cancel every slot, and cancel a "
                        + "running act_plan -- or an array of slot names "
                        + "('move'|'look'|'interact'). Omitting 'slots' cancels all. A live intent is "
                        + "flagged for a clean teardown on its next game tick before it ends "
                        + "CANCELLED; an idle/terminal slot is reset. Returns the list of slots for "
                        + "which a LIVE intent was flagged -- an empty list means there was nothing "
                        + "live to cancel, which is not the same as a cancel that succeeded.")
                .inputSchema(objectSchema(Map.of("slots", cancelSlotsSchema()), List.of()))
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
                        return error("act_cancel: unknown slot '" + o + "' -- an ARRAY names slots "
                                + "individually (" + slotNames() + "). To cancel every slot pass the "
                                + "bare string \"all\" as 'slots' itself, not as an element");
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

    /**
     * {@code slots} as the handler ACTUALLY accepts it: the string {@code "all"}, or an array of
     * slot names.
     *
     * <p><b>This was the recovery tool's own documented form being rejected at the boundary.</b>
     * The schema said {@code {"type":"array"}} while the description, the handler's
     * {@link #isAll} branch and the handler's own error text all said the string {@code "all"}
     * was valid -- and {@code IoProbe} enforces {@code type} before dispatch, so a model sending
     * exactly what the tool told it to send got a schema rejection and never reached the code that
     * would have honoured it. On the tool an agent reaches for <i>when something has gone
     * wrong</i>, that is the worst possible place for the documented recovery to be unsendable.
     *
     * <p><b>Why {@code oneOf} and not a widened {@code type}.</b> JSON Schema has no
     * "array or this one string", and the alternative that would type-check at L7 -- making the
     * property untyped -- would remove the array check from {@code IoProbe} entirely and leave the
     * handler as the only gate. Declaring both branches keeps the slot names an {@code enum} a
     * model can be handed by completion, and leaves the type gate where it was for every tool that
     * has not opted into this shape.
     *
     * <p>The description sits ONCE on this node, not on either branch: repeating it would put the
     * same sentence in the tools/list payload twice over, which is what
     * {@code DescriptionsNameToolsThatExistTest} exists to catch.
     */
    private static Map<String, Object> cancelSlotsSchema() {
        Map<String, Object> array = new LinkedHashMap<>();
        array.put("type", "array");
        array.put("items", Map.of("type", "string", "enum", slotNameList()));

        Map<String, Object> all = new LinkedHashMap<>();
        all.put("type", "string");
        all.put("enum", List.of("all"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("description", "EITHER the string \"all\" -- cancel every slot and cancel a running "
                + "act_plan -- or an array of slot names to cancel (" + slotNames() + "). Omitting "
                + "it cancels all. Note the two shapes: \"all\" is the value of 'slots' ITSELF, "
                + "never an element of the array.");
        m.put("oneOf", List.of(array, all));
        return m;
    }

    /** The slot names as they appear in the schema's enum, derived from the enum itself. */
    private static List<String> slotNameList() {
        List<String> names = new ArrayList<>(ActSlot.values().length);
        for (ActSlot slot : ActSlot.values()) {
            names.add(slot.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** The same names in the pipe-separated form a refusal quotes. */
    private static String slotNames() {
        return String.join("|", slotNameList());
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
                        + "intentKind, ticksActive, message, heldBy, hazard, belief, unreadCells, tactic} — plus "
                        + "'plan', the sidecar "
                        + "sequencer (not a fourth slot): {phase (IDLE|RUNNING|COMPLETE|FAILED|"
                        + "CANCELLED), index (0-based current step), size, waitingOn (slot names the "
                        + "current step still needs COMPLETE), message}. IDLE plan means none is "
                        + "bound. The next step is submitted only once every slot in waitingOn is "
                        + "COMPLETE with the same intent identity. Reference-free. Use it to see "
                        + "'what am I doing right now and did the last thing finish' without a "
                        + "screenshot. "
                        + "'tickNow' is the one game clock's current tickId, the same value "
                        + "clock_now reports: monotonic, and 0 before the first tick / if the tick "
                        + "seam is not armed. Read it FIRST, because the act layer only steps on "
                        + "that seam: a tickNow of 0, or one that does not grow between two calls, "
                        + "means nothing is driving the appliers and every intent you submit will "
                        + "sit at IDLE forever no matter how correct it was. That is a dead act "
                        + "layer, not a slow one, and seam_tick_enable is what arms it. "
                        + "'hasIntent' says only that the slot HOLDS an intent record, not that it "
                        + "is doing anything: a slot keeps its intent after reaching a terminal "
                        + "phase, so hasIntent stays true once COMPLETE, FAILED or CANCELLED and "
                        + "only a slot never used since startup reports false. For 'is this channel "
                        + "busy' read hasIntent AND a non-terminal phase; phase is the field that "
                        + "answers it. "
                        + "'heldBy' is the LEASE on the channel: null when nothing holds it, and "
                        + "otherwise '<owner>/<priority>'. In the shipped system that is exactly two "
                        + "possible values: 'act_set/DIRECT' (a command you just issued) and "
                        + "'act_plan/REPLAY' (a step of a bound act_plan being replayed). BE READY "
                        + "FOR THIS TO BE MOSTLY ONE VALUE: every act_set submits as act_set/DIRECT, "
                        + "which outranks act_plan/REPLAY, so on the live path you will normally see "
                        + "act_set/DIRECT or null and nothing else. act_set is NEVER refused, so "
                        + "heldBy=act_set/DIRECT does NOT mean you are being blocked; it means the "
                        + "channel is yours. You are only blocked when you sent act_plan and its "
                        + "message names a holder you did not expect -- read plan.message then, not "
                        + "heldBy. A lease whose owner's intent is not being advanced is reclaimed "
                        + "after about 40 ticks and the channel frees itself, so heldBy stuck on one "
                        + "value while phase does not advance means that. "
                        + "'hazard' is null on every slot that is not walking a straight line, and "
                        + "otherwise {kind (LAVA|WATER|DEEP_DROP), x, y, z, blocksAhead, detail} "
                        + "for something sampled on the line ahead of the player. THIS IS AN EARLY "
                        + "WARNING, NOT A POSTMORTEM: it is present on every walking tick, including "
                        + "the first, so poll while you walk and you learn about the lava or the "
                        + "drop while there are still blocks left to turn around on. "
                        + "'blocksAhead' is SIGNED and updates as the player walks: positive is "
                        + "still ahead of them, negative means they have walked past that cell, and "
                        + "the hazard is kept reported either way rather than being deleted when it "
                        + "goes behind. It is a SAMPLE one block at a time, so it cannot promise the "
                        + "rest of the line is clear. walk_straight never routes around anything and "
                        + "never refuses to move because of this — it warns and it walks, which is "
                        + "why the field exists: cancel it yourself, or use go_to, which routes. "
                        + "A hazard is never guessed from a cell that could not be read, so an "
                        + "unloaded chunk reports no hazard rather than a bottomless pit. "
                        + "'belief' grades the 'message' beside it: null, or OBSERVED (read it "
                        + "directly), INFERRED (derived from a read by a named derivation), or "
                        + "UNKNOWN (somebody looked and COULD NOT SEE -- a claim about this "
                        + "client's view of the world, not about the world). NULL AND UNKNOWN ARE "
                        + "DIFFERENT ANSWERS AND THE DIFFERENCE IS THE POINT: null means no part "
                        + "of the system examined that sentence, so there is nothing behind it to "
                        + "trust or doubt; UNKNOWN means something DID examine it and the answer "
                        + "was 'I could not see' -- for a refused go_to that usually means "
                        + "'unreadCells' is the HOW MUCH beside that WHETHER, and it is the same distinction made checkable: a number means a search ran and could not read that many of the cells it asked about, so 412 is a chunk-loading problem quantified rather than guessed at; null means the line is not about a searched area at all, which is NOT the same as 0 ('a search ran and read everything'). Never read a null as 0. "
+ "unloaded chunks rather than impassable ground, so the response is to "
                        + "load chunks and ask again, not to reroute. Only OBSERVED and INFERRED "
                        + "are claims you may act on without looking yourself. So a go_to refused "
                        + "over 400 unread cells and an ordinary 'still walking' line now read "
                        + "differently (UNKNOWN vs null), where before both said UNKNOWN and the "
                        + "difference was lost. "
                        + "'tactic' is what the walk DECIDED this tick -- {forward, strafe, jump, "
                        + "yawChange, lane, givenUp, describe} -- and it is null for every slot that "
                        + "is not go_to, INCLUDING walk_straight, which genuinely publishes no "
                        + "tactic: only a route has somewhere to record a decision. A null there is "
                        + "honest, not missing. 'givenUp' is the field worth reading: NOTHING means "
                        + "the direct line, taken, with no option spent; ARRIVED_AFTER_A_LANE means "
                        + "it arrived but had to give up the direct line for a lane; LIMITS_REACHED "
                        + "means every recovery it had failed; OUT_OF_TICKS means the clock ran out "
                        + "while it was still making progress (raise the budget, do not reroute); "
                        + "THE_UNNAMED_STALL and THE_UNSURVEYED_WEDGE mean it could not read what "
                        + "was in the way, so no option could even be chosen; THE_UNMOVABLE_MEDIUM "
                        + "means a ladder or current is not carrying the body. They are separate "
                        + "values because they cost you different things. The terminal row is the "
                        + "one to read for what a walk spent: mid-walk ticks each describe one tick, "
                        + "and the last one is the summary.")
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
                row.put("heldBy", runtime.leaseHolder(s.slot()));
                row.put("hazard", hazardObject(s.hazard()));
                row.put("belief", s.belief() == null ? null : s.belief().name());
                // Absent, not zero, when there is no count: null says this line is not a
                // statement about a searched area, and 0 would claim a search ran and found nothing
                // unread -- a stronger claim about the world than any site made.
                row.put("unreadCells", s.unreadCells());
                row.put("tactic", tacticObject(s.tactic()));
                slots.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tickNow", st.tickNow());
            out.put("slots", slots);
            out.put("plan", planObject(runtime.planStatus()));
            return ok(Json.write(out));
        });
    }

    /**
     * The typed hazard as a JSON object, or null when no hazard is known.
     *
     * <p>Projected by hand rather than handed to {@code Json} as the record, for two reasons.
     * {@code kind} is emitted as its enum NAME and not as whatever {@code toString} happens to
     * produce, because a caller branches on this value and a renamed constant would silently
     * change what it branches on. And {@code blocksAhead} is emitted as the signed number it
     * actually is -- a caller that sees "2.4 blocks ahead" for a cell the player is standing on
     * has been told a falsehood, which is the specific failure mode this whole field exists to
     * remove.
     *
     * <p>Null rather than an empty object for "nothing known", so absence is unambiguous: an
     * absent hazard and a hazard whose details failed to serialise must not look alike.
     */
    private static Map<String, Object> hazardObject(NavHazard hz) {
        if (hz == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", hz.kind().name());
        row.put("x", hz.x());
        row.put("y", hz.y());
        row.put("z", hz.z());
        row.put("blocksAhead", hz.blocksAhead());
        row.put("detail", hz.detail());
        return row;
    }

    /**
     * The movement decision as a JSON object, or null when none has been chosen.
     *
     * <p>Null rather than an empty object for "nothing chosen", so absence is unambiguous: a caller
     * cannot read an absent tactic as a tactic that chose nothing. That distinction is not
     * hypothetical here -- {@code walk_straight} publishes no tactic at all, because only a
     * {@code RouteIntent} has anywhere to stamp one.
     *
     * <p>{@code describe} is emitted alongside the fields rather than instead of them. The fields
     * are what a caller branches on and they are the reason this exists; the sentence is what a
     * model reads first, and {@link MoveTactic#describe()} names every axis as the KEY it is
     * rather than as the float it is stored as -- "forward 1.0" is not a number a pair of fingers
     * can produce.
     *
     * <p>Projection is by hand for the reason {@link #hazardObject} is: {@code givenUp} is emitted
     * as its enum NAME because a caller branches on it, and a renamed constant would silently
     * change what it branches on.
     */
    private static Map<String, Object> tacticObject(MoveTactic t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("forward", t.forward());
        row.put("strafe", t.strafe());
        row.put("jump", t.jump());
        row.put("yawChange", t.yawChange());
        row.put("lane", t.lane() == null ? null : t.lane().describe());
        row.put("givenUp", t.givenUp().name());
        row.put("describe", t.describe());
        return row;
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
