package net.marcloud.mcp.core.drivers.act;

/**
 * A reference-free window onto the MOVE slot's current locomotion target, read
 * by {@link ActMovementInput} on the game thread every tick. Owned by this
 * package (not by the runtime) so {@link ActMovementInput} — which must be free
 * of any heavy Core dependency to stay a thin {@code MovementInput} subclass —
 * depends only on this small interface, and {@link ActRuntime} implements it.
 *
 * <p>{@link #moveActive()} gates the whole override: when false, {@code
 * ActMovementInput} delegates entirely to the wrapped vanilla input. When true,
 * the four analog/boolean getters and {@link #sprint()} describe what to force
 * this tick.
 */
public interface MoveIntentView {

    /** True while a MOVE intent is {@link ActPhase#ACTIVE} and should drive input. */
    boolean moveActive();

    /** Forward axis to force this tick (vanilla sign: +ahead / -back). */
    float moveForward();

    /** Strafe axis to force this tick (vanilla sign: +left / -right). */
    float moveStrafe();

    /** Whether to hold jump this tick. */
    boolean jump();

    /** Whether to hold sneak this tick. */
    boolean sneak();

    /** Whether sprint is requested (applied via the player's {@code setSprinting}). */
    boolean sprint();
}
