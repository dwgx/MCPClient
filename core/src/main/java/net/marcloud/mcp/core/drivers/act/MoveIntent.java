package net.marcloud.mcp.core.drivers.act;

/**
 * A locomotion intent for the {@link ActSlot#MOVE} slot. Mirrors the fields the
 * vanilla {@code MovementInput} exposes so {@link ActMovementInput} can copy them
 * straight through each tick while the intent is {@link ActPhase#ACTIVE}.
 *
 * <p>{@code forward}/{@code strafe} follow vanilla sign conventions
 * ({@code forward} +1 = ahead, -1 = back; {@code strafe} +1 = left, -1 = right).
 * {@code durationTicks} bounds how long the input is held: after that many active
 * ticks the slot completes and movement reverts to vanilla. A non-positive
 * duration means "hold until cancelled or replaced".
 *
 * @param forward       forward axis, vanilla sign (+ahead / -back), clamped [-1,1]
 * @param strafe        strafe axis, vanilla sign (+left / -right), clamped [-1,1]
 * @param jump          hold jump this tick
 * @param sneak         hold sneak this tick
 * @param sprint        request sprint (applied via {@code setSprinting})
 * @param durationTicks active-tick budget; {@code <= 0} = until cancelled/replaced
 */
public record MoveIntent(
        float forward,
        float strafe,
        boolean jump,
        boolean sneak,
        boolean sprint,
        int durationTicks) implements ActIntent {

    /**
     * Put the raw axes through the SAME key snap the navigator uses, on construction.
     *
     * <p>They used to be clamped to [-1, 1] and published as arbitrary floats, which is the one
     * way this project could make a player move in a way no person can. Two things follow from a
     * float axis that a key press would not:
     *
     * <ul>
     *   <li><b>It is a tell.</b> Nobody walks at 0.5 speed. A half-forward is not a slow human, it
     *       is a machine with an analogue output.</li>
     *   <li><b>It costs a real property, not just realism.</b> Publishing +-1 here is what lets
     *       vanilla's OWN friction and acceleration do the ramping. Publishing +-0.3 bypasses
     *       exactly that physics, so the player accelerates instantly to a speed no key produces.
     *       The navigator already snapped for this reason; the raw path simply had not caught up,
     *       and the two paths disagreed about what a movement axis is.</li>
     * </ul>
     *
     * <p>Snapping is therefore free realism under ADR-0005: the constraint being imitated is one
     * the real device imposes, and obeying it gets vanilla's physics back as a side effect.
     */
    public MoveIntent {
        double[] keys = NavController.nearestKeys(clamp(forward), clamp(strafe));
        forward = (float) keys[0];
        strafe = (float) keys[1];
    }

    private static float clamp(float v) {
        if (v < -1.0f) {
            return -1.0f;
        }
        if (v > 1.0f) {
            return 1.0f;
        }
        return v;
    }

    @Override
    public ActSlot slot() {
        return ActSlot.MOVE;
    }
}
