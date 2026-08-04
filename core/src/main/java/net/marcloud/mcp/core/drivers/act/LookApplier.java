package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#LOOK} applier: bridges the per-tick slot lifecycle to a
 * {@link LookController} state machine over an {@link ActActuator}.
 *
 * <p>Stateful, but only ever touched on the game thread by {@link ActTickLoop},
 * so no synchronization is needed. It caches the controller for the intent it is
 * currently driving and rebuilds when the slot's intent changes (identity), so a
 * new {@code submitLook} always starts a fresh state machine.
 *
 * <p><b>Identity, and why nothing here rewrites the intent.</b> Freshness is detected by
 * intent IDENTITY, so a {@link LookIntent.AimMode#KEEP} aim -- which lasts many ticks and
 * recomputes its target angle on each of them -- must keep the SAME record in the slot. A
 * per-tick swap would make every tick look like a new submission and restart the aim forever,
 * resetting its tick count and its duration bound with it. {@link InteractApplier} carries the
 * same constraint for the hold channel and {@code ActRuntime} publishes nav axes out-of-band
 * for exactly this reason.
 *
 * <p>A cancel is routed THROUGH the controller rather than short-circuited here, so the
 * message carries how many ticks the aim was held. That number is only knowable inside the
 * controller and is gone the moment the slot resets -- and for a KEEP aim, which has no other
 * ending unless the caller gave it a duration, the cancel report is the whole account of what
 * the intent did.
 */
public final class LookApplier implements ActApplier {

    private final ActActuator actuator;

    private ActIntent boundTo;
    private LookController controller;

    public LookApplier(ActActuator actuator) {
        this.actuator = actuator;
    }

    @Override
    public SlotRecord apply(SlotRecord current) {
        if (!(current.intent() instanceof LookIntent li)) {
            return current.withPhase(ActPhase.FAILED, "LOOK slot given a non-look intent");
        }
        if (controller == null || boundTo != current.intent()) {
            controller = new LookController(li);
            boundTo = current.intent();
        }
        if (current.cancelRequested()) {
            controller.requestCancel();
            ActOutcome out = controller.tick(actuator);
            reset();
            return current.markActive(current.lastAppliedTick(), out.message())
                    .withPhase(ActPhase.CANCELLED, out.message());
        }
        ActOutcome outcome = controller.tick(actuator);
        long tick = current.lastAppliedTick();
        if (outcome.terminal()) {
            reset();
            return current.markActive(tick, outcome.message())
                    .withPhase(outcome.state(), outcome.message());
        }
        return current.markActive(tick, outcome.message());
    }

    private void reset() {
        controller = null;
        boundTo = null;
    }
}
