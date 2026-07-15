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

    /** Clamp the analog axes to the vanilla [-1, 1] range on construction. */
    public MoveIntent {
        forward = clamp(forward);
        strafe = clamp(strafe);
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
