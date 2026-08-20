package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One plan step: the same maps {@code act_set} accepts, turned into 1-3
 * {@link ActIntent}s on distinct slots. Concurrent channels in one step are
 * submitted together and waited on together.
 */
public final class ActPlanStep {

    private static final Set<String> CHANNELS = Set.of("move", "look", "interact");

    private final ActIntent move;
    private final LookIntent look;
    private final InteractIntent interact;
    private final List<ActIntent> intents;

    private ActPlanStep(ActIntent move, LookIntent look, InteractIntent interact) {
        this.move = move;
        this.look = look;
        this.interact = interact;
        List<ActIntent> out = new ArrayList<>(3);
        if (move != null) {
            out.add(move);
        }
        if (look != null) {
            out.add(look);
        }
        if (interact != null) {
            out.add(interact);
        }
        this.intents = List.copyOf(out);
    }

    /**
     * Parse one step object. Unknown keys, missing channels, unbounded raw axes,
     * and unbounded KEEP looks are refused here so they never occupy the runtime.
     */
    static ActPlanStep parse(Map<String, Object> raw, int index) {
        if (raw == null) {
            throw new IllegalArgumentException("act_plan step " + index + " must be an object");
        }
        for (String k : raw.keySet()) {
            if (k == null || !CHANNELS.contains(k.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("act_plan step " + index + " unknown key '" + k
                        + "' (want move|look|interact; wait/eval/craft/skill are not plan verbs)");
            }
        }
        Map<String, Object> moveMap = presentMap(raw, "move", index);
        Map<String, Object> lookMap = presentMap(raw, "look", index);
        Map<String, Object> interactMap = presentMap(raw, "interact", index);
        if (moveMap == null && lookMap == null && interactMap == null) {
            throw new IllegalArgumentException("act_plan step " + index
                    + ": supply at least one of 'move', 'look', 'interact'");
        }
        ActIntent move = null;
        if (moveMap != null) {
            move = ActIntentParser.parseMoveSlot(moveMap);
            if (move instanceof MoveIntent mi && mi.durationTicks() <= 0) {
                throw new IllegalArgumentException("act_plan step " + index
                        + " raw move axes need durationTicks > 0; durationTicks<=0 holds until "
                        + "cancelled and a plan cannot finish that step");
            }
        }
        LookIntent look = null;
        if (lookMap != null) {
            look = ActIntentParser.parseLook(lookMap);
            if (look.keepsAiming() && look.durationTicks() <= 0) {
                throw new IllegalArgumentException("act_plan step " + index
                        + " look 'track' needs durationTicks > 0; durationTicks<=0 KEEP never "
                        + "completes on its own");
            }
        }
        InteractIntent interact = null;
        if (interactMap != null) {
            interact = ActIntentParser.parseInteract(interactMap);
        }
        return new ActPlanStep(move, look, interact);
    }

    private static Map<String, Object> presentMap(Map<String, Object> raw, String key, int index) {
        if (!raw.containsKey(key)) {
            return null;
        }
        Map<String, Object> m = ActIntentParser.mapArg(raw, key);
        if (m == null) {
            throw new IllegalArgumentException("act_plan step " + index + " '" + key
                    + "' must be an object");
        }
        return m;
    }

    /** Intents this step submits, in MOVE / LOOK / INTERACT order. */
    public List<ActIntent> intents() {
        return intents;
    }

    /** Slots this step touches, enum order. */
    public Set<ActSlot> slots() {
        LinkedHashSet<ActSlot> out = new LinkedHashSet<>();
        for (ActIntent intent : intents) {
            out.add(intent.slot());
        }
        return out;
    }
}
