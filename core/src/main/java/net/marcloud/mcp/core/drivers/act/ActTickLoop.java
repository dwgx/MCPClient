package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * Drives the act runtime once per game tick. Subscribes to {@link TickEvent} (the
 * {@code Minecraft.runTick} seam) so {@link #onTick} runs on the GAME THREAD, and
 * for each {@link ActSlot} with a live intent it invokes the registered
 * {@link ActApplier} and stores the returned record back into {@link ActRuntime}.
 *
 * <p><b>Effective-tick gate.</b> A freshly-submitted intent carries an {@code
 * effectiveTick} one past the tick it was submitted on. Until the clock reaches
 * it the loop leaves the slot IDLE, so an intent submitted from a worker thread
 * mid-tick always begins on a clean tick boundary — never half-applied inside the
 * tick it arrived.
 *
 * <p><b>Fault isolation.</b> An applier that throws must never break the game
 * loop or stop the other slots. Each applier runs in its own try/catch; a throw
 * marks only that slot {@link ActPhase#FAILED} and the loop moves on. This mirrors
 * {@link EventBus#publish}'s own subscriber isolation but is done per-slot here so
 * one bad slot cannot starve the others.
 *
 * <p><b>Concurrent writes.</b> A worker thread may submit or cancel any slot at any
 * moment — that is the point of the runtime's lock-free design — and the applier's
 * window is a whole tick of real work. So every store below goes through
 * {@link ActRuntime#compareAndStore} against the record this tick read at the top:
 * a write that arrived during the window leaves a different record in the slot, the
 * compare fails, and the loop DROPS its own superseded result instead of writing it
 * back over the caller's. Without that guard {@code act_set} answered
 * {@code accepted:true} for an intent the slot did not hold, and {@code act_cancel}
 * reported a cancel that had been written away.
 */
public final class ActTickLoop {

    private final ActRuntime runtime;

    /** Kept so {@link #detach} can unsubscribe the exact handler instance. */
    private final java.util.function.Consumer<TickEvent> handler = this::onTick;

    private volatile EventBus bus;

    public ActTickLoop(ActRuntime runtime) {
        this.runtime = runtime == null ? ActRuntime.INSTANCE : runtime;
    }

    /** Subscribe to the tick stream on {@code bus}. Idempotent-ish: re-attach re-subscribes. */
    public void attach(EventBus bus) {
        this.bus = bus;
        bus.subscribe(TickEvent.class, handler);
    }

    /** Stop driving (unsubscribe this loop's handler). */
    public void detach() {
        EventBus b = this.bus;
        if (b != null) {
            b.unsubscribe(handler);
        }
    }

    /**
     * One tick step for every slot, then the sidecar sequencer. The plan advances
     * AFTER slots so a step that reaches COMPLETE this tick can submit the next
     * step on the same tick (eligible next tick, like any other submit).
     *
     * <p>Public and driveable directly with a synthetic {@link TickEvent} so the
     * whole loop is testable headlessly without a live bus.
     */
    public void onTick(TickEvent event) {
        long tick = event == null ? 0L : event.tickId();
        for (ActSlot slot : ActSlot.values()) {
            stepSlot(slot, tick);
        }
        runtime.stepPlan(tick);
    }

    private void stepSlot(ActSlot slot, long tick) {
        SlotRecord rec = runtime.record(slot);

        // Nothing to do: empty slot or already finished.
        if (rec.intent() == null || rec.phase().isTerminal()) {
            return;
        }
        // Not yet eligible — leave it IDLE until its effective tick arrives.
        if (tick < rec.effectiveTick()) {
            // A cancel before the intent ever started needs no teardown: end now. Guarded like every
            // other store here -- a submit that landed since the read above must not be ended by this
            // record's cancellation.
            if (rec.cancelRequested()) {
                runtime.compareAndStore(slot, rec,
                        rec.withPhase(ActPhase.CANCELLED, "cancelled before start"));
            }
            return;
        }
        ActApplier applier = runtime.applier(slot);
        if (applier == null) {
            // A live intent with no applier can never progress. If a cancel is
            // pending, honor it; otherwise fail honestly rather than spinning IDLE.
            runtime.compareAndStore(slot, rec, rec.cancelRequested()
                    ? rec.withPhase(ActPhase.CANCELLED, "cancelled (no applier)")
                    : rec.withPhase(ActPhase.FAILED, "no applier registered for slot " + slot));
            return;
        }
        // Stamp the current tick so the applier can read it back via
        // SlotRecord#lastAppliedTick() without a signature change to ActApplier.
        SlotRecord stamped = rec.stampTick(tick);
        try {
            SlotRecord next = applier.apply(stamped);
            // Commit only while the slot STILL HOLDS the record `next` was derived from: a submit or
            // cancel that landed anywhere inside apply() leaves a different record there, and writing
            // this continuation of the old one back would both resurrect the superseded intent and
            // discard the caller's write. A failed compare means exactly that, and the loop moves on --
            // retrying would replay the applier's side effects for an intent that is already gone.
            runtime.compareAndStore(slot, rec, next == null ? stamped : next);
        } catch (Throwable t) {
            // Fault-isolated: this slot fails, the tick and the other slots survive. Guarded like the
            // success path, so a throw cannot roll back a submit or cancel either -- that FAILED record
            // describes an intent the slot may no longer hold.
            runtime.compareAndStore(slot, rec,
                    rec.withPhase(ActPhase.FAILED, "applier threw: " + t));
        }
    }
}
