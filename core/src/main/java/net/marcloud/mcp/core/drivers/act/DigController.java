package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;

/**
 * Pure state machine that breaks a block. Ticked by the INTERACT applier through
 * an {@link ActActuator}; it never marshals threads itself.
 *
 * <p>States: RESOLVING (validate target + reach, then {@code startDig}) → DIGGING
 * ({@code pumpDig} each tick, polling {@code blockPresent} for completion) →
 * COMPLETE / CANCELLED / FAILED.
 *
 * <ul>
 *   <li><b>Reach.</b> If the eye-to-block-center distance exceeds
 *       {@link ActActuator#reachDistance()}, fail honestly — the game would reject it.
 *   <li><b>No block.</b> An already-air target fails ("nothing to dig") rather than
 *       pretending to mine air.
 *   <li><b>Start retry.</b> {@code startDig} may report "not yet" (false) while the
 *       game is in its post-hit delay window; the controller retries each tick up to
 *       {@code blockHitDelay} attempts, then fails. This honors the vanilla
 *       {@code blockHitDelay} instead of hammering start every tick forever.
 *   <li><b>Progress.</b> Once digging, a {@code pumpDig} that reports no progress
 *       (false) fails — the dig stalled (e.g. tool can't break it).
 *   <li><b>Completion.</b> When the block is gone ({@code blockPresent} false) the
 *       dig is COMPLETE.
 *   <li><b>Cancel.</b> A cancel calls {@code cancelDig} and ends CANCELLED.
 *   <li><b>Completion, and what it saw.</b> A completion is one of three grades, not one
 *       boolean: both names read this tick and different is {@link Belief#OBSERVED}; a start
 *       sample that could not be read is {@link Belief#INFERRED}, carrying the fallback's own
 *       admission as its derivation; a target that reads no name this tick is
 *       {@link Belief#UNKNOWN}, because a missing name is air, an unloaded chunk and a failed
 *       read alike at this seam. The grade costs ZERO world reads: it is a function of the name
 *       {@link #targetGone} already had to read to answer its own question.
 * </ul>
 */
public final class DigController {

    /** Default start-retry budget when the caller does not specify one. */
    public static final int DEFAULT_START_ATTEMPTS = 6;

    /**
     * The derivation behind every {@link Belief#INFERRED} completion.
     *
     * <p>{@link #targetGone}'s own javadoc says the emptiness fallback "is wrong in the safe
     * direction (it under-reports completion rather than over-reporting it)". That sentence was
     * prose nobody could branch on, so an INFERRED completion and an OBSERVED one were the same
     * value to every caller. It is also the {@code why} the type will not accept without, and it
     * is the clause appended to the message, so the sentence a reader sees and the provenance the
     * constructor enforces are one string and cannot drift apart.
     */
    private static final String INFERRED_WHY = "the block could not be named on the tick the dig "
            + "started, so this rests on the emptiness test, which is wrong in the safe direction "
            + "(it under-reports completion rather than over-reporting it)";

    /**
     * Why a target that reads no name is {@link Belief#UNKNOWN} and not either of the others.
     *
     * <p>{@code blockAt} returns null for air, out of range, no world, and -- in
     * {@code LivePlayerActuator}'s catch block -- for a read that threw. A dig that breaks its
     * block cleanly into air is therefore indistinguishable at this seam from a dig whose target
     * it could not look at, and calling either one OBSERVED would be the same confident wrong
     * answer the layer exists to make unconstructible.
     */
    private static final String UNKNOWN_WHY = "the target reads no name this tick, and a missing "
            + "name is air, an unloaded chunk and a failed read alike at this seam, so this is a "
            + "statement about what could be seen rather than about the world";

    private enum State { RESOLVING, DIGGING }

    private final int x;
    private final int y;
    private final int z;
    private final ActActuator.Face face;
    private final int maxStartAttempts;

    private State state = State.RESOLVING;
    private int startAttempts;
    private int pumps;
    private boolean cancelRequested;

    /**
     * The name {@link #targetGone} read on its last call, kept so grading a completion costs no
     * world read of its own.
     *
     * <p>Null means either "the read came back empty" or "the emptiness fallback ran", and those
     * two are told apart by {@link #diggingBlock}, which is what makes the grade a function of two
     * values already in hand rather than a fresh look at the world.
     */
    private String completionRead;

    /** The grade this dig has already put on the record, or null while it has reported none. */
    private Belief completionGrade;

    /**
     * Whether a world read has happened since {@link #completionGrade} was recorded.
     *
     * <p>Set by {@link #targetGone} and consumed by {@link #withoutUpgrade}. It is the whole
     * content of "without a new read": an upgrade recorded while this was false was manufactured
     * out of a cached value rather than earned by looking again.
     */
    private boolean readSinceGraded;

    /**
     * The registry name of the block this dig is actually breaking, sampled when the dig started.
     *
     * <p>The completion test compares against THIS rather than asking whether the space is empty.
     * Measured on a live client, {@code blockPresent} is true for water, lava, gravel and tall grass
     * -- everything but air -- so as a completion test it reports "still digging" for any position
     * that has been refilled, when the block in fact broke.
     *
     * <p><b>Not a fix for an observed stall,</b> and the record of that correction belongs here: the
     * predicted failure was that mining underwater would announce "dig stalled" about a broken block,
     * and measured live it does not. Water reaches the emptied space 3 game ticks after the break
     * (its {@code tickRate} is 5) while this controller polls once per tick, so the deciding poll
     * still sees air. This is therefore correctness by construction -- the test now asks the
     * caller's actual question regardless of refill timing -- while the defect that WAS reachable is
     * the ordering one below, which does not depend on timing at all.
     *
     * <p>Sampled on the tick the dig STARTS, not at construction: those are different ticks, and in
     * between the world can change. Null when the target could not be read, which the completion
     * test treats the same way it treats air -- see {@link #targetGone}.
     */
    private String diggingBlock;

    public DigController(InteractIntent intent) {
        this(intent, DEFAULT_START_ATTEMPTS);
    }

    public DigController(InteractIntent intent, int blockHitDelay) {
        this.x = intent.blockX();
        this.y = intent.blockY();
        this.z = intent.blockZ();
        this.face = ActActuator.Face.fromIndex(intent.face());
        this.maxStartAttempts = Math.max(1, blockHitDelay);
    }

    /** Request cancellation; the next {@link #tick} tears down and ends CANCELLED. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** Number of {@code pumpDig} calls issued so far (for status/tests). */
    public int pumps() {
        return pumps;
    }

    /** Number of {@code startDig} attempts issued so far (for status/tests). */
    public int startAttempts() {
        return startAttempts;
    }

    /** Advance one tick against {@code act}. */
    public ActOutcome tick(ActActuator act) {
        if (cancelRequested) {
            act.cancelDig();
            // READ_DIRECTLY: the sentence is about this controller's own flag and the write it just
            // made. It makes no claim about whether the server honoured the cancellation, and could
            // not -- the dig stop packet has no reply in 1.8.9.
            return ActOutcome.cancelled("dig cancelled", ActOutcome.READ_DIRECTLY);
        }
        if (!act.inWorld()) {
            return ActOutcome.failed("not in world", ActOutcome.READ_DIRECTLY);
        }
        if (state == State.DIGGING) {
            // Once digging, the question is whether OUR block is gone -- not whether the space is
            // empty. Something flowing or falling in leaves the space occupied while the target is
            // broken, which an emptiness test reads as "still digging".
            if (targetGone(act)) {
                return complete(act);
            }
        } else if (!act.blockPresent(x, y, z)) {
            // Not started yet and nothing there: there was never anything to dig. Emptiness IS the
            // right question here -- the caller named a position expecting a block at it.
            //
            // READ_CAME_BACK_EMPTY, and this is the case the dig's own completion grades are the
            // precedent for. `blockPresent` is `getMaterial() != Material.air`, which is false for
            // air AND for a cell nothing could read, so "there was never anything to dig" is a
            // statement about one probe's answer rather than about the world. Note it is a REFUSAL
            // and not a completion, so it costs the caller nothing to refuse -- but a caller reading
            // it as "the world is empty there" would be reading a fact nobody established, and the
            // chunk this dig names may simply not be loaded.
            return ActOutcome.failed("no block to dig at (" + x + "," + y + "," + z + ")",
                    ActOutcome.READ_CAME_BACK_EMPTY);
        }
        if (outOfReach(act)) {
            // DERIVED_FROM_READS: `outOfReach` compares the distance between two this-tick position
            // reads against a reach constant, so the sentence is a comparison rather than a field.
            // The same shape as InteractController's reach check, which the design grades 2.B #13,
            // and the same reason: the client's reach constant stands in for a server-side rule this
            // client cannot see.
            return ActOutcome.failed("block (" + x + "," + y + "," + z + ") is out of reach",
                    ActOutcome.DERIVED_FROM_READS);
        }

        switch (state) {
            case RESOLVING:
                startAttempts++;
                if (act.startDig(x, y, z, face)) {
                    state = State.DIGGING;
                    // Sampled HERE, on the tick the dig actually began, because that is the block
                    // vanilla is now breaking. Sampling at construction would record whatever was
                    // there when the intent was built, which can be several ticks earlier.
                    diggingBlock = act.blockAt(x, y, z);
                    // READ_CAME_BACK_EMPTY when the name did not come back, DERIVED_FROM_READS when
                    // it did. The sentence interpolates the name into itself, so the grade is the
                    // name's: "started digging stone (0,1,0)" publishes a block identity that a read
                    // supports, and "started digging (0,1,0)" publishes none, and the difference is
                    // whether anyone could name what is being broken.
                    return ActOutcome.running("started digging "
                            + (diggingBlock == null ? "" : diggingBlock + " ")
                            + "(" + x + "," + y + "," + z + ")",
                            diggingBlock == null
                                    ? ActOutcome.READ_CAME_BACK_EMPTY
                                    : ActOutcome.DERIVED_FROM_READS);
                }
                if (startAttempts >= maxStartAttempts) {
                    // READ_CAME_BACK_EMPTY: `startDig` returned false N times and the seam cannot say
                    // whether that is a refusal by the game or an absent client -- the same false
                    // that `blockAt` reports for an unreadable cell, arriving through a boolean
                    // instead. The sentence names the count rather than a cause, which is the
                    // honest report of a comparison that cannot separate its two readings.
                    return ActOutcome.failed("could not start digging after " + startAttempts
                            + " attempts", ActOutcome.READ_CAME_BACK_EMPTY);
                }
                // READ_DIRECTLY: the counter is this controller's own and the sentence claims nothing
                // about a cell or a body.
                return ActOutcome.running("waiting to start dig (attempt " + startAttempts + ")",
                        ActOutcome.READ_DIRECTLY);
            case DIGGING:
            default:
                boolean progressed = act.pumpDig(x, y, z, face);
                pumps++;
                // The GONE test comes before the stall test, and the order is load-bearing. A pump
                // reports whether damage was applied, so the tick that finishes a block can report
                // false -- there is nothing left to damage. Checking the stall first announced
                // "dig stalled" for the very block that had just broken, and with something flowing
                // into the space the emptiness test that used to follow could not correct it either.
                if (targetGone(act)) {
                    return complete(act);
                }
                if (!progressed) {
                    // READ_CAME_BACK_EMPTY, and it is the honest one for the same reason the start
                    // refusal is: a false from `pumpDig` is "no damage was applied this tick", which
                    // is true both of a block that stopped being breakable and of a client that could
                    // not ask. The sentence reports the position and the coordinates rather than
                    // inventing a cause, and the grade says the same thing.
                    return ActOutcome.failed("dig stalled at (" + x + "," + y + "," + z + ")",
                            ActOutcome.READ_CAME_BACK_EMPTY);
                }
                // Same shape as the RESOLVING progress line: the name rides in the sentence, so the
                // grade follows the name's. A dig whose target could never be named says nothing
                // about what is being broken, and it should not claim the standing of one that could.
                return ActOutcome.running("digging " + (diggingBlock == null ? "" : diggingBlock + " ")
                        + "(" + x + "," + y + "," + z + "), " + pumps + " ticks",
                        diggingBlock == null
                                ? ActOutcome.READ_CAME_BACK_EMPTY
                                : ActOutcome.DERIVED_FROM_READS);
        }
    }

    /**
     * Whether the block this dig started on is no longer at the target.
     *
     * <p>The completion test, and it asks about the TARGET rather than about the space. Air, a
     * different block, or an unreadable position all mean our block is gone; water or gravel filling
     * the space is a DIFFERENT block, which is precisely the case an emptiness test got wrong.
     *
     * <p>Falls back to the emptiness test only when the start sample could not be read
     * ({@code diggingBlock == null}). That keeps a broken {@code blockAt} from making every dig
     * complete instantly: with no baseline to compare, the old question is the only one available,
     * and it is wrong in the safe direction (it under-reports completion rather than over-reporting
     * it) -- which is now {@link #INFERRED_WHY} rather than a line of prose.
     *
     * <p><b>The name it read is kept rather than discarded,</b> and that is the entire reason a
     * grade here is affordable. The completion tick already reads the world twice: once here to
     * answer this question, and once in {@link #brokenMessage} to build the ", now X" clause. A
     * grade computed from a third read would be ADR-0005's pure-cost category arriving under a
     * new name, so the derivation runs on the value this method had to read anyway.
     */
    private boolean targetGone(ActActuator act) {
        readSinceGraded = true;
        if (diggingBlock == null) {
            completionRead = null;
            return !act.blockPresent(x, y, z);
        }
        completionRead = act.blockAt(x, y, z);
        return !diggingBlock.equals(completionRead);
    }

    /**
     * The grade {@link #targetGone} earned, as a value, and computed from the read it already made.
     *
     * <p>Three cases and no default, which is the point: the cheap mapping is "a completion is
     * observed", and it is wrong in two of the three.
     *
     * <ul>
     *   <li><b>{@link Belief#OBSERVED}</b> -- the baseline was a real name and a different real
     *       name came back. Both sides of the comparison are this-tick reads, which is all
     *       OBSERVED claims: "the block that was there is not there now". It does not claim this
     *       client broke it, and nothing downstream needs that claim to exist.
     *   <li><b>{@link Belief#INFERRED}</b> -- the baseline could not be read, so the completion
     *       rests on the emptiness fallback, under {@link #INFERRED_WHY}.
     *   <li><b>{@link Belief#UNKNOWN}</b> -- the target reads no name this tick, under
     *       {@link #UNKNOWN_WHY}.
     * </ul>
     */
    private Graded<Boolean> completionVerdict() {
        if (diggingBlock == null) {
            return Graded.inferred(Boolean.TRUE, INFERRED_WHY);
        }
        return completionRead == null
                ? Graded.unknown(Boolean.TRUE, UNKNOWN_WHY)
                : new Graded<>(Boolean.TRUE, Belief.OBSERVED, null);
    }

    /**
     * The COMPLETE outcome: the prose that was already here, plus the grade the reads behind it
     * actually earned.
     *
     * <p>Behaviour is unchanged -- the dig ends COMPLETE in all three cases exactly as it did
     * before. What is new is that a caller can now tell a dig that watched a named block be
     * replaced by a named block from one that concluded the space was empty, and the grade is what
     * a consumer branches on instead of the sentence.
     */
    private ActOutcome complete(ActActuator act) {
        Graded<Boolean> verdict = completionVerdict();
        Belief grade = withoutUpgrade(completionGrade, verdict.belief(), readSinceGraded);
        completionGrade = grade;
        readSinceGraded = false;
        return ActOutcome.done(brokenMessage(act, verdict.why()), grade);
    }

    /**
     * The COMPLETE message, naming what replaced the block when something did, and saying what the
     * completion was worth when it was worth less than an observation.
     *
     * <p>The sentence before the derivation is byte-identical to what it was before the belief
     * layer -- a reader who knows the old text still finds it, and the derivation is a separate
     * clause after it rather than a rewording. Nothing is appended to an OBSERVED completion: a
     * hedge on a claim that earned none teaches every later reader to skip the hedges.
     */
    private String brokenMessage(ActActuator act, String why) {
        String now = act.blockAt(x, y, z);
        String what = diggingBlock == null ? "block" : diggingBlock;
        // The replacement is named because it changes what the caller should do next: a hole it can
        // walk into is not the same as one that just filled with lava, and "broken" alone reads as
        // the former. Silent when the space is empty, which is the ordinary case.
        String filled = now == null ? "" : ", now " + now;
        return what + " (" + x + "," + y + "," + z + ") broken after " + pumps + " ticks" + filled
                + (why == null ? "" : " -- " + why);
    }

    /**
     * Per-tick monotonicity, as a rule that can be tested rather than as a sentence.
     *
     * <p>A grade may go down, never up -- unless a world read happened since the last grade was
     * reported. An upgrade with nothing read behind it is a code defect and not an optimisation:
     * it means a stronger claim about the world was manufactured out of a cached value, which is
     * the failure {@link Belief#OBSERVED}'s definition exists to prevent.
     *
     * <p>Package-private rather than private because the rule is worth testing at its own
     * boundary. In production it can only fire if somebody later memoises the verdict so that a
     * tick can re-report it without reading -- which is exactly the change this refuses.
     *
     * @param alreadyReported the grade this dig has already recorded, or null if it has recorded none
     * @param candidate       the grade about to be recorded
     * @param readSince       whether a world read has happened since {@code alreadyReported}
     */
    static Belief withoutUpgrade(Belief alreadyReported, Belief candidate, boolean readSince) {
        if (alreadyReported != null && !readSince
                && strength(candidate) < strength(alreadyReported)) {
            throw new IllegalStateException("a dig completion grade went from " + alreadyReported
                    + " to " + candidate + " with no world read in between: grade may go down, never "
                    + "up, and an upgrade with nothing read behind it is a defect");
        }
        return candidate;
    }

    /**
     * How strong a claim a grade is; lower is stronger.
     *
     * <p>Written out rather than {@code ordinal()}, so reordering the enum constant cannot silently
     * reverse the rule this method exists to enforce.
     */
    private static int strength(Belief belief) {
        return switch (belief) {
            case OBSERVED -> 0;
            case INFERRED -> 1;
            case UNKNOWN -> 2;
        };
    }

    private boolean outOfReach(ActActuator act) {
        double[] eye = act.eyePos();
        if (eye == null) {
            return true;
        }
        double dx = (x + 0.5) - eye[0];
        double dy = (y + 0.5) - eye[1];
        double dz = (z + 0.5) - eye[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist > act.reachDistance();
    }
}
