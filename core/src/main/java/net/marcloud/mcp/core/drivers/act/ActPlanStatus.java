package net.marcloud.mcp.core.drivers.act;

import java.util.List;

/**
 * Snapshot of the sidecar sequencer for {@code act_status.plan}. Not a slot
 * status: a plan occupies the existing three channels one step at a time.
 *
 * @param phase     sequencer lifecycle
 * @param index     current step while {@link Phase#RUNNING} (0-based); the failed
 *                  step when {@link Phase#FAILED}
 * @param size      number of steps in the bound plan, or 0 when idle
 * @param waitingOn lowercase slot names the current step still needs
 *                  {@link ActPhase#COMPLETE} on, same identity
 * @param message   last human-readable reason
 */
public record ActPlanStatus(
        Phase phase,
        int index,
        int size,
        List<String> waitingOn,
        String message) {

    public enum Phase {
        IDLE,
        RUNNING,
        COMPLETE,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETE || this == FAILED || this == CANCELLED;
        }
    }

    static ActPlanStatus idle() {
        return new ActPlanStatus(Phase.IDLE, 0, 0, List.of(), "no plan");
    }

    public ActPlanStatus {
        waitingOn = waitingOn == null ? List.of() : List.copyOf(waitingOn);
        message = message == null ? "" : message;
    }
}
