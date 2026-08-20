package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An immutable parsed sequence of {@link ActPlanStep}s. Not an {@link ActIntent}
 * and not a fourth {@link ActSlot}: the interpreter submits ordinary intents onto
 * the existing three channels, one step at a time.
 *
 * <p>{@link #parse(List)} is the submit-time gate. Unknown verbs, empty steps, and
 * unbounded axes / KEEP looks are refused here so a plan that cannot finish never
 * occupies the runtime.
 */
public final class ActPlan {

    private final List<ActPlanStep> steps;

    private ActPlan(List<ActPlanStep> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * Parse {@code steps} from {@code act_plan}'s argument list. Each element is
     * one {@code act_set}-shaped object.
     *
     * @throws IllegalArgumentException empty list, non-object step, unknown key,
     *                                  missing channel, or unbounded step
     */
    @SuppressWarnings("unchecked")
    public static ActPlan parse(List<?> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            throw new IllegalArgumentException("act_plan: 'steps' must be a non-empty array");
        }
        List<ActPlanStep> parsed = new ArrayList<>(rawSteps.size());
        for (int i = 0; i < rawSteps.size(); i++) {
            Object o = rawSteps.get(i);
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("act_plan step " + i + " must be an object");
            }
            parsed.add(ActPlanStep.parse((Map<String, Object>) m, i));
        }
        return new ActPlan(parsed);
    }

    public List<ActPlanStep> steps() {
        return steps;
    }

    public int size() {
        return steps.size();
    }
}
