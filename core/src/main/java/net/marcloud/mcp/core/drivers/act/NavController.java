package net.marcloud.mcp.core.drivers.act;

import java.util.Locale;
import net.marcloud.mcp.core.util.Belief;

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
 *
 * <p><b>Two arrivals, because two callers ask different questions.</b> A {@link NavIntent} names a
 * point: its Y is recorded and never steered toward -- the tool says so, and callers legitimately
 * pass the block position {@code find_block} returned rather than the block the feet can occupy --
 * so that controller's arrival is horizontal, exactly as it was, and it never jumps. A ROUTE names
 * a stance, and there the Y is load-bearing twice: arrival means the feet are in the destination's
 * own block with the world holding them up, and a destination exactly one block above the current
 * stance is jumped for, because step height is 0.6 and a 1.0 rise cannot be walked. That second
 * controller is built by {@link #toStance}; the point constructor keeps the first contract as it
 * stands, which is why the two are separate entry points rather than one widened one.
 */
public final class NavController implements LocomotionController {

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
    /**
     * How far SHORT of a block face a jam may be and still be a jam, in blocks.
     *
     * <p><b>It is one tick of travel, and that is the whole derivation.</b> A jam is read on a tick
     * where the body reported {@code collidedHorizontally} and did not advance, which means the step
     * that was just rejected was one tick long. The rejected step is the step that would have
     * carried the body's leading face past the blocking face, so the body is short of that face by
     * strictly less than the step it failed to take -- and the step is at most a tick of travel.
     * Nothing here is an epsilon and nothing is tuned: it is the distance the body can possibly have
     * failed to move, and a body that is further away than that was not stopped by this cell.
     *
     * <p><b>Why not a floating-point tolerance, which is what the shape of the bug suggested.</b>
     * The reported failure was a span of {@code -3.55e-15} on a body at cell 11, which does look
     * like accumulated rounding. It is not the size of the problem. Driving the real {@code BodySim}
     * into a wall at cells 1..24 shows the shortfall is whatever the step lattice leaves: at cells
     * 4..9 the body halts a full {@code 0.20} blocks short, because the last accepted position put
     * its face at {@code wall - 0.2} and the next step of {@code +0.2} would have crossed. An
     * epsilon fixes three cells in twenty-four. The step does not: the shortfall is bounded by
     * {@code 0.2} at every cell offset, which is what "one tick of travel" means.
     *
     * <p><b>Why 0.2 and not something relative to the body.</b> The shortfall is set by how far the
     * body travels and by nothing about the body. {@link #BODY_HALF} would be a sound but idle
     * bound -- 0.3 > 0.2 -- and it would buy its extra 0.1 by letting a jam name a block a third
     * of a block away, which is the invented obstruction this tolerance has to avoid being. The
     * number itself is not new physics asserted here: it is the per-tick travel
     * {@link #ARRIVE_EPSILON} already states from the live measurement (4.2 blocks/second), and the
     * one {@code BodySim} drives the body with.
     *
     * <p><b>It is one-sided, and that is the load-bearing half.</b> The reach is relaxed only on
     * the faces the body is travelling TOWARD, and only outward -- from "reaches the face" to
     * "reaches within one tick of the face". Nothing behind the body can be what stopped it, so
     * the trailing bound is untouched; vertical contact stays strictly positive, because a body
     * standing on a floor would otherwise name its own floor. A cell that only this slack can see
     * is a guess, so {@link #readJam} ranks it below anything the box measurably reaches into.
     */
    static final double JAM_REACH = 0.2D;


    /** Consecutive no-progress ticks while in contact before calling it stuck. */
    private static final int STUCK_TICKS = 8;

    /**
     * Ticks one side-step may take before the opposite side is tried.
     *
     * <p>Sized from the geometry the recovery actually performs, because the old 12 was sized for
     * a different shape and the two no longer agree. A step is not one block of sideways travel:
     * the body goes laterally until it is clear of the jam's cell, and then ALONG that lane until
     * its trailing edge is past the jam's far face, which is the only pose from which resuming the
     * straight line is safe. For a single-block obstruction that is 1 block across plus 1 cell of
     * jam plus two half-widths to clear it -- 2.6 blocks -- and the bearing is one of eight
     * directions, so a diagonal key spends the distance at 0.2/√2 per axis. 2.6 / (0.2/√2) is
     * 18.4 ticks; 24 leaves headroom for a longer obstruction without letting a genuinely
     * boxed-in body wander: two sides is 48 ticks, which is still inside {@link #timeoutTicks}
     * and lets a route read the wedge verdict rather than a deadline.
     */
    public static final int UNWEDGE_TICKS_PER_SIDE = 24;

    /**
     * How many side-steps one walk may complete before it stops trying.
     *
     * <p>The bound is the point, and the reason for it is that an unbounded recovery is worse than
     * no recovery: a body that steps aside, re-wedges, steps aside again spends its whole budget
     * shuffling across the cell it started in and ends it further from the goal than stopping would
     * have. Three is enough for the real case -- the body squeezes past one obstruction, recovers
     * its line and walks on -- and low enough that a genuinely boxed-in body spends 24 of its ticks
     * proving it rather than the full budget.
     */
    public static final int UNWEDGE_MAX_STEPS = 3;

    /**
     * Half the body's width, in blocks. An {@code EntityPlayer} is 0.6 wide
     * ({@code EntityPlayer.java:580}), which is also what makes a sub-cell arrival mistake cost
     * something: the box is wider than the 0.06 of headroom a premature stop leaves.
     */
    static final double BODY_HALF = 0.3D;

    /** Body height in blocks; a box this tall spans three cells when it does not start on an edge. */
    static final double BODY_HEIGHT = 1.8D;

    /**
     * The block a wedged body is pressed against, read out of the world rather than inferred.
     *
     * <p>Every field is a read: the cell, the registry name actually observed there, and how far
     * the body box reaches into that cell. The name is never null -- a cell this controller could
     * not read is not evidence of anything, so it is skipped rather than reported.
     *
     * @param reach how far the body box reaches into this cell, in blocks, SIGNED: positive when
     *              the box overlaps the cell, zero when the box is exactly flush with its face,
     *              and negative when the box stops short of the face by that many blocks. It is
     *              signed because the three are three different sentences in the failure message
     *              and "flush against its face" is a false sentence about a body 0.20 blocks away.
     */
    record Jam(int x, int y, int z, String name, double reach) { }

    /**
     * How far ahead the straight-line hazard scan looks, in blocks.
     *
     * <p>48 because a warning is only useful while there is still room to act on it, and the
     * walk covers roughly 0.2 blocks a tick -- so 48 blocks is about four seconds of warning at
     * a normal jog, and a caller reading act_status every second gets four chances to cancel.
     * Scanning the whole line instead would cost a world read per block for a destination 400
     * blocks away, and the far end is not where the danger is.
     */
    private static final int SCAN_MAX_BLOCKS = 48;

    /**
     * How many blocks of open air under the line count as a drop worth interrupting for.
     *
     * <p>6, and vanilla is the reason: {@code EntityLivingBase:233} charges
     * {@code ceil(distance - 3)}, so 6 blocks costs 3 of a 20-HP player and 9 costs 6. The two
     * deaths this was written for were a 30-block fall and an ocean; 6 is well below either and
     * still above the ordinary 1-2 block step down that is not worth a warning.
     */
    static final int SAFE_DROP = 6;

    /**
     * How close a step-up has to be before this controller asks for a jump, in blocks from the
     * destination's centre.
     *
     * <p>Sits just above the closest a body can get while still below the step, and that bound is
     * geometry rather than taste: a player is 0.6 wide ({@code EntityPlayer.java:580}), so pressed
     * against the step's face the centre is 0.3 from that face and the destination's centre is 0.5
     * beyond it -- 0.8 blocks. Opening the window above that means the jump is asked for while the
     * player is AT the step; a wider window would fire on open ground, where the hop buys nothing
     * and costs the next move the arc to undo.
     */
    private static final double STEP_UP_RANGE = 0.9D;

    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final int timeoutTicks;

    /**
     * Whether arrival is asked as a STANCE (the feet must end in {@code targetY}'s block, held up
     * by the world) or as a POINT (horizontal distance only, the {@link NavIntent} contract).
     *
     * <p>A field rather than a subclass because every other line of the controller -- the axes, the
     * progress accounting, the stuck test, the deadline -- is identical and must stay identical:
     * a copy is how this repo's block-name rule reached six implementations.
     */
    private final boolean arriveAtStance;

    private boolean done;
    private boolean cancelRequested;
    private int ticks;
    private int stillTicks;
    private double[] last;
    /**
     * The most recent straight-line hazard, as a value, and carried into every outcome so
     * {@code act_status} reports it in a form a caller can branch on.
     *
     * <p>Sticky within one intent, for the same reason the string it replaced was: a hazard is
     * found by sampling AHEAD of the player, so it leaves the sample as they walk through it, and
     * dropping the warning the moment the hazard is behind them would leave the remaining line
     * looking clear on no new evidence.
     *
     * <p>{@code blocksAhead} is refreshed from the live position every tick (see {@link #scanHazards}),
     * so the distance a caller reads shrinks as the player closes on it. That is arithmetic on
     * two doubles, not another world read.
     */
    private NavHazard hazard;

    /**
     * The planner's own standability, or null when this walk was given no world view.
     *
     * <p>Null is a wiring fact and not a verdict about terrain: it means the caller had no
     * {@link Standable} to supply, so there is nowhere this controller can step to and a wedge is
     * <i>reported</i> rather than recovered. A {@code walk_straight} is permanently in that state,
     * which is consistent with that tool's own contract -- it never plans and never steers around
     * anything -- and a routed move is not, because {@code RouteExecutor} hands down the same view
     * the plan was built from.
     */
    private final Standable standable;

    /**
     * The side-step in progress: the cell it aims for, and which of the two perpendicular
     * directions it is ({@code 0} when none).
     *
     * <p>Kept as fields rather than recomputed per tick on purpose. The cell is a decision made
     * once -- "step to (7,64,-5)" -- and re-deciding it every tick is how a recovery turns into a
     * wander: the answer would change as the body's box moves, and each change would look like
     * progress while spending the same budget.
     */
    private int sideX;
    private int sideY;
    private int sideZ;
    private int sideSign;

    /**
     * The lateral lane the running side-step holds, as a cell offset from the feet's own cell,
     * and the DIRECTION it runs in, frozen at the moment the body wedged.
     *
     * <p>An offset and not a destination, and that is the whole shape of the recovery: the body
     * walks the ADJACENT LANE to the target rather than the lane it started in, so it passes the
     * obstruction and still ends up going where it was going. An offset is what makes that
     * expressible -- aiming at a fixed cell stops the body the moment it arrives, which is how the
     * first version spent a whole budget shuffling across a boundary and finished no further along.
     *
     * <p>The direction is frozen rather than recomputed, and that is load-bearing. The bearing to
     * the target is a bearing to a POINT, so it rotates as the body steps sideways, and a lane that
     * rotates is not a lane -- see {@link #beginUnwedge} for the traced run of the rotating version.
     */
    private int laneOffX;
    private int laneOffZ;
    private double laneUx = 1.0D;
    private double laneUz;

    /**
     * The lane the running side-step committed to, as a value.
     *
     * <p>The same three facts {@link #laneOffX}/{@link #laneOffZ}/{@link #laneUx}/{@link #laneUz}
     * already hold, frozen at the moment the body wedged and packaged once so that a caller can
     * read WHICH lane the body is on rather than inferring it from the axes. Allocated once per
     * side-step, not once per tick: the lane is deliberately frozen, so re-packaging it every tick
     * would buy nothing.
     */
    private MoveTactic.Lane lane;

    /**
     * The tactic this controller chose on the current tick, as a value.
     *
     * <p>Before this existed the choice was four private fields and two private methods, and
     * nothing outside the class could read any of it: no caller, no test, no other component. A
     * decision that leaves no trace cannot be learned from and cannot be held to account, and this
     * controller makes a real decision on every tick -- straight line or a named lane, jump or no
     * jump, and, when a recovery runs out of options, the difference between "never needed one" and
     * "has none left".
     *
     * <p>Published rather than held privately, and it always mirrors the axes: on any tick,
     * {@code tactic.forward() == forward()} and {@code tactic.strafe() == strafe()}. See
     * {@link MoveTactic} for why that equivalence is the whole contract.
     */
    private MoveTactic tactic;

    /**
     * Which axes the running side-step moves the body along: one for a square-on press, both for
     * the diagonal squeeze this recovery was written for.
     *
     * <p>Set once per side-step beside {@link #sideX}/{@link #sideZ}, and read only by
     * {@link #sideStepDone}, because it is the same fact the cell choice was made from. Deciding
     * it per tick is how a step would change its own definition of finished halfway through.
     */
    private boolean sideAxisX;
    private boolean sideAxisZ;

    /** Ticks spent on the current side-step. */
    private int sideTicks;

    /** Side-steps started on this walk, which is how the escalation knows both were tried. */
    private int sideTries;

    /**
     * The jam a side-step is working around, kept so every tick of the step can report the same
     * obstruction without re-reading the world for it.
     */
    private Jam wedge;

    /**
     * Every tick this walk has spent recovering, across all side-steps.
     *
     * <p>Separate from {@link #sideTicks}, which is per-side and is what the escalation compares
     * against its bound. This one is the cost, and it is what a caller asking "what did the
     * recovery cost me" reads -- and what
     * {@code aWalkThatDoesNotWedgeSpendsNoTicksOnRecoveryAtAll} pins at zero.
     */
    private int recoverTicks;

    /** Side-steps completed on this walk, checked against {@link #UNWEDGE_MAX_STEPS}. */
    private int sideSteps;

    /**
     * The hazard this walk is walking into, or null when none is known.
     *
     * <p><b>A report, never a veto.</b> Nothing inside this controller reads it: no axis, no
     * arrival test and no terminal branch consults the hazard, and {@code walk_straight} keeps

     * moving exactly as it would with a clear line. The tool is documented as never planning and
     * never steering around anything, and quietly refusing to walk would make it a different tool
     * under the same name -- one whose refusal the caller has to diagnose before it can route
     * around the hazard itself.
     *
     * <p>Read it on ANY tick, including the first. The scan runs before the reaction pause is
     * served, so the warning is available during the 4-8 ticks the player spends standing still
     * before the first step -- which is the cheapest place in the whole walk for a caller to
     * learn it is about to walk into an ocean.
     */
    public NavHazard hazard() {
        return hazard;
    }

    /**
     * Where the cached hazard scan was taken from, and the cell it ended at.
     *
     * <p>The scan is 48 samples x up to 2 reads plus a readability probe -- 97 block reads on a
     * clear line -- and it used to run EVERY tick of a walk that can run for hundreds. The world
     * does not change while a player walks a straight line, so every tick after the first was
     * re-deriving a fact that had not changed. That is the "humanisation" tax ADR-0005 forbids
     * paying with the owner's standing instruction: input realism may not cost capability, and a
     * diagnostic that costs a hundred world reads a tick is costing the walk it is describing.
     */
    private double scanFromX;
    private double scanFromZ;
    private int scanToX = Integer.MIN_VALUE;
    private int scanToZ = Integer.MIN_VALUE;
    /** How far the player may drift before the cached line is discarded and re-sampled. */
    private static final int SCAN_DRIFT = 2;
    /**
     * Below this magnitude, no key is pressed.
     *
     * <p>It was 1e-6, which is a numeric guard and not a dead zone: with it, a raw axis of 0.001
     * became a FULL-SPEED walk while 0.0 was a standstill. A caller nudging the axis by a
     * thousandth got a sprint, and the discontinuity is exactly the chatter a deadband exists to
     * remove.
     *
     * <p>Half a key is the honest threshold, and the alternative -- treating a small magnitude as
     * a small speed -- is not available: vanilla has no partial forward, so 0.3 cannot mean "30%".
     * The only slower-than-normal gait is sneaking, and it is its own flag. So an axis below half
     * is a release, and above it is a press; a caller that wants slow should pass sneak.
     */
    static final double DEAD_ZONE = 0.5;

    // The decision-to-fingertip delay is NOT here, and its absence is the fix rather than an
    // omission. It used to be a field of this class, which is the wrong lifetime twice over: a
    // RouteExecutor mints one NavController PER MOVE (steeringFor), so a per-instance draw is a
    // per-BLOCK draw, and a 20-block route emitted ~20 runs of zero displacement at a ~1.25-block
    // period. The delay belongs to the WALK -- to the decision to put a body in motion -- and the
    // applier that owns the walk is MoveApplier, which binds on intent identity. It draws it there
    // and withholds the axes; see MoveApplier.reactionTicks.

    /** Axes this controller wants applied this tick; read by the applier after {@link #tick}. */
    private float forward;
    private float strafe;
    private boolean jump;


    /**
     * Ticks this walk has spent recovering from a wedge, for status and for the tests that pin
     * what recovery costs a route that does not wedge.
     *
     * <p>On a walk that never wedges this stays at zero for the whole walk, and not because of a
     * sampling interval: the branch that can increment it is guarded on the body failing to
     * advance for {@link #STUCK_TICKS} consecutive ticks <i>while in contact with something</i>, and
     * a body moving at 0.2 blocks a tick clears {@link #MOVED_EPSILON} every single tick, so the
     * guard is false from the first step to the arrival. Nothing inside it runs, nothing is read,
     * and no extra world query is issued.
     */
    public int unwedgeTicks() {
        return recoverTicks;
    }

    /** Side-steps completed on this walk so far; zero on every walk that does not wedge. */
    public int unwedgeSteps() {
        return sideSteps;
    }

    /**
     * The tactic chosen on the current tick: the axes, the lane they follow, and what was given up
     * to take them.
     *
     * <p>Readable on every tick including the first, and null before the first one -- there is no
     * decision to report until the controller has read the world, and inventing one would be the
     * invented certainty this class is written against. It is the same value on a terminal tick as
     * the axes that tick published, and on that tick it names the CAUSE rather than a cost alone:
     * a walk that arrived reads zero axes and either {@code NOTHING} or, when it had to clear a jam
     * to get there, {@code ARRIVED_AFTER_A_LANE}; a walk that ran out of its budget reads
     * {@code OUT_OF_TICKS} or {@code OUT_OF_TICKS_AFTER_A_LANE} depending on whether it had spent
     * a lane ({@link #timeoutGivenUp()}); a walk stopped by a jam whose cause could not be read
     * reads {@code THE_UNNAMED_STALL}; and {@code LIMITS_REACHED} is reserved for the one walk
     * that measured every candidate cell and rejected it ({@link #wedged}).
     */
    @Override
    public MoveTactic tactic() {
        return tactic;
    }

    /**
     * A controller that walks toward a POINT: horizontal arrival, no vertical steering.
     *
     * <p>{@code targetY} is recorded and never used, which is the contract {@link NavIntent}
     * documents -- callers pass a full block position without stripping a coordinate, and the walk
     * says nothing about height.
     */
    public NavController(double targetX, double targetY, double targetZ, int timeoutTicks) {
        this(targetX, targetY, targetZ, timeoutTicks, false, null);
    }

    /**
     * A controller that must ARRIVE AT A STANCE: within {@link #ARRIVE_EPSILON} of the block's
     * centre, standing in the block {@code y} names, and held up by the world.
     *
     * <p>Both halves of the Y matter here. Arrival is not "close enough horizontally" because a
     * body falling past a destination is close enough horizontally, and a one-block rise is jumped
     * for ({@link #STEP_UP_RANGE}) because it cannot be walked.
     *
     * <p>This view-less form is the only stance walk that cannot recover from a wedge. It still
     * <i>detects</i> one from the world -- the block and the body box are read the same way -- so a
     * walk made through here fails naming what is in the way rather than saying "stuck". What it
     * cannot do is decide there is somewhere to step, because deciding that is a question about
     * standability and this controller has no authority to answer it.
     *
     * @param targetY the destination's block Y -- the block the FEET end in, not the block they
     *                stand on
     */

    public static NavController toStance(double targetX, double targetY, double targetZ,
                                         int timeoutTicks) {
        return toStance(targetX, targetY, targetZ, timeoutTicks, null);
    }

    /**
     * A stance walk that can also get itself out of a jam.
     *
     * <p>This is the entry point a route uses, and the {@code standable} it carries is the whole
     * difference between a walk that reports a wedge and one that walks around it. The view is a
     * lambda rather than a world because the question is not "what is in this cell" -- the
     * actuator answers that -- it is "may a body legally STAND in that cell", and only the plan
     * layer is allowed to answer it. See {@link Standable}.
     *
     * @param standable the planner's own standability, or null when there is no world view and a
     *                  wedge will therefore be reported rather than recovered
     */
    public static NavController toStance(double targetX, double targetY, double targetZ,
                                         int timeoutTicks, Standable standable) {
        return new NavController(targetX, targetY, targetZ, timeoutTicks, true, standable);
    }

    private NavController(double targetX, double targetY, double targetZ, int timeoutTicks,
                          boolean arriveAtStance, Standable standable) {
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.timeoutTicks = timeoutTicks > 0 ? timeoutTicks : 400;
        this.arriveAtStance = arriveAtStance;
        this.standable = standable;
    }

    /** Forward axis for this tick, vanilla sign. Meaningless once terminal. */
    public float forward() {
        return forward;
    }

    /** Strafe axis for this tick, vanilla sign. Meaningless once terminal. */
    public float strafe() {
        return strafe;
    }

    /**
     * Jump axis for this tick: true while a one-block rise is what the walk still has to do.
     *
     * <p>False in point mode, and that is the contract rather than an omission -- a point walk
     * neither climbs nor jumps. In stance mode it is true only from the ground, only when the
     * destination is exactly one block above the stance the feet are in, and only once the player
     * is at the step ({@link #STEP_UP_RANGE}). The exactly-one-block term is what keeps this from
     * becoming "jump whenever the destination is higher": a destination two blocks up is not the
     * step this axis helps with, and asking then would spend the arc without gaining the block.
     */
    @Override
    public boolean jump() {
        return jump;
    }

    /**
     * Whether this walk must be crept because the route it came from ends at a brink.
     *
     * <p>False, and not by omission: a point walk has no PLAN, so there is no move whose
     * destination could be a brink. Whether to creep one is the caller's whole decision, and
     * MoveApplier reads it off the NavIntent.
     */
    @Override
    public boolean creeping() {
        return false;
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
            return ActOutcome.done("already finished", ActOutcome.READ_DIRECTLY);
        }
        if (cancelRequested) {
            // The tick count and the cancel flag are this machine's own state, read on the tick the
            // sentence is built, so nothing about the WORLD is being claimed here at all. READ_DIRECTLY
            // says exactly that and no more -- it is not a claim that the player is safe, and a
            // caller acting on a cancellation is acting on the controller's bookkeeping.
            return finish(ActOutcome.cancelled("navigation cancelled after " + ticks + " ticks",
                    hazard, ActOutcome.READ_DIRECTLY));
        }
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world", hazard, ActOutcome.READ_DIRECTLY));
        }
        double[] pos = act.position();
        if (pos == null) {
            // READ_CAME_BACK_EMPTY and not READ_DIRECTLY, and the difference is the whole point of
            // this branch existing. `act.position()` returning null is not an observation of a
            // world in which the player has no position -- it is a statement about what this seam
            // could produce, and `LivePlayerActuator.position()` returns null for a player the
            // client does not hold. Claiming OBSERVED here would be the design's 2.C #15 in a new
            // costume: "I could not see" and "there is nothing there" as the same value.
            return finish(ActOutcome.failed("position unavailable",
                    ActOutcome.READ_CAME_BACK_EMPTY));
        }

        double dx = targetX - pos[0];
        double dz = targetZ - pos[2];
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Arrival is tested before anything else: a target underfoot has no meaningful direction,
        // and steering toward it would produce noise rather than motion.
        //
        // In stance mode the horizontal test is not enough on its own, and the cases that need the
        // difference are exactly the ones a route is made of: a body falling past a drop's
        // destination is within 0.6 of its centre while still in the air above it, and a body
        // rising through a step is within 0.6 of it while still in the air below. Both would be
        // "arrived" horizontally, and neither has arrived. So a stance arrival also asks the two
        // world questions the plan names: the feet are in the destination's block, and the world is
        // holding them up.
        // A terminal tick also carries the hazard, because the terminal tick is the one where the
        // warning either proved itself or cost the player everything. "arrived" and "gave up"
        // with no mention of the lava the line ran through are the reports audit 2.3 was written
        // against: a caller can only learn why the walk ended from what it ends with.
        boolean onGround = act.onGround();
        if (dist <= ARRIVE_EPSILON
                && (!arriveAtStance || standingInDestination(pos, onGround))) {
            stop(arrivalGivenUp());
            // Design 2.A #1, at the site that makes the claim rather than at the read. `onGround` is
            // OBSERVED -- it is a vanilla field read with no intermediary -- and `NetHandlerPlayServer
            // :373` uses that same field as the input to `playerEntity.jump()`, so the value is at once
            // an observation about our own body and a lever the server has adopted. Grading it
            // INFERRED would overstate the doubt; grading it without saying WHICH claim it covers
            // would understate it. So the grade below is about the SENTENCE, which is arithmetic on
            // the client's own position copy plus a test against the destination -- a derivation, and
            // an honest one, because nothing in it is stale.
            return finish(ActOutcome.done(String.format(Locale.ROOT,
                    "arrived within %.2f blocks after %d ticks%s", dist, ticks, said()), hazard,
                    walkGrade()));
        }
        ticks++;
        if (ticks > timeoutTicks) {
            stop(timeoutGivenUp());
            return finish(ActOutcome.failed(String.format(Locale.ROOT,
                    "gave up after %d ticks, still %.2f blocks out -- the target may be "
                    + "unreachable in a straight line, which is all this controller attempts%s",
                    ticks - 1, dist, said()), hazard, walkGrade()));
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

        // The wedge, read out of the world rather than counted off a clock.
        //
        // The old test asked two questions -- "has the body stopped advancing" and "is it in
        // contact" -- and both are true of a body standing on a slope, on a ladder, or being held
        // by a server, so its only possible answer was to give up. That is the whole capability
        // hole: a human who wedges against a log sidesteps and walks on, and this controller used
        // to stop, fail the route, and never try anything else.
        //
        // So the third question is added, and it is the one that decides: WHAT is in the way. The
        // body box is measured against the cells it actually overlaps and the block name is read
        // from one of them. A wedge is "not advancing, in contact, and a named block inside the
        // box, in the direction being pushed" -- which a slope and a ladder both fail. When no
        // such block can be read the controller does exactly what it did before and reports the
        // stall, because an unreadable cell is an absence and calling it an obstruction would be
        // the same invented certainty this class is written against.
        //
        // Re-entered on every tick of a side-step, and NOT re-read from the world while one runs.
        // Two reasons, and both matter. Re-reading would restart the very budget the escalation is
        // spending -- a body standing in the cell it stepped to is still touching the block it
        // stepped around, so a fresh read there is a jam every tick. And skipping the re-entry
        // would leave the recovery steering exactly one tick and then fall back to the straight
        // line, which is the failure a one-tick side-step looks like from outside: a message
        // saying it is stepping aside and a body that is not.
        Jam jam;
        if (sideSign != 0) {
            jam = wedge;
        } else if (stillTicks >= STUCK_TICKS && act.collidedHorizontally()) {
            jam = readJam(act, pos, dx / dist, dz / dist);
            if (jam == null) {
                // Not LIMITS_REACHED, and the distinction is the whole of this branch. The walk is
                // terminal HERE, before beginUnwedge is ever reached, so no lane was offered and
                // none was refused: beginUnwedge cannot choose a cell without naming what is in
                // the way, and nothing in the body box could be named. That is a capability limit,
                // not an exhausted budget -- typically the ninth tick of a walk with most of its
                // time left -- and reporting it as "every option, spent" claims a survey of
                // options that never happened.
                stop(MoveTactic.GivenUp.THE_UNNAMED_STALL);
                // READ_CAME_BACK_EMPTY, and this is the strongest case in the class for it. The
                // sentence's whole content is "no block could be read inside the body box": every
                // cell the box overlaps came back with no name, and `blockAt` returns null for air,
                // for an unloaded chunk, for an unnamed block and for a read that threw alike. So
                // this is a statement about what this client could see, and INFERRED would be a
                // guess at a cause nobody established. The hazard rides beside it unchanged --
                // a caller asking about the hazard asks {@code hazard()}, and asking about the stall
                // asks this.
                return finish(ActOutcome.failed(String.format(Locale.ROOT,
                        "stuck against a wall for %d ticks, %.2f blocks short of the target -- and "
                        + "no block could be read inside the body box, so what is stopping it is "
                        + "not something this controller can name%s",
                        stillTicks, dist, said()), hazard,
                        ActOutcome.READ_CAME_BACK_EMPTY));
            }
        } else {
            jam = null;
        }
        // The jump decision is made AFTER the guards and BEFORE the axes, for two reasons. After
        // the guards, because a terminal tick must not publish a jump (stop() clears it) -- and
        // before the axes, because the two are published together: vanilla reads this axis on the
        // tick the forward axis is held, and a jump with no forward would go straight up and land
        // where it started.
        //
        // The straight line's hazards, reported WHILE there is still distance to change your mind.
        //
        // Found on a live client, twice, and both times the same way: `to` walked the player
        // off a cliff (30 blocks, health 20 -> 0) and then into an ocean (drowned). `to` is
        // documented as never routing around anything, and it does not — but "does not route"
        // is not the same as "does not warn", and the observation layer already knows how to
        // read water and lava. The controller was asking the world nothing, so a caller who
        // asked to walk somewhere got a faithful execution of a fatal instruction with no
        // chance to intervene: act_status said "walking, 135.25 blocks to go" all the way down.
        //
        // The scan is placed HERE rather than at the end of the method, so the very first tick
        // already carries the hazard: a warning that first appears after the caller could have
        // used it is not a warning. Nothing downstream reads the result -- the axes, the arrival
        // test and every terminal branch are exactly what they were before the scan existed.
        //
        // The reaction pause that used to sit between this scan and the axes is GONE, and where it
        // went is the whole of this file's contribution to the metronome. It was a per-INSTANCE
        // pause on a class RouteExecutor rebuilds once per move, so it fired 20 times on a
        // 20-block route. It now belongs to the walk and lives in MoveApplier, which knows the
        // difference between one walk and twenty moves.
        NavHazard found = scanHazard(act, pos);
        // The sticky hazard's DISTANCE is refreshed on every tick, including the ticks where a
        // fresh scan found nothing new. Leaving it frozen at the last tick that re-sampled would
        // make the number a caller reads stale exactly when it matters most -- after the player
        // has walked past the hazard and needs to see that it is now behind them.
        hazard = found != null ? found : reposition(hazard, pos);
        jump = arriveAtStance && stepUpNeeded(pos, dist, onGround);
        if (jam != null) {
            // The recovery publishes its own axes, so it runs where the straight-line steer would
            // have. Returning null from it means the side-step LANDED -- the feet are in the cell it
            // aimed at -- and steering resumes at the original target from wherever the body now
            // is, which is the whole recovery: free the body, then carry on, not reroute it.
            ActOutcome recovered = unwedge(act, pos, jam, dist, dx / dist, dz / dist);
            if (recovered != null) {
                return recovered;
            }
        }
        // The straight line IS the tactic, and this is the call that decides it: no lane, and the
        // only thing possibly given up is the way around a hazard this tick had already named. The
        // controller does not steer around lava, water or a deep drop -- it reports them and walks
        // on -- and that is the one cost of this decision a caller cannot reconstruct from the
        // axes, because the axes of walking straight into an ocean and the axes of walking down a
        // clear corridor are the same two numbers.
        tactic = steer(dx / dist, dz / dist, act.yaw(),
                hazard == null
                        ? MoveTactic.GivenUp.NOTHING
                        : MoveTactic.GivenUp.THE_NAMED_HAZARD);
        return ActOutcome.running(String.format(Locale.ROOT,
                "walking, %.2f blocks to go (tick %d/%d)%s", dist, ticks, timeoutTicks, said()),
                hazard, walkGrade());
    }

    /**
     * The grade for a sentence about this walk, and the reason there is a function rather than a
     * constant at each site.
     *
     * <p><b>Two sentences come out of this class and they are not the same claim.</b> With no hazard
     * on the line, the sentence is arithmetic on the position read this tick: derived, honest, and
     * nothing in it stale, so {@link ActOutcome#DERIVED_FROM_READS}. With a hazard, the sentence
     * also quotes the hazard, and the hazard's CELL was sampled on an earlier tick -- {@code
     * scanHazard} reuses it while the body walks the same line, and {@link #reposition} recomputes
     * only the distance. That is design 2.B #7 exactly: "the cell, the kind and the consequence are
     * facts about the world and cannot change while the player walks a straight line toward them",
     * which is an inference that was written as fact in a comment. {@link ActOutcome#REUSED_CELL}
     * is that comment, typed.
     *
     * <p><b>Zero world reads, and not by luck.</b> The grade is a function of the {@code hazard}
     * field this method already holds -- whether it is null was already the condition behind
     * {@link #said()} and behind the {@code steer} call's {@code THE_NAMED_HAZARD} choice. No read
     * was added to learn a grade; a value the sentence was already built from decided it.
     *
     * <p>The design rejected {@code PARTIAL} -- "sampled rather than exhaustive" -- as a case that
     * changes no caller's behaviour. This is not that case: the two values here are the same enum
     * constant, but they are different SITES, and a reader asking "was the hazard on that line read
     * on the tick the warning was printed" now has an answer at each of them rather than a javadoc
     * forty lines away that describes the behaviour of both.
     */
    private Belief walkGrade() {
        return hazard == null
                ? ActOutcome.DERIVED_FROM_READS
                : ActOutcome.REUSED_CELL;
    }

    /**
     * The hazard clause appended to a running message, or the empty string when there is none.
     *
     * <p>Derived from the typed field rather than kept beside it, so the prose and the value
     * cannot disagree: there is one hazard and this renders it.
     */
    private String said() {
        return hazard == null ? "" : " -- " + hazard.describe();
    }

    /**
     * What this walk spent to arrive, which is not always nothing.
     *
     * <p>The arrival branch used to stamp {@link MoveTactic.GivenUp#NOTHING} unconditionally, and
     * that value is documented as "the direct line, and no option was spent to take it". So a body
     * that wedged against a log, spent {@code THE_DIRECT_LINE} for up to
     * {@link #UNWEDGE_TICKS_PER_SIDE} ticks, cleared the jam and then arrived published a terminal
     * record claiming nothing was ever spent to get there -- on the one tick the walk is ever
     * summarised on, and the only tick a caller who did not watch it tick by tick ever sees.
     *
     * <p>A distinct value rather than a lane-carrying {@code NOTHING}, for two reasons. Nothing is
     * defined by the absence of a spend, so attaching a lane to it would leave the record
     * contradicting itself -- {@code onTheDirectLine()} false beside a {@code givenUp} that says no
     * option was given up -- and would have to rewrite NOTHING's own definition to become true.
     * And the lane cannot carry the fact anyway: {@link #unwedge} drops it the moment the
     * side-step lands, deliberately, so the lane field is null on the arrival tick of exactly the
     * walk that recovered.
     *
     * <p>{@code sideTries} rather than the lane, because it is the counter that means "a lane was
     * actually taken". It is incremented in {@link #beginUnwedge} once per lane the body commits
     * to, and the three paths that return without incrementing it all end the walk -- a recovery
     * that finds nowhere to step cannot arrive. So on the arrival branch the two agree, and this
     * one is the statement rather than the coincidence.
     *
     * <p>A walk that never needed a recovery still reads {@code NOTHING}, which is the point: the
     * overwhelming majority of successful walks spent nothing, and a value that made every
     * arrival claim a spend would be as false as the one it replaced.
     */
    private MoveTactic.GivenUp arrivalGivenUp() {
        return sideTries > 0
                ? MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE
                : MoveTactic.GivenUp.NOTHING;
    }

    /**
     * What this walk spent to run out of ticks, which is not always nothing.
     *
     * <p>The timeout branch stamped {@link MoveTactic.GivenUp#LIMITS_REACHED}, whose declared
     * meaning is "every option, spent". On a clear corridor that is false in the plainest way
     * available: the body never met a jam, never took a lane and never spent a recovery tick, and
     * the budget ended while it was still making progress toward a target it simply cannot reach
     * in a straight line. Nothing was exhausted because there was nothing to exhaust, and a caller
     * reading that value is told to reroute a walk that only needed more time.
     *
     * <p>So the clock is its own verdict, split on the one fact a terminal record cannot otherwise
     * carry. {@code sideTries} rather than the lane, for the same reason and with the same
     * guarantee {@link #arrivalGivenUp()} gives: it is incremented once per lane the body commits
     * to, and the lane field is dropped the moment a side-step lands, so a walk that recovered and
     * then ran out reports no lane on a terminal tick and would otherwise be indistinguishable
     * from one that never met anything.
     */
    private MoveTactic.GivenUp timeoutGivenUp() {
        return sideTries > 0
                ? MoveTactic.GivenUp.OUT_OF_TICKS_AFTER_A_LANE
                : MoveTactic.GivenUp.OUT_OF_TICKS;
    }

    /**
     * One tick of getting a wedged body free, or the terminal failure that says it could not be.
     *
     * <p>The shape is a person sidestepping: pick the free side, walk one cell, carry on. It is
     * deliberately not a detour -- the body re-joins the original straight line the moment it is
     * free, because a recovery that chooses its own destination is a planner, and this controller
     * is documented as not being one.
     *
     * @return null when the side-step landed and normal steering should resume this tick, a running
     *         outcome while one is in progress, or a terminal failure when it could not be freed
     */
    private ActOutcome unwedge(ActActuator act, double[] pos, Jam jam, double dist,
                               double ux, double uz) {
        recoverTicks++;
        if (sideSign == 0) {
            ActOutcome refused = beginUnwedge(pos, jam, dist, ux, uz, 0);
            if (refused != null) {
                return refused;
            }
        }
        if (sideStepDone(pos)) {
            // It worked, and the two conditions it now takes are the ones that were missing. Being
            // in the neighbouring cell is not being free of the jam -- a body whose edge has just
            // crossed into the next lane, at z=1.10, still reaches 0.10 back into the jam, and one
            // that is clear of the jam's cell but still LEVEL with it cannot resume the straight
            // line without walking back into it. Both were read as success, and both spent the
            // budget ping-ponging against the obstruction and finished no further along.
            sideSign = 0;
            wedge = null;
            sideTicks = 0;
            sideSteps++;
            stillTicks = 0;
            // The lane is dropped HERE rather than left to be cleared by whoever reads it next. It
            // is the same fact as sideSign, packaged for reading, and a lane that outlives the step
            // it was taken for would be the worst kind of stale: a caller reading a direct-line
            // tactic would be told the body is still working around a log it cleared two seconds
            // ago. tick() republishes on the straight line below, so this is the value for the
            // remainder of this tick and nothing depends on it beyond that.
            lane = null;
            return null;
        }
        if (++sideTicks > UNWEDGE_TICKS_PER_SIDE) {
            if (sideTries >= 2) {
                return wedged(pos, jam, dist, String.format(Locale.ROOT,
                        "Both cells beside the jam were standable and neither freed the body within "
                                + "%d ticks (%d ticks of recovery in total, bounded on purpose: a "
                                + "recovery that wanders is worse than one that stops)",
                        UNWEDGE_TICKS_PER_SIDE, 2 * UNWEDGE_TICKS_PER_SIDE));
            }
            // Escalate rather than repeat: the opposite perpendicular is a different cell, and
            // picking it is the last thing tried before the walk reports the jam.
            ActOutcome refused = beginUnwedge(pos, jam, dist, ux, uz, sideSign);
            if (refused != null) {
                return refused;
            }
        }
        // The bearing is normalised HERE, and it must be. `keysForBearing` documents that the
        // magnitude is irrelevant because `nearestKeys` normalises before choosing -- but
        // `nearestKeys` thresholds the magnitude FIRST (`mag < DEAD_ZONE` -> release every key),
        // and rotation preserves length, so `mag` is the DISTANCE IN BLOCKS to the aim, not an
        // input strength. Passing the raw displacement therefore made the side-step steer correctly
        // until the body came within 0.5 blocks of the aim and then go completely still -- short of
        // the completion test, which needs the box past the shared face by more than BODY_HALF. One
        // threshold, two meanings: DEAD_ZONE is right for a unit bearing and wrong for a distance.
        //
        // The aim is a point AHEAD in the lane, not the lane cell itself, and that is the second
        // half of this defect.
        //
        // Aiming at `sideX,sideZ` asks the body to walk to a fixed cell. It gets there, the
        // completion test fires, and the body resumes the ORIGINAL straight line -- which, from one
        // cell to the side and still level with the obstruction, runs straight back into it. The
        // traced route failure is exactly that: a body side-stepped to (0,64,1), declared free at
        // (0.56,64.00,1.44) while its box was still clear of the log's row but nowhere NEAR past
        // the log's column, then steered down-and-forward into (1,64,0) and wedged again. The
        // recovery had moved the body to the side of the obstacle and not along it.
        //
        // So the bearing has TWO INDEPENDENT terms, and they are separate because a single aim
        // point cannot be both "converge on the lane" and "keep going": any fixed point the body
        // approaches eventually falls inside the dead zone and every key is released, which parks
        // the body metres from a target it was walking towards. The traced stall of this: a body
        // reached (1.04,1.44) with its trailing edge 0.34 short of the log, the aim sat 0.06 away,
        // `nearestKeys` released every key on `mag < DEAD_ZONE`, and the walk burned its whole
        // budget standing still against a log it had already stepped around.
        //
        // The LATERAL term is the signed distance from the body to the lane line, which is zero
        // exactly when the body is in the lane and never stalls: a body that is already in the lane
        // has nothing to correct. The lane is the line through the jam's far face offset by the
        // chosen side, so it is fixed in the world and runs parallel to travel; anchoring it to the
        // jam rather than to the cell the step started from is what stops the aim from swinging
        // behind the body once it is level with the jam and dragging it back into the row it
        // cleared.
        // Both terms are measured in the lane's FROZEN frame ({@link #laneUx}, {@link #laneUz}),
        // never in the bearing to the target. The lane is parallel to the direction the body was
        // travelling when it hit the log, and the target bearing is a bearing to a point that moves
        // relative to the body as it steps sideways -- using it makes both the perpendicular and the
        // forward term swing, and the forward term swinging back toward the target walks the body
        // into the very log it just stepped around.
        double perpX = -laneUz;
        double perpZ = laneUx;
        double laneX = jam.x() + 0.5D + laneOffX;
        double laneZ = jam.z() + 0.5D + laneOffZ;
        double lateral = (pos[0] - laneX) * perpX + (pos[2] - laneZ) * perpZ;
        double bx = -lateral * perpX;
        double bz = -lateral * perpZ;
        //
        // The FORWARD term is how much further the body must travel before its trailing edge
        // clears the jam's far face -- the same quantity `sideStepDone` completes on, so the aim and
        // the test cannot disagree about where "past" is. It is GATED ON BEING LATERALLY CLEAR, and
        // that gate is not a refinement but the physics: the world refuses a step whose box ends up
        // overlapping the jam, and a body still level with the log cannot move forward AT ALL,
        // because its box already reaches 0.0 into the log's column and the 0.14 blocks a diagonal
        // key would add is exactly what tips that 0.0 into a collision. A body pressing east into a
        // log and asking for the diagonal goes nowhere; it must go north first, and only then east.
        //
        // Floored at one block, and that floor is load-bearing: a body level with the jam's far face
        // but short of it has a forward distance of zero, and a zero forward term plus a zero
        // lateral term is a bearing of zero -- the dead-zone stall again. The floor keeps a real
        // direction under the keys right up to the tick the completion test fires.
        double spanX = span(pos[0] - BODY_HALF, pos[0] + BODY_HALF, jam.x());
        double spanZ = span(pos[2] - BODY_HALF, pos[2] + BODY_HALF, jam.z());
        if ((!sideAxisX || spanX < 0) && (!sideAxisZ || spanZ < 0)) {
            double tBody = pos[0] * laneUx + pos[2] * laneUz;
            double jamFar = (jam.x() + 0.5D) * laneUx + (jam.z() + 0.5D) * laneUz
                    + 0.5D * (Math.abs(laneUx) + Math.abs(laneUz));
            double lead = Math.max(1.0D, jamFar + BODY_HALF + 0.5D - tBody);
            bx += laneUx * lead;
            bz += laneUz * lead;
        }
        double blen = Math.hypot(bx, bz);
        // The lane IS the tactic, and this is the call that decides it: the direct line is given up
        // for as long as the lane runs. Which of the two lane give-ups it is depends on sideTries,
        // because the SECOND attempt means the first lane already ran out of ticks -- a caller
        // reading THE_FIRST_LANE knows recovery is on its last option before it happens, rather
        // than having to reconstruct it from the message.
        tactic = steer(blen > 1.0E-9D ? bx / blen : bx, blen > 1.0E-9D ? bz / blen : bz, act.yaw(),
                sideTries > 1
                        ? MoveTactic.GivenUp.THE_FIRST_LANE
                        : MoveTactic.GivenUp.THE_DIRECT_LINE);
        return ActOutcome.running(String.format(Locale.ROOT,
                "wedged against %s at (%d,%d,%d): sidestepping to (%d,%d,%d), tick %d/%d, "
                        + "side-step %d of at most %d%s",
                jam.name(), jam.x(), jam.y(), jam.z(), sideX, sideY, sideZ,
                Math.min(sideTicks, UNWEDGE_TICKS_PER_SIDE), UNWEDGE_TICKS_PER_SIDE,
                sideTries, UNWEDGE_MAX_STEPS, said()), hazard, walkGrade());
    }

    /**
     * Choose a cell to step to, or say why there is none.
     *
     * <p>{@code skipSign} is the side already tried: 0 on the first attempt, and afterwards the
     * sign that just ran out of ticks, so the escalation goes to the OTHER side rather than
     * spending the second budget in the same cell.
     */
    private ActOutcome beginUnwedge(double[] pos, Jam jam, double dist, double ux, double uz,
                                    int skipSign) {
        if (sideSteps >= UNWEDGE_MAX_STEPS) {
            return wedged(pos, jam, dist, String.format(Locale.ROOT,
                    "The body was freed %d time(s) on this walk and wedged again, and %d is the "
                            + "bound: a recovery that wanders is worse than one that stops",
                    sideSteps, UNWEDGE_MAX_STEPS));
        }
        int sign = pickSide(pos, ux, uz, skipSign);
        if (sign == 0) {
            return wedged(pos, jam, dist, standable == null
                    ? "This walk was given no world view, so there is no way to ask whether a "
                            + "neighbouring cell is standable -- the wedge is reported rather than "
                            + "recovered"
                    : (skipSign == 0
                        ? "Neither of the two cells beside the body is standable, so there is "
                                + "nowhere to step"
                        : "The other cell beside the body is not standable either, so there is "
                                + "nowhere left to step"));
        }
        sideSign = sign;
        // Kept so the ticks AFTER this one can report the same obstruction. Re-reading it every
        // tick would be a fresh answer to a question about a body that is still mid-step.
        wedge = jam;
        // The lane's DIRECTION is frozen here, at the moment the body wedged, and every later tick
        // of this step steers along it. It is emphatically not re-derived per tick from the bearing
        // to the target: the target is a point, so that bearing rotates as the body steps sideways,
        // and a lane that rotates is not a lane. The traced failure of the rotating version is a
        // route to (2.5,0.5) with a log at (1,64,0): once the body stood in the z=1 lane the bearing
        // to the target had a NEGATIVE z component, so the forward term that was supposed to carry
        // the body along the lane pointed back down into the log's row, and it walked backwards for
        // the whole remaining budget. The direction the body was travelling when it hit the log is
        // the one the lane has to be parallel to.
        laneUx = ux;
        laneUz = uz;
        // Packaged ONCE, here, and not re-packaged per tick: the lane is frozen by the two lines
        // above, so a per-tick package would be a second copy of a constant. This is the whole
        // point of the field -- the lane is otherwise four private numbers that a caller has no way
        // to ask for, so "which lane did it choose" was unanswerable while "it moved sideways" was
        // perfectly readable.
        lane = new MoveTactic.Lane(laneOffX, laneOffZ, laneUx, laneUz);
        sideTicks = 0;
        sideTries++;
        return null;
    }

    /**
     * Which perpendicular cell to step into, and stores it. Zero when neither will do.
     *
     * <p>The perpendicular to the direction of travel is the free axis by definition -- the jam is
     * in the way along the travel axis, so it cannot also be in the way across it -- and rounding
     * it to a cell offset is what makes the answer a cell rather than a bearing. On a diagonal
     * squeeze it rounds to a diagonal offset, which is the correct cell: a body wedged going
     * south-east escapes north-east, not south.
     *
     * <p>Two candidates, both adjacent to the cell the feet are in, both at the feet's own height.
     * That is the bound on wander in its simplest form: the search cannot propose a cell further
     * than one step away, so recovery cannot become a detour even in principle. When both are
     * standable the one CLOSER TO THE TARGET wins, because a person sidesteps towards where they
     * were going and because it is the only tie-break that can help rather than cost.
     */
    private int pickSide(double[] pos, double ux, double uz, int skipSign) {
        if (standable == null) {
            return 0;
        }
        int cellX = (int) Math.floor(pos[0]);
        int cellY = (int) Math.floor(pos[1]);
        int cellZ = (int) Math.floor(pos[2]);
        int rx = (int) Math.round(-uz);
        int rz = (int) Math.round(ux);
        int chosen = 0;
        double chosenScore = Double.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            int sign = i == 0 ? 1 : -1;
            if (sign == skipSign) {
                continue;
            }
            int cx = cellX + sign * rx;
            int cz = cellZ + sign * rz;
            if (!standable.standableAt(cx, cellY, cz)) {
                continue;
            }
            double score = Math.hypot(targetX - (cx + 0.5D), targetZ - (cz + 0.5D));
            if (score < chosenScore) {
                chosenScore = score;
                chosen = sign;
                sideX = cx;
                sideY = cellY;
                sideZ = cz;
                // The lane is stored as an OFFSET, so the body can keep travelling along it after
                // it arrives, instead of stopping on the cell it aimed at. The axes it travels are
                // fixed by the perpendicular rather than by the sign, and are recorded with it.
                laneOffX = sign * rx;
                laneOffZ = sign * rz;
                sideAxisX = rx != 0;
                sideAxisZ = rz != 0;
            }
        }
        return chosen;
    }

    /**
     * The block the body box is pressed against, or null when nothing readable is inside it.
     *
     * <p>Measured, not sampled: the box is intersected with every cell it touches, and the deepest
     * reach into one of them wins. The body box matters more than the centre here, because a body
     * 0.3 wide straddling a cell boundary is stopped by a block whose CENTRE is half a block away
     * -- the case this recovery was written for, where the box reaches 0.06 into the log and that
     * 0.06 is the entire distance between the route stalling and the route finishing.
     *
     * <p><b>Contact counts, and it is the only asymmetry in the test.</b> A body that has walked
     * up to a wall and stopped has a box EXACTLY flush with that wall's face, and
     * {@code AxisAlignedBB.intersectsWith} calls that a non-intersection -- so a strict overlap
     * test finds nothing at the precise moment the body is stuck, and every jam in this world is a
     * flush jam with nothing but the sub-tick overshoot of a diagonal for company. Hence X and Z
     * accept a zero reach and Y does not: vertical contact is not being stopped, it is STANDING on
     * something, and a body resting on the floor would otherwise name its own floor as the thing
     * in the way.
     *
     * <p><b>Zero is not the only flush, and that is what {@link #JAM_REACH} is for.</b> The body
     * does not stop where its face meets the wall's face; it stops at the last position from which
     * one more tick of travel would not have crossed, which is anywhere from flush to a full tick
     * short depending on where the step lattice left it. Measured over walls at cells 1..24, a
     * body halts 0.20 blocks short at five of them and a few times 1e-14 short at the rest, and
     * an exact {@code span >= 0} found NOTHING at twenty-four of twenty-four. The reach is that
     * one tick of travel and nothing more, it is applied only to the faces the body is travelling
     * toward, and it is not allowed to promote a guess over a measurement.
     *
     * <p>A block the body is moving AWAY from loses to one it is moving into, and only ties are
     * broken on reach. Without that ranking a body walking past a wall it happens to touch would
     * name the wall as its obstruction, which is the kind of confident wrong answer this class has
     * been written against all along.
     *
     * <p>Fluids are excluded by NAME, and that is the one taxonomy this file is allowed: the
     * helpers are the ones the hazard scan already uses. {@code blockPresent} is measured true for
     * water and lava, so without the name check a body wading in a river would report the river as
     * the thing stopping it.
     */
    private Jam readJam(ActActuator act, double[] pos, double ux, double uz) {
        double minX = pos[0] - BODY_HALF;
        double maxX = pos[0] + BODY_HALF;
        double minY = pos[1];
        double maxY = pos[1] + BODY_HEIGHT;
        double minZ = pos[2] - BODY_HALF;
        double maxZ = pos[2] + BODY_HALF;
        Jam best = null;
        boolean bestAhead = false;
        // The reach widens the window on EVERY horizontal face, and the trailing ones are not an
        // exception. A diagonal jam is a cell the body is short of on one axis and already past on
        // the other -- measured, driving a body into a log at (20,64,0) on a south-west bearing,
        // it stops at (21.36,64,1.36) with its box 0.06 past the log's far face in x and 0.06
        // short of its near face in z. The near face is the one that is short, and a reach on the
        // leading faces only would leave that jam unnamed, which is the same false claim this
        // whole read is being fixed for. What keeps a block behind the body from winning is the
        // ranking below, not the window: it prefers what the box measurably reaches into, then the
        // cell ahead, and only then the deepest reach.
        for (int x = (int) Math.floor(minX - JAM_REACH);
                x <= (int) Math.floor(maxX + JAM_REACH); x++) {
            double ox = span(minX, maxX, x);
            // Horizontal CONTACT is a jam -- see the javadoc. A body flush against a wall overlaps
            // nothing at all, and a flush jam is what a jam looks like. Short of the face by up to
            // one tick of travel is the same jam, measured a moment earlier.
            if (ox < -JAM_REACH) {
                continue;
            }
            for (int z = (int) Math.floor(minZ - JAM_REACH);
                    z <= (int) Math.floor(maxZ + JAM_REACH); z++) {
                double oz = span(minZ, maxZ, z);
                if (oz < -JAM_REACH) {
                    continue;
                }
                for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++) {
                    double oy = span(minY, maxY, y);
                    if (oy <= 0 || !act.blockPresent(x, y, z)) {
                        continue;
                    }
                    String block = name(act, x, y, z);
                    // An unreadable cell is an absence, not an obstruction: it is skipped, and a
                    // body pressed against something nobody can name falls back to the plain stall.
                    if (block == null || isWater(block) || isLava(block)) {
                        continue;
                    }
                    boolean ahead = (x + 0.5D - pos[0]) * ux + (z + 0.5D - pos[2]) * uz > 0;
                    // Signed on purpose. Zero is a flush face, negative is a face the body stops
                    // short of, and the message has to be able to say which -- "flush against its
                    // face" is a false sentence about a body 0.20 blocks away from it.
                    double reach = Math.min(ox, Math.min(oy, oz));
                    boolean measured = reach > 0.0D;
                    boolean bestMeasured = best != null && best.reach() > 0.0D;
                    if (best == null
                            // A cell the box measurably reaches into outranks one only the reach can
                            // see, on either axis. The reach exists so a jam is not lost to the step
                            // lattice; a candidate only it can produce is a guess, and a guess must
                            // never outrank a measurement of what the body is touching.
                            || (measured && !bestMeasured)
                            || (measured == bestMeasured
                                    && ((ahead && !bestAhead)
                                            || (ahead == bestAhead && reach > best.reach())))) {
                        best = new Jam(x, y, z, block, reach);
                        bestAhead = ahead;
                    }
                }
            }
        }
        return best;
    }

    /** How far {@code [lo,hi]} reaches into the cell {@code [cell,cell+1]}; negative when short. */
    private static double span(double lo, double hi, int cell) {
        return Math.min(hi, cell + 1.0D) - Math.max(lo, cell);
    }

    /**
     * Whether the side-step is DONE: the body is out of the jam's cell on every axis the step
     * travels, and past the jam's far face along the bearing.
     *
     * <p>Arithmetic on the remembered jam's coordinates, not a re-read of the world. The cell
     * cannot have changed identity while the body is the only thing moving, and the alternative --
     * asking the world again -- would be paying reads on the one path that has to stay cheap.
     *
     * <p>It deliberately does NOT ask whether the feet are still in the cell the step chose. That
     * was the old first condition and it is wrong for a step that is walked rather than landed in:
     * the body is meant to keep travelling ALONG the lane, so it leaves that cell immediately, and
     * a test pinned to it ends the step on the boundary -- which is the ping-pong across a shared
     * face that spends the whole budget and finishes no further along than it started.
     */
    private boolean sideStepDone(double[] pos) {
        Jam jam = wedge;
        if (jam == null) {
            return true;
        }
        // The clearance test, and the OR that used to be here is the whole defect.
        //
        // "Stopped touching the cell" was read as "clear on EITHER horizontal axis, or not standing
        // on it". A box touches a cell when it reaches into that cell's span on BOTH horizontal
        // axes, so the negation is a conjunction, not a disjunction -- and on a square-on press
        // the two disagree at exactly the moment the step appears to finish.
        //
        // The traced failure: a body wedged on a log at (3,64,0) picks the side-step to (2,64,1),
        // and one tick later its box sits at x[2.2586,2.8586] z[0.9414,1.5414]. That box is
        // 0.06 blocks INSIDE the log's row (z span +0.0586) and 0.14 clear of its column
        // (x span -0.1414). The old OR read the clear column and called the step landed. The body
        // then resumed the straight line, walked back into the log's cell -- where the box no
        // longer reaches x=3 at all, so `readJam` scanned only cell x=2, found nothing, and the
        // walk died on "no block could be read inside the body box" 5.99 blocks short of a target
        // it had already nearly reached.
        //
        // So clearance is asked PER AXIS THE STEP ACTUALLY TRAVELS ({@link #sideAxisX},
        // {@link #sideAxisZ}), and each of those must be clear. The axes the step does not travel
        // are not asked about: a step that moves in z only is not required also to be clear in x,
        // because on a square-on press clearing x would mean backing out of the very lane the
        // step was taken to enter, and the body would never land. The vertical term is kept as an
        // escape rather than a requirement -- a body that dropped below the jam's cell is clear of
        // it whatever its horizontal span says.
        double spanX = span(pos[0] - BODY_HALF, pos[0] + BODY_HALF, jam.x());
        double spanZ = span(pos[2] - BODY_HALF, pos[2] + BODY_HALF, jam.z());
        if (span(pos[1], pos[1] + BODY_HEIGHT, jam.y()) <= 0) {
            return true;
        }
        if (sideAxisX && spanX >= 0) {
            return false;
        }
        if (sideAxisZ && spanZ >= 0) {
            return false;
        }
        // And the second half of the defect: clear of the jam is not the same as PAST it. A body
        // beside a log is clear of the log's cell and still cannot resume the straight line,
        // because from level with the obstruction that line runs back into it. The traced route
        // failure is exactly that -- a body stepped to (0,64,1), was called free at (0.56,1.44)
        // while level with a log at x[1,2], and immediately re-wedged on it. The step ends at the
        // only pose from which resuming the line is safe: past the jam.
        //
        // Measured in the lane's FROZEN frame, for the same reason the aim is: the bearing to the
        // target is a bearing to a point, and it rotates as the body steps sideways, so a "past"
        // test measured in it would answer a different question each tick of the step. The jam's cell
        // projects onto the lane direction with a half-extent of 0.5*(|ux|+|uz|) about its centre,
        // and the body's trailing edge is already `pos` less BODY_HALF, so "past" is the trailing
        // edge beyond that far face -- the same expression the aim's forward term is built from.
        double jamFar = (jam.x() + 0.5D) * laneUx + (jam.z() + 0.5D) * laneUz
                + 0.5D * (Math.abs(laneUx) + Math.abs(laneUz));
        double bodyTrail = (pos[0] - BODY_HALF) * laneUx + (pos[2] - BODY_HALF) * laneUz;
        return bodyTrail > jamFar;
    }

    /**
     * The terminal report for a wedge that could not be recovered.
     *
     * <p>It names the body's position, the block and the cell it is in, how far the box reaches
     * into that cell, and what was tried. That is the whole difference between a caller that can
     * act and one that has to guess: "not planned" or "stuck" send a caller looking for a route
     * problem, and the route was fine -- there is a log at (5,64,-4).
     *
     * <p><b>Four paths lead here and they are not one fact, so the verdict is derived rather than
     * stamped.</b> Three of them are exhaustion: both lanes were standable and neither freed the
     * body in time; the body had been freed {@link #UNWEDGE_MAX_STEPS} times already and wedged
     * again; or {@link #pickSide} MEASURED both cells beside the jam and rejected both. In each of
     * those an option existed and did not work, which is the one thing
     * {@link MoveTactic.GivenUp#LIMITS_REACHED} means.
     *
     * <p>The fourth is {@code pickSide}'s first line: no standability view at all, so it answers
     * "no cell" before it has looked at anything. Nothing was surveyed and nothing could be spent,
     * and this is the common path rather than an edge case, because
     * {@code MoveApplier} builds every {@link NavIntent} as exactly this controller -- a point walk
     * with no view. The verdict for it is
     * {@link MoveTactic.GivenUp#THE_UNSURVEYED_WEDGE}, which sends a caller to ask for a walk that
     * carries a world view rather than to reroute or to raise a budget.
     *
     * <p>Derived from {@code standable == null} rather than passed in by the callers because that
     * is the ONLY distinction between the four: the two exhaustion paths that are not
     * {@code pickSide}'s rejection ({@link #UNWEDGE_TICKS_PER_SIDE} exceeded on the second lane,
     * and the step bound) both require {@code sideTries > 0} or {@code sideSteps > 0} to have
     * happened, and a lane can only be taken when a cell was chosen, which requires the view. So
     * {@code standable == null} is unreachable from those two, and the one test below separates
     * all four without a caller having to remember which path it came in on.
     */
    private ActOutcome wedged(double[] pos, Jam jam, double dist, String why) {
        // Derived from `standable == null`, the SAME predicate the verdict above is derived from,
        // because they are the same distinction and a caller reading one wants the other.
        //
        // With no view, nothing was surveyed: `pickSide` answered "no cell" before looking at
        // anything, so the sentence's own clause says "this walk was given no world view". That is
        // UNKNOWN and it is the honest grade -- nobody looked, so a claim about which lanes existed
        // would be invented. `readJam` DID name a block, so the obstruction itself is not the
        // unknown part; the unknown is the survey behind the clause.
        //
        // With a view, both cells beside the jam were MEASURED standable and one or both failed to
        // free the body. That is arithmetic over reads -- DERIVED_FROM_READS -- and it stays that
        // way when a hazard is also quoted, because the weakest link in a sentence is the grade the
        // sentence gets: a caller acting on it is acting on both clauses, and grading the
        // obstruction clause INFERRED while the sentence says "no lane existed" would be grading the
        // part that is certain.
        Belief grade = standable == null
                ? ActOutcome.READ_CAME_BACK_EMPTY
                : walkGrade();
        stop(standable == null
                ? MoveTactic.GivenUp.THE_UNSURVEYED_WEDGE
                : MoveTactic.GivenUp.LIMITS_REACHED);
        return finish(ActOutcome.failed(String.format(Locale.ROOT,
                "wedged at (%.2f,%.2f,%.2f): the body box is pressed against %s at (%d,%d,%d)%s "
                        + "and has not advanced while pressing into it. %s. %.2f blocks short of "
                        + "the target%s",
                pos[0], pos[1], pos[2], jam.name(), jam.x(), jam.y(), jam.z(),
                // The reach is reported as the three different things it is. A body flush against a
                // wall reaches 0.00 blocks into it and "overlaps by 0.00 blocks" is a sentence that
                // contradicts itself, so zero reads as contact. A body SHORT of the face reads as
                // short, with the distance in it: "flush against its face" about a body 0.20 blocks
                // away would be the invented certainty this whole read exists to avoid, and it is
                // the sentence the reach made reachable.
                jam.reach() > 0.0D
                        ? String.format(Locale.ROOT, ", reaching %.2f blocks into it", jam.reach())
                        // A shortfall that rounds away at the precision this message prints is the
                        // flush case, and it says so. A body 3.55e-15 short of a face is 3.55e-15
                        // short, not "0.00 blocks short": the first is the reach absorbing the
                        // step lattice's rounding, and the second is a sentence that reads as a
                        // missing measurement.
                        : jam.reach() > -0.005D
                                ? " (flush against its face)"
                                : String.format(Locale.ROOT, ", %.2f blocks short of its face",
                                        -jam.reach()),
                why, dist, said()), hazard, grade));
    }

    /**
     * What is between here and the target, sampled one block at a time along the straight line.
     *
     * <p>Three things are worth interrupting a caller for, and they are the three that killed
     * the player on a live client: lava (never survivable), water (a {@code walk_straight} line
     * that ends in an ocean walks into it and the player drowns), and a drop of more than
     * {@link #SAFE_DROP} blocks with no floor for at least {@link #SAFE_DROP} more (fall damage
     * is {@code ceil(distance - 3)}, so a 6-block drop is already certain damage for a
     * full-health player and a 30-block drop is death).
     *
     * <p>Sampled, not exhaustive, and {@link NavHazard#describe()} says so in those words. A
     * one-block step can straddle a hazard the walk will still meet, so this cannot promise the
     * line is clear — it promises the KNOWN hazards are named, with a distance in blocks, while
     * there is still walking left to do something about them.
     *
     * <p>{@code blockAt} returns a registry name, and the names checked are 1.8.9's: the material
     * for lava and the block id for water, which is the naming the rest of the world readers in
     * this project already match on.
     *
     * @return the hazard, or null when none is known. Never a hazard of unknown kind: every value
     *         built below names the block that was actually read, or a run of open air under a
     *         floor the reader had already proven it could read.
     */
    private NavHazard scanHazard(ActActuator act, double[] pos) {
        int steps = (int) Math.min(SCAN_MAX_BLOCKS, Math.hypot(targetX - pos[0], targetZ - pos[2]));
        if (steps <= 0) {
            return null;
        }
        // Reuse the sampled geometry while the player is still walking the same line. The part
        // that genuinely changes every tick is only the DISTANCE, and it is re-derived from the
        // live position in {@link #reposition} -- arithmetic on two doubles. What is NOT recomputed
        // is the part that cannot have changed, which is the world between here and there.
        boolean sameLine = scanToX == (int) Math.floor(targetX) && scanToZ == (int) Math.floor(targetZ)
                && Math.abs(pos[0] - scanFromX) <= SCAN_DRIFT
                && Math.abs(pos[2] - scanFromZ) <= SCAN_DRIFT;
        if (sameLine) {
            scanToX = (int) Math.floor(targetX);
            scanToZ = (int) Math.floor(targetZ);
            return reposition(hazard, pos);
        }
        scanFromX = pos[0];
        scanFromZ = pos[2];
        scanToX = (int) Math.floor(targetX);
        scanToZ = (int) Math.floor(targetZ);
        int yFeet = (int) Math.floor(pos[1]);
        // null from blockAt means air OR "could not read", and the drop test cannot tell them
        // apart -- so it only runs once the reader has proven it can answer at all. Without
        // this an unloaded chunk reads as a bottomless pit on every line out of it, which is
        // the same class of invented hazard this method exists to remove.
        boolean floorsReadable = floorIsReadable(act, pos);
        int dropRun = 0;
        int dropStartX = 0;
        int dropStartZ = 0;
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            int x = (int) Math.floor(pos[0] + (targetX - pos[0]) * f);
            int z = (int) Math.floor(pos[2] + (targetZ - pos[2]) * f);

            String atFeet = name(act, x, yFeet, z);
            if (isLava(atFeet)) {
                return aheadOf(pos, new NavHazard(NavHazard.Kind.LAVA, x, yFeet, z, 0.0,
                        "4 damage a tick -- Entity.setOnFireFromLava():543 charges "
                                + "DamageSource.lava 4.0F every tick the body is in it, so a "
                                + "full-health player is dead within 5 ticks and this walk does "
                                + "not stop"));
            }
            if (isWater(atFeet)) {
                return aheadOf(pos, new NavHazard(NavHazard.Kind.WATER, x, yFeet, z, 0.0,
                        "'walk_straight' does not swim, and walking into deep water drowns the "
                                + "player; 'go_to' is the key that reaches a block without "
                                + "walking into it"));
            }
            // A gap is a hazard only while it stays a gap: any floor inside the drop closes it.
            if (floorsReadable && name(act, x, yFeet - 1, z) == null) {
                if (dropRun == 0) {
                    // The cell where the open air BEGINS, not the one where the run happened to
                    // reach SAFE_DROP. Those are six blocks apart, and it is the first that tells
                    // a caller how much walking is left before the fall starts -- so the distance
                    // this reports is the distance to the edge, not to the bottom.
                    dropStartX = x;
                    dropStartZ = z;
                }
                dropRun++;
                if (dropRun >= SAFE_DROP) {
                    return aheadOf(pos, new NavHazard(NavHazard.Kind.DEEP_DROP,
                            dropStartX, yFeet - 1, dropStartZ, 0.0,
                            "with no floor for " + SAFE_DROP + " blocks -- vanilla fall damage is "
                                    + "ceil(distance-3), so this is not survivable at full health"));
                }
            } else {
                dropRun = 0;
            }
        }
        return null;
    }

    /**
     * Measure a freshly sampled hazard from where the player is standing.
     *
     * <p>The distance belongs to the sample, not to the loop: a hazard six cells away is six
     * cells away the moment it is found, and the loop variable that found it is about to be gone.
     */
    private NavHazard aheadOf(double[] pos, NavHazard found) {
        return found.aheadBy(distanceTo(pos, found.x(), found.z()));
    }

    /**
     * Re-measure a cached hazard against the player's CURRENT position, without re-reading the
     * world.
     *
     * <p>The cell, the kind and the consequence are facts about the world and cannot change while
     * the player walks a straight line toward them. The distance can, and it is the only part of
     * the warning a caller acts on: "a hazard exists" is static, "it is 4 blocks away and closing"
     * is what decides whether cancelling now is cheap.
     *
     * <p>This replaces a {@code String.replaceAll} over the rendered message, which recompiled a
     * pattern and re-rendered a sentence every tick to move a number along. The number is now a
     * field.
     */
    private NavHazard reposition(NavHazard cached, double[] pos) {
        if (cached == null) {
            return null;
        }
        return cached.aheadBy(distanceTo(pos, cached.x(), cached.z()));
    }

    /**
     * The SIGNED distance from the player to a hazard cell, measured ALONG THE WALK LINE.
     *
     * <p>Signed because the scan looks ahead and the warning stays sticky: once the player has
     * walked through the hazard's cell an unsigned distance would keep reporting "1.3 blocks
     * ahead" for something they are standing on, and a caller reading that would cancel a walk
     * that had already passed the danger. Negative means behind them.
     *
     * <p>Measured along the line rather than as a plain euclidean distance because "ahead" is a
     * statement about the walk and not about geometry: a line that drifts a block sideways still
     * has one forward direction, and that is the direction the player is spending their next four
     * seconds travelling.
     *
     * <p>No world read. The sampled cell is a fact about the world and does not move; this is the
     * only part of the warning that genuinely changes from tick to tick, and it is two dots and a
     * divide.
     */
    private double distanceTo(double[] pos, int x, int z) {
        double dx = x + 0.5 - pos[0];
        double dz = z + 0.5 - pos[2];
        double lineX = targetX - scanFromX;
        double lineZ = targetZ - scanFromZ;
        double lineLen = Math.hypot(lineX, lineZ);
        if (lineLen < 1e-9) {
            // The walk is standing on its own target, so there is no line to measure against and
            // the plain distance is the only honest answer available.
            return Math.hypot(dx, dz);
        }
        return (dx * lineX + dz * lineZ) / lineLen;
    }

    /**
     * Whether this actuator can be asked about blocks at all right now.
     *
     * <p>{@code blockAt} returns {@code null} for AIR and also for "no world" and for an unnamed
     * block, so a {@code null} floor is ambiguous between "there is no floor there" and "the read
     * did not happen". Treating those the same is how a diagnostic starts inventing hazards: an
     * unloaded chunk would read as a bottomless pit on every straight line out of it.
     *
     * <p>So the drop test is gated on a cell the player is demonstrably standing above. If that
     * reads back null the reader is answering null for everything, and the drop test is skipped
     * rather than run on a signal that carries no information. Water and lava do not need this:
     * they are detected by NAME, and a name that is absent cannot be mistaken for a hazard.
     */
    private static boolean floorIsReadable(ActActuator act, double[] pos) {
        return name(act, (int) Math.floor(pos[0]),
                (int) Math.floor(pos[1]) - 1, (int) Math.floor(pos[2])) != null;
    }

    private static String name(ActActuator act, int x, int y, int z) {
        try {
            return act.blockAt(x, y, z);
        } catch (Throwable t) {
            // An unread cell is not a hazard and not a clearance: it is an absence, and claiming
            // either would be the same quiet wrongness this method exists to remove.
            return null;
        }
    }

    private static boolean isLava(String n) {
        return n != null && n.replace("minecraft:", "").equalsIgnoreCase("lava");
    }

    private static boolean isWater(String n) {
        return n != null && (n.replace("minecraft:", "").equalsIgnoreCase("water")
                || n.replace("minecraft:", "").equalsIgnoreCase("flowing_water"));
    }

    /**
     * Whether the feet are in the destination's own block with the world holding them up.
     *
     * <p>Three facts, and all are needed. The block test alone accepts a body falling past the
     * destination at 0.3 above its surface, because {@code floor(62.3)} is 62; the grounding test
     * alone accepts a body standing a block below it. Together with the XZ half of the cell test
     * they are the plan's own claim.
     *
     * <p><b>The XZ half was missing, and it is the same defect this method's Y half exists to
     * prevent, one layer down.</b> A stance's centre is 0.5 from the face of its own cell, so the
     * 0.6 epsilon above can be satisfied by a body standing up to 0.1 into the PREVIOUS cell --
     * and then this controller declared the move arrived and stopped steering, handing its caller a
     * destination it had not reached. Measured downstream: a route move credited with the feet in
     * cell z=-4 while the plan said z=-5, which put the body 0.06 blocks too far north to clear the
     * log the next move had to walk past. The Y half asks "is the body in the cell the plan named";
     * asking it on one axis only is a question about a PLANE, and a plan's stance is a volume.
     *
     * <p>It has to be asked here as well as in {@code RouteExecutor}, and the two are not a
     * duplicated rule: this one keeps the controller from stopping short, and that one keeps a
     * controller which stopped short -- or was never asked -- from being believed. Tightening the
     * caller alone would have made every route fail at moves this one reports as done.
     *
     * <p>The residual epsilon is now the sub-cell tail and nothing else. {@code (int) Math.floor} is
     * Java's floor, which rounds toward negative infinity and is what vanilla's
     * {@code MathHelper.floor_double} does, so a destination on the negative side needs no
     * special case.
     */
    private boolean standingInDestination(double[] pos, boolean onGround) {
        return onGround
                && (int) Math.floor(pos[0]) == (int) Math.floor(targetX)
                && (int) Math.floor(pos[1]) == (int) Math.floor(targetY)
                && (int) Math.floor(pos[2]) == (int) Math.floor(targetZ);
    }

    /**
     * Whether the destination is a one-block step this tick has to jump.
     *
     * <p>Three conditions, all load-bearing. From the ground, because vanilla only leaves it from
     * there and an axis held through the arc would turn one step into a hop. Exactly one block up,
     * because {@code stepHeight} is {@code 0.6F} ({@code EntityLivingBase.java:208}): a 1.0 rise is
     * the one this axis can buy and the one a walk cannot, while a 2.0 rise is not a step at all.
     * And at the step ({@link #STEP_UP_RANGE}), because jumping from across the block is a jump on
     * open ground.
     */
    private boolean stepUpNeeded(double[] pos, double dist, boolean onGround) {
        if (!onGround) {
            return false;
        }
        if ((int) Math.floor(targetY) != (int) Math.floor(pos[1]) + 1) {
            return false;
        }
        return dist <= STEP_UP_RANGE;
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
     *
     * <p><b>It returns the tactic, and that is the change.</b> It used to set two fields and return
     * void, which is exactly what made the decision unreadable: the axes were the only output, and
     * the facts around them -- that the camera is not being turned, and that something was given up
     * to take this line rather than another one -- existed nowhere at all. The arithmetic did not
     * change; it is the same call and produces the same two floats.
     *
     * @param givenUp what taking this line rather than another one cost. The caller decides which
     *                that is, because only the caller knows why it is steering
     * @return the tactic these axes are, ready to be published
     */
    private MoveTactic steer(double wx, double wz, float yawDeg, MoveTactic.GivenUp givenUp) {
        // The rotation and the snapping are both below, in the one place that owns them.
        double[] keys = keysForBearing(wx, wz, yawDeg);
        forward = (float) keys[0];
        strafe = (float) keys[1];
        // yawChange is null, and null is not a default standing in for zero. This controller does
        // not turn the camera -- the class contract says so, and LOOK belongs to another slot -- and
        // "decided not to rotate" is the fact a reader needs. A 0f here would say "re-aim by
        // nothing", which is a different decision this controller never makes.
        return new MoveTactic(forward, strafe,
                arriveAtStance ? Boolean.valueOf(jump) : null,
                null,
                lane,
                givenUp);
    }

    /**
     * The eight-way key pair for a world direction at the player's current yaw: vanilla's own
     * rotation, inverted, then snapped to a legal press.
     *
     * <p><b>Package-private because the conversion is a RULE, and a rule with two copies drifts.</b>
     * It exists separately from {@link #steer} so that a second kind of locomotion with a
     * horizontal component -- a swim, which has to push a body through water toward a bearing --
     * does not re-derive the four lines of {@code Entity.moveFlying} inversion and risk getting
     * one of them mirrored. The repo has already paid for exactly that: the first
     * {@code nearestKeys} derived its eight key pairs from bit patterns and encoded strafe with a
     * sign bit but no negative branch, so strafe was only ever 0 or +1 and a walk needing to
     * strafe LEFT walked the wrong way at four of nine camera headings.
     *
     * <p>It adds no allocation: {@link #nearestKeys} already returned a fresh pair, and it still
     * does, so this is the same one array per call that {@code steer} allocated before.
     *
     * <p>The argument convention is the caller's world direction, not a unit vector: the
     * magnitude is irrelevant, because {@code nearestKeys} normalises before choosing. Passing
     * the un-normalised bearing is deliberate and is what {@code steer} always did.
     */
    static double[] keysForBearing(double wx, double wz, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        // The direction in BODY axes, before any decision about which keys to press.
        return nearestKeys(wz * cos - wx * sin, wx * cos + wz * sin);
    }

    /**
     * The keyboard press closest to a bearing: two axes, three states each.
     *
     * <p>Eight directions, chosen by which of the eight unit-ish key pairs the bearing falls
     * nearest. Ties go to the axis that was already held, which keeps a walk from chattering
     * between two diagonals when the target sits exactly on the boundary between them -- and
     * chatter is its own tell, so a rule that only ever changes the key mask when the bearing
     * has genuinely crossed a boundary is part of the point.
     *
     * <p><b>Why snapping at all.</b> This is the whole "strictly human" argument for movement, and
     * it is a structural fact rather than a tuning one. {@code Entity.moveFlying:1224-1241}
     * NORMALISES the input vector: with |input| >= 1 it forces the acceleration to length a
     * regardless of direction, and with |input| < 1 it scales proportionally. So in 1.8.9 the
     * speed magnitude is decided entirely by the key mask plus sneak plus sprint -- yaw
     * contributes nothing to it. The only thing a human can change about how fast they go is
     * WHICH KEYS ARE DOWN.
     *
     * <p>A keyboard has three states per axis, so a human's whole reachable input set is
     * {-1, 0, +1} per axis, or {-0.3, 0, +0.3} while sneaking (MovementInputFromOptions
     * multiplies the whole mask by 0.3). Every other value is a number a human cannot produce,
     * and publishing one is the same class of giveaway as a reaction time no person has: the
     * controller was writing 0.997 forward and 0.07 strafe, and no pair of fingers on any
     * keyboard makes that.
     *
     * <p>Snapping is therefore not a rounding convenience -- it is the difference between
     * "moves correctly" and "moves like a person". It costs nothing: the exact bearing is
     * re-derived from the live position every tick, so the walk still closes on the target, and it
     * re-snaps as the bearing changes, which is exactly what a player does when they let go of W
     * and press D for a moment.
     *
     * <p>{@code sneaking} is passed rather than read, because the sneak multiplier is applied by
     * {@code MovementInputFromOptions} downstream of this seam: publishing +-1 here and letting
     * the player scale it is the vanilla path, and publishing +-0.3 would double-apply it.
     */
    static double[] nearestKeys(double bodyForward, double bodyStrafe) {
        double mag = Math.hypot(bodyForward, bodyStrafe);
        if (mag < DEAD_ZONE) {
            return new double[] {0, 0};
        }
        double nf = bodyForward / mag;
        double ns = bodyStrafe / mag;
        // The eight reachable headings, as (forward, strafe). Each is the closest one a pair of
        // keys can produce: the four cardinals and the four diagonals, the diagonals being what
        // two keys give and NOT a shorter straight line -- moveFlying normalises them to the same
        // length, which is why a diagonal is not faster.
        // The eight reachable (forward, strafe) pairs, written out.
        //
        // An earlier version derived them from bit patterns and was WRONG in a way no unit test
        // on the old behaviour could see: the encoding gave strafe a sign bit but no negative
        // branch, so strafe was only ever 0 or +1, and (0,+1) appeared twice. A walk that needed
        // to strafe LEFT snapped to "strafe right" or to a pure forward press, and the walk
        // walked away from its target at four of nine camera headings. An enumerated table cannot
        // be mis-encoded this way, and eight rows cost nothing.
        double[][] keys = {
            {0, 0}, {1, 0}, {0, 1}, {1, 1}, {0, -1}, {1, -1}, {-1, 0}, {-1, 1}, {-1, -1},
        };
        double bestF = 0;
        double bestS = 0;
        double bestDot = -2;
        for (double[] k : keys) {
            double len = Math.hypot(k[0], k[1]);
            if (len < 1e-9) {
                continue;                       // no keys down is not a heading
            }
            double dot = nf * (k[0] / len) + ns * (k[1] / len);
            if (dot > bestDot + 1e-9) {
                bestDot = dot;
                bestF = k[0];
                bestS = k[1];
            }
        }
        return new double[] {bestF, bestS};
    }

    /**
     * Release the keys and publish the tactic that released them.
     *
     * <p>The {@code givenUp} argument is the third encoding this package was missing. Everything
     * that stops a walk used to stop it the same way -- two zero floats and a {@code false} -- so a
     * walk that ARRIVED and a walk that had spent every option it had were the same three numbers,
     * and a caller reading them could not tell a finished walk from a dead one. It takes a named
     * reason because the caller of this method is the only place that knows which of the two it is.
     *
     * <p>The lane is deliberately NOT cleared here, and the published tactic carries it. Two
     * reasons, and both are the reason the promise is kept rather than deleted. First, a walk that
     * dies mid-side-step did not die on the direct line, and {@code hold(GivenUp)} alone could not
     * say so: it builds its record with a null lane, so the road the body was on when it stopped
     * existed in a private field and in no value anybody could read. Second, the terminal arrival
     * value needs it to be unambiguous -- {@link MoveTactic.GivenUp#ARRIVED_AFTER_A_LANE} says a
     * lane was spent, and this says whether the body was still on it.
     *
     * <p>Every fresh walk starts with no lane because {@code beginUnwedge} is the only thing that
     * sets one and it is only ever called mid-walk.
     */
    private void stop(MoveTactic.GivenUp givenUp) {
        forward = 0f;
        strafe = 0f;
        // NOTE: hazard is deliberately NOT cleared here. It is cleared in finish(), which only
        // runs on a terminal outcome, and reset per intent.
        //
        // The reaction-delay bookkeeping that used to live here is gone, and so is the paragraph
        // explaining why it must not be reset. There is no draw in this class to reset: the
        // decision-to-fingertip delay moved to MoveApplier, whose lifetime is the walk rather
        // than the move. The "redrawn every tick and raced the tick counter" defect it described
        // was real, and it is now structurally impossible rather than merely fixed -- there is
        // no per-tick re-entry that can reach the draw.

        // The jump axis is cleared with the others. A terminal tick that left it set would have
        // the applier publish one more hop for a walk that has already ended -- and in stance mode
        // the arrival tick is a grounded tick, which is exactly when vanilla would take it.
        jump = false;
        // Published last, so it is built from the axes this method has just zeroed and therefore
        // cannot disagree with them. The jump component comes from hold() for the same reason: the
        // tactic always mirrors what was published, and on a terminal tick that is nothing. The
        // lane is the one component that is NOT from this method -- it is the field, deliberately
        // left alone above, so the published record names the road the walk ended on.
        tactic = MoveTactic.hold(givenUp, lane);
    }

    private ActOutcome finish(ActOutcome out) {
        done = true;
        // Terminal: no NEW hazard will be found from here -- but the one this walk ENDED with is
        // carried into the outcome by the caller, which reads the field before this runs. This
        // field itself is cleared so a fresh intent on the same controller cannot inherit it.
        // Kept out of stop(), which also runs on the arrival tick.
        hazard = null;
        scanToX = Integer.MIN_VALUE;
        scanToZ = Integer.MIN_VALUE;
        return out;
    }
}
