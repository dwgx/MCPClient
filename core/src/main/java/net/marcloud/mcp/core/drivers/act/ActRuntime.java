package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.marcloud.mcp.core.ke.GameClock;

/**
 * The lock-free hub of the act layer. Holds one {@link SlotRecord} per
 * {@link ActSlot} in an {@link AtomicReference}, so any thread can replace or
 * flag an intent with atomic writes while the game thread reads and advances the
 * slot each tick — no locks on the game-thread path.
 *
 * <p><b>Threading model.</b> MCP worker threads call {@code submit*}/{@code
 * cancel} (cold path). The single game thread calls the per-slot applier via
 * {@link ActTickLoop} and stores the next record (hot path). A submit sets the
 * intent's {@code effectiveTick} to {@link GameClock#lastCompletedTick()}{@code
 * + 1}: the applier ignores the intent until the clock reaches that tick, so an
 * intent submitted mid-tick always takes effect at a clean tick boundary and can
 * never be half-applied within the tick it arrived.
 *
 * <p><b>Lost updates.</b> That gate solves half-application, not this: a worker
 * write landing inside the applier's window used to be overwritten by the game
 * thread's continuation of the record it read BEFORE the window opened, so
 * {@code act_set} answered {@code accepted:true} for an intent the slot no longer
 * held and {@code act_cancel} reported a slot cancelled while it kept running.
 * Every game-thread store therefore goes through {@link #compareAndStore} against
 * the record that tick derived it from, and the superseded record is dropped
 * rather than retried. {@code submit} replaces and needs no compare of its own;
 * {@code cancel} derives its flag from the record that is there and so takes a
 * compare-and-set loop of its own.
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

    /**
     * Owner id of the direct channel: the model's own {@code act_set} command, issued now.
     *
     * <p>A lease owner is a NAME, not an object, and that is a deliberate narrowing of the
     * reference's {@code owner: Any} compared with {@code ===}. There is exactly one player and one
     * MCP server, so "who is asking" has exactly two answers in the shipped system, and a caller
     * that wants to be recognised as a new consumer has to say which of them it is. Comparing names
     * by equality rather than by object identity is the one concession: an owner that never
     * instantiates anything is exactly the kind that cannot be resurrected by a GC and cannot leak
     * a reference into a record that outlives it.
     */
    public static final String DIRECT_OWNER = "act_set";

    /**
     * Owner id of the plan interpreter's replayed steps.
     *
     * <p>A plan step is not a command the model is issuing at this moment: the model authorised the
     * whole sequence earlier and the interpreter is replaying entry N on a schedule the runtime
     * chose. That difference in <i>when the intent was formed</i> is the only priority fact this
     * runtime actually has, and it is a fact about the world rather than a tunable number.
     */
    public static final String PLAN_OWNER = "act_plan";

    /**
     * Ticks a lease may go without its owner's intent still occupying the slot before it is
     * reclaimed. Two game seconds at 20 TPS.
     *
     * <p>This is the load-bearing half of the whole mechanism. Without it a lease is a mutex whose
     * owner can forget to unlock, and a mutex that can be forgotten open is a hang: the only way out
     * is a restart. The number is deliberately far longer than any legitimate gap — a live intent
     * refreshes its lease on the applier's tick, so this counter is only ever reached by a lease
     * whose intent is NOT being driven, which is exactly the case that needs reclaiming.
     */
    public static final int DEFAULT_MAX_IDLE_TICKS = 40;

    /** One atomic lease per slot, indexed by {@link ActSlot#ordinal()}. */
    private final AtomicReferenceArray<Lease> leases;

    /**
     * When each lease last saw its owner's intent in the slot, indexed by ordinal.
     *
     * <p>Separate from {@link #leases} so the per-tick refresh on the game-thread hot path is a
     * bare {@code lazySet} rather than an allocation: a lease is taken once per submit, but it is
     * refreshed on every applier tick for as long as its intent runs, and a walk can run four
     * hundred ticks. An immutable record carrying the timestamp would allocate on the hot path for
     * a value nobody reads but the expiry check.
     */
    private final AtomicLongArray leaseRefreshed;
    /**
     * The {@code lastAppliedTick} each lease was last seen at, indexed by ordinal.
     *
     * <p>The progress watermark {@link #tickLeases(long, int)} compares against. It exists so
     * "was this lease refreshed" is answerable as a comparison rather than as an allocation: the
     * lease record itself is immutable, and rebuilding it every tick for a four-hundred-tick walk
     * would put four hundred garbage records on the game thread for a value only the expiry check
     * reads.
     */
    private final AtomicLongArray leaseApplied;

    /** Monotonic grant counter, so two grants are never the same generation. */
    private final AtomicLong leaseSequence = new AtomicLong();

    /** One atomic record per slot, indexed by {@link ActSlot#ordinal()}. */
    private final AtomicReferenceArray<SlotRecord> records;

    /** One applier per slot, registered at startup, read on the game thread. */
    private final AtomicReferenceArray<ActApplier> appliers;

    /**
     * Sidecar sequencer. Constructed here so {@code new ActRuntime(clock)} tests
     * get a planner without {@code McpCore}.
     */
    private final ActPlanInterpreter interpreter;

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
        this.leases = new AtomicReferenceArray<>(n);
        this.leaseRefreshed = new AtomicLongArray(n);
        this.leaseApplied = new AtomicLongArray(n);
        for (int i = 0; i < n; i++) {
            records.set(i, SlotRecord.empty());
        }
        this.interpreter = new ActPlanInterpreter(this);
    }

    /**
     * Bind {@code plan}, replacing any previous one, and submit step 0.
     *
     * @return the sequencer snapshot after the first step is submitted
     */
    public ActPlanStatus submitPlan(ActPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        interpreter.bind(plan);
        return interpreter.status();
    }

    /** Cancel a running plan. Idle/terminal is a no-op. */
    public void cancelPlan() {
        interpreter.cancel();
    }

    /** Sequencer snapshot for {@code act_status.plan}. */
    public ActPlanStatus planStatus() {
        return interpreter.status();
    }

    /**
     * Advance the sequencer after the slot loop has applied {@code tick}. No-op
     * when no plan is running. The interpreter must not tick locomotion itself.
     *
     * <p>Lease upkeep runs here too, and that is deliberate rather than convenient:
     * {@link ActTickLoop} already calls exactly one method on the runtime after stepping the slots,
     * so putting upkeep on this seam means the per-tick release of finished leases and the per-tick
     * refresh of live ones cannot be forgotten at a second call site — and a lease cannot survive a
     * tick in which the loop ran. It runs BEFORE the sequencer step, so a plan submitting this tick
     * meets a lease state that already reflects this tick's progress.
     */
    public void stepPlan(long tick) {
        tickLeases(tick);
        interpreter.step(tick);
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

    /** Submit a MOVE intent as the DIRECT owner, replacing whatever the slot held. */
    public SlotRecord submitMove(MoveIntent intent) {
        return submit(intent);
    }

    /**
     * Submit a NAV intent. Shares the MOVE slot with every other locomotion intent, so it takes the
     * channel from whatever held it.
     */
    public SlotRecord submitNav(NavIntent intent) {
        return submit(intent);
    }

    /** Submit a LOOK intent as the DIRECT owner, replacing whatever the slot held. */
    public SlotRecord submitLook(LookIntent intent) {
        return submit(intent);
    }

    /** Submit an INTERACT intent as the DIRECT owner, replacing whatever the slot held. */
    public SlotRecord submitInteract(InteractIntent intent) {
        return submit(intent);
    }

    // ===== leases =====

    /**
     * Which of two real owners is asking for a slot.
     *
     * <p><b>This is not a priority framework, and the refusal to build one is the point.</b> There
     * is no ordering between consumers of this runtime beyond one fact that is true of the world
     * rather than tunable: a command the model is issuing <i>right now</i> is a different kind of
     * thing from a step the interpreter is replaying out of a sequence the model authorised earlier,
     * and when they collide on one channel the one being asked for now is the one the player meant.
     * Inventing a third or fourth level here would be a number with nothing behind it, and a
     * fabricated ordering is worse than an honest two-way one because it will be tuned later by
     * whoever finds it inconvenient, which is how a fail-open turns into a fail-closed by accident.
     *
     * <p>Note what this deliberately is NOT: it does not rank intents by kind. A {@code go_to} does
     * not outrank a {@code walk_straight} because it is the cleverer route; both are the model
     * asking, and the lease is about who asked last, not what they asked for.
     */
    public enum Priority {
        /** A step replayed from a plan the model bound earlier. Outranked by a live command. */
        REPLAY(0),
        /** The model's own {@code act_set}, issued at this moment. */
        DIRECT(1);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }

        /** True when a holder at this level may take a slot from one held at {@code other}. */
        public boolean outranks(Priority other) {
            return rank > other.rank;
        }
    }

    /**
     * A claim on one slot: who holds it, at what level, and for which intent.
     *
     * <p>{@code intent} is what makes expiry decidable without a timer per lease. A lease is being
     * USED while its own intent is the live one in the slot; a lease whose intent is gone, dead, or
     * replaced is a lease whose owner stopped using it, and that is released at once rather than
     * waited out. Only a lease whose intent is still sitting there and never being advanced —
     * the tick loop detached, the applier missing, the owner crashed mid-step — accrues idle time,
     * and that is the one case that has to end on a clock instead of on a fact.
     */
    public record Lease(String owner, Priority priority, long sequence, ActIntent intent) {

        public Lease {
            if (owner == null || priority == null || intent == null) {
                throw new IllegalArgumentException(
                        "lease owner, priority and intent must not be null");
            }
        }
    }

    /** A lease on a slot, granted to {@link #DIRECT_OWNER}. */
    public static Lease directLease(ActIntent intent) {
        return new Lease(DIRECT_OWNER, Priority.DIRECT, 0L, intent);
    }

    /** A lease on a slot, granted to {@link #PLAN_OWNER}. */
    public static Lease replayLease(ActIntent intent) {
        return new Lease(PLAN_OWNER, Priority.REPLAY, 0L, intent);
    }

    /** The outcome of asking for a slot: the channel, or a stated reason it was not granted. */
    public sealed interface Submission permits Granted, Refused {
    }

    /** The channel was granted; the record is in the slot. */
    public record Granted(SlotRecord record, Lease lease) implements Submission {
    }

    /**
     * The channel was NOT granted, and this says who has it, at what level, and for how long.
     *
     * <p>A refusal is a VALUE, not an absence. The defect this replaces was a submit that reported
     * success and was then either silently overwritten by somebody else or silently overwrote
     * somebody else — and the only evidence either party ever got was a status line describing a
     * channel neither of them believed they were driving.
     *
     * @param heldForTicks ticks since the incumbent's lease was last refreshed, for diagnosis
     */
    public record Refused(ActSlot slot, String heldBy, Priority heldAt, int heldForTicks,
                          String reason) implements Submission {
    }

    /** True when {@code submission} carries the channel. */
    public static boolean granted(Submission submission) {
        return submission instanceof Granted;
    }

    /** The lease currently held on {@code slot}, or null when it is free. */
    public Lease lease(ActSlot slot) {
        return leases.get(slot.ordinal());
    }

    /**
     * Who holds {@code slot}, or null when it is free, as a short label for {@code act_status}.
     *
     * <p>A refusal the caller cannot then query is half a fix: the model needs to be able to answer
     * "why is my move not happening", and the answer is always a name and a priority. Kept as a
     * string rather than the enum so the tool layer does not have to import the lease machinery.
     */
    public String leaseHolder(ActSlot slot) {
        Lease held = leases.get(slot.ordinal());
        return held == null ? null : held.owner() + "/" + held.priority();
    }

    /**
     * Whether two intents are the same WALK, which is not the same question as whether they are
     * the same OBJECT.
     *
     * <p><b>Why the question exists at all.</b> {@code MoveApplier} republishes the slot record's
     * intent every tick to stamp the chosen {@code MoveTactic} onto it, and
     * {@link RouteIntent#withTactic} returns a new intent rather than mutating one. So for a routed
     * walk {@code record.intent()} is a FRESH object on every tick carrying the same goal. Any
     * check comparing intents by object identity breaks on the first stamped tick:
     * {@link MoveApplier#driveLocomotion} would rebuild the machine every tick and the walk would
     * never move -- the reaction draw re-rolled, the frozen lane bearing lost, the wedge-recovery
     * budget reset -- and this class's {@link #tickLeases} would read the lease as RELEASED and
     * free the MOVE channel while the body is still standing on it. {@code ActPlanInterpreter}
     * would abort its own step with "superseded ... racing act_set" while nothing superseded it.
     *
     * <p><b>The key is the GOAL, and only for the one intent kind that is rewritten.</b> A route is
     * compared on its destination, budget and creep -- every field {@code withTactic} carries over
     * byte for byte. Every other intent is never republished, so for those "the same walk" is still
     * object identity, and this method says so by falling through to {@code ==}.
     *
     * <p><b>Why not a string key.</b> The obvious encoding -- build a {@code String} of the
     * fields and compare those -- is wrong twice over. It allocates on every call, and this is
     * called per slot per tick on the lease path and once per tick on the applier's bind, so that
     * is three to four throwaway strings a tick forever. And for a record, a field-derived string
     * is VALUE equality wearing a disguise: two separately-submitted but identical
     * {@link NavIntent}s share a key, so a caller replacing a running walk with an identical one
     * would be told it is the same walk. That is a different walk, it is exactly the substitution
     * the fresh reaction draw exists to price, and no caller should be able to make it free.
     *
     * @param expected the intent someone is asking about, typically the one they submitted
     * @param actual   the intent the slot is carrying now
     * @return true when {@code actual} is the same walk {@code expected} is still being worked on
     */
    public static boolean sameIntent(ActIntent expected, ActIntent actual) {
        if (expected == null) {
            return false;
        }
        if (expected == actual) {
            return true;
        }
        // Both, or neither: a RouteIntent can only ever be the same walk as another RouteIntent,
        // and a mismatched pair is a substitution rather than a republication.
        if (expected instanceof RouteIntent wanted && actual instanceof RouteIntent held) {
            return wanted.targetX() == held.targetX()
                    && wanted.targetY() == held.targetY()
                    && wanted.targetZ() == held.targetZ()
                    && wanted.blockBudget() == held.blockBudget()
                    && wanted.creeping() == held.creeping();
        }
        return false;
    }

    /**
     * Ask for {@code slot} on {@code lease}'s behalf, and take it only if the lease rules allow.
     *
     * <p>The three ways in, mirroring {@code RotationRequestArbiter.canAcquire}: the slot is free,
     * the asker already holds it (re-acquire, which is how a multi-intent step re-asserts its own
     * claim), or the asker's level outranks the incumbent's. Everything else is a {@link Refused}
     * naming the holder — including the stale case, which is released first so the refusal can say
     * that the holder's lease had lapsed and was taken over rather than that somebody simply won.
     *
     * @return {@link Granted} with the stored record, or {@link Refused} naming the holder
     */
    public Submission trySubmit(ActIntent intent, Lease lease) {
        if (intent == null || lease == null) {
            throw new IllegalArgumentException("intent and lease must not be null");
        }
        int index = intent.slot().ordinal();
        long now = clock.lastCompletedTick();

        Lease held = leases.get(index);
        if (held != null && !held.owner().equals(lease.owner())
                && now - leaseRefreshed.get(index) > DEFAULT_MAX_IDLE_TICKS) {
            // The backstop, on the acquire path as well as the tick path: a caller must not have to
            // wait for the tick loop to notice that a dead owner is still sitting on the channel.
            // Only a FOREIGN stale lease is reclaimed -- a lease you hold yourself is yours to
            // refresh, and silently dropping it here would let two writes by one owner race.
            if (leases.compareAndSet(index, held, null)) {
                held = null;
            }
        }

        if (held != null && !held.owner().equals(lease.owner())
                && !lease.priority().outranks(held.priority())) {
            long age = Math.max(0L, now - leaseRefreshed.get(index));
            return new Refused(intent.slot(), held.owner(), held.priority(), (int) age,
                    "slot " + intent.slot() + " is held by " + held.owner() + " at " + held.priority()
                            + ", refreshed " + age + " ticks ago; " + lease.owner() + " is "
                            + lease.priority() + " and does not outrank it. Wait for it to finish, "
                            + "or cancel " + intent.slot().name().toLowerCase(Locale.ROOT)
                            + " to take the channel");
        }

        long effective = now + 1;
        SlotRecord rec = SlotRecord.submitted(intent, now, effective,
                "submitted at tick " + now + ", effective tick " + effective);
        records.set(index, rec);
        Lease granted = new Lease(lease.owner(), lease.priority(),
                leaseSequence.incrementAndGet(), intent);
        leases.set(index, granted);
        leaseRefreshed.set(index, now);
        leaseApplied.set(index, 0L);
        return new Granted(rec, granted);
    }

    /**
     * Per-tick lease upkeep: a lease whose intent is being ADVANCED is being used, so it is
     * refreshed; a lease whose intent is gone or dead is released at once; a lease whose intent is
     * still there but has not moved for {@link #DEFAULT_MAX_IDLE_TICKS} is reclaimed.
     *
     * <p>Progress, not presence, is what refreshes. Presence is not evidence of use: an intent
     * submitted into a slot whose tick loop is detached sits there forever looking perfectly live,
     * and a lease refreshed on presence would make the expiry unreachable — which would leave a
     * mutex whose owner can forget to unlock, which is a hang with extra steps.
     *
     * <p>The watermark is {@link SlotRecord#ticksActive()}, which every applier bumps exactly when
     * it advances its intent. It is deliberately NOT {@code lastAppliedTick}: {@code
     * SlotRecord.stampTick} passes its own old {@code lastAppliedTick} through unchanged, so that
     * field does not move on a tick the loop stepped, and a watermark that never moves would make
     * every lease look abandoned and reclaim channels out from under running work.
     */
    public void tickLeases(long tick) {
        tickLeases(tick, DEFAULT_MAX_IDLE_TICKS);
    }

    /** {@link #tickLeases(long)} with the idle bound stated by the caller. */
    public void tickLeases(long tick, int maxIdleTicks) {
        for (int i = 0; i < leases.length(); i++) {
            Lease held = leases.get(i);
            if (held == null) {
                continue;
            }
            SlotRecord rec = records.get(i);
            if (sameIntent(held.intent(), rec.intent()) && rec.isLive() && !rec.cancelRequested()) {
                if (rec.ticksActive() > leaseApplied.get(i)) {
                    leaseApplied.lazySet(i, rec.ticksActive());
                    leaseRefreshed.lazySet(i, tick);
                } else if (tick - leaseRefreshed.get(i) > maxIdleTicks) {
                    // Our intent is still in the slot and still live, but nothing has advanced it
                    // for the whole bound: the loop is detached, the applier is missing or stuck, or
                    // the owner died mid-step. Any of those is a hang for the other consumers of
                    // this channel, so the lease goes back and the record with it.
                    leases.compareAndSet(i, held, null);
                }
                continue;
            }
            // Ours but finished, ours but flagged for teardown, or somebody else's intent sitting
            // where ours was. In every case the holder is not holding a channel any more: a flagged
            // intent is on its last tick and is not asking for anything. The channel goes back
            // immediately rather than waiting out a timer it is not using.
            leases.compareAndSet(i, held, null);
        }
    }

    /**
     * Submit any intent to its own slot as the DIRECT owner, i.e. as the model's own live command.
     *
     * <p>The intent becomes eligible at the next clean tick boundary
     * ({@code lastCompletedTick + 1}). Atomic: a single store that a game-thread reader either sees
     * whole or not at all.
     *
     * <p><b>This method cannot refuse, and that is a property of the caller, not of the lease.</b>
     * {@link Priority#DIRECT} outranks {@link Priority#REPLAY} and a repeated DIRECT submit is a
     * same-owner re-acquire, so the only writer that could block this one does not exist. That is
     * the right answer for the live path — a model that says "walk here" while a replayed plan step
     * is walking there means the plan step is stale, and the model wins — but it is a property that
     * has to be EARNED by the ordering, not assumed, which is why {@link #trySubmit} is the general
     * form and this is a thin convenience over it.
     *
     * <p>Every existing caller in the tree goes through here, which is why the measured set of lost
     * submissions is empty for this path: no existing operation loses anything by this change,
     * because no existing operation is ever refused.
     *
     * @return the stored {@link SlotRecord}
     */
    public SlotRecord submit(ActIntent intent) {
        Submission s = trySubmit(intent, directLease(intent));
        if (s instanceof Refused r) {
            // Unreachable while DIRECT is the top level, and turned into a loud failure rather than
            // a silent no-op if a third owner is ever added above it without this being revisited.
            throw new IllegalStateException("a DIRECT submit was refused, which the priority order "
                    + "says is impossible: " + r.reason());
        }
        return ((Granted) s).record();
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
        int index = slot.ordinal();
        // A read-modify-write, so a compare-and-set loop rather than a get-then-set: the flag is
        // derived from the record that is THERE, and setting a record read a moment ago can clobber a
        // submit that landed in between -- the cancel would carry an intent the caller had just been
        // told was accepted off to the same place. That is the tick loop's lost update one level down,
        // and it is the reason this is not a plain set. Each failed compare means some other write
        // completed (submit, cancel, or the tick loop's guarded store), so the loop cannot spin
        // without progress.
        while (true) {
            SlotRecord cur = records.get(index);
            SlotRecord next = cur.isLive() ? cur.requestCancel() : SlotRecord.empty();
            if (records.compareAndSet(index, cur, next)) {
                // Release on EVERY path, flagged or not. A cancel is the owner saying it is done
                // with the channel, and the teardown tick is the RECORD's business: the applier
                // reads cancelRequested off the record and tears down on its own next tick whether
                // or not a lease still names that record.
                //
                // Holding the lease across the flag was tried here on the theory that releasing
                // early would let a second owner submit over the flagged record and skip the
                // teardown. That theory is false and the comment it produced was a lie about this
                // code: a DIRECT submit outranks and replaces the record outright, carrying the
                // cancelRequested flag away with it, so the lease never prevented the skip it
                // claimed to prevent. All it did was leave heldBy reporting a holder for a channel
                // nobody was using -- a model reading act_status would conclude the channel was
                // taken when it was free.
                leases.compareAndSet(index, leases.get(index), null);
                return cur.isLive();
            }
        }
    }

    /**
     * Give {@code slot} back without cancelling the intent in it.
     *
     * <p>The reference's {@code release(owner)}: a caller that is done with a channel hands it back
     * explicitly. Only {@code owner} can release {@code owner}'s lease, because one owner handing
     * back another owner's channel is the defect in a new shape.
     *
     * @return true if {@code owner} held the lease and it is now free
     */
    public boolean release(ActSlot slot, String owner) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        int index = slot.ordinal();
        Lease held = leases.get(index);
        if (held == null || !held.owner().equals(owner)) {
            return false;
        }
        return leases.compareAndSet(index, held, null);
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

    /**
     * Store {@code next} as the record for {@code slot}, unconditionally (game thread).
     *
     * <p>Only for a caller that owns the slot for the whole of the write — a test driving a slot by
     * hand. {@link ActTickLoop} must NOT use it: it derives {@code next} from a record it read at the
     * START of the tick, and an unconditional store of that derivation silently rolls back any {@link
     * #submit}/{@link #cancel} that landed while the applier was working. {@link #compareAndStore} is
     * the guarded form the loop uses.
     */
    public void store(ActSlot slot, SlotRecord next) {
        records.set(slot.ordinal(), next == null ? SlotRecord.empty() : next);
    }

    /**
     * Store {@code next} as the record for {@code slot} ONLY while the slot still holds {@code
     * expected}; return whether it was stored.
     *
     * <p>This is the missing half of the lock-free contract. {@link #record} + an applier + {@link
     * #store} is a read-modify-write spread over a whole tick's worth of real work, and the game
     * thread's store used to be unconditional: a worker {@link #submit} or {@link #cancel} landing
     * anywhere inside the applier's window was overwritten by a record derived from the OLD intent —
     * so {@code act_set} answered {@code accepted:true} for an intent that was not in the slot, and
     * {@code act_cancel} reported a slot cancelled while it kept running. Both writes were the single
     * atomic store the design was built around; the lost update was in the composition.
     *
     * <p><b>Reference identity IS the generation.</b> Every mutation produces a new {@link SlotRecord}
     * (see its javadoc), so comparing the record is a version check — and one that cannot be fooled by
     * an equal-but-different record. No counter to keep in step, and no allocation on the hot path: a
     * failed compare drops work the caller has already superseded.
     *
     * <p><b>The caller must not retry a failed compare.</b> The record being dropped is the superseded
     * one; writing it again would resurrect the very intent the concurrent write removed, and re-running
     * the applier would replay side effects (a click, an attack) for an intent that is no longer there.
     * The concurrent write already stands.
     *
     * @return true if {@code next} was stored; false if the slot was replaced concurrently
     */
    public boolean compareAndStore(ActSlot slot, SlotRecord expected, SlotRecord next) {
        return records.compareAndSet(slot.ordinal(), expected,
                next == null ? SlotRecord.empty() : next);
    }

    // ===== status =====

    /** A consistent snapshot of every slot for {@code act_status}. */
    public ActStatus status() {
        List<ActStatus.SlotStatus> out = new ArrayList<>(ActSlot.values().length);
        for (ActSlot slot : ActSlot.values()) {
            SlotRecord r = records.get(slot.ordinal());
            // The tactic is read off the INTENT, off the same snapshot the rest of the row comes
            // from, so a row can never report a decision that belongs to a walk this slot has
            // already replaced. It is null for every intent that is not a RouteIntent, and that is
            // honest rather than missing: MoveApplier.stamp stamps only a RouteIntent (a NavIntent
            // has no field to stamp into, and a raw MoveIntent has no tactic at all), so a
            // walk_straight publishes no tactic and this reports that fact instead of inventing a
            // decision nobody made.
            MoveTactic tactic = r.intent() instanceof RouteIntent route ? route.tactic() : null;
            out.add(new ActStatus.SlotStatus(
                    slot, r.phase(), r.intent() != null, intentKind(r.intent()),
                    r.ticksActive(), r.message(), r.hazard(), r.belief(), tactic,
                // The count rides the same construction as the grade, on the same snapshot of the
                // same record -- a projection that reports one without the other is the row reading
                // `belief: UNKNOWN, unreadCells: null`, which is the prose shape this replaced one
                // field down.
                r.unreadCells()));
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
