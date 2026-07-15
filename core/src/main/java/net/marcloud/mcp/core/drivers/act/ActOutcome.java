package net.marcloud.mcp.core.drivers.act;

/**
 * The result of one controller {@code tick()} step. Controllers are pure state
 * machines over an {@link ActActuator}; each step returns an outcome describing
 * where the machine now is and whether it finished.
 *
 * @param state    the controller's own state phase, mapped onto {@link ActPhase}
 * @param terminal true once the machine has finished (no more ticks will change it)
 * @param ok       for a terminal outcome: did it succeed? (meaningless while non-terminal)
 * @param message  human-readable status/reason (why it failed, what it did)
 */
public record ActOutcome(ActPhase state, boolean terminal, boolean ok, String message) {

    /** Still running; not terminal. */
    public static ActOutcome running(String message) {
        return new ActOutcome(ActPhase.ACTIVE, false, false, message);
    }

    /** Finished successfully. */
    public static ActOutcome done(String message) {
        return new ActOutcome(ActPhase.COMPLETE, true, true, message);
    }

    /** Finished, but could not be carried out honestly. */
    public static ActOutcome failed(String message) {
        return new ActOutcome(ActPhase.FAILED, true, false, message);
    }

    /** Finished because it was cancelled/superseded. */
    public static ActOutcome cancelled(String message) {
        return new ActOutcome(ActPhase.CANCELLED, true, false, message);
    }
}
