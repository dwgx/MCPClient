package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sidecar sequencer over {@link ActRuntime}. Binds an {@link ActPlan}, submits
 * the current step via {@code ActRuntime.submit*}, and advances only when every
 * slot that step touched is {@link ActPhase#COMPLETE} with the same intent
 * identity.
 *
 * <p>Not a fourth {@link ActSlot} and not an {@link ActIntent}. It does not tick
 * {@link NavController} / {@link net.marcloud.mcp.core.drivers.act.MoveApplier}
 * itself — {@link ActTickLoop} still drives the slots, then calls
 * {@link ActRuntime#stepPlan(long)}.
 *
 * <p>Wait policy: COMPLETE + identity. FAILED fails the plan and does not submit
 * the next step. CANCELLED or a racing {@code act_set} (identity mismatch) aborts
 * naming supersession. A new bind replaces the previous plan.
 */
public final class ActPlanInterpreter {

    private final ActRuntime runtime;

    private ActPlan plan;
    private ActPlanStatus.Phase phase = ActPlanStatus.Phase.IDLE;
    private int index;
    private List<ActIntent> submitted = List.of();
    private String message = "no plan";

    public ActPlanInterpreter(ActRuntime runtime) {
        this.runtime = runtime;
    }

    /** Replace any previous plan and submit step 0. */
    public synchronized void bind(ActPlan plan) {
        if (plan == null || plan.size() == 0) {
            throw new IllegalArgumentException("act_plan: 'steps' must be a non-empty array");
        }
        cancelWaitingSlots();
        this.plan = plan;
        this.phase = ActPlanStatus.Phase.RUNNING;
        this.index = 0;
        this.message = "running step 0/" + plan.size();
        submitCurrent();
    }

    /** Tear down a live plan without submitting further steps. Idle/terminal is a no-op. */
    public synchronized void cancel() {
        if (phase != ActPlanStatus.Phase.RUNNING) {
            return;
        }
        cancelWaitingSlots();
        phase = ActPlanStatus.Phase.CANCELLED;
        submitted = List.of();
        message = "plan cancelled";
    }

    /**
     * After the slot loop has applied this tick: advance or fail. No-op when no
     * plan is running. Must not tick locomotion controllers itself.
     */
    public synchronized void step(long tick) {
        if (phase != ActPlanStatus.Phase.RUNNING || plan == null) {
            return;
        }
        for (ActIntent expected : submitted) {
            SlotRecord rec = runtime.record(expected.slot());
            if (rec.intent() != expected) {
                abort(ActPlanStatus.Phase.CANCELLED,
                        "superseded: " + expected.slot().name()
                                + " identity mismatch (racing act_set)");
                return;
            }
            ActPhase p = rec.phase();
            if (p == ActPhase.FAILED) {
                abort(ActPlanStatus.Phase.FAILED,
                        "step " + index + " failed: " + rec.message());
                return;
            }
            if (p == ActPhase.CANCELLED) {
                abort(ActPlanStatus.Phase.CANCELLED,
                        "superseded: " + expected.slot().name() + " CANCELLED");
                return;
            }
            if (p != ActPhase.COMPLETE) {
                return;
            }
        }
        index++;
        if (index >= plan.size()) {
            phase = ActPlanStatus.Phase.COMPLETE;
            submitted = List.of();
            message = "plan complete";
            return;
        }
        message = "running step " + index + "/" + plan.size();
        submitCurrent();
    }

    public synchronized ActPlanStatus status() {
        if (plan == null) {
            return ActPlanStatus.idle();
        }
        return new ActPlanStatus(phase, index, plan.size(), waitingOn(), message);
    }

    private void submitCurrent() {
        ActPlanStep step = plan.steps().get(index);
        List<ActIntent> next = new ArrayList<>(3);
        for (ActIntent intent : step.intents()) {
            SlotRecord rec = runtime.submit(intent);
            next.add(rec.intent());
        }
        submitted = List.copyOf(next);
        message = "running step " + index + "/" + plan.size();
    }

    private void abort(ActPlanStatus.Phase terminal, String reason) {
        cancelWaitingSlots();
        phase = terminal;
        submitted = List.of();
        message = reason;
    }

    /**
     * Cancel slots that still hold our identity. A racing submit is left alone —
     * cancelling it would tear down the act_set that superseded us.
     */
    private void cancelWaitingSlots() {
        for (ActIntent expected : submitted) {
            SlotRecord rec = runtime.record(expected.slot());
            if (rec.intent() == expected && rec.isLive()) {
                runtime.cancel(expected.slot());
            }
        }
    }

    private List<String> waitingOn() {
        if (phase != ActPlanStatus.Phase.RUNNING) {
            return List.of();
        }
        List<String> out = new ArrayList<>(submitted.size());
        for (ActIntent expected : submitted) {
            SlotRecord rec = runtime.record(expected.slot());
            if (rec.intent() != expected || rec.phase() != ActPhase.COMPLETE) {
                out.add(expected.slot().name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
