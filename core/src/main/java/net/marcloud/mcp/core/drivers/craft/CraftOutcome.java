package net.marcloud.mcp.core.drivers.craft;

/**
 * The result of one {@link CraftController} tick.
 *
 * <p>Same four-way shape as {@code ActOutcome} and deliberately a separate type. The act package's
 * outcome is documented in terms of an INTERACT slot's lifecycle and is the vocabulary of an
 * applier that owns a player's body; a craft is a sequence of container clicks with no slot behind
 * it. Reusing that record would have made this package depend on the act package's phase enum for
 * nothing but the word "ACTIVE", and would have suggested the craft is tickable by
 * {@code ActTickLoop}, which it is not -- who ticks it is not decided yet.
 *
 * @param terminal true once the machine has finished; no further tick will change it
 * @param ok       for a terminal outcome, whether it succeeded (meaningless while non-terminal)
 * @param message  what it did, or precisely why it could not
 */
public record CraftOutcome(boolean terminal, boolean ok, String message) {

    /** Still working; not terminal. */
    public static CraftOutcome running(String message) {
        return new CraftOutcome(false, false, message);
    }

    /** The output is in the inventory and the matrix is empty. */
    public static CraftOutcome done(String message) {
        return new CraftOutcome(true, true, message);
    }

    /** Could not be carried out honestly. Says what is short, or what the window did. */
    public static CraftOutcome failed(String message) {
        return new CraftOutcome(true, false, message);
    }

    /** Finished because it was cancelled. */
    public static CraftOutcome cancelled(String message) {
        return new CraftOutcome(true, false, message);
    }
}
