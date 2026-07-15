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
            return "LOOK:" + li.mode();
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
        return r.phase() == ActPhase.ACTIVE && r.intent() instanceof MoveIntent;
    }

    @Override
    public float moveForward() {
        return moveIntent().forward();
    }

    @Override
    public float moveStrafe() {
        return moveIntent().strafe();
    }

    @Override
    public boolean jump() {
        return moveIntent().jump();
    }

    @Override
    public boolean sneak() {
        return moveIntent().sneak();
    }

    @Override
    public boolean sprint() {
        return moveIntent().sprint();
    }

    /** The live MOVE intent, or a neutral one when the slot is not driving. */
    private MoveIntent moveIntent() {
        SlotRecord r = records.get(ActSlot.MOVE.ordinal());
        if (r.intent() instanceof MoveIntent mi) {
            return mi;
        }
        return new MoveIntent(0f, 0f, false, false, false, 0);
    }
}
