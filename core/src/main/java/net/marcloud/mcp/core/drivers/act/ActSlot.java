package net.marcloud.mcp.core.drivers.act;

/**
 * The three independent actuation channels the AI can drive concurrently. Each
 * slot holds at most one live intent at a time (a new submit replaces the old),
 * so the slots are orthogonal: the player can be walking ({@link #MOVE}) while
 * slewing the camera ({@link #LOOK}) while mining a block ({@link #INTERACT}).
 *
 * <p>Slots exist because these three families of action have different cadences
 * and are applied through different game seams — movement through the per-tick
 * {@code MovementInput}, look through the rotation fields, interaction through
 * {@code PlayerControllerMP}. Keeping them separate lets each run its own state
 * machine without one blocking another.
 */
public enum ActSlot {
    /** Locomotion: forward/strafe/jump/sneak/sprint, applied via {@code MovementInput}. */
    MOVE,
    /** Camera aim: absolute set or slew-to-target, applied via the rotation fields. */
    LOOK,
    /** World interaction: dig / use / place / attack / hotbar select. */
    INTERACT
}
