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
 * <p>Wait policy: COMPLETE, same goal, still ours. FAILED fails the plan and does not submit
 * the next step. CANCELLED, a different walk on the slot, or a lease held by a foreign owner
 * aborts naming supersession — and a same-goal racing {@code act_set} is the second of those,
 * not an identity mismatch: it passes the goal test and is caught by the lease. A new bind
 * replaces the previous plan.
 *
 * <p><b>Two questions, two comparisons.</b> "Is this the same WALK?" is
 * {@link ActRuntime#sameIntent}, which is goal-keyed for a route because the MOVE applier
 * re-stamps the record's intent every tick. "Is this still OURS?" is
 * {@link #takenByAnother(ActSlot)}, which reads the lease, because a same-goal racing
 * {@code act_set} is the same walk and not our walk. Asking only the first question is how a
 * plan teardown came to cancel a command the model had just been told was accepted.
 */
public final class ActPlanInterpreter {

    private final ActRuntime runtime;

    private ActPlan plan;
    private ActPlanStatus.Phase phase = ActPlanStatus.Phase.IDLE;
    private int index;
    private List<ActIntent> submitted = List.of();

    /**
     * Slots the CURRENT step could not get a lease on, empty on every other path.
     *
     * <p>This is what makes a blocked plan step observable. The previous behaviour submitted over
     * whatever was in the slot and reported the step as running, so the only evidence a step had
     * clobbered somebody was a status line describing an intent nobody had submitted. A blocked step
     * keeps its index, names the holder in {@code message}, and lists the slot in {@code waitingOn}
     * BEFORE anything is submitted.
     */
    private List<String> leaseBlocked = List.of();

    private String message = "no plan";

    public ActPlanInterpreter(ActRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Replace any previous plan and submit step 0.
     *
     * <p>A bind whose step 0 cannot get its lease still returns a RUNNING plan: it is blocked, not
     * failed, and {@code status()} names the holder. Failing here would be wrong — a plan that waits
     * for a channel is a normal outcome of a model issuing a command while a plan is walking — and
     * the caller can see the difference in {@code message} and {@code waitingOn}.
     */
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
        leaseBlocked = List.of();
        message = "plan cancelled";
    }

    /**
     * After the slot loop has applied this tick: advance or fail. No-op when no
     * plan is running. Must not tick locomotion controllers itself.
     *
     * <p>A step blocked on a lease retries here every tick rather than being reported as running.
     * Retrying is safe because a same-owner re-acquire is always admitted, so the attempt cannot
     * fail for a reason the previous attempt did not have; and the holder's lease either releases on
     * its own completion or is reclaimed after {@link ActRuntime#DEFAULT_MAX_IDLE_TICKS} idle ticks,
     * so the retry cannot wait forever either.
     */
    public synchronized void step(long tick) {
        if (phase != ActPlanStatus.Phase.RUNNING || plan == null) {
            return;
        }
        if (!leaseBlocked.isEmpty()) {
            if (!submitCurrent()) {
                return;
            }
        }

        for (ActIntent expected : submitted) {
            SlotRecord rec = runtime.record(expected.slot());
            if (!ActRuntime.sameIntent(expected, rec.intent()) || takenByAnother(expected.slot())) {
                abort(ActPlanStatus.Phase.CANCELLED,
                        "superseded: " + expected.slot().name()
                                + " is no longer this plan's to run (racing act_set)");
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

    /**
     * Submit the current step, all of it or none of it.
     *
     * <p><b>All-or-nothing is the point, and it is a change of behaviour.</b> The old version
     * submitted each intent in turn and reported the step as running. A step touching two slots
     * could therefore half-run: the first intent overwritten whatever the model had asked for on that
     * channel, the second one clobbered by a racing {@code act_set}, and the step was reported
     * running while the player was doing neither thing anybody asked for. Here every intent in the
     * step takes a REPLAY lease first, and a single refusal leaves the step unsubmitted and blocked.
     *
     * <p>The leases already taken by this attempt are kept rather than rolled back. They are
     * same-owner leases on intents we hold, and dropping them would release a channel back to
     * another owner mid-step for no gain; the blocked slots are simply retried next tick, and a
     * same-owner re-acquire is always admitted so the retry costs nothing.
     *
     * @return true when the whole step is submitted; false when it is blocked on a lease
     */
    private boolean submitCurrent() {
        ActPlanStep step = plan.steps().get(index);
        List<ActIntent> next = new ArrayList<>(3);
        List<String> blocked = new ArrayList<>(1);
        List<String> reasons = new ArrayList<>(1);
        for (ActIntent intent : step.intents()) {
            ActRuntime.Submission s = runtime.trySubmit(intent, ActRuntime.replayLease(intent));
            if (s instanceof ActRuntime.Granted g) {
                next.add(g.record().intent());
                continue;
            }
            ActRuntime.Refused r = (ActRuntime.Refused) s;
            blocked.add(intent.slot().name().toLowerCase(Locale.ROOT));
            reasons.add(r.reason());
        }
        submitted = List.copyOf(next);
        if (!blocked.isEmpty()) {
            leaseBlocked = List.copyOf(blocked);
            message = "step " + index + "/" + plan.size() + " blocked on "
                    + String.join("; ", reasons) + ". The plan is waiting, not running: nothing else "
                    + "in this step was refused";
            return false;
        }
        leaseBlocked = List.of();
        message = "running step " + index + "/" + plan.size();
        return true;
    }

    private void abort(ActPlanStatus.Phase terminal, String reason) {
        cancelWaitingSlots();
        phase = terminal;
        submitted = List.of();
        leaseBlocked = List.of();
        message = reason;
    }

    /**
     * Cancel the slots still running THIS plan's walk, and nothing else.
     *
     * <p>A racing submit is left alone -- cancelling it would tear down the act_set that
     * superseded us, and the model was told {@code accepted:true} for it. Two tests therefore
     * have to pass together, and neither alone is enough: the goal test says the walk on the slot
     * is the one we replayed, and {@link #takenByAnother} says the channel is still ours to tear
     * down. The goal test alone is what let a same-goal racing {@code act_set} be cancelled by a
     * plan that had already lost the channel.
     */
    private void cancelWaitingSlots() {
        for (ActIntent expected : submitted) {
            SlotRecord rec = runtime.record(expected.slot());
            if (ActRuntime.sameIntent(expected, rec.intent()) && rec.isLive()
                    && !takenByAnother(expected.slot())) {
                runtime.cancel(expected.slot());
            }
        }
    }

    /**
     * Whether somebody who is not this plan holds {@code slot}'s channel right now.
     *
     * <p><b>This is the question "is this still OURS?", and the lease is the only record that
     * answers it.</b> {@link ActRuntime#sameIntent} answers a different one -- "are these the same
     * WALK?" -- and for a {@link net.marcloud.mcp.core.drivers.act.RouteIntent} it is keyed on the
     * goal, so a model's own {@code act_set} naming the same target, budget and creep is the same
     * walk by that test. It has to be: the MOVE applier re-stamps the record's intent every tick
     * (see {@link ActRuntime#sameIntent}), so an identity test would call a plan's own walk a
     * fresh one on the first stamped tick. Goal equality is therefore necessary here and not
     * sufficient, and the ownership half of the answer is the lease, which only a real submit
     * against a real channel can move.
     *
     * <p>A null lease is deliberately NOT a takeover. The channel being free means nobody has
     * taken it from us; combined with the goal check that is either our own walk or a same-goal
     * walk whose owner already let the channel go, and the first of those is a stalled walk
     * rather than a supersession. A non-null lease held by a foreign owner is the other thing
     * entirely: a live foreign intent, because {@link ActRuntime#tickLeases} releases a lease as
     * soon as its record is terminal or flagged, so a standing foreign lease is standing on a
     * live foreign walk.
     */
    private boolean takenByAnother(ActSlot slot) {
        ActRuntime.Lease held = runtime.lease(slot);
        return held != null && !ActRuntime.PLAN_OWNER.equals(held.owner());
    }

    /**
     * Slots this plan is waiting on: the ones a blocked step has not got a lease on, plus the ones
     * a submitted step is still running.
     *
     * <p>The blocked ones are listed FIRST, because they are the ones a caller cannot see from the
     * slot list: a blocked step has submitted nothing on them, so {@code act_status} shows those
     * channels as somebody else's and the plan as running step N with nothing started.
     */
    private List<String> waitingOn() {
        if (phase != ActPlanStatus.Phase.RUNNING) {
            return List.of();
        }
        List<String> out = new ArrayList<>(leaseBlocked.size() + submitted.size());
        for (String slot : leaseBlocked) {
            out.add(slot);
        }
        for (ActIntent expected : submitted) {
            SlotRecord rec = runtime.record(expected.slot());
            if (!ActRuntime.sameIntent(expected, rec.intent())
                    || rec.phase() != ActPhase.COMPLETE
                    || takenByAnother(expected.slot())) {
                out.add(expected.slot().name().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }
}
