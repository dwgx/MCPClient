package net.marcloud.mcp.core.drivers.act;

/**
 * The immutable per-slot state held in {@link ActRuntime}'s lock-free
 * {@code AtomicReference}. Every mutation (submit, cancel, an applier's per-tick
 * step) produces a NEW record and stores it, so a cross-thread reader always sees
 * a consistent snapshot — never a half-updated slot.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code intent} — the data the slot is executing (null when empty).
 *   <li>{@code phase} — {@link ActPhase} lifecycle position.
 *   <li>{@code submittedTick} — the clock tick at which the intent was submitted.
 *   <li>{@code effectiveTick} — the first tick the applier may act on
 *       ({@code submittedTick + 1}); before it, the tick loop leaves the slot IDLE.
 *   <li>{@code lastAppliedTick} — the most recent tick an applier ran this slot.
 *   <li>{@code ticksActive} — count of ticks the slot has been ACTIVE (drives
 *       duration limits and slew progress).
 *   <li>{@code cancelRequested} — a cancel was asked for while the intent was still
 *       live; the slot stays non-terminal so the applier gets ONE more tick on the
 *       game thread to tear down (e.g. abort an in-progress dig) before ending
 *       {@link ActPhase#CANCELLED}.
 *   <li>{@code message} — human-readable last status/outcome line.
 * </ul>
 */
public record SlotRecord(
        ActIntent intent,
        ActPhase phase,
        long submittedTick,
        long effectiveTick,
        long lastAppliedTick,
        int ticksActive,
        boolean cancelRequested,
        String message) {

    /** The empty slot: no intent, IDLE, no timing. */
    public static SlotRecord empty() {
        return new SlotRecord(null, ActPhase.IDLE, 0L, 0L, 0L, 0, false, "idle");
    }

    /** A freshly-submitted intent that becomes eligible at {@code effectiveTick}. */
    public static SlotRecord submitted(ActIntent intent, long submittedTick, long effectiveTick,
                                       String message) {
        return new SlotRecord(intent, ActPhase.IDLE, submittedTick, effectiveTick,
                0L, 0, false, message);
    }

    /** True if there is an intent and it has not reached a terminal phase. */
    public boolean isLive() {
        return intent != null && !phase.isTerminal();
    }

    // ===== copy-with helpers (records are immutable; each returns a new one) =====

    /** Same slot, new phase + message (clears the cancel request on a terminal phase). */
    public SlotRecord withPhase(ActPhase newPhase, String newMessage) {
        return new SlotRecord(intent, newPhase, submittedTick, effectiveTick,
                lastAppliedTick, ticksActive, cancelRequested && !newPhase.isTerminal(), newMessage);
    }

    /** Same slot marked ACTIVE for another tick: bumps {@code ticksActive}. */
    public SlotRecord markActive(long tick, String newMessage) {
        return new SlotRecord(intent, ActPhase.ACTIVE, submittedTick, effectiveTick,
                tick, ticksActive + 1, cancelRequested, newMessage);
    }

    /** Same slot with a pending cancel flag set (kept non-terminal for teardown). */
    public SlotRecord requestCancel() {
        return new SlotRecord(intent, phase, submittedTick, effectiveTick,
                lastAppliedTick, ticksActive, true, "cancel requested");
    }

    /** Same slot with only the {@code lastAppliedTick} advanced. */
    public SlotRecord stampTick(long tick) {
        return new SlotRecord(intent, phase, submittedTick, effectiveTick,
                tick, ticksActive, cancelRequested, message);
    }
}
