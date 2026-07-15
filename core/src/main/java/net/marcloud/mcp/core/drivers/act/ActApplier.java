package net.marcloud.mcp.core.drivers.act;

/**
 * A per-slot, per-tick step. {@link ActTickLoop} calls {@link #apply(SlotRecord)}
 * once per tick on the GAME THREAD for the slot's current record and stores the
 * returned record back. The applier does the live work for that slot — a
 * controller advancing its state machine against an {@link ActActuator}.
 *
 * <p><b>Contract:</b> return the next {@link SlotRecord} (possibly the same
 * instance if nothing changed). Never call {@code GameBridge.onGameThread}
 * from here — the loop already runs on the game thread. An applier MAY throw; the
 * loop is fault-isolated and will mark the slot {@link ActPhase#FAILED} rather
 * than letting the exception reach the game loop.
 */
@FunctionalInterface
public interface ActApplier {

    /**
     * Advance {@code current} by one tick and return the next record.
     *
     * @param current the slot's present state (never null; may be empty/terminal)
     * @return the slot's state after this tick
     */
    SlotRecord apply(SlotRecord current);
}
