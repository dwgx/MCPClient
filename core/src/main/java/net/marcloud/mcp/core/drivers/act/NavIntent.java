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
 * <p><b>{@code sneak} is a gait, not a destination, and it is here because vanilla's ledge guard
 * reads a key rather than the terrain.</b> {@code Entity.moveEntity:626-662} opens with
 * {@code boolean flag = onGround && isSneaking() && instanceof EntityPlayer}, and while that holds
 * each horizontal axis is walked back in 0.05 steps until the box one block below is no longer
 * clear. A sneaking player therefore stops at a brink instead of walking off it -- so a walk that
 * will cross a ledge has to be able to ASK for that, and before this field the only way to hold the
 * key was raw axes, which is a form with no destination and no arrival.
 *
 * <p>It is deliberately a field here rather than something {@link NavController} decides. The
 * controller does not know the terrain ahead of the body, and the one that would tell it -- the
 * hazard scan -- already runs and reports without touching the axes. Deciding a creep from a
 * hazard scan would change the walk the caller asked for on the strength of a warning it is
 * entitled to ignore, and {@code hazard} is a report, not a policy. What a creeping walk costs is
 * speed: sneaking scales the movement input to about 0.3 in 1.8.9, so the caller is the only one
 * who can weigh that against arriving.
 *
 * @param targetX      destination X, block or precise
 * @param targetY      destination Y, recorded but not steered toward
 * @param targetZ      destination Z
 * @param timeoutTicks give up after this many ticks; {@code <= 0} takes the controller's default
 * @param sneak        hold the sneak key for the whole walk, so vanilla's ledge guard stops the
 *                     body at a brink instead of walking it off. {@code false} walks at full
 *                     speed, exactly as every route did before this field existed
 */
public record NavIntent(double targetX, double targetY, double targetZ, int timeoutTicks,
                        boolean sneak)
        implements ActIntent {

    /**
     * The four-argument form, which is a walk at full speed.
     *
     * <p>Present because this record is the plan-time vocabulary every existing caller and test
     * was written against, and "an addition to the record" has to mean that. Nothing here is
     * deprecated and nothing routes through a shim: a caller that does not care about creeping
     * says so by not saying anything, and the value it gets is the value it got before.
     */
    public NavIntent(double targetX, double targetY, double targetZ, int timeoutTicks) {
        this(targetX, targetY, targetZ, timeoutTicks, false);
    }

    @Override
    public ActSlot slot() {
        return ActSlot.MOVE;
    }
}
