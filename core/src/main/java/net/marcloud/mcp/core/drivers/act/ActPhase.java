package net.marcloud.mcp.core.drivers.act;

/**
 * Lifecycle of the intent currently occupying a slot. A freshly-submitted intent
 * starts {@link #IDLE} (accepted, not yet applied by a game tick), becomes
 * {@link #ACTIVE} once the first tick at/after its {@code effectiveTick} applies
 * it, and ends in exactly one terminal state.
 *
 * <p>{@link #isTerminal()} is the single source of truth for "this slot is done";
 * a terminal slot is left in place so the caller can read the outcome via
 * {@code act_status} until it submits something new or cancels.
 */
public enum ActPhase {
    /** Submitted and accepted; waiting for the first tick at/after its effectiveTick. */
    IDLE,
    /** Being applied on the game thread each tick. */
    ACTIVE,
    /** Finished successfully (target reached / block broken / interaction sent). */
    COMPLETE,
    /** Could not be carried out honestly (no target, out of reach, seam refused). */
    FAILED,
    /** Superseded or explicitly cancelled before completing. */
    CANCELLED;

    /** True for {@link #COMPLETE}, {@link #FAILED}, {@link #CANCELLED} — no more ticks will change it. */
    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED || this == CANCELLED;
    }
}
