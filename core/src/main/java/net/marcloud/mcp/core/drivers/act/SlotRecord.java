package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.util.Belief;

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
 *   <li>{@code belief} — how well-earned {@code message} is as a statement about the WORLD, or
 *       {@code null} when no site has examined it. Carried forward by every copy-with helper below
 *       rather than cleared, for {@link #hazard}'s reason: a terminal dig's grade is the last thing
 *       anybody will ask this slot, and dropping it on the way out would leave {@code act_status}
 *       quoting a sentence with nothing to weigh it by. See {@link #UNGRADED} for what an
 *       unexamined line carries, and for why it is not {@link Belief#UNKNOWN}.
 *   <li>{@code unreadCells} — how many cells a search asked about and could not read, or null when
 *       the line is not a statement about a searched area. <b>Beside the grade and not inside it</b>,
 *       because a grade says how a value was obtained and a count is a fact about the world the
 *       claim was about — the same reason {@code hazard} rides here rather than inside the message.
 *       Null is a distinct value from {@code 0}: null says this line did not involve unread terrain,
 *       and {@code 0} says it did and every cell was readable, which is a stronger claim about the
 *       world that only a search can make.
 */
public record SlotRecord(
        ActIntent intent,
        ActPhase phase,
        long submittedTick,
        long effectiveTick,
        long lastAppliedTick,
        int ticksActive,
        boolean cancelRequested,
        String message,
        NavHazard hazard,
        Belief belief,
        Integer unreadCells) {

    /**
     * The grade a record carries when nothing has graded it: <b>none</b>.
     *
     * <p><b>Why this is null and not {@link Belief#UNKNOWN}, and why that change is the fix rather
     * than a retreat.</b> This constant used to be spelled {@code Belief.UNKNOWN}, which is the
     * whole defect. {@code Belief.UNKNOWN} has a precise meaning -- "somebody looked and could not
     * see" -- so a record carrying it claims an observation that was never made, and a record
     * carrying it for a line <i>nobody looked into</i> claims the same thing as a record carrying
     * it for a route refusal that counted 412 unread cells. Those are opposite advice for a caller:
     * one says "go load the chunks and ask again", the other says "nobody has looked at this, so
     * there is nothing yet to act on". {@code Graded}'s own javadoc names the prohibition -- "nobody
     * looked and nobody recorded that nobody looked must not be the same value" -- and a constant
     * aliased onto {@code UNKNOWN} broke it on the way into the record, on every walk, silently.
     *
     * <p>So the two are different values now: {@code null} is nobody having looked, and
     * {@link Belief#UNKNOWN} is somebody having looked and failed. Neither is derivable from the
     * other, and {@link #withBelief(Belief)} is the only door onto a non-null one -- which makes the
     * layer total over its own output: every grade a record carries is a grade a site chose, and the
     * absence is a first-class state rather than a lie told in a real grade's clothing.
     *
     * <p><b>Why not a fourth {@link Belief} constant instead.</b> Because "not graded" is not a
     * fourth way of OBTAINING a value; it is the absence of one, and {@link Belief}'s own javadoc
     * enumerates and rejects every fourth constant it considered (coarser, {@code STALE},
     * {@code PARTIAL}, {@code CONTRADICTED}) for being gradations rather than absences. Folding the
     * absence into the same enum would put a case next to three that answer "how" for a case that
     * answers "whether", and every {@code switch} over {@code Belief} would then owe a branch for
     * something that is not a belief. A null is the honest shape: it is what every other
     * "nothing here" on this record already is. See {@link #hazard} for the same convention and the
     * same reason.
     *
     * <p><b>Why not a paired boolean.</b> A non-null {@code belief} beside a {@code graded} flag is
     * two sources of truth for one fact, and they disagree the moment a site grades a line and
     * forgets the flag -- which is precisely the failure {@link Graded}'s carrier rule exists to
     * make unconstructible.
     *
     * <p><b>What becomes unrepresentable.</b> No caller can write {@code record.belief() ==
     * Belief.UNKNOWN} and conclude "this client looked and could not see", because that equality
     * now means exactly one thing. And no site can say "nobody looked" while holding
     * {@code UNKNOWN}, because the two are no longer the same value in the first place. The
     * {@link #mayActOn()} accessor below is what keeps a null default from failing OPEN instead.
     */
    public static final Belief UNGRADED = null;

    /**
     * Whether this record's {@code message} is a claim a caller may act on without looking again.
     *
     * <p><b>This is the door, and it exists because {@link #UNGRADED} is null.</b> Slice A made
     * {@code UNGRADED} be {@link Belief#UNKNOWN} so that a caller's {@code belief() != UNKNOWN}
     * check failed SAFE, and that remains the reason this accessor is not optional. Under a null
     * default the same check fails OPEN -- {@code null != UNKNOWN} is true, so a caller written that
     * way would find every line nobody graded actionable -- and the only thing that changed is that
     * the mistake moved from a constant nobody can miss into an expression that reads as ordinary
     * null-safe code. {@link net.marcloud.mcp.core.util.Graded#mayActOn()} already has this shape
     * and is the precedent inside this layer.
     *
     * <p>So the honest value and the safe fallback are split across two things rather than fought
     * over inside one: the field says nobody looked, and this answers what a caller does about it.
     * Both directions fail safe. {@code null} refuses, {@link Belief#UNKNOWN} refuses, and only a
     * grade somebody chose -- {@link Belief#OBSERVED} or {@link Belief#INFERRED} -- permits acting.
     */
    public boolean mayActOn() {
        return belief != null && belief != Belief.UNKNOWN;
    }

    /**
     * The nine-field shape, byte-identical in behaviour to what it was before the grade existed.
     *
     * <p>Every existing construction site -- the appliers, the tests, and every copy-with helper
     * below that does not care about the grade -- keeps compiling and gets {@link #UNGRADED}. A
     * field that changed the meaning of an existing constructor would make the grade look like it
     * was always there, which is the one thing it must not look like.
     */
    public SlotRecord(ActIntent intent, ActPhase phase, long submittedTick, long effectiveTick,
                      long lastAppliedTick, int ticksActive, boolean cancelRequested,
                      String message, NavHazard hazard) {
        this(intent, phase, submittedTick, effectiveTick, lastAppliedTick, ticksActive,
                cancelRequested, message, hazard, UNGRADED, NO_COUNT);
    }

    /**
     * The ten-field shape: a grade with no count beside it.
     *
     * <p>Carries {@link #NO_COUNT} and not {@code 0}, for the reason {@link ActOutcome}'s six-field
     * constructor does: a caller reading {@code 0} here would conclude the line is a statement about
     * a searched area in which nothing was unread, and no search ran for a slot that was merely
     * submitted.
     */
    public SlotRecord(ActIntent intent, ActPhase phase, long submittedTick, long effectiveTick,
                      long lastAppliedTick, int ticksActive, boolean cancelRequested,
                      String message, NavHazard hazard, Belief belief) {
        this(intent, phase, submittedTick, effectiveTick, lastAppliedTick, ticksActive,
                cancelRequested, message, hazard, belief, NO_COUNT);
    }

    /**
     * No unread-cell claim travels with this record, because no search produced one.
     *
     * <p>The counterpart of {@link ActOutcome#NO_COUNT}, and it must be the SAME value: the seam
     * between the two is a bare field copy, and two constants that agreed on the wrong value once
     * are exactly how the grade's own null-default defect survived every test that read one side.
     */
    public static final Integer NO_COUNT = null;

    /**
     * Same slot, new grade, and every other field untouched.
     *
     * <p>A separate door rather than an extra argument on the helpers a tick loop calls every
     * frame. Those helpers carry the previous grade forward on purpose, so a terminal line keeps
     * the grade it was earned with until something else occupies the slot -- exactly how
     * {@link #hazard} behaves -- and the one place that sets a new grade says so out loud.
     */
    public SlotRecord withBelief(Belief newBelief) {
        return new SlotRecord(intent, phase, submittedTick, effectiveTick, lastAppliedTick,
                ticksActive, cancelRequested, message, hazard, newBelief, unreadCells);
    }

    /**
     * Same slot, new unread-cell count, and every other field untouched.
     *
     * <p>The one door onto the count, and it accepts {@code null} -- which means "this line is not
     * about unread terrain" and is NOT the same claim as {@code 0}. A separate door rather than an
     * extra argument on the per-tick helpers, for {@link #withBelief}'s reason: those carry the
     * previous value forward on purpose, and the one place that sets a new one says so out loud.
     */
    public SlotRecord withUnreadCells(Integer cells) {
        return new SlotRecord(intent, phase, submittedTick, effectiveTick, lastAppliedTick,
                ticksActive, cancelRequested, message, hazard, belief, cells);
    }

    /** The empty slot: no intent, IDLE, no timing, no line being walked, and no claim made. */
    public static SlotRecord empty() {
        return new SlotRecord(null, ActPhase.IDLE, 0L, 0L, 0L, 0, false, "idle", null, UNGRADED,
                NO_COUNT);
    }

    /** A freshly-submitted intent that becomes eligible at {@code effectiveTick}. */
    public static SlotRecord submitted(ActIntent intent, long submittedTick, long effectiveTick,
                                       String message) {
        // A fresh intent starts with no hazard, and that is the load-bearing half of this factory:
        // the previous occupant's warning is a statement about a line this intent is not walking,
        // and inheriting it would warn about terrain the player is walking away from. The grade is
        // reset for exactly that reason: the previous occupant's grade is a statement about a read
        // this intent never made.
        return new SlotRecord(intent, ActPhase.IDLE, submittedTick, effectiveTick,
                0L, 0, false, message, null, UNGRADED, NO_COUNT);
    }

    /** True if there is an intent and it has not reached a terminal phase. */
    public boolean isLive() {
        return intent != null && !phase.isTerminal();
    }

    // ===== copy-with helpers (records are immutable; each returns a new one) =====

    /**
     * Same slot carrying a different intent, with every timing and lifecycle field untouched.
     *
     * <p>Exists for one caller. {@code MoveApplier} republishes the intent each tick to stamp the
     * tactic the controller chose onto it, and {@link RouteIntent#withTactic} returns a new intent
     * rather than mutating one -- so the only way to get the decision into the record is to build
     * the record around the new intent. A general "replace the intent" setter would be a trap:
     * {@code submittedTick}, {@code effectiveTick} and {@code cancelRequested} are facts about the
     * SUBMISSION, and an intent swapped in without them would inherit a gating decision that was
     * made about a different walk.
     */
    public SlotRecord withIntent(ActIntent newIntent) {
        return new SlotRecord(newIntent, phase, submittedTick, effectiveTick,
                lastAppliedTick, ticksActive, cancelRequested, message, hazard, belief, unreadCells);
    }

    /** Same slot, new phase + message (clears the cancel request on a terminal phase). */
    public SlotRecord withPhase(ActPhase newPhase, String newMessage) {
        return new SlotRecord(intent, newPhase, submittedTick, effectiveTick,
                lastAppliedTick, ticksActive, cancelRequested && !newPhase.isTerminal(),
                newMessage, hazard, belief, unreadCells);
    }

    /** Same slot marked ACTIVE for another tick: bumps {@code ticksActive}. */
    public SlotRecord markActive(long tick, String newMessage) {
        return new SlotRecord(intent, ActPhase.ACTIVE, submittedTick, effectiveTick,
                tick, ticksActive + 1, cancelRequested, newMessage, hazard, belief, unreadCells);
    }

    /**
     * Same slot marked ACTIVE, carrying the hazard this tick observed.
     *
     * <p>The applier's per-tick report is where the hazard has to land: {@link ActOutcome} knows
     * it, and {@code act_status} reads the slot. Writing it on the terminal path too is the point
     * -- a walk that ended in lava is the walk whose warning finally mattered, and dropping the
     * field on the way out would leave the terminal status line describing a failure with no cause.
     */
    public SlotRecord markActive(long tick, String newMessage, NavHazard newHazard) {
        return new SlotRecord(intent, ActPhase.ACTIVE, submittedTick, effectiveTick,
                tick, ticksActive + 1, cancelRequested, newMessage, newHazard, belief, unreadCells);
    }

    /** Same slot with a pending cancel flag set (kept non-terminal for teardown). */
    public SlotRecord requestCancel() {
        return new SlotRecord(intent, phase, submittedTick, effectiveTick,
                lastAppliedTick, ticksActive, true, "cancel requested", hazard, belief, unreadCells);
    }

    /** Same slot with only the {@code lastAppliedTick} advanced. */
    public SlotRecord stampTick(long tick) {
        return new SlotRecord(intent, phase, submittedTick, effectiveTick,
                lastAppliedTick, ticksActive, cancelRequested, message, hazard, belief, unreadCells);
    }
}