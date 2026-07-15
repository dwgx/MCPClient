package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#LOOK} applier: bridges the per-tick slot lifecycle to a
 * {@link LookController} state machine over an {@link ActActuator}.
 *
 * <p>Stateful, but only ever touched on the game thread by {@link ActTickLoop},
 * so no synchronization is needed. It caches the controller for the intent it is
 * currently driving and rebuilds when the slot's intent changes (identity), so a
 * new {@code submitLook} always starts a fresh state machine.
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
        if (current.cancelRequested()) {
            reset();
            return current.withPhase(ActPhase.CANCELLED, "look cancelled");
        }
        if (controller == null || boundTo != current.intent()) {
            controller = new LookController(li);
            boundTo = current.intent();
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
