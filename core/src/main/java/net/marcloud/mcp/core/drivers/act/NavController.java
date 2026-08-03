package net.marcloud.mcp.core.drivers.act;

import java.util.Locale;

/**
 * Walks the player toward a coordinate, correcting every tick. The first behaviour that makes
 * "one command" true for locomotion: submitted once, it runs to arrival across many ticks with no
 * round trip per step.
 *
 * <p>Shaped after {@link DigController}, which is this package's proven durable behaviour: a pure
 * state machine over an {@link ActActuator}, ticked by an applier, marshalling no threads of its
 * own, and terminating honestly. {@code MoveApplier} could only count ticks because nothing read the
 * world back; now that {@link ActActuator#position()} exists, the same shape works for walking.
 *
 * <p><b>It does not pathfind, deliberately.</b> Straight line with heading correction, and honest
 * failure when that is not enough. Two things justify the split. First, a measurement: an open-loop
 * MOVE intent holds a straight line within 0.04 degrees over 8.4 blocks on flat ground, so the line
 * itself was never the missing part -- knowing you arrived, and knowing you are stuck, were. Second,
 * a planner must own its own neighbour generator to be worth having: vanilla's
 * {@code WalkNodeProcessor} offers four cardinal moves and cannot express mining through or bridging
 * over, which is why Baritone and mineflayer-pathfinder both wrote their own. A planner therefore
 * becomes a layer that feeds waypoints to this controller, the way Baritone separates search from
 * execution -- so building the follower first is the half either engine needs.
 *
 * <p><b>It does not turn the camera.</b> Axes are computed relative to whatever yaw currently is, so
 * LOOK stays independently owned and a caller that wants the bot to face its destination submits a
 * look intent alongside. The slots are orthogonal by design and this respects that.
 */
public final class NavController {

    /**
     * Horizontal distance at which the target counts as reached, in blocks.
     *
     * <p>Larger than one tick of travel on purpose. Walking covers about 0.2 blocks per tick
     * (measured live: 4.2 blocks/second), so a window narrower than that could be stepped over
     * between samples and the controller would circle its target forever.
     */
    private static final double ARRIVE_EPSILON = 0.6;

    /** Per-tick displacement below which a tick counts as no progress, in blocks. */
    private static final double MOVED_EPSILON = 0.01;

    /** Consecutive no-progress ticks while in contact before calling it stuck. */
    private static final int STUCK_TICKS = 8;

    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final int timeoutTicks;

    private boolean done;
    private boolean cancelRequested;
    private int ticks;
    private int stillTicks;
    private double[] last;

    /** Axes this controller wants applied this tick; read by the applier after {@link #tick}. */
    private float forward;
    private float strafe;

    public NavController(double targetX, double targetY, double targetZ, int timeoutTicks) {
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.timeoutTicks = timeoutTicks > 0 ? timeoutTicks : 400;
    }

    /** Forward axis for this tick, vanilla sign. Meaningless once terminal. */
    public float forward() {
        return forward;
    }

    /** Strafe axis for this tick, vanilla sign. Meaningless once terminal. */
    public float strafe() {
        return strafe;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    /** Ticks spent walking, for status and tests. */
    public int ticks() {
        return ticks;
    }

    /**
     * One step. Returns a non-terminal outcome while walking and a terminal one on
     * arrival, jam, timeout or cancel.
     */
    public ActOutcome tick(ActActuator act) {
        if (done) {
            return ActOutcome.done("already finished");
        }
        if (cancelRequested) {
            return finish(ActOutcome.cancelled("navigation cancelled after " + ticks + " ticks"));
        }
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world"));
        }
        double[] pos = act.position();
        if (pos == null) {
            return finish(ActOutcome.failed("position unavailable"));
        }

        double dx = targetX - pos[0];
        double dz = targetZ - pos[2];
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Arrival is tested before anything else: a target underfoot has no meaningful direction,
        // and steering toward it would produce noise rather than motion.
        if (dist <= ARRIVE_EPSILON) {
            stop();
            return finish(ActOutcome.done(String.format(Locale.ROOT,
                    "arrived within %.2f blocks after %d ticks", dist, ticks)));
        }

        ticks++;
        if (ticks > timeoutTicks) {
            stop();
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "gave up after %d ticks, still %.2f blocks out -- the target may be "
                    + "unreachable in a straight line, which is all this controller attempts",
                    ticks - 1, dist)));
        }

        // Progress, measured per tick rather than accumulated.
        //
        // No discontinuity guard here, and that is a considered omission. The first version had one
        // -- an implausible step treated as a teleport rather than travel -- and injecting a break
        // proved it was a no-op: a 300-block step already fails the MOVED_EPSILON test and lands in
        // the same else branch, setting the counter to the same 0. Unlike MoveApplier, which
        // accumulates displacement from an origin and would report a teleport as distance walked,
        // this controller recomputes distance from the live position every tick, so there is no
        // accumulator for a discontinuity to corrupt. A teleport simply moves the player and the
        // next tick steers from wherever they now are, which is correct.
        if (last != null) {
            double sx = pos[0] - last[0];
            double sz = pos[2] - last[2];
            stillTicks = Math.sqrt(sx * sx + sz * sz) < MOVED_EPSILON ? stillTicks + 1 : 0;
        }
        last = pos;

        if (stillTicks >= STUCK_TICKS && act.collidedHorizontally()) {
            stop();
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "stuck against a wall for %d ticks, %.2f blocks short of the target",
                    stillTicks, dist)));
        }

        steer(dx / dist, dz / dist, act.yaw());
        return ActOutcome.running(String.format(Locale.ROOT,
                "walking, %.2f blocks to go (tick %d/%d)", dist, ticks, timeoutTicks));
    }

    /**
     * Set the axes that move the player along world direction {@code (wx, wz)} at the current yaw.
     *
     * <p>This is vanilla's own rotation, inverted. {@code Entity.moveFlying:1242-1243} applies
     *
     * <pre>
     *   motionX += strafe*cos(yaw) - forward*sin(yaw)
     *   motionZ += forward*cos(yaw) + strafe*sin(yaw)
     * </pre>
     *
     * so choosing
     *
     * <pre>
     *   forward = wz*cos(yaw) - wx*sin(yaw)
     *   strafe  = wx*cos(yaw) + wz*sin(yaw)
     * </pre>
     *
     * substitutes back to exactly {@code (wx, wz)} -- the cross terms cancel and
     * {@code cos^2 + sin^2 = 1}. The 0.98 damping vanilla applies at
     * {@code EntityLivingBase:2031-2032} scales both axes equally, so it changes speed and not
     * heading.
     *
     * <p>The axis names are vanilla's and are not independently trustworthy: {@code MoveIntent}
     * documents {@code strafe +1} as LEFT while {@code moveFlying} names nothing. If the convention
     * is mirrored the player walks the wrong way along one axis, which is why the live check asserts
     * that distance CLOSED rather than that the player moved.
     */
    private void steer(double wx, double wz, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        forward = (float) (wz * cos - wx * sin);
        strafe = (float) (wx * cos + wz * sin);
    }

    private void stop() {
        forward = 0f;
        strafe = 0f;
    }

    private ActOutcome finish(ActOutcome out) {
        done = true;
        return out;
    }
}
