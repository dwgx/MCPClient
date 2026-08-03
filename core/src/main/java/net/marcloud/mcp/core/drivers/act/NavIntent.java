package net.marcloud.mcp.core.drivers.act;

/**
 * "Walk to here" for the {@link ActSlot#MOVE} slot: a destination rather than a pair of axes.
 *
 * <p>This is the first intent in the package that states an OUTCOME instead of an input. A
 * {@link MoveIntent} says "hold forward"; this says "be at these coordinates", and
 * {@link NavController} works out the axes each tick. That difference is the whole point -- it is
 * what lets one call cross fifteen blocks without an LLM round trip per step.
 *
 * <p><b>Why it lives in the MOVE slot rather than a fourth slot.</b> {@link ActMovementInput} takes
 * locomotion from one {@link MoveIntentView}. A separate slot could be ACTIVE at the same time as
 * MOVE and both would want to drive that single view, so the model would need a precedence rule for
 * a state it can genuinely be in. Sharing the slot means there is exactly one locomotion owner by
 * construction and the ambiguity cannot arise. The controller is a pure state machine over
 * {@link ActActuator}, so moving it to its own slot later is a wiring change rather than a rewrite.
 *
 * <p>Y is carried but not steered toward: this walks, it does not fly or climb. It is kept so a
 * caller can name a full block position -- which is what {@code find_block} returns -- without
 * having to strip a coordinate, and so a later controller that does handle vertical movement has the
 * information it needs.
 *
 * @param targetX      destination X, block or precise
 * @param targetY      destination Y, recorded but not steered toward
 * @param targetZ      destination Z
 * @param timeoutTicks give up after this many ticks; {@code <= 0} takes the controller's default
 */
public record NavIntent(double targetX, double targetY, double targetZ, int timeoutTicks)
        implements ActIntent {

    @Override
    public ActSlot slot() {
        return ActSlot.MOVE;
    }
}
