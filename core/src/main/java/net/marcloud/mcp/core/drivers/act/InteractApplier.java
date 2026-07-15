package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#INTERACT} applier: routes an {@link InteractIntent} to the
 * right pure controller ({@link DigController} for multi-tick digging,
 * {@link InteractController} for use/place/attack, {@link HotbarController} for
 * slot select) and steps it over an {@link ActActuator}.
 *
 * <p>Stateful, game-thread-only (driven by {@link ActTickLoop}), so no
 * synchronization. Caches the controller for the intent it is driving and
 * rebuilds when the slot's intent changes, so a new {@code submitInteract} always
 * starts fresh. A pending cancel is forwarded to a live {@link DigController} so
 * an in-progress dig is aborted cleanly on the game thread.
 */
public final class InteractApplier implements ActApplier {

    private final ActActuator actuator;

    private ActIntent boundTo;
    private DigController dig;
    private InteractController interact;
    private HotbarController hotbar;

    public InteractApplier(ActActuator actuator) {
        this.actuator = actuator;
    }

    @Override
    public SlotRecord apply(SlotRecord current) {
        if (!(current.intent() instanceof InteractIntent ii)) {
            return current.withPhase(ActPhase.FAILED, "INTERACT slot given a non-interact intent");
        }
        boolean fresh = boundTo != current.intent();
        if (fresh) {
            bind(ii, current.intent());
        }

        // Cancellation: DIG can be mid-break and needs a real teardown; the others
        // are single-shot, so a cancel just ends them.
        if (current.cancelRequested()) {
            if (dig != null) {
                dig.requestCancel();
                ActOutcome out = dig.tick(actuator);
                reset();
                return current.markActive(current.lastAppliedTick(), out.message())
                        .withPhase(ActPhase.CANCELLED, out.message());
            }
            reset();
            return current.withPhase(ActPhase.CANCELLED, "interact cancelled");
        }

        ActOutcome outcome = step(ii);
        long tick = current.lastAppliedTick();
        if (outcome.terminal()) {
            reset();
            return current.markActive(tick, outcome.message())
                    .withPhase(outcome.state(), outcome.message());
        }
        return current.markActive(tick, outcome.message());
    }

    private ActOutcome step(InteractIntent ii) {
        return switch (ii.kind()) {
            case DIG -> dig.tick(actuator);
            case HOTBAR -> hotbar.tick(actuator);
            default -> interact.tick(actuator);
        };
    }

    private void bind(InteractIntent ii, ActIntent identity) {
        reset();
        boundTo = identity;
        switch (ii.kind()) {
            case DIG -> dig = new DigController(ii);
            case HOTBAR -> hotbar = new HotbarController(ii);
            default -> interact = new InteractController(ii);
        }
    }

    private void reset() {
        boundTo = null;
        dig = null;
        interact = null;
        hotbar = null;
    }
}
