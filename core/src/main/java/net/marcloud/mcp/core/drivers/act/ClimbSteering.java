package net.marcloud.mcp.core.drivers.act;

import java.util.Locale;

/**
 * Carries the player one block up or down a ladder or vine, using vanilla's own climb mechanic.
 *
 * <p><b>Separate from {@link NavController} because its arrival is a different question.</b> A walk
 * arrives when the feet are in the destination's block AND {@code act.onGround()} says something
 * underneath is holding them up. On a ladder that second half is never true -- vanilla's ladder
 * handling ({@code EntityLivingBase.moveEntityWithHeading:1637-1662}) clamps the body's motion and
 * zeroes its fall distance without ever setting {@code onGround} -- so a controller that reused
 * the grounding half could never complete a climb, no matter how correctly it steered. Folding it
 * in as a mode would have meant one class whose "arrived" meant two different things depending on
 * a field, and the arrival test is precisely the part that is hard to reason about.
 *
 * <p><b>What the player does, and where it is in vanilla.</b> Ascending: hold the forward key while
 * pressed against the ladder. That is the whole mechanic -- {@code isCollidedHorizontally &&
 * isOnLadder()} sets {@code motionY = 0.2} per tick
 * ({@code EntityLivingBase:1659-1662}), and the same block clamps horizontal drift to 0.15
 * ({@code 1639-1641}) so the body cannot slide off the column while climbing it. Descending: release
 * forward. The same clamp holds the descent to 0.15 per tick ({@code 1644-1647}) and zeroes
 * {@code fallDistance}, so a ladder descent costs no fall damage -- which is why descending is this
 * controller's job and not the route executor's fall guard's.
 *
 * <p><b>No jump axis, ever.</b> Vanilla's jump is a ground action
 * ({@code EntityLivingBase.onLivingUpdate:2018-2022} takes it only when {@code onGround}), and a
 * climb never sets that. Publishing a jump here would be publishing an input no player can produce
 * on a ladder.
 *
 * <p><b>It does not turn the camera</b>, for the same reason {@link NavController} does not: LOOK
 * is an independently owned slot, and a controller that rotated would fight it. The bearing is
 * therefore computed against whatever yaw the player currently has, and the axis is re-snapped from
 * the live position every tick exactly as {@code NavController} does -- so if the caller points the
 * camera elsewhere mid-climb, this steers for the ladder relative to where the player is looking,
 * which is the same contract the walker offers.
 */
public final class ClimbSteering implements LocomotionController {

    /**
     * How close the feet have to get to the destination's centre, in blocks.
     *
     * <p>Deliberately the same value {@code NavController} uses for a point arrival rather than a
     * number tuned for ladders. The geometry is identical -- a cell is a cell, and a ladder column
     * is exactly as wide as a corridor -- so a second constant would be a second opinion about the
     * same measurement, and the first live run of this code has no measurement to justify changing
     * either one.
     */
    private static final double ARRIVE_EPSILON = 0.6D;

    /**
     * How many ticks one block of climbing may take before it is called a jam.
     *
     * <p>Derived, not tuned. {@code motionY = 0.2} per tick on the way up means 5 ticks a block
     * ({@code EntityLivingBase:1659-1662}); the descent clamp of 0.15 makes it about 7
     * ({@code 1644-1647}). Sixty leaves an order of magnitude over the slowest of those for a
     * server round trip and a camera slew, and is deliberately generous rather than tight, because
     * a deadline that fires on a healthy climb reports "stuck" for what is really lag.
     */
    public static final int CLIMB_TICK_BUDGET = 60;

    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final boolean ascending;
    private final int timeoutTicks;

    private boolean done;
    private boolean cancelRequested;
    private int ticks;
    private double[] last;
    private int stillTicks;

    private float forward;
    private float strafe;

    /**
     * What this climb spent to be the climb it is, as of the last tick.
     *
     * <p>{@link MoveTactic.GivenUp#NOTHING} until the controller gives something up, and a climb
     * has two ways to do that which are NOT the same fact. The tick budget running out is the
     * clock: the body was still on the ladder pressing forward, so it is
     * {@link MoveTactic.GivenUp#OUT_OF_TICKS}. The stall test firing is the world: eight ticks of
     * no rise against a budget of tens, on a body vanilla still reports as climbable, so it is
     * {@link MoveTactic.GivenUp#THE_UNMOVABLE_MEDIUM}.
     *
     * <p>They used to be one value, {@link MoveTactic.GivenUp#LIMITS_REACHED}, on the argument
     * that "both mean every option this controller has was spent". That argument was false in the
     * way this enum exists to prevent: a climb has no lane and no recovery, so it never had an
     * option to spend, and it needed a caller to be able to tell "raise the budget" from "this
     * ladder ends here" -- which are opposite responses to a failed climb. A climb that arrived
     * still reads {@code NOTHING}, because a column that carried the body cost nothing.
     */
    private MoveTactic.GivenUp givenUp = MoveTactic.GivenUp.NOTHING;

    /**
     * How many consecutive ticks without vertical progress before this calls it a jam.
     *
     * <p>Vertical, and that is the one measurement that differs from the walker's. A climber
     * pressed against a ladder has {@code isCollidedHorizontally} true on every single tick -- it
     * is leaning on a wall on purpose -- so the walker's rule (no progress AND collided) would read
     * a perfectly healthy climb as stuck on its first bad frame and kill the route.
     */
    private static final int STUCK_TICKS = 8;

    /**
     * Per-tick rise below which a tick counts as no progress, in blocks.
     *
     * <p>A third of the slowest vanilla climb rate (0.15 on the way down), so ordinary damping and a
     * dropped frame cannot accumulate into a false jam while a real stall -- a body hanging on a
     * ladder that has stopped responding -- still trips inside {@link #STUCK_TICKS}.
     */
    private static final double ROSE_EPSILON = 0.05D;

    /**
     * @param targetX      the destination column's centre X
     * @param targetY      the destination cell's Y -- the block the FEET end in
     * @param targetZ      the destination column's centre Z
     * @param ascending    true to rise, false to descend; the caller reads this off the move's own
     *                     two stances rather than deciding it here
     * @param timeoutTicks ticks before giving up
     */
    public ClimbSteering(double targetX, double targetY, double targetZ, boolean ascending,
                         int timeoutTicks) {
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.ascending = ascending;
        this.timeoutTicks = timeoutTicks > 0 ? timeoutTicks : CLIMB_TICK_BUDGET;
    }

    @Override
    public float forward() {
        return forward;
    }

    @Override
    public float strafe() {
        return strafe;
    }

    /**
     * Always false.
     *
     * <p>Not an omission and not a simplification: vanilla only jumps from the ground
     * ({@code EntityLivingBase.onLivingUpdate:2018-2022}), and a climb is by definition not on the
     * ground. A true here would be an input no player can produce while doing the thing this
     * controller exists to do.
     */
    @Override
    public boolean jump() {
        return false;
    }

    /**
     * Always false.
     *
     * <p>Not an omission, and for the same reason {@link #jump()} is: {@code Entity.moveEntity:626}
     * requires {@code onGround}, and a body on a ladder is held by
     * {@code EntityLivingBase.moveEntityWithHeading:1637-1662} clamping its Y rather than by ground
     * under its feet -- which never sets the flag the guard tests. {@code Stance.hasFloor} already
     * refuses a ladder as a floor, so no climb was ever a walk along a brink.
     */
    @Override
    public boolean creeping() {
        return false;
    }

    /**
     * The climb's own axes as a decision, rebuilt on read.
     *
     * <p>Built here rather than mirrored into a field beside {@code forward}/{@code strafe},
     * because those two ARE the published axes and a copy of them would be a second source of
     * truth about the same fact -- the shape of bug this package has deleted six times. Reading
     * them is what makes {@link MoveTactic}'s invariant structural here rather than a promise:
     * {@code tactic.forward()} is {@link #forward()} because they are the same field.
     *
     * <p>Three components are fixed, and each is a claim rather than a default:
     *
     * <ul>
     *   <li>{@code jump} is null -- "not a question this controller asks". A ladder is not the
     *       ground and vanilla only jumps from the ground ({@code EntityLivingBase:2018-2022}),
     *       which is the argument {@link #jump()} already makes. A {@code FALSE} here could not be
     *       told from a decision that was made and lost.
     *   <li>{@code yawChange} is null -- "chose not to rotate". The camera belongs to the LOOK
     *       slot, and a climb that turned it would reach across an independently owned channel.
     *   <li>{@code lane} is null -- the direct line. This controller has no recovery: a ladder
     *       either holds the body up or it does not, so there is no obstruction to step around.
     * </ul>
     */
    @Override
    public MoveTactic tactic() {
        return new MoveTactic(forward, strafe, null, null, null, givenUp);
    }

    @Override
    public int ticks() {
        return ticks;
    }

    @Override
    public void requestCancel() {
        cancelRequested = true;
    }

    @Override
    public ActOutcome tick(ActActuator act) {
        if (done) {
            return ActOutcome.done("already finished", ActOutcome.READ_DIRECTLY);
        }
        if (cancelRequested) {
            return finish(ActOutcome.cancelled("climb cancelled after " + ticks + " ticks",
                    ActOutcome.READ_DIRECTLY));
        }
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world", ActOutcome.READ_DIRECTLY));
        }
        double[] pos = act.position();
        if (pos == null) {
            // READ_CAME_BACK_EMPTY, same argument as NavController's identical branch: a null
            // position is a statement about this seam, not an observation of a player with no
            // position.
            return finish(ActOutcome.failed("position unavailable",
                    ActOutcome.READ_CAME_BACK_EMPTY));
        }

        // The player must actually BE on the ladder. This is checked before arrival for the same
        // reason it is checked at all: a ladder holds a player up without setting onGround, so this
        // is the only question on the seam that can distinguish "climbed" from "standing near a
        // ladder". Without it a player who walked past the column and stopped in the cell above
        // would be credited with a climb.
        if (!act.onClimbable()) {
            // READ_DIRECTLY: `onClimbable()` is vanilla's own `isOnLadder` predicate -- it floors
            // the bounding box, reads one cell and tests it against Blocks.ladder/vine (see
            // ActActuator's javadoc for why this seam asks it rather than deriving it from
            // solidity). The sentence reports that boolean and cites the line it comes from. Nothing
            // is derived and nothing is stale.
            return finish(ActOutcome.failed(ascending
                    ? "the player is not on a ladder or vine, so there is nothing to climb: "
                            + "vanilla raises the body only while isOnLadder() is true "
                            + "(EntityLivingBase:1659-1662), and it is not"
                    : "the player is not on a ladder or vine, so there is nothing to climb down "
                            + "from", ActOutcome.READ_DIRECTLY));
        }

        double dx = targetX - pos[0];
        double dz = targetZ - pos[2];
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dy = pos[1] - targetY;

        // Arrival is the feet being in the DESTINATION'S OWN CELL, not "within a block of it".
        // A distance test is wrong here in a way it is not for a walk: a climb's two stances are
        // exactly one block apart, so |dy| <= 1.0 declared the player ARRIVED the instant the move
        // began, the executor then verified against a player still standing on the bottom rung, and
        // the route failed having climbed nothing. The cell test is the one
        // NavController.standingInDestination already asks, and it is immune to that off-by-one
        // because a body at the bottom of the cell below is simply not in this one.
        // The XZ half of that test too. A stance's centre is 0.5 from the face of its own cell, so
        // a 0.6 epsilon is satisfiable by a body still in the PREVIOUS one -- and a climb is where
        // that is least survivable, because the executor's arrival check asks the world and would
        // refuse the destination this controller had already reported. The Y half alone is a
        // question about a plane; a plan's stance is a volume.
        boolean feetInDestination = pos[1] >= targetY && pos[1] < targetY + 1.0D
                && (int) Math.floor(pos[0]) == (int) Math.floor(targetX)
                && (int) Math.floor(pos[2]) == (int) Math.floor(targetZ);
        if (feetInDestination && dist <= ARRIVE_EPSILON) {
            stop();
            // DERIVED_FROM_READS: the height and the two distances are arithmetic over the position
            // read this tick, compared against a destination the PLAN named -- so this is the
            // NavController arrival grade applied to the same proposition, for the same reason.
            return finish(ActOutcome.done(String.format(Locale.ROOT,
                    "climbed %s to y=%.2f after %d ticks", ascending ? "up" : "down", pos[1], ticks),
                    ActOutcome.DERIVED_FROM_READS));
        }

        ticks++;
        if (ticks > timeoutTicks) {
            // The clock, and only the clock. A climb has no lane and no recovery -- there is
            // nothing to sidestep to and nothing to escalate to -- so on the tick the budget ends
            // it has spent NOTHING, and LIMITS_REACHED's "every option, spent" would be a claim
            // about options that did not exist. A caller reading it is told the walk fought and
            // lost; the truth is that it was still on the ladder pressing the only key it has and
            // ran out of time. The response is a bigger budget.
            //
            // DERIVED_FROM_READS: both numbers are arithmetic over this tick's position read.
            givenUp = MoveTactic.GivenUp.OUT_OF_TICKS;
            stop();
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "gave up climbing after %d ticks, still %.2f blocks up and %.2f blocks out",
                    ticks - 1, dy, dist), ActOutcome.DERIVED_FROM_READS));
        }

        // Progress is VERTICAL. A climber leans on a wall every tick by design, so the horizontal
        // measurement every other controller uses reads a healthy climb as a standstill.
        if (last != null) {
            double moved = Math.abs(pos[1] - last[1]);
            // Rising must count progress and descending must too, so this is a magnitude: a body
            // moving the WRONG way is not progress, and neither is one that has stopped.
            stillTicks = moved < ROSE_EPSILON ? stillTicks + 1 : 0;
        }
        last = pos;

        if (stillTicks >= STUCK_TICKS) {
            // The world, not the clock. STUCK_TICKS is 8 against a budget of tens, so the budget
            // is demonstrably not what ended this climb, and nothing was spent because a climb has
            // no option to spend -- it was already pressing forward, which is the whole of what
            // this controller can do. What stopped it is the ladder: the body is on a climbable
            // and not rising, which is a column that ends here. A caller raising the budget would
            // get the same eight ticks again, so this is not the clock's verdict either.
            //
            // DERIVED_FROM_READS: "the climb stalled" is a conclusion from a streak of ticks in
            // which the vertical delta stayed under a threshold, compared against a plan height.
            // The `onClimbable()` fact it also rests on was read this tick, so nothing is stale.
            givenUp = MoveTactic.GivenUp.THE_UNMOVABLE_MEDIUM;
            stop();
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "the climb stalled for %d ticks at y=%.2f: the player is on the ladder and not "
                            + "moving, which is a ladder that ends here rather than a route",
                    stillTicks, pos[1]), ActOutcome.DERIVED_FROM_READS));
        }

        if (ascending) {
            // Hold forward. NOT a bearing, and the difference is the whole reason this is one line.
            // A climb's destination is directly OVERHEAD, so the world bearing is (0,0) and
            // NavController.keysForBearing returns the "no keys down" pair for a degenerate bearing
            // -- a climb steered by bearing presses nothing and never leaves the rung. What vanilla
            // actually does is raise the body when the player is on a ladder and pressed into it
            // (EntityLivingBase:1659-1662), so the action is the forward key and nothing else.
            //
            // The one thing this cannot do is turn the player to face the ladder, because LOOK is
            // an independently owned slot (the same contract NavController offers). A player facing
            // away will walk out of the column, onClimbable() will go false, and the tick above
            // fails honestly naming the ladder rather than reporting a stall.
            forward = 1f;
            strafe = 0f;
            // DERIVED_FROM_READS for the same reason as the arrival: the distance to rise is
            // subtraction over this tick's position read against the plan's height.
            return ActOutcome.running(String.format(Locale.ROOT,
                    "climbing, %.2f blocks to rise (tick %d/%d)", dy, ticks, timeoutTicks),
                    ActOutcome.DERIVED_FROM_READS);
        }
        // Descending is a release: the ladder's own clamp walks the body down at 0.15 a tick, and
        // pressing forward would climb instead. Publishing an axis here would fight vanilla.
        stop();
        return ActOutcome.running(String.format(Locale.ROOT,
                "descending, %.2f blocks to fall (tick %d/%d)", dy, ticks, timeoutTicks),
                ActOutcome.DERIVED_FROM_READS);
    }

    private void stop() {
        forward = 0f;
        strafe = 0f;
    }

    private ActOutcome finish(ActOutcome out) {
        done = true;
        stop();
        return out;
    }
}
