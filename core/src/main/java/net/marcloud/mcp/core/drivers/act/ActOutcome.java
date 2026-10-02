package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.util.Belief;

/**
 * The result of one controller {@code tick()} step. Controllers are pure state
 * machines over an {@link ActActuator}; each step returns an outcome describing
 * where the machine now is and whether it finished.
 *
 * @param state    the controller's own state phase, mapped onto {@link ActPhase}
 * @param terminal true once the machine has finished (no more ticks will change it)
 * @param ok       for a terminal outcome: did it succeed? (meaningless while non-terminal)
 * @param message  human-readable status/reason (why it failed, what it did)
 * @param hazard   the hazard sampled on the line being walked, or null when none is known. Typed
 *                 rather than prose on purpose: a caller deciding whether to cancel a walk needs a
 *                 value to branch on and a distance to size the reaction against, not a sentence
 *                 to substring-match while the player is still walking toward the pit. See
 *                 {@link NavHazard}.
 * @param belief   how well-earned the {@code message} is as a statement about the WORLD, or null
 *                 when no site examined it. The message and the grade are separate fields on
 *                 purpose: prose is for a reader and a grade is for a caller, and folding them
 *                 together would mean a caller substring-matches a sentence to find out whether it
 *                 may act. Null is NOT {@link Belief#UNKNOWN} and must never be collapsed into it:
 *                 null says nobody looked, UNKNOWN says somebody looked and could not see. Ask
 *                 {@link #mayActOn()} rather than comparing {@code belief()} yourself, because a
 *                 bare {@code != UNKNOWN} test fails open on null. See {@link #UNGRADED}.
 * @param unreadCells how many cells a search asked about and could not read, or null when this
 *                 outcome is not a statement about a searched area at all. Beside the grade and
 *                 NOT inside {@link net.marcloud.mcp.core.util.Graded}, because a grade says how a
 *                 value was obtained while "412 cells were unread" is a fact about the world the
 *                 claim was about; it rides the way {@link NavHazard} rides. Null is a distinct
 *                 value from {@code 0}: null says this failure did not involve unread terrain, and
 *                 {@code 0} says it did and every cell was readable -- a stronger claim that only
 *                 a search can make.
 */
public record ActOutcome(ActPhase state, boolean terminal, boolean ok, String message,
                        NavHazard hazard, Belief belief, Integer unreadCells) {

    // ===== the named derivations every graded site uses =====
    //
    // Five constants, and the point of naming them is that the DERIVATION becomes statically
    // visible at the site instead of living in a comment. Each factory call in this package states
    // which derivation its sentence rests on, and the reflection test in
    // `ABeliefCensusOverTheActScenariosTest.everyOutcomeSiteInTheActLayerStatesItsDerivation`
    // fails on one that states none. That is the whole
    // difference between "40 sites were graded" as a claim in a report and as a property of the
    // tree.
    //
    // They are constants here rather than inline `Belief.OBSERVED` at forty call sites for two
    // reasons, and the second is the one that matters: `Belief.OBSERVED` is spelled exactly once in
    // this file, so `GradedCallSitesAreAllowlistedTest` gains ONE allowlist entry with one reason
    // instead of nine files each holding a permission. A permission held in one named place is
    // reviewable; the same permission copied into nine files is nine permissions nobody granted.

    /**
     * OBSERVED: every clause of the sentence is a value read from a live field, or a counter on
     * this machine, on the tick the sentence was built.
     *
     * <p>What this is true about, stated because it is the sharp edge: the CLIENT's copy. 1.8.9
     * sends no verdict back, so an OBSERVED claim is a claim about what this client can see and
     * nothing else. {@code p.onGround} is the sharpest case -- {@code NetHandlerPlayServer:373}
     * reads that same field and calls {@code playerEntity.jump()} with it -- so the value is at once
     * an observation about our own body and a lever the server has adopted. Grading a walk that
     * stepped off a ledge because it read {@code onGround == false} must not imply the server
     * agreed.
     */
    public static final Belief READ_DIRECTLY = Belief.OBSERVED;

    /**
     * INFERRED: arithmetic on values read this tick, or a comparison between them.
     *
     * <p>A distance, a bounding-box reach, an arrival epsilon, a count of ticks. Every input was
     * read this tick and no input is stale, so the derivation cannot be wrong -- it is still a
     * derivation, and {@link net.marcloud.mcp.core.util.Graded} requires it to say so rather than
     * letting an arithmetic result borrow the grade of its inputs.
     */
    public static final Belief DERIVED_FROM_READS = Belief.INFERRED;

    /**
     * INFERRED: the plan was searched against the client's world copy, so every sentence about
     * where the route goes rests on client prediction the server may revert.
     *
     * <p>{@code RoutePlanning.java:26-27} states this in prose -- "a plan built on the client's
     * belief can be rubber-banded away" -- and {@code RoutePlanning.executorFor} names the world it
     * used in the sentence. Naming it as a grade is what lets a caller branch on it: a routed walk
     * and a point walk to the same cell are not the same quality of claim, and before this wave
     * nothing on the row said so.
     */
    public static final Belief PLANNED_ON_CLIENT_WORLD = Belief.INFERRED;

    /**
     * INFERRED: the CELL was sampled on an earlier tick and only its distance is arithmetic.
     *
     * <p>{@code NavController.reposition} -- "the cell, the kind and the consequence are facts about
     * the world and cannot change while the player walks a straight line toward them" -- an honest
     * inference that was written as fact in a comment. This constant is that comment, typed: a
     * hazard's distance is the one part of the warning that genuinely changes tick to tick, and it
     * changes by arithmetic on a cell nobody re-read.
     */
    public static final Belief REUSED_CELL = Belief.INFERRED;

    /**
     * UNKNOWN: a read this sentence depends on came back with nothing, so this client cannot see
     * what it is being told about.
     *
     * <p>The honest reading of a null on this seam. {@code blockAt} returns null for air, for an
     * unloaded chunk, for an unnamed block and -- in {@code LivePlayerActuator}'s catch block -- for
     * a read that threw, and {@code position()} returns null for a body that is not there. A
     * sentence built on one of those is a statement about what could be looked at, never a
     * statement about the world, and {@link #mayActOn()} refuses it.
     */
    public static final Belief READ_CAME_BACK_EMPTY = Belief.UNKNOWN;

    /** Every derivation a graded site may name. A site outside this set is not graded. */
    public static final Belief[] GRADED_DERIVATIONS = {
        READ_DIRECTLY, DERIVED_FROM_READS, PLANNED_ON_CLIENT_WORLD, REUSED_CELL,
        READ_CAME_BACK_EMPTY,
    };

    /**
     * The grade an outcome carries when its message was never graded: <b>none</b>.
     *
     * <p>Every one of the eight ungraded factories below predates the belief layer, and none of them
     * has been audited against the layer's 18 sites. Claiming OBSERVED for all of them would be the
     * exact lie this layer exists to prevent, applied 40 times: 12 of the design's 18 sites are
     * lying under "every read is OBSERVED", and that mapping is cheapest precisely because nobody
     * has to look. So they say <i>nothing</i>, which is honest -- and which is why this value had to
     * change rather than merely be documented.
     *
     * <p><b>Why null, and why that is a fix rather than a retreat.</b> This constant used to be
     * {@link Belief#UNKNOWN}, and that is exactly the defect: {@code UNKNOWN} means "somebody
     * looked and could not see", so an ungraded line carrying it claims an observation nobody made.
     * Worse, it made the two indistinguishable <i>after</i> this layer started propagating grades to
     * the record: {@code MoveApplier} copies {@code out.belief()} onto every walk, so a route
     * refusal that counted 412 unread cells -- a genuine {@code UNKNOWN} -- and a
     * {@code NavController} "still walking" line -- never examined -- arrived at {@code act_status}
     * as the same value. The two want opposite advice from a caller: one says load the chunks and
     * ask again, the other says nobody has looked, so there is nothing yet to act on. Under null
     * they are different values, and {@link #mayActOn()} is what keeps a null default failing safe
     * rather than open.
     *
     * <p>Consistency with {@link SlotRecord#UNGRADED} is load-bearing rather than tidy: the seam
     * between them is a bare field copy, so the two constants must agree or the copy invents a
     * grade. They used to agree, on the same wrong value, which is why the conflation survived
     * every test that read one side of the seam.
     */
    public static final Belief UNGRADED = null;

    /**
     * Whether this outcome's {@code message} is a claim a caller may act on without looking again.
     *
     * <p><b>Why this accessor exists rather than being left to the caller.</b> Slice A spelled
     * {@link #UNGRADED} as {@link Belief#UNKNOWN} precisely so that a bare
     * {@code belief() != Belief.UNKNOWN} failed SAFE -- and its own javadoc says why that direction
     * is the one to keep: "a field whose wrong value fails safe is a field a maintainer will set
     * optimistically, and the failure mode is recoverable; a field whose wrong value fails open is
     * one nobody sets at all". Under a null default that bare check fails OPEN, because
     * {@code null != UNKNOWN} is true -- so the mistake would move from a constant nobody can miss
     * into an expression that reads as ordinary null-safe code, and every ungraded line would
     * become actionable.
     *
     * <p>So the honest value and the safe fallback live in two places: the field says nobody
     * looked, and this answers what a caller does about it. Both refuse. Only a grade a site
     * actually chose -- {@link Belief#OBSERVED} or {@link Belief#INFERRED} -- permits acting.
     * {@link net.marcloud.mcp.core.util.Graded#mayActOn()} is the same shape and the precedent
     * inside this layer.
     */
    public boolean mayActOn() {
        return belief != null && belief != Belief.UNKNOWN;
    }

    /**
     * The five-argument shape, kept byte-identical in behaviour to what it was before the belief
     * existed.
     *
     * <p>Every caller in this repository that built an outcome directly still compiles, and every
     * one of them gets {@link #UNGRADED} rather than a claim nobody made.
     */
    public ActOutcome(ActPhase state, boolean terminal, boolean ok, String message,
                      NavHazard hazard) {
        this(state, terminal, ok, message, hazard, UNGRADED);
    }

    /**
     * The six-field shape: a grade with no count beside it.
     *
     * <p>Carries {@link #NO_COUNT} and not {@code 0}, and that is the load-bearing half. A caller
     * reading {@code 0} off this constructor would conclude the outcome is a statement about a
     * searched area in which nothing was unread -- a claim about the world that nothing here
     * supports. {@code null} says only what is true: this outcome is not about unread terrain.
     */
    public ActOutcome(ActPhase state, boolean terminal, boolean ok, String message,
                      NavHazard hazard, Belief belief) {
        this(state, terminal, ok, message, hazard, belief, NO_COUNT);
    }

    /** No unread-cell claim travels with this outcome, because no search produced one. */
    public static final Integer NO_COUNT = null;

    /** Still running; not terminal. */
    public static ActOutcome running(String message) {
        return new ActOutcome(ActPhase.ACTIVE, false, false, message, null, UNGRADED, NO_COUNT);
    }

    /**
     * Still running, and carrying a hazard sampled on the line being walked.
     *
     * <p>The typed field is the point. {@link #message()} says the same thing in prose, but a
     * caller that has to substring-match a sentence before it can decide to cancel is a caller
     * that cancels late, and "warn before the player falls in" is a claim about latency. Null
     * means no hazard is known -- never "a hazard of unknown kind": an unreadable cell is an
     * absence, and {@link NavHazard} is only ever built from a read that came back with a name
     * or from a floor the reader had already proven it could read.
     */
    public static ActOutcome running(String message, NavHazard hazard) {
        return new ActOutcome(ActPhase.ACTIVE, false, false, message, hazard, UNGRADED, NO_COUNT);
    }

    /** Finished successfully. */
    public static ActOutcome done(String message) {
        return new ActOutcome(ActPhase.COMPLETE, true, true, message, null, UNGRADED, NO_COUNT);
    }

    /**
     * Finished successfully, still reporting what the line held.
     *
     * <p>A walk that ends is the walk whose hazard finally mattered: the player fell the 30
     * blocks, drowned, or jammed against the wall above the pit. Throwing that away at the
     * terminal tick is the defect this field exists to close, so the terminal outcome carries
     * the hazard it ended with.
     */
    public static ActOutcome done(String message, NavHazard hazard) {
        return new ActOutcome(ActPhase.COMPLETE, true, true, message, hazard, UNGRADED, NO_COUNT);
    }

    /** Finished, but could not be carried out honestly. */
    public static ActOutcome failed(String message) {
        return new ActOutcome(ActPhase.FAILED, true, false, message, null, UNGRADED, NO_COUNT);
    }

    /** Finished, but could not be carried out honestly, still reporting the line's hazard. */
    public static ActOutcome failed(String message, NavHazard hazard) {
        return new ActOutcome(ActPhase.FAILED, true, false, message, hazard, UNGRADED, NO_COUNT);
    }

    /** Finished because it was cancelled/superseded. */
    public static ActOutcome cancelled(String message) {
        return new ActOutcome(ActPhase.CANCELLED, true, false, message, null, UNGRADED, NO_COUNT);
    }

    /** Finished because it was cancelled/superseded, still reporting the line's hazard. */
    public static ActOutcome cancelled(String message, NavHazard hazard) {
        return new ActOutcome(ActPhase.CANCELLED, true, false, message, hazard, UNGRADED, NO_COUNT);
    }

    // ===== the graded overloads =====
    //
    // Purely additive. The eight factories above keep their signatures and their bodies, so every
    // existing caller is byte-for-byte unaffected; these are the only ways to attach a grade. A
    // refusal that counted unread cells cannot be reported the same way as one that did not, and
    // the cheapest way to make that true without touching a shared file's existing API is a new
    // door rather than a changed one.

    /**
     * Finished, could not be carried out honestly, and the reason is graded.
     *
     * <p>The first honest use in production. A route refusal over terrain the search never read is
     * a statement about this client, and before this overload a caller could only tell by counting
     * the words in the sentence.
     */
    public static ActOutcome failed(String message, Belief belief) {
        return new ActOutcome(ActPhase.FAILED, true, false, message, null, belief, NO_COUNT);
    }

    /** As {@link #failed(String, Belief)}, still reporting the line's hazard. */
    public static ActOutcome failed(String message, NavHazard hazard, Belief belief) {
        return new ActOutcome(ActPhase.FAILED, true, false, message, hazard, belief, NO_COUNT);
    }

    /**
     * Finished, could not be carried out honestly, the reason is graded, and a search counted the
     * cells it could not read.
     *
     * <p><b>The count travels, and it was decided rather than deferred.</b> It is already computed
     * -- {@code RoutePlanning.noRouteMessage} was putting it in the sentence -- so carrying it costs
     * a field rather than a probe, which is the only reason it was affordable at all under the
     * zero-world-read rule. It sits BESIDE the grade rather than inside
     * {@link net.marcloud.mcp.core.util.Graded}: a grade says how a value was obtained, and "412
     * cells were unread" is a fact about the world the refusal was about. It rides the way
     * {@link NavHazard} rides -- a second concern beside the claim, not a fourth thing the carrier
     * is trying to be.
     *
     * <p>The contrast with the refusal it grades is the whole reason this field exists. Before it,
     * a caller that could see the grade could learn THAT a walk failed to see enough and still not
     * learn HOW MUCH -- and a caller in that position has to substring-match prose, which is the
     * same defect {@link NavHazard} was added to close.
     */
    public static ActOutcome failed(String message, Belief belief, int unreadCells) {
        return new ActOutcome(ActPhase.FAILED, true, false, message, null, belief, unreadCells);
    }

    /** Still running, with the current status graded. */
    public static ActOutcome running(String message, Belief belief) {
        return new ActOutcome(ActPhase.ACTIVE, false, false, message, null, belief, NO_COUNT);
    }

    /** As {@link #running(String, Belief)}, still reporting the line's hazard. */
    public static ActOutcome running(String message, NavHazard hazard, Belief belief) {
        return new ActOutcome(ActPhase.ACTIVE, false, false, message, hazard, belief, NO_COUNT);
    }

    /** Finished successfully, with the arrival graded. */
    public static ActOutcome done(String message, Belief belief) {
        return new ActOutcome(ActPhase.COMPLETE, true, true, message, null, belief, NO_COUNT);
    }

    /** As {@link #done(String, Belief)}, still reporting the line's hazard. */
    public static ActOutcome done(String message, NavHazard hazard, Belief belief) {
        return new ActOutcome(ActPhase.COMPLETE, true, true, message, hazard, belief, NO_COUNT);
    }

    /** Cancelled, with the reason graded. */
    public static ActOutcome cancelled(String message, Belief belief) {
        return new ActOutcome(ActPhase.CANCELLED, true, false, message, null, belief, NO_COUNT);
    }

    /** As {@link #cancelled(String, Belief)}, still reporting the line's hazard. */
    public static ActOutcome cancelled(String message, NavHazard hazard, Belief belief) {
        return new ActOutcome(ActPhase.CANCELLED, true, false, message, hazard, belief, NO_COUNT);
    }

    /**
     * Same outcome, with the unread-cell count attached or detached.
     *
     * <p>The one door onto the count, exactly as {@link net.marcloud.mcp.core.util.Graded} has one
     * door per grade: a count cannot appear on an outcome without a site saying where it came from.
     * {@code null} is a legal value here and means "this outcome is not about unread terrain",
     * which is not the same claim as {@code 0}.
     */
    public ActOutcome withUnreadCells(Integer cells) {
        return new ActOutcome(state, terminal, ok, message, hazard, belief, cells);
    }
}
