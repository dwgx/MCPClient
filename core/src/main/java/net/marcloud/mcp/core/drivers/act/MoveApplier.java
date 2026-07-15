package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#MOVE} applier: a pure lifecycle step. The actual locomotion
 * is applied by {@link ActMovementInput} reading the {@link MoveIntentView} on the
 * game thread every tick; this applier only owns the MOVE slot's lifecycle —
 * marking it ACTIVE, counting active ticks, honoring the duration budget, and
 * finalizing a cancel.
 *
 * <p>A {@link MoveIntent#durationTicks()} of {@code <= 0} means "hold until
 * cancelled or replaced": the slot stays ACTIVE indefinitely. A positive duration
 * completes the slot once that many ticks have been applied, after which
 * {@link ActMovementInput} sees {@code moveActive()==false} and reverts to vanilla.
 */
public final class MoveApplier implements ActApplier {

    @Override
    public SlotRecord apply(SlotRecord current) {
        if (!(current.intent() instanceof MoveIntent mi)) {
            return current.withPhase(ActPhase.FAILED, "MOVE slot given a non-move intent");
        }
        if (current.cancelRequested()) {
            return current.withPhase(ActPhase.CANCELLED,
                    "movement cancelled after " + current.ticksActive() + " ticks");
        }
        long tick = current.lastAppliedTick();
        int activeAfter = current.ticksActive() + 1;
        int duration = mi.durationTicks();
        if (duration > 0 && activeAfter >= duration) {
            // This is the last tick of the budget: apply it, then complete.
            return current.markActive(tick, "moved for " + activeAfter + " ticks")
                    .withPhase(ActPhase.COMPLETE, "movement complete after " + activeAfter + " ticks");
        }
        return current.markActive(tick, "moving (tick " + activeAfter
                + (duration > 0 ? "/" + duration : "") + ")");
    }
}
