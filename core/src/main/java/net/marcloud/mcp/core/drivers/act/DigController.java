package net.marcloud.mcp.core.drivers.act;

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
 * </ul>
 */
public final class DigController {

    /** Default start-retry budget when the caller does not specify one. */
    public static final int DEFAULT_START_ATTEMPTS = 6;

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
            return ActOutcome.cancelled("dig cancelled");
        }
        if (!act.inWorld()) {
            return ActOutcome.failed("not in world");
        }
        if (state == State.DIGGING) {
            // Once digging, the question is whether OUR block is gone -- not whether the space is
            // empty. Something flowing or falling in leaves the space occupied while the target is
            // broken, which an emptiness test reads as "still digging".
            if (targetGone(act)) {
                return ActOutcome.done(brokenMessage(act));
            }
        } else if (!act.blockPresent(x, y, z)) {
            // Not started yet and nothing there: there was never anything to dig. Emptiness IS the
            // right question here -- the caller named a position expecting a block at it.
            return ActOutcome.failed("no block to dig at (" + x + "," + y + "," + z + ")");
        }
        if (outOfReach(act)) {
            return ActOutcome.failed("block (" + x + "," + y + "," + z + ") is out of reach");
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
                    return ActOutcome.running("started digging "
                            + (diggingBlock == null ? "" : diggingBlock + " ")
                            + "(" + x + "," + y + "," + z + ")");
                }
                if (startAttempts >= maxStartAttempts) {
                    return ActOutcome.failed("could not start digging after " + startAttempts + " attempts");
                }
                return ActOutcome.running("waiting to start dig (attempt " + startAttempts + ")");
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
                    return ActOutcome.done(brokenMessage(act));
                }
                if (!progressed) {
                    return ActOutcome.failed("dig stalled at (" + x + "," + y + "," + z + ")");
                }
                return ActOutcome.running("digging " + (diggingBlock == null ? "" : diggingBlock + " ")
                        + "(" + x + "," + y + "," + z + "), " + pumps + " ticks");
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
     * it).
     */
    private boolean targetGone(ActActuator act) {
        if (diggingBlock == null) {
            return !act.blockPresent(x, y, z);
        }
        return !diggingBlock.equals(act.blockAt(x, y, z));
    }

    /** The COMPLETE message, naming what replaced the block when something did. */
    private String brokenMessage(ActActuator act) {
        String now = act.blockAt(x, y, z);
        String what = diggingBlock == null ? "block" : diggingBlock;
        // The replacement is named because it changes what the caller should do next: a hole it can
        // walk into is not the same as one that just filled with lava, and "broken" alone reads as
        // the former. Silent when the space is empty, which is the ordinary case.
        String filled = now == null ? "" : ", now " + now;
        return what + " (" + x + "," + y + "," + z + ") broken after " + pumps + " ticks" + filled;
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
