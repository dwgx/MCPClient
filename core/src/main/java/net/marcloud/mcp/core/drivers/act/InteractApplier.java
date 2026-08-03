package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#INTERACT} applier: routes an {@link InteractIntent} to the
 * right pure controller ({@link DigController} for multi-tick digging,
 * {@link HoldController} for a sustained use, {@link InteractController} for
 * use/place/attack, {@link HotbarController} for slot select) and steps it over an
 * {@link ActActuator}.
 *
 * <p>Stateful, game-thread-only (driven by {@link ActTickLoop}), so no
 * synchronization. Caches the controller for the intent it is driving and
 * rebuilds when the slot's intent changes, so a new {@code submitInteract} always
 * starts fresh. Freshness is detected by intent IDENTITY, which is why nothing here
 * rewrites the slot's intent per tick: a hold lasts many ticks, and a per-tick swap
 * would make every one of them look like a new submission and restart the hold
 * forever.
 *
 * <p>A pending cancel is forwarded to whichever controller can be MID-something and
 * needs a real teardown on the game thread: a {@link DigController} to abort a break,
 * and a {@link HoldController} to release vanilla's use key -- a hold left asserted
 * would keep the player eating or blocking with nothing driving it.
 */
public final class InteractApplier implements ActApplier {

    private final ActActuator actuator;

    private ActIntent boundTo;
    private DigController dig;
    private HoldController hold;
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

        // Cancellation: DIG can be mid-break and HOLD is mid-use, so both need a real teardown on
        // the game thread -- resetBlockRemoving for one, releasing vanilla's use key for the other.
        // The others are single-shot, so a cancel just ends them.
        if (current.cancelRequested()) {
            ActOutcome out = cancelLiveController();
            if (out != null) {
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

    /**
     * Tear down whichever controller is holding live game state, or return null if none is.
     *
     * <p>Null rather than an outcome means "nothing to undo", which is the honest answer for a
     * single-shot controller, and keeps the caller's distinction between a teardown that ran and one
     * that was not needed.
     */
    private ActOutcome cancelLiveController() {
        if (dig != null) {
            dig.requestCancel();
            return dig.tick(actuator);
        }
        if (hold != null) {
            hold.requestCancel();
            return hold.tick(actuator);
        }
        return null;
    }

    private ActOutcome step(InteractIntent ii) {
        return switch (ii.kind()) {
            case DIG -> dig.tick(actuator);
            case HOLD -> hold.tick(actuator);
            case HOTBAR -> hotbar.tick(actuator);
            default -> interact.tick(actuator);
        };
    }

    private void bind(InteractIntent ii, ActIntent identity) {
        // Tear down before dropping the reference, not just after cancel. A live HOLD owns state
        // OUTSIDE this object -- vanilla's use key, asserted in a static KeyBinding -- so nulling the
        // field abandons an assertion that nothing is left to lift. Vanilla then re-fires
        // rightClickMouse on every tick nothing is in use (Minecraft.java:2158), which eats the rest
        // of the stack or re-draws the bow forever, and act_cancel cannot rescue it because the slot
        // no longer holds the controller that knows how to let go. The cancel path always did this;
        // the replace path beside it did not, and a test that asserted only "the new intent ran"
        // could not tell the difference.
        reset();
        boundTo = identity;
        switch (ii.kind()) {
            case DIG -> dig = new DigController(ii);
            case HOLD -> hold = new HoldController(ii);
            case HOTBAR -> hotbar = new HotbarController(ii);
            default -> interact = new InteractController(ii);
        }
    }

    /**
     * Drop every controller, releasing any live game state first.
     *
     * <p>The release is unconditional rather than "only when replacing": every path that reaches here
     * is one where this applier stops driving the controller, and an asserted key with no driver is
     * the same failure regardless of which path abandoned it.
     */
    private void reset() {
        if (hold != null) {
            hold.releaseIfHolding(actuator);
        }
        boundTo = null;
        dig = null;
        hold = null;
        interact = null;
        hotbar = null;
    }
}
