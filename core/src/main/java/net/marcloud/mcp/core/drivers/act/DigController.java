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
        if (!act.blockPresent(x, y, z)) {
            // Nothing there. If we had already started, the block broke → success;
            // otherwise there was never anything to dig → honest fail.
            if (state == State.DIGGING) {
                return ActOutcome.done("block (" + x + "," + y + "," + z + ") broken after " + pumps + " ticks");
            }
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
                    return ActOutcome.running("started digging (" + x + "," + y + "," + z + ")");
                }
                if (startAttempts >= maxStartAttempts) {
                    return ActOutcome.failed("could not start digging after " + startAttempts + " attempts");
                }
                return ActOutcome.running("waiting to start dig (attempt " + startAttempts + ")");
            case DIGGING:
            default:
                boolean progressed = act.pumpDig(x, y, z, face);
                pumps++;
                if (!progressed) {
                    return ActOutcome.failed("dig stalled at (" + x + "," + y + "," + z + ")");
                }
                if (!act.blockPresent(x, y, z)) {
                    return ActOutcome.done("block (" + x + "," + y + "," + z + ") broken after " + pumps + " ticks");
                }
                return ActOutcome.running("digging (" + x + "," + y + "," + z + "), " + pumps + " ticks");
        }
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
