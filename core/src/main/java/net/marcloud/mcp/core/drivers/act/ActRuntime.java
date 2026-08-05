package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.marcloud.mcp.core.ke.GameClock;

/**
 * The lock-free hub of the act layer. Holds one {@link SlotRecord} per
 * {@link ActSlot} in an {@link AtomicReference}, so any thread can submit or
 * cancel an intent with a single atomic store while the game thread reads and
 * advances the slot each tick — no locks on the game-thread path.
 *
 * <p><b>Threading model.</b> MCP worker threads call {@code submit*}/{@code
 * cancel} (cold path). The single game thread calls the per-slot applier via
 * {@link ActTickLoop} and stores the next record (hot path). A submit sets the
 * intent's {@code effectiveTick} to {@link GameClock#lastCompletedTick()}{@code
 * + 1}: the applier ignores the intent until the clock reaches that tick, so an
 * intent submitted mid-tick always takes effect at a clean tick boundary and can
 * never be half-applied within the tick it arrived.
 *
 * <p>{@link MoveIntentView} is implemented here and read by {@link
 * ActMovementInput} on the game thread; it exposes the MOVE slot's current
 * target reference-free.
 *
 * <p>Game-type-free by construction — this class imports no {@code net.minecraft}
 * type, only the kernel {@link GameClock}. That is what lets the whole runtime be
 * driven headlessly in tests through a fake clock advance and a {@link FakeActuator}.
 */
public final class ActRuntime implements MoveIntentView {

    /** Process-wide instance wired by {@code McpCore}. Tests construct their own. */
    public static final ActRuntime INSTANCE = new ActRuntime();

    private final GameClock clock;

    /** One atomic record per slot, indexed by {@link ActSlot#ordinal()}. */
    private final AtomicReferenceArray<SlotRecord> records;

    /** One applier per slot, registered at startup, read on the game thread. */
    private final AtomicReferenceArray<ActApplier> appliers;

    /** Uses the global {@link GameClock#INSTANCE}. */
    public ActRuntime() {
        this(GameClock.INSTANCE);
    }

    /** Test/DI constructor with an explicit clock. */
    public ActRuntime(GameClock clock) {
        this.clock = clock == null ? GameClock.INSTANCE : clock;
        int n = ActSlot.values().length;
        this.records = new AtomicReferenceArray<>(n);
        this.appliers = new AtomicReferenceArray<>(n);
        for (int i = 0; i < n; i++) {
            records.set(i, SlotRecord.empty());
        }
    }

    // ===== registration =====

    /** Register the applier that steps {@code slot} once per tick. */
    public void registerApplier(ActSlot slot, ActApplier applier) {
        if (slot == null || applier == null) {
            throw new IllegalArgumentException("slot and applier must not be null");
        }
        appliers.set(slot.ordinal(), applier);
    }

    /** The applier registered for {@code slot}, or null if none. */
    public ActApplier applier(ActSlot slot) {
        return appliers.get(slot.ordinal());
    }

    // ===== submit / cancel (any thread) =====

    /** Submit a MOVE intent, replacing whatever the slot held. */
    public SlotRecord submitMove(MoveIntent intent) {
        return submit(intent);
    }

    /** Submit a NAV intent. Shares the MOVE slot, so it replaces whatever that slot held. */
    public SlotRecord submitNav(NavIntent intent) {
        return submit(intent);
    }

    /** Submit a LOOK intent, replacing whatever the slot held. */
    public SlotRecord submitLook(LookIntent intent) {
        return submit(intent);
    }

    /** Submit an INTERACT intent, replacing whatever the slot held. */
    public SlotRecord submitInteract(InteractIntent intent) {
        return submit(intent);
    }

    /**
     * Submit any intent to its own slot. The intent becomes eligible at the next
     * clean tick boundary ({@code lastCompletedTick + 1}). Atomic: a single store
     * that a game-thread reader either sees whole or not at all.
     *
     * @return the stored {@link SlotRecord}
     */
    public SlotRecord submit(ActIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        long now = clock.lastCompletedTick();
        long effective = now + 1;
        SlotRecord rec = SlotRecord.submitted(intent, now, effective,
                "submitted at tick " + now + ", effective tick " + effective);
        records.set(intent.slot().ordinal(), rec);
        return rec;
    }

    /**
     * Cancel whatever occupies {@code slot}. A live intent is FLAGGED for
     * cancellation (kept non-terminal) so the applier's next game-thread tick can
     * tear down cleanly — e.g. abort an in-progress dig — before the slot ends
     * {@link ActPhase#CANCELLED}. The {@link ActTickLoop} finalizes a flagged slot
     * that never started (or has no applier) directly to CANCELLED. An
     * empty/terminal slot is reset to {@link SlotRecord#empty()}.
     *
     * @return true if a live intent was flagged for cancellation
     */
    public boolean cancel(ActSlot slot) {
        SlotRecord cur = records.get(slot.ordinal());
        if (cur.isLive()) {
            records.set(slot.ordinal(), cur.requestCancel());
            return true;
        }
        records.set(slot.ordinal(), SlotRecord.empty());
        return false;
    }

    /** Cancel every slot. Returns how many held a live intent. */
    public int cancelAll() {
        int n = 0;
        for (ActSlot slot : ActSlot.values()) {
            if (cancel(slot)) {
                n++;
            }
        }
        return n;
    }

    // ===== game-thread record access (used by ActTickLoop) =====

    /** The current record for {@code slot} (consistent snapshot). */
    public SlotRecord record(ActSlot slot) {
        return records.get(slot.ordinal());
    }

    /** Store {@code next} as the record for {@code slot} (game thread). */
    public void store(ActSlot slot, SlotRecord next) {
        records.set(slot.ordinal(), next == null ? SlotRecord.empty() : next);
    }

    // ===== status =====

    /** A consistent snapshot of every slot for {@code act_status}. */
    public ActStatus status() {
        List<ActStatus.SlotStatus> out = new ArrayList<>(ActSlot.values().length);
        for (ActSlot slot : ActSlot.values()) {
            SlotRecord r = records.get(slot.ordinal());
            out.add(new ActStatus.SlotStatus(
                    slot, r.phase(), r.intent() != null, intentKind(r.intent()),
                    r.ticksActive(), r.message()));
        }
        return new ActStatus(clock.lastCompletedTick(), List.copyOf(out));
    }

    private static String intentKind(ActIntent intent) {
        if (intent == null) {
            return "-";
        }
        if (intent instanceof LookIntent li) {
            // The aim mode is appended only when it is KEEP, so the label of an ordinary aim is
            // unchanged. It belongs here at all because the two have different lifetimes: a KEEP
            // aim holds the slot until something cancels it, and a caller reading act_status needs
            // to know that the LOOK channel is occupied by a track rather than by an aim that is
            // about to finish on its own.
            return "LOOK:" + li.mode() + (li.keepsAiming() ? "+KEEP" : "");
        }
        if (intent instanceof InteractIntent ii) {
            return "INTERACT:" + ii.kind();
        }
        return "MOVE";
    }

    // ===== MoveIntentView (read on the game thread by ActMovementInput) =====

    @Override
    public boolean moveActive() {
        SlotRecord r = records.get(ActSlot.MOVE.ordinal());
        // EVERY intent that can occupy the MOVE slot drives this input -- the test is the slot, not a
        // list of types. The list version cost a live session: RouteIntent was dispatched, planned,
        // and ticked correctly for 50 ticks while this method answered false, so the override never
        // engaged and the player never moved. Nothing reported an error, because every component was
        // doing its job; the axes simply had no consumer. A whitelist here has to be updated by
        // whoever adds a locomotion intent, and the failure for forgetting is silent, so it is the
        // wrong shape for this question. If an intent is in the MOVE slot and ACTIVE, it moves.
        return r.phase() == ActPhase.ACTIVE && r.intent() != null
                && r.intent().slot() == ActSlot.MOVE;
    }

    @Override
    public float moveForward() {
        return effective().forward();
    }

    @Override
    public float moveStrafe() {
        return effective().strafe();
    }

    @Override
    public boolean jump() {
        return effective().jump();
    }

    @Override
    public boolean sneak() {
        return effective().sneak();
    }

    @Override
    public boolean sprint() {
        return effective().sprint();
    }

    /**
     * The axes in force this tick.
     *
     * <p>Read from the intent when it carries them and from the applier's published value only when
     * it cannot. A {@link MoveIntent} IS its axes -- they are fixed for its lifetime, so the intent
     * is the single source of truth and nothing needs to republish it. A {@link NavIntent} has no
     * axes to read: {@link NavController} derives them from the live position every tick, so there
     * the published value is the only source. Because exactly one of the two applies at a time,
     * they cannot disagree.
     *
     * <p>The alternative -- publishing for both -- was tried and rejected: it made an applier built
     * without a runtime silently stop the player from moving at all, which an existing test caught.
     * A constructor that quietly disables locomotion is worse than a little dispatch here.
     */
    private LocomotionAxes effective() {
        SlotRecord r = records.get(ActSlot.MOVE.ordinal());
        if (r.intent() instanceof MoveIntent mi) {
            return new LocomotionAxes(mi.forward(), mi.strafe(), mi.jump(), mi.sneak(), mi.sprint());
        }
        return axes;
    }

    /**
     * The axes the MOVE applier decided on this tick.
     *
     * <p>Published by the applier rather than read out of the intent, because a
     * {@link NavIntent} has no axes to read -- {@link NavController} computes them each tick from
     * the live position. Routing both intent kinds through one published value keeps a single path
     * into {@link ActMovementInput} and, just as importantly, means nothing has to rewrite the
     * slot's intent per tick: {@code LookApplier} detects a fresh submit by intent IDENTITY, and a
     * per-tick swap would make every tick look like a new submission.
     *
     * <p>Volatile because the applier writes on the game thread and the input reads there too, but
     * status calls arrive from worker threads.
     */
    public record LocomotionAxes(float forward, float strafe, boolean jump, boolean sneak,
                                 boolean sprint) {

        static final LocomotionAxes NEUTRAL = new LocomotionAxes(0f, 0f, false, false, false);
    }

    private volatile LocomotionAxes axes = LocomotionAxes.NEUTRAL;

    /** Called by the MOVE applier each tick with what it wants applied. */
    public void publishAxes(LocomotionAxes next) {
        axes = next == null ? LocomotionAxes.NEUTRAL : next;
    }
}
