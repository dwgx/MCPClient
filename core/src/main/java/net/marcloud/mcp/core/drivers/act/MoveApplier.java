package net.marcloud.mcp.core.drivers.act;

/**
 * The {@link ActSlot#MOVE} applier: owns the slot's lifecycle and reports what the intent actually
 * achieved. The locomotion itself is applied by {@link ActMovementInput}, which reads the
 * {@link MoveIntentView} on the game thread every tick.
 *
 * <p>A {@link MoveIntent#durationTicks()} of {@code <= 0} means "hold until cancelled or replaced":
 * the slot stays ACTIVE indefinitely. A positive duration completes the slot once that many ticks
 * have been applied, after which {@link ActMovementInput} sees {@code moveActive()==false} and
 * reverts to vanilla.
 *
 * <p><b>Why it reads the world back.</b> This used to report {@code "moving (tick N/M)"} -- a
 * statement about its own bookkeeping, identical for a player crossing open ground and a player
 * pressed against a wall. Measured on a live client, those are very different: an open-loop intent
 * holds a straight line on flat ground (8.408 blocks travelled, 0.04 degrees off the facing yaw)
 * and travels nothing at all into a wall, and {@code act_status} called both of them "moving".
 * Reading {@link ActActuator#position()} makes the slot answer the question a caller actually has,
 * which is whether the intent accomplished anything.
 *
 * <p>Displacement rather than velocity: velocity separates the two states too (measured, the Z
 * component reads 0.09 walking against 0.0 jammed) but it answers "how fast right now" when the
 * question is "did this get anywhere", and it is not on this seam. Displacement since the intent
 * became ACTIVE is also the term a stuck test needs.
 *
 * <p>The actuator is optional. A null one degrades to the old tick-counting message rather than
 * failing, because a status line is not worth breaking locomotion over, and the applier is
 * constructed in {@code McpCore} where the actuator exists anyway.
 */
public final class MoveApplier implements ActApplier {

    /** Ticks of zero displacement while ACTIVE before the slot calls itself stuck. */
    private static final int STUCK_TICKS = 3;

    /**
     * Below this, a tick counts as no movement, in blocks.
     *
     * <p>Not zero: a player settling onto ground or brushing a wall drifts by tiny amounts, and
     * calling that progress would make the stuck test never fire. Walking covers about 0.2 blocks
     * per tick (measured: 4.2 blocks/second), so this is two orders of magnitude below a real step.
     */
    private static final double MOVED_EPSILON = 0.002;

    private final ActActuator actuator;

    private double[] origin;
    private double[] last;
    private int stillTicks;
    private ActIntent boundTo;

    /** Lifecycle only; the status line degrades to tick counting. */
    public MoveApplier() {
        this(null);
    }

    public MoveApplier(ActActuator actuator) {
        this.actuator = actuator;
    }

    @Override
    public SlotRecord apply(SlotRecord current) {
        if (!(current.intent() instanceof MoveIntent mi)) {
            reset();
            return current.withPhase(ActPhase.FAILED, "MOVE slot given a non-move intent");
        }
        // A fresh submit restarts the measurement: displacement is per-intent, so a replaced intent
        // must not inherit the previous one's origin. Identity is the same test LookApplier uses.
        if (boundTo != current.intent()) {
            boundTo = current.intent();
            origin = read();
            last = origin;
            stillTicks = 0;
        }
        if (current.cancelRequested()) {
            String moved = travelled();
            reset();
            return current.withPhase(ActPhase.CANCELLED,
                    "movement cancelled after " + current.ticksActive() + " ticks" + moved);
        }

        double[] now = read();
        double step = distance(last, now);
        last = now;
        if (step < MOVED_EPSILON) {
            stillTicks++;
        } else {
            stillTicks = 0;
        }

        long tick = current.lastAppliedTick();
        int activeAfter = current.ticksActive() + 1;
        int duration = mi.durationTicks();
        if (duration > 0 && activeAfter >= duration) {
            String moved = travelled();
            SlotRecord done = current.markActive(tick, "moved for " + activeAfter + " ticks" + moved)
                    .withPhase(ActPhase.COMPLETE,
                            "movement complete after " + activeAfter + " ticks" + moved);
            reset();
            return done;
        }

        // A jam is the one locomotion failure invisible any other way, and collidedHorizontally is
        // already the boolean for it. Reported rather than failed: the caller decides whether to
        // turn, jump or give up, and an intent that fails itself would take that choice away.
        boolean stuck = stillTicks >= STUCK_TICKS
                && actuator != null && actuator.collidedHorizontally();
        String state = stuck
                ? "stuck against a wall for " + stillTicks + " ticks"
                : "moving (tick " + activeAfter + (duration > 0 ? "/" + duration : "") + ")";
        return current.markActive(tick, state + travelled());
    }

    private double[] read() {
        return actuator == null ? null : actuator.position();
    }

    /** {@code ", moved N.NN blocks"}, or empty when there is nothing trustworthy to say. */
    private String travelled() {
        if (origin == null || last == null) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, ", moved %.2f blocks", distance(origin, last));
    }

    private static double distance(double[] a, double[] b) {
        if (a == null || b == null) {
            return 0.0;
        }
        // Horizontal only: falling is not progress toward a destination, and counting it would make
        // a player dropping down a shaft look like it was walking.
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void reset() {
        boundTo = null;
        origin = null;
        last = null;
        stillTicks = 0;
    }
}
