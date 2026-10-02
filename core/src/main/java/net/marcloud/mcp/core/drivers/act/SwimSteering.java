package net.marcloud.mcp.core.drivers.act;

import java.util.Locale;

/**
 * Carries the player one block through water, horizontally or upward, on vanilla's swim physics.
 *
 * <p><b>Separate from {@link NavController} for two reasons, both about arrival.</b> The first is
 * the same one that separates {@link ClimbSteering}: a swimmer is not on the ground, so the
 * walker's grounding half of its arrival test can never be true in water. The second is that the
 * thing holding a swimmer up is the water, and {@code act.inWater()} is the only question on the
 * seam that says so.
 *
 * <p><b>What the player does, and where it is in vanilla.</b> Across: hold forward, and vanilla's
 * water branch of {@code moveEntityWithHeading} ({@code 1700-1733}) applies
 * {@code moveFlying(strafe, forward, 0.02F)} and damps by 0.8, which is the whole of swimming
 * sideways. Up: hold forward AND jump -- {@code onLivingUpdate:2010-2013} routes
 * {@code isJumping} into {@code updateAITick} ({@code 1591}) which adds 0.04 to {@code motionY},
 * and only while {@code isInWater()}. Neither is an invented action; both are the keys a player
 * presses, and both are the axes {@link LocomotionController} already publishes.
 *
 * <p><b>Water is a hazard here, and it stays one.</b> This is the point worth being careful about,
 * because the obvious "improvement" after adding a swim capability is to stop warning about water --
 * the route obviously intends to be in it. That would be a silent regression in the safety layer,
 * and {@link NavHazard.Kind#WATER} would then be true only of a controller nobody routes through.
 * So this controller publishes a hazard of its own, every tick, and what it says is the truth about
 * SWIMMING rather than the truth about WALKING: the player is submerged, the air is finite, and here
 * is how much of it is left. A caller that cancels on {@code WATER} still cancels, which is the
 * correct response to a river, and now it cancels with a number to act on instead of a sentence.
 */
public final class SwimSteering implements LocomotionController {

    /** How close the feet get to the destination's centre before it counts, in blocks. */
    private static final double ARRIVE_EPSILON = 0.6D;

    /**
     * How close the feet get to the destination's own Y before it counts, in blocks.
     *
     * <p>Generous next to the walker's because a swimmer's body is not at a fixed height inside
     * its cell: vanilla applies drag every tick and the player settles wherever the water's own
     * damping puts them. Half a block is the point at which "the player is in the destination's
     * cell" stops being true, and demanding better would be demanding a precision the physics does
     * not offer.
     */
    private static final double ARRIVE_HEIGHT = 0.55D;

    /**
     * Ticks before a swim is called a jam. Generous for the same reason the climb's is: vanilla
     * settles a swimmer at 0.08 blocks per tick across and 0.06 up, so a single block is 13 to 17
     * ticks and a server round trip is not free.
     */
    public static final int SWIM_TICK_BUDGET = 60;
    /**
     * Vanilla's full air supply, in ticks ({@code EntityLivingBase:326} sets 300).
     *
     * <p>Restated here rather than read across from the planner's {@code Move}, because this
     * controller lives in {@code act} and the planner's copy is a BUDGET -- the number a plan is
     * allowed to spend. This one is the number the player actually has, and it is quoted into a
     * message a human reads. Two names for the same vanilla constant is a small duplication; a
     * package cycle between a pure controller and a search is not a trade.
     */
    public static final int AIR_FULL = 300;


    /**
     * Air at or below which the hazard starts saying the player is drowning rather than swimming.
     *
     * <p>Vanilla's own threshold, not a round number chosen to look tidy: {@code air} is decremented
     * every submerged tick ({@code EntityLivingBase:301}) and {@code setAir(0)} plus a 2-damage hit
     * happens at {@code -20} ({@code 303-317}). Warning at the number where damage starts is
     * warning one step too late to be a warning.
     */
    public static final int DROWNING_AIR = -20;

    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final boolean rising;
    private final int timeoutTicks;

    private boolean done;
    private boolean cancelRequested;
    private int ticks;
    private double[] last;
    private int stillTicks;

    private float forward;
    private float strafe;
    private boolean jump;
    private NavHazard hazard;

    /**
     * What this swim spent to be the swim it is, as of the last tick.
     *
     * <p>{@link MoveTactic.GivenUp#NOTHING} until the swim genuinely runs out, and the two ways it
     * runs out are not the same fact. The tick budget is the clock, and it is
     * {@link MoveTactic.GivenUp#OUT_OF_TICKS}. The stall test is the world -- the body is in water
     * and not moving, which is a current or a wall -- and it is
     * {@link MoveTactic.GivenUp#THE_UNMOVABLE_MEDIUM}, the same value a climb's stall reports,
     * because a caller cannot do anything differently about the two.
     *
     * <p>They used to share {@link MoveTactic.GivenUp#LIMITS_REACHED} on the argument that
     * "every option this controller has is spent" covers both. It did not: a swim has no lane and
     * no recovery, so it never had an option to spend, and the sentence sent a caller to reroute a
     * swim that only needed more ticks. A swim that arrived still reads {@code NOTHING}, because a
     * river that carried the body where the plan asked cost nothing but holding forward.
     */
    private MoveTactic.GivenUp givenUp = MoveTactic.GivenUp.NOTHING;

    /**
     * @param rising true to swim up one block, false to swim across; read off the move's own two
     *               stances by the caller, so the controller never has an opinion about direction
     *               the plan did not state
     */
    public SwimSteering(double targetX, double targetY, double targetZ, boolean rising,
                        int timeoutTicks) {
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.rising = rising;
        this.timeoutTicks = timeoutTicks > 0 ? timeoutTicks : SWIM_TICK_BUDGET;
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
     * True only while rising.
     *
     * <p>That is the whole of vanilla's swim-up: {@code onLivingUpdate:2010-2013} consumes
     * {@code isJumping} into {@code updateAITick} and adds to {@code motionY}, and it does so
     * <i>instead of</i> the ground jump in the same if-chain. So the axis is exactly "am I trying to
     * go up", and a horizontal swim asking for it would push a player into the ceiling above them.
     */
    @Override
    public boolean jump() {
        return jump;
    }

    /**
     * Always false.
     *
     * <p>Not an omission. {@code Entity.moveEntity:626} opens its edge guard with
     * {@code onGround && isSneaking()}, and a swimmer is not on the ground --
     * {@code Entity.handleWaterMovement} is what holds a body in water, and it never sets
     * {@code onGround}. There is no floor under a swim to be protected from, so the key could only
     * cost the player a third of their speed for nothing.
     */
    @Override
    public boolean creeping() {
        return false;
    }

    /**
     * The swim's own axes as a decision, rebuilt on read.
     *
     * <p>Built from the fields rather than mirrored into one, for the reason
     * {@link MoveTactic}'s whole contract depends on: these ARE the published axes, so
     * {@code tactic.forward()} is {@link #forward()} by construction rather than by agreement
     * between two copies that can drift.
     *
     * <p>Two components are fixed, and both are claims:
     *
     * <ul>
     *   <li>{@code jump} is {@link #jump()} boxed, not null, because a swim genuinely asks the
     *       question -- "am I trying to go up" is the whole of vanilla's swim-up
     *       ({@code EntityLivingBase:2010-2013} into {@code updateAITick:1591}). A null here would
     *       report that the question was never put, which is false in the one direction that
     *       matters: a horizontal swim that has sunk below the row the plan named IS trying to go
     *       up, and {@code tick} says so.
     *   <li>{@code lane} is null and {@code yawChange} is null -- the direct line, and no camera.
     *       A swim has no recovery (the river either carries the body or it does not) and turning
     *       the camera belongs to the LOOK slot.
     * </ul>
     */
    @Override
    public MoveTactic tactic() {
        return new MoveTactic(forward, strafe, Boolean.valueOf(jump), null, null, givenUp);
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
            return finish(ActOutcome.cancelled("swim cancelled after " + ticks + " ticks", hazard,
                    ActOutcome.READ_DIRECTLY));
        }
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world", hazard, ActOutcome.READ_DIRECTLY));
        }
        double[] pos = act.position();
        if (pos == null) {
            return finish(ActOutcome.failed("position unavailable", hazard,
                    ActOutcome.READ_CAME_BACK_EMPTY));
        }
        if (!act.inWater()) {
            // READ_DIRECTLY: `inWater()` is `Entity.isInWater()`, a boolean field with no
            // intermediary, and the sentence reports it alongside the vanilla lines it comes from.
            return finish(ActOutcome.failed(
                    "the player is not in water, so there is nothing to swim: vanilla only applies "
                            + "swim physics while isInWater() is true (EntityLivingBase:1706, and "
                            + "the branch at 1700-1733), and it is not", hazard,
                    ActOutcome.READ_DIRECTLY));
        }

        double dx = targetX - pos[0];
        double dz = targetZ - pos[2];
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dy = Math.abs(pos[1] - targetY);

        // The feet being in the DESTINATION'S OWN CELL, the same rule the climb uses and for the
        // same reason. A distance band is wrong for a swim because a swimmer is never still: the
        // water branch damps motionY and subtracts 0.02 on every tick regardless of input
        // (:1726-1728), so a band narrow enough to be meaningful is also narrow enough that the
        // player drifts out of it between the controller declaring arrival and the executor asking
        // the world. A cell is a cell -- the feet are in it or they are not, and sinking within it
        // does not un-arrive.
        // The XZ half of that test, and it is the half that was missing. A stance's centre is 0.5
        // from the face of its own cell, so a 0.6 epsilon is satisfiable by a body still in the
        // PREVIOUS one -- and a crossing swim is exactly where that shows: the executor verifies
        // arrival against the world and would refuse the destination this controller reported, so
        // the crossing stopped one bank short and called it done. The Y half alone is a question
        // about a plane; a plan's stance is a volume.
        boolean feetInDestination = pos[1] >= targetY && pos[1] < targetY + 1.0D
                && (int) Math.floor(pos[0]) == (int) Math.floor(targetX)
                && (int) Math.floor(pos[2]) == (int) Math.floor(targetZ);
        if (feetInDestination && dist <= ARRIVE_EPSILON) {
            stop();
            // DERIVED_FROM_READS: the coordinates are this tick's position read and the verdict is a
            // comparison against a destination the PLAN named -- the same proposition NavController
            // grades the same way, for the same reason.
            return finish(ActOutcome.done(String.format(Locale.ROOT,
                    "swam %s to (%.2f,%.2f) after %d ticks", rising ? "up" : "across",
                    pos[0], pos[1], ticks), hazard, ActOutcome.DERIVED_FROM_READS));
        }

        ticks++;
        if (ticks > timeoutTicks) {
            // The clock, and only the clock. A swim has no lane and no recovery, so on the tick the
            // budget ends it has spent NOTHING and LIMITS_REACHED's "every option, spent" would be
            // a claim about options that did not exist. The body was still being steered toward
            // the destination; the caller reading this needs to know it should raise the budget,
            // not that the water won.
            //
            // DERIVED_FROM_READS: both numbers are arithmetic over this tick's position read.
            givenUp = MoveTactic.GivenUp.OUT_OF_TICKS;
            stop();
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "gave up swimming after %d ticks, still %.2f blocks out and %.2f blocks off "
                            + "the destination's height", ticks - 1, dist, dy), hazard,
                    ActOutcome.DERIVED_FROM_READS));
        }

        if (last != null) {
            double sx = pos[0] - last[0];
            double sy = pos[1] - last[1];
            double sz = pos[2] - last[2];
            double moved = Math.sqrt(sx * sx + sy * sy + sz * sz);
            // A third of the slowest vanilla swim rate, so a dipped frame is not a stall and a
            // genuine one still trips inside STUCK_TICKS.
            stillTicks = moved < 0.02D ? stillTicks + 1 : 0;
        }
        last = pos;

        if (stillTicks >= 8) {
            // The world, not the clock. Eight still ticks against a budget of tens, on a body
            // vanilla still reports as in water, so the budget is not what ended this swim and
            // nothing was spent because a swim has no option to spend -- the bearing was already
            // being pressed toward the destination. What stopped it is the water itself: a current
            // or a wall. Same value as the climb's stall, because it is the same fact -- the body
            // is where the move needed it to be and the medium will not carry it.
            //
            // DERIVED_FROM_READS: "the swim stalled" is a conclusion from a streak of ticks whose
            // three-axis displacement stayed under 0.02, plus an `inWater()` read this tick that
            // rules out the medium leaving. Both facts are live; the verdict is a comparison.
            givenUp = MoveTactic.GivenUp.THE_UNMOVABLE_MEDIUM;
            stop();
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "the swim stalled for %d ticks at (%.2f,%.2f): the player is in water and not "
                            + "moving, which is a current or a wall rather than a route",
                    stillTicks, pos[0], pos[1]), hazard, ActOutcome.DERIVED_FROM_READS));
        }

        // Published BEFORE the arrival test, so the first tick of a swim already carries the
        // warning. A hazard that first appears after the player is committed is not a warning, and
        // this is the cheapest moment in the whole swim to learn the river is there.
        hazard = waterHazard(act, pos);

        double len = dist < 1e-9 ? 1.0D : dist;
        double[] keys = NavController.keysForBearing(dx / len, dz / len, act.yaw());
        forward = (float) keys[0];
        strafe = (float) keys[1];
        // Jump is "am I trying to go up", and a horizontal swim that has sunk below the row the
        // plan named IS trying to go up. Without this the controller presses a direction, vanilla's
        // drag walks the body 0.02 a tick downward, and the swimmer drifts out of the destination
        // cell and can never get back into it -- the route then failed on a player doing precisely
        // what it was told. Holding jump is the vanilla correction
        // (EntityLivingBase.onLivingUpdate:2008-2013 into updateAITick:1591), and the air it costs is
        // air the plan already budgeted for.
        jump = rising || pos[1] < targetY;
        // REUSED_CELL when a hazard is attached, DERIVED_FROM_READS when none is -- the same rule
        // NavController.walkGrade applies, for the same reason. `waterHazard` is re-derived on
        // every tick of a swim (there is no scan-cache here), so what makes this REUSED_CELL is not
        // that the cell is old but that `hazard.describe()` in the sentence quotes a hazard the
        // caller may act on, and a hazard a caller acts on is a claim about terrain rather than a
        // claim about this controller's arithmetic. Where the walk's hazard cell is genuinely
        // sampled on an earlier tick, this class's is not, and the difference is the reason the two
        // grades are different CONSTANTS rather than one reused name.
        return ActOutcome.running(String.format(Locale.ROOT,
                "swimming %s, %.2f blocks to go (tick %d/%d) -- %s", rising ? "up" : "across",
                rising ? dy : dist, ticks, timeoutTicks, hazard.describe()), hazard,
                hazard == null
                        ? ActOutcome.DERIVED_FROM_READS
                        : ActOutcome.REUSED_CELL);
    }

    /**
     * The water this swim is in, as a hazard a caller can branch on and size a reaction against.
     *
     * <p>Same {@link NavHazard.Kind#WATER} as the straight-line walker's, and deliberately: a
     * caller that has learned "cancel on WATER" must keep cancelling, and changing the kind to
     * something new would silently disarm every caller written against the old one. What changes
     * is the {@code detail}, which is the field documented as the consequence -- and the
     * consequence of swimming is not "this controller cannot swim" any more, it is a countdown.
     *
     * <p>The cell reported is the player's own feet, not the destination, because that is where the
     * air is actually being spent; and the distance is 0 because the player is standing in it.
     */
    private NavHazard waterHazard(ActActuator act, double[] pos) {
        int air = act.air();
        String detail = air < 0
                ? "the player is in water and the remaining air could not be read, so how long "
                        + "this can be sustained is unknown"
                : String.format(Locale.ROOT,
                        "the player is in water: %d of %d ticks of air left, and vanilla drains one "
                                + "per submerged tick (EntityLivingBase:301) and starts drowning at "
                                + "-20 (:303-317)", air, AIR_FULL);
        return new NavHazard(NavHazard.Kind.WATER,
                (int) Math.floor(pos[0]), (int) Math.floor(pos[1]), (int) Math.floor(pos[2]),
                0.0D, detail);
    }

    private void stop() {
        forward = 0f;
        strafe = 0f;
        jump = false;
    }

    private ActOutcome finish(ActOutcome out) {
        done = true;
        stop();
        return out;
    }
}
