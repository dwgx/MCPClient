package net.marcloud.mcp.core.drivers.plan;

import java.util.List;
import java.util.Locale;

import net.marcloud.mcp.core.drivers.act.ClimbSteering;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.drivers.act.SwimSteering;
import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.NavController;
import net.marcloud.mcp.core.drivers.act.MoveTactic;
import net.marcloud.mcp.core.drivers.act.Standable;

/**
 * Drives a {@link Planner.Plan} on the live player, one {@link Move} at a time.
 *
 * <p>This is the executing half of "the AI works out what it needs and does it". The planner decides
 * that a gap is crossed by placing a block; this walks the player there and places it. Neither half
 * contains a bridging routine: {@link Move.Kind#BRIDGE} arrives here as data, and the only thing this
 * class knows about it is that a block must exist before the step is possible.
 *
 * <p><b>Movement is delegated to {@link NavController}, not reimplemented.</b> Its yaw-relative
 * forward/strafe arithmetic was verified on a real client (12/12, including the diagonal case that
 * took several rounds to ever land), and the repo's own scar tissue says a second copy of a rule
 * drifts from the first -- the block-name rule reached six implementations with three different
 * failure answers before anyone noticed. One controller is created per move, which costs an object
 * and buys the guarantee that this class cannot invent a different idea of "walking".
 *
 * <p><b>Shape note.</b> This deliberately mirrors {@code NavController}'s surface -- {@code tick(act)}
 * plus {@link #forward()} and {@link #strafe()} -- because movement does not go through the actuator
 * at all: {@code MoveApplier} reads those two values and writes them into {@code ActMovementInput}.
 * Wiring this into the MOVE slot therefore means either teaching {@code MoveApplier} a second
 * controller type or introducing the MANEUVER concept (one controller holding several slots for its
 * lifetime). That is a frozen-contract decision and is NOT taken here; this class is complete and
 * testable without it.
 *
 * <p><b>The four things it must report honestly</b>, each of which has a test and a mutation:
 *
 * <ul>
 *   <li>Arrival is asked of the WORLD, never counted. "I issued N placements" is not "the bridge is
 *       there" -- the same shape as dig completion being an identity question rather than an
 *       emptiness one.</li>
 *   <li>A refused placement does not count. The server rejects out-of-reach placements and returns
 *       the item, so counting queued clicks reports a bridge that was reverted. The confirmation is
 *       an IDENTITY question -- did the cell's block change from what it was before the click --
 *       never the presence question {@code blockPresent} answers, which is true for water, lava and
 *       tall grass and would confirm a bridge over a cell nothing was placed into.</li>
 *   <li>Running out of blocks mid-route is FAILED <i>and names where the player is standing</i>. The
 *       caller is somewhere it did not plan to stop and needs to know where.</li>
 *   <li>Falling is a terminal failure that names the reason. Reporting COMPLETE while the player is
 *       in free fall is the worst available lie here. A descent the PLAN asked for is not a fall,
 *       though: the guard measures against the lowest point the current move reaches, not the ledge
 *       it started from, or every {@link Move.Kind#DROP} would abort on its way down.</li>
 * </ul>
 *
 * <p>All terminal exits funnel through {@link #finish}, for {@code CraftController}'s reason: an
 * abandoned run leaves the player standing on half a bridge over a drop, so releasing keys and
 * dropping the steering is not optional cleanup, it is part of the outcome.
 */
public final class RouteExecutor
        implements net.marcloud.mcp.core.drivers.act.LocomotionController {

    /**
     * Where the machine is. Public so a status tool can report it without inference.
     *
     * <p><b>VERIFYING is not a step of a normal walk.</b> It is what the executor falls into when
     * the steering controller stopped and the world has <i>not</i> confirmed the step, which is
     * a disagreement between two components rather than a phase every move passes through. The
     * arrival question is asked on every walking tick ({@link #readArrival}), so a move that has
     * landed is credited on the tick it landed and the next move's steering takes the axes on that
     * same tick. See {@link #walk}.
     */
    public enum Phase {
        /** Validating the plan against the world before touching anything. */
        CHECKING,
        /** Placing the block this move needs before the step is possible. */
        PLACING,
        /** Steering toward the destination of the current move. */
        WALKING,
        /**
         * The steering stopped and the world has not agreed: settling a landing, or failing the
         * move with the arrival mismatch and the steering's own verdict.
         */
        VERIFYING,
        /** Terminal. */
        DONE,
    }

    /**
     * Ticks a single move may take before it is called stuck.
     *
     * <p>Generous rather than tight: a one-block walk is about five ticks at the measured 0.2
     * blocks/tick, so 60 leaves room for a jump arc, a server round trip and a slow slew without
     * ever being the reason a healthy move fails. A bound is still required -- without one a refused
     * placement or a blocked step waits forever and the caller cannot tell a hang from progress.
     */
    public static final int MOVE_TICK_BUDGET = 60;

    /**
     * Deadline handed to the steering controller: strictly shorter than {@link #MOVE_TICK_BUDGET}.
     *
     * <p>The inequality is the point. When the two were equal the outer budget expired first, so a
     * steering controller that gave up never reached the arrival check and the failure message blamed
     * a timeout instead of naming that the player was not where the plan required. Leaving headroom
     * means steering-gave-up and route-hung are distinguishable outcomes rather than one message
     * covering both.
     */
    public static final int NAV_TICK_BUDGET = MOVE_TICK_BUDGET - 10;

    /**
     * How far from a move's target centre still counts as arrived, in blocks.
     *
     * <p>This is the RESIDUE tolerance and nothing else: how far short of the exact centre the
     * sub-cell tail of a GCD-quantised step may leave the body. It is NOT what decides which cell
     * the feet are in -- {@link #feetInOwnCell} does that, and did not exist until this number was
     * already too wide to tell the two apart.
     *
     * <p><b>It was 0.7, and every digit above 0.5 was a lie about the world.</b> The body is 0.6
     * wide, so a stance's centre is 0.5 from the face of its own cell; a tolerance wider than 0.5
     * admits a body that is still in the PREVIOUS cell, and 0.7 admitted half a cell of early
     * credit on every move. Measured consequences, both deterministic: a route move credited at
     * z=-4.24 was 0.28 from the centre of the stance it claimed to have reached while its feet
     * were in cell z=-4, and the NEXT move then steered into the cell that body box still
     * overlapped and wedged there. The route reported the move it had not finished, and the
     * player stood ten blocks short of the goal with a trace showing every step succeeded.
     *
     * <p><b>0.6 now, and 0.6 is the floor.</b> {@code NavController.ARRIVE_EPSILON} is 0.6 and it
     * stops on the FIRST tick that satisfies it, so a stance arrival it declares is legitimately
     * as far as 0.6 from the centre -- any tighter and this class rejects moves the steering
     * considers complete, which is exactly the mismatch that killed a live route on its first
     * move before the cell check existed (measured: a player stopped 0.56 blocks from centre,
     * which is arrival to the steering and a different BLOCK to a floored-coordinate test).
     *
     * <p>What the extra 0.1 bought between 0.6 and 0.7 was the ability to credit a body a
     * neighbouring cell away. {@link #feetInOwnCell} now refuses that outright, so the band no
     * longer has to carry the cell question and the tolerance can sit where the steering actually
     * delivers.
     */
    public static final double ARRIVE_TOLERANCE = 0.6D;

    /**
     * How far BELOW the planned cell the feet may rest and still count as having arrived.
     *
     * <p>Not slack for its own sake. {@code Stance.isStandable} asks whether the cell below has a
     * collision box, and a bottom slab, a stair and a ladder all do, so a route onto one is
     * legitimately planned with the body occupying cell {@code y} while the world rests the feet
     * on the PARTIAL block's top surface instead. The tallest partial top vanilla produces inside
     * one cell is a bottom slab at 0.5 above the cell floor, and everything else in 1.8.9 is at or
     * below that; 0.55 admits it with room to spare.
     *
     * <p>It is deliberately narrower than a block, because a whole block of height error is a
     * different failure and the one this check exists for: the player one cell down, in a dip that
     * has its own floor, is not where the route said, and the world holding them up does not make
     * it so. 0.55 refuses that and admits the slab.
     */
    public static final double ARRIVE_HEIGHT_SLACK = 0.55D;
    /**
     * Ticks an arrival will wait for the body to actually LAND before calling it a failure.
     *
     * <p>A planned {@link Move.Kind#DROP} ends with the player in the air for a tick or two after
     * the steering has stopped: the horizontal question is answered while the vertical one is
     * still settling, and {@code onGround} is genuinely false until the body touches down. Failing
     * there reports a route as broken for the last few centimetres of a descent the plan itself
     * asked for, which is worse than waiting.
     *
     * <p>Twenty ticks covers a full three-block drop from its apex ({@link Stance#SAFE_DROP_MAX})
     * with room over. A body still airborne after that is not landing, it is going somewhere the
     * route did not send it, and the honest failure below names that instead.
     */
    public static final int SETTLE_TICKS = 20;


    /**
     * How many ticks a placement may be retried before the move fails.
     *
     * <p>Retried at all because a refusal can be transient: the server may not yet have the player
     * at the position the reach check is measured from. Bounded at three because a placement that is
     * refused three times is being refused for a reason that will not change, and retrying it to the
     * move budget would report "stuck" for what is really "illegal".
     */
    public static final int PLACE_RETRIES = 3;

    /**
     * How far below the LOWEST height the current move plots the player may be before it is called
     * a fall.
     *
     * <p>Measured from the move's lowest point rather than from the one it started at, and the
     * difference is the whole of {@link Move.Kind#DROP}: for a drop those are two different
     * heights, and comparing against the ledge the player fell FROM put every planned 2- and
     * 3-block drop below the threshold while the player was still in the air. A move kind the
     * planner emits has to be a move kind the executor can finish, or the planner is writing plans
     * its own executor refuses.
     *
     * <p>One block of slack absorbs the normal case: a step down inside a move, and the sub-block
     * dip a landing produces. Past that the player is somewhere the plan did not send it, and no
     * amount of further ticking recovers the route -- the two ways that ends badly are a fall deep
     * enough to hurt (vanilla's free fall depth is {@link Stance#SAFE_DROP_MAX} blocks) and a
     * landing in water below the route, where the fall is cancelled rather than ended.
     */
    public static final double FALL_SLACK = 1.5D;

    private final List<Move> moves;
    private final int blockBudget;

    private Phase phase = Phase.CHECKING;
    private int index;
    private int blocksSpent;
    private int moveTicks;
    private int placeAttempts;
    private int totalTicks;
    /** Ticks spent waiting for an arrival to land; reset when a move starts and when one is credited. */
    private int settleTicks;
    private boolean cancelRequested;
    private boolean finished;
    /**
     * The controller steering the current move, which is one of three types.
     *
     * <p>Typed as the interface rather than as {@link NavController} because the kind decides the
     * type, and three of the six kinds need something other than a walker. Declaring the concrete
     * type and casting at the use site would put an unchecked cast on the path that has to be
     * right, and the alternative -- forcing a climb through a walker -- is the defect this whole
     * change exists to remove: a walker's arrival asks whether the world is holding the player up
     * from BELOW, and a ladder or a river holds them up from somewhere else entirely.
     */
    private LocomotionController nav;

    /**
     * What the machine driving the CURRENT move last chose, latched at the tick it was dropped.
     *
     * <p><b>Why this field exists: an ordering, and the ordering is the whole bug.</b> This class
     * publishes zero axes the moment {@link #nav} is null, which is what stops a finished move's
     * keys from carrying into the next block. But the applier reads {@link #tactic()} AFTER
     * {@code tick()} has returned, and on the terminal tick {@link #tick}'s terminal exits have
     * already run {@link #finish} -- so a {@code nav == null} test in {@code tactic()} cannot tell
     * "no machine has ever run" from "the machine ran, decided, and was released on this very
     * tick", and it answered for the second with the first's value. Every terminal record a routed
     * walk has ever carried said {@link MoveTactic.GivenUp#NOTHING} and no lane, so
     * {@link MoveTactic.GivenUp#ARRIVED_AFTER_A_LANE}, {@code OUT_OF_TICKS_AFTER_A_LANE} and
     * {@code LIMITS_REACHED} were correct values no shipped caller ever read.
     *
     * <p><b>Latched at the drop, never at the read.</b> A read-side cache cannot work here and the
     * reason is the same ordering: on the terminal tick the FIRST call to {@code tactic()} is the
     * one the applier makes, and it arrives after the drop. Caching on read would therefore latch
     * nothing at all on the only tick that matters. The value has to be taken while the machine
     * that produced it is still reachable, which is what {@link #dropSteering} is for -- it is the
     * single place any {@code nav} is discarded, so a new site cannot forget the capture the way a
     * bare {@code nav = null} can.
     *
     * <p><b>Cost and lane, re-wrapped; the axes are deliberately dropped.</b>
     * {@link MoveTactic} promises its axes are the axes published <i>on the same tick</i>, and on
     * the tick this is read the applier has already called {@code publish(null)}: the keys are up.
     * Carrying the machine's last forward axis across would put a record beside the fingers saying
     * otherwise, which is the one failure this type was written to be worth less than no record
     * at all. {@link MoveTactic#hold(MoveTactic.GivenUp, MoveTactic.Lane)} is zero axes with the
     * cost and the road preserved, and it is exactly the shape the machine's own {@code stop()}
     * produces -- so a walk that gave up latches back a value equal to the one it published.
     *
     * <p><b>Scoped to one move, and cleared where a new move begins.</b> {@link #startMove} drops
     * it beside {@link #steeringVerdict}, for the same reason that one is dropped there: a verdict
     * belongs to the move that earned it. Without that clear a walk placing the floor for move 4
     * would be recorded as striding down move 3's lane, which is the exact defect the
     * {@code hold(NOTHING)} default was originally correct about.
     */
    private MoveTactic lastTactic;

    /**
     * The planner's own standability, handed down to the steering controller, or null when this
     * executor was built without a world view.
     *
     * <p>It is the SAME view the plan was searched against, and that is the whole point: a body
     * that wedges asks "is there a cell I can stand in next to this jam", and only one answer to
     * that question is allowed. A second, weaker notion of standability built here would let the
     * agent step onto a cell the planner had already refused and call it recovery.
     *
     * <p>Null means a wedge is <i>reported</i> rather than recovered -- see the two constructors.
     */
    private final Standable standable;

    /**
     * What the steering controller said when it gave up on the current move, quoted verbatim in
     * the route's own failure.
     *
     * <p>Without it the route's failure is a statement about ARRIVAL -- "ended 1.40 blocks from the
     * centre of (5,64,-5)" -- which is true and does not say what stopped the body. The steering
     * had already read the world and named it: a log at (5,64,-4), and the two side-steps it tried.
     * Dropping that verdict on the floor is the same defect as not reading the world back at all,
     * one layer up.
     */
    private String steeringVerdict;

    /**
     * The grade that verdict carried when it was written.
     *
     * <p>Kept beside {@link #steeringVerdict} for the same reason {@link #hazard} rides beside a
     * sentence rather than inside it: a sentence QUOTING an earlier sentence is making a claim that
     * inherits the quoted one's evidence, and quoting is the one way a stale grade enters a fresh
     * row. When the steering said "no block could be read inside the body box" -- UNKNOWN -- and
     * this class quotes that into an arrival failure, the arrival failure must not be graded as if
     * this class had established the cause itself. It did not: it read a position and compared it
     * to a cell, and separately it has a sentence it cannot vouch for.
     *
     * <p>Null when there is no verdict, which is the common case and needs no special handling:
     * {@link #weaker} treats null as "no second claim", not as "a weaker claim".
     */
    private net.marcloud.mcp.core.util.Belief steeringVerdictGrade;

    /**
     * The block NAME at the cell the current move is placing into, sampled before the first click.
     *
     * <p>The baseline for the only identity test this seam can make: {@link ActActuator} exposes
     * {@code blockAt} but no held stack, so "is the block I placed there" has to be asked as "is
     * the cell no longer the block it was". Sampled once per move, before any click, because
     * sampling per attempt would make a successful placement's own first tick the new baseline.
     */
    private String placeBaseline;

    /**
     * An executor with no world view behind it: a wedge is named, not recovered.
     *
     * <p>Kept because it is a real configuration rather than a leftover -- an executor driven
     * against a scripted or absent world has nothing to ask about standability, and reporting the
     * jam honestly is the correct behaviour there. Anything that HAS a view uses the three-argument
     * form, which is what {@code RoutePlanning} does: it has already built the
     * {@link LiveBlockView} it planned with.
     */
    public RouteExecutor(Planner.Plan plan, int blockBudget) {
        this(plan, blockBudget, null);
    }

    /**
     * An executor that can hand its steering controller the world view the plan was built on.
     *
     * @param world the same {@link BlockView} the search used, or null for no recovery
     */
    public RouteExecutor(Planner.Plan plan, int blockBudget, BlockView world) {
        this.moves = plan == null ? List.of() : List.copyOf(plan.moves());
        this.blockBudget = Math.max(0, blockBudget);
        // Asked at most twice per side-step and never on a walk that does not wedge, so the three
        // cells' worth of short-lived Stances it can allocate over a whole route is not a cost
        // worth an API on Stance for.
        this.standable = world == null ? null
                : (x, y, z) -> new Stance(x, y, z).isStandable(world);
    }



    /**
     * Whether the move now being walked must be crept: its destination is a brink.
     *
     * <p>Read by MoveApplier's gait seam, which is the only publisher of the sneak key. False
     * outside a walk (placing, verifying, terminal) because there is no move being walked to have
     * an edge.
     */
    public boolean creeping() {
        return nav != null && index < moves.size() && moves.get(index).creeping();
    }

    /** Forward axis for the MOVE channel, in the same units {@code NavController} produces. */
    public float forward() {
        return nav == null ? 0f : nav.forward();
    }

    /** Strafe axis for the MOVE channel. */
    public float strafe() {
        return nav == null ? 0f : nav.strafe();
    }

    /**
     * Jump axis for the MOVE channel, taken from the steering controller of the move being walked.
     *
     * <p>Delegated rather than decided here for the same reason the two axes are: whether a jump is
     * right is a fact about the move's own destination, and the object that holds it is the
     * {@link NavController} driving the walk. False whenever nothing is being walked -- placing,
     * verifying and terminal phases have no steering, so they have nothing to jump for.
     */
    public boolean jump() {
        return nav != null && nav.jump();
    }

    /**
     * The tactic the machine executing the current move chose, as a value.
     *
     * <p>Delegated for the same reason {@link #jump()} is, and the reasoning is the stronger of
     * the two: this class is not a steering machine, it is a sequencer that installs one. The
     * decision belongs to whichever of the three controllers is driving right now, and an executor
     * answering for itself would be inventing a tactic on behalf of a controller nobody asked.
     *
     * <p><b>What the phases with no steering say, and why it is not null.</b> {@code CHECKING},
     * {@code PLACING} and {@code VERIFYING} all run with {@code nav == null}: the body is placing a
     * block, or waiting for a landing to settle, and this class publishes zero axes on every one of
     * those ticks ({@link #forward()} returns 0f with no steering). Null would be the laziest
     * answer and the worst one -- the applier skips stamping on a null, so the slot would carry the
     * PREVIOUS move's tactic, and a walk placing the floor for move 4 would be recorded as striding
     * down move 3's lane. {@link MoveTactic#hold} with {@link MoveTactic.GivenUp#NOTHING} is the
     * truthful value and says exactly what the axes say: no keys, and nothing spent to get here. A
     * placement is not a sacrifice, it is the plan's own instruction.
     *
     * <p><b>On a terminal tick this returns what the steering spent, lane and all.</b> The terminal
     * exits all release the machine on the tick they return (see {@link #finish}), so a bare
     * {@code nav == null} could not tell that tick from a placement and answered both with
     * {@code NOTHING}: every routed record ever published named no cost and no lane, and the
     * applier's own comment about the terminal tactic being "the walk's only summary" described a
     * value no walk could produce. {@link #lastTactic} is what the machine said before it was
     * released, and it is the second answer rather than the default for exactly one reason: it is
     * set only where a machine really ran, and cleared when the next move begins, so the placement
     * case above still answers {@code NOTHING} and the terminal case answers the truth.
     */
    @Override
    public MoveTactic tactic() {
        if (nav != null) {
            return nav.tactic();
        }
        return lastTactic == null ? MoveTactic.hold(MoveTactic.GivenUp.NOTHING) : lastTactic;
    }

    public Phase phase() {
        return phase;
    }

    /** Moves completed so far, so a caller can report progress without guessing. */
    public int movesDone() {
        return index;
    }

    public int blocksSpent() {
        return blocksSpent;
    }

    public int ticks() {
        return totalTicks;
    }

    /** Ask the machine to stop at its next tick, releasing the player cleanly. */
    public void requestCancel() {
        cancelRequested = true;
    }

    /**
     * One step of the machine.
     *
     * <p>The order of the guards at the top is load-bearing. Cancel is honoured before anything else
     * so a caller can always get the player back; the fall check comes before progress so a player
     * already off the route cannot be reported as advancing along it.
     */
    public ActOutcome tick(ActActuator act) {
        if (finished) {
            return ActOutcome.failed("this route has already finished; a controller past its terminal "
                    + "state must not be re-driven, because its cleanup has already run",
                    ActOutcome.READ_DIRECTLY);
        }
        totalTicks++;

        if (cancelRequested) {
            // `where(act)` reads the client's own position copy this tick, so the sentence is
            // arithmetic over a live read rather than a claim about terrain. DERIVED_FROM_READS and
            // not READ_DIRECTLY, because "the player was released at (x,y,z)" is not the same claim
            // as "these three numbers are on the field" -- the first is a position and the second is
            // a fact about a controller.
            return finish(act, ActOutcome.cancelled("route cancelled after " + index + " of "
                    + moves.size() + " move(s), " + blocksSpent + " block(s) spent; the player was "
                    + "released " + where(act), ActOutcome.DERIVED_FROM_READS));
        }
        if (!act.inWorld()) {
            return finish(act, ActOutcome.failed("the world went away mid-route after " + index
                    + " of " + moves.size() + " move(s)", ActOutcome.READ_DIRECTLY));
        }
        if (moves.isEmpty()) {
            // "The player is already where it asked to be" is a CONCLUSION from an empty plan, not
            // a read: an empty plan says no cell was named as a destination, which is not the same
            // as a read proving the player is at the goal. Derived, and honestly so.
            return finish(act, ActOutcome.done("nothing to do: the plan was empty, so the player is "
                    + "already where it asked to be", ActOutcome.DERIVED_FROM_READS));
        }

        ActOutcome fall = checkNotFalling(act);
        if (fall != null) {
            return finish(act, fall);
        }

        return switch (phase) {
            case CHECKING -> startMove(act);
            case PLACING -> place(act);
            case WALKING -> walk(act);
            case VERIFYING -> verify(act);
            case DONE -> ActOutcome.failed("unreachable: DONE is terminal and finished was false",
                    ActOutcome.READ_DIRECTLY);
        };
    }

    /**
     * Has the player left the route downward?
     *
     * <p>Compared against the LOWEST height the CURRENT move legitimately puts the player at, not
     * against the height the move started from. The two differ exactly for a
     * {@link Move.Kind#DROP}: its start is the ledge above and its destination is where the fall is
     * meant to end, so measuring against the start aborted the route mid-air on a descent the plan
     * itself asked for. For every other kind the start is the lowest point, and a descent the plan
     * did not ask for fails either way.
     *
     * <p>Returning a terminal failure here rather than trying to recover is deliberate: once the
     * player is off a bridge there is nothing under it to walk back onto, and a machine that kept
     * ticking would spend its budget steering in mid-air and then report "stuck", naming the wrong
     * cause.
     */
    private ActOutcome checkNotFalling(ActActuator act) {
        if (index >= moves.size()) {
            return null;
        }
        double[] pos = act.position();
        if (pos == null) {
            // Design 2.C #17, and the sharpest of its two sites. The code already refuses IN PROSE
            // -- "reporting progress on an unread position would be inventing it" -- and that
            // refusal was invisible to a caller, who could only learn it by reading a sentence. Typed,
            // it says what it always was: somebody asked and could not see. And it is UNKNOWN rather
            // than INFERRED because there is no derivation to name -- the position did not arrive,
            // so nothing was derived from it.
            return ActOutcome.failed("the player position could not be read, so whether the route is "
                    + "still being followed is unknown -- reporting progress on an unread position "
                    + "would be inventing it", ActOutcome.READ_CAME_BACK_EMPTY);
        }
        Move m = moves.get(index);
        double lowestY = Math.min(m.from().y(), m.to().y());
        // A swimmer below the route's line is not falling OUT of it, and the guard's own javadoc
        // already names the case it was written for -- "a landing in water below the route, where
        // the fall is cancelled rather than ended". Vanilla cancels it in handleWaterMovement
        // (Entity:1111-1130), which sets inWater and zeroes fallDistance, so there is no damage and
        // no void: the player is in the same river the plan put them in, and SwimSteering's own
        // arrival and stall tests own what happens next. Failing the route here would report a
        // drowning-risk that vanilla has already removed, and the message would blame a bridge that
        // may not exist.
        if (m.kind() == Move.Kind.SWIM) {
            return null;
        }
        if (pos[1] < lowestY - FALL_SLACK) {
            // The comparison is between the client's own position copy and a height the PLAN named,
            // and the plan was searched against a world the server may revert -- so this sentence
            // carries both derivations at once: arithmetic on a live read, and a plan resting on
            // client prediction. DERIVED_FROM_READS is the weaker of the two, which is the correct
            // grade for a sentence a caller acts on as a whole.
            return ActOutcome.failed(String.format(
                    "fell out of the route: move %d of %d (%s) puts the player no lower than y=%.1f, "
                    + "but the player is at y=%.2f. This is terminal on purpose -- there is nothing "
                    + "under a bridge to climb back onto, and %d block(s) were already spent",
                    index + 1, moves.size(), m.kind(), lowestY, pos[1], blocksSpent),
                    ActOutcome.DERIVED_FROM_READS);
        }
        return null;
    }

    /** Set up the next move, or finish the route. */
    private ActOutcome startMove(ActActuator act) {
        if (index >= moves.size()) {
            // Every move in this route was verified against the world on the tick it was credited,
            // and this tick's `where(act)` is a live read. The claim is about this controller's own
            // accounting plus one position read -- derived, and honest.
            return finish(act, ActOutcome.done("route complete: " + moves.size() + " move(s), "
                    + blocksSpent + " block(s) spent, ending " + where(act),
                    ActOutcome.DERIVED_FROM_READS));
        }
        Move m = moves.get(index);
        moveTicks = 0;
        placeAttempts = 0;
        settleTicks = 0;
        // A verdict belongs to the move that earned it; carrying it into the next one would let a
        // later failure quote a wedge the body has already walked away from.
        steeringVerdict = null;
        // Cleared beside it for the same reason: a verdict belongs to the move that earned it. A
        // grade left behind after its message is gone is a grade the row cannot explain -- the
        // arrival failure would refuse for a reason no sentence on the slot names.
        steeringVerdictGrade = null;
        // Same rule, same place, and the ordering is load-bearing: the end-of-plan exit above runs
        // finish() and must still be able to see the LAST move's cost, so the clear goes after it
        // and not at the top of the method. With the clear here, the arrival a route ends on is
        // still reportable, and the placement ticks of the move after it are not carrying it.
        lastTactic = null;

        if (m.requiresPlacement()) {
            if (blocksSpent >= blockBudget) {
                // Two clauses with two grades, and the weaker one wins because a caller acts on the
                // sentence. "Out of blocks" is arithmetic over this controller's own two counters;
                // "the player is " + where(act) + " -- on a partial bridge, not at the goal" is a
                // position read this tick plus a conclusion about what standing there means. Both
                // are derivations, both are honest, and neither is a claim that the world was read
                // in front of the player.
                return finish(act, ActOutcome.failed("out of blocks after " + index + " of "
                        + moves.size() + " move(s): the route needs another placement and the budget "
                        + "of " + blockBudget + " is spent. The player is " + where(act)
                        + " -- on a partial bridge, not at the goal", ActOutcome.DERIVED_FROM_READS));
            }
            phase = Phase.PLACING;
            // Sampled before the first click, because the confirmation below compares against it
            // and a baseline taken after one attempt would already be the placed block.
            placeBaseline = act.blockAt(m.placeCell().x(), m.placeCell().y(), m.placeCell().z());
            // PLANNED_ON_CLIENT_WORLD and not READ_DIRECTLY, and this is the honest one: the cell
            // named in the sentence is a cell the PLAN chose, and the plan was searched against the
            // client's world copy (RoutePlanning.java:26-27 -- "a plan built on the client's belief
            // can be rubber-banded away"). Before this wave a caller could read that sentence and
            // not know the cell came from a prediction rather than a plan the server agreed to.
            return ActOutcome.running("placing the floor for move " + (index + 1) + " of "
                    + moves.size() + " at " + cellOf(m.placeCell()),
                    ActOutcome.PLANNED_ON_CLIENT_WORLD);
        }
        beginWalk(m);
        // Same derivation as the cell above, one clause further on: the destination is the plan's.
        return ActOutcome.running("walking move " + (index + 1) + " of " + moves.size() + " ("
                + m.kind() + ") toward " + cellOf(m.to()), ActOutcome.PLANNED_ON_CLIENT_WORLD);
    }

    /**
     * Place the block this move stands on, then CONFIRM it against the world.
     *
     * <p>The confirmation is the whole point. {@code rightClickBlock} returning true means the click
     * was issued, not that a block exists: the server refuses out-of-reach placements and returns the
     * item to the inventory, so a machine that trusted the return value would step onto nothing and
     * report a bridge it does not have. The same discipline that turned dig completion from "the
     * cell is empty" into "my block is gone" applies one question over -- and the question this had
     * wrong was worse than the dig's: this one asked {@code blockPresent}, which is
     * {@code getMaterial() != Material.air} and therefore TRUE for water, flowing water, lava,
     * gravel, tall grass and a torch. The planner picks exactly those cells for a bridge, because a
     * bridge is needed where there is no FLOOR and water, lava and grass are all floorless. So the
     * old check confirmed a bridge that was never built, debited a block that was still in the
     * inventory, and walked the player onto the water or lava it was supposed to have covered.
     *
     * <p>What is asked instead is {@link #placedSince}: the cell's block is no longer the one it
     * held before the click. See that method for what the test can and cannot separate.
     */
    private ActOutcome place(ActActuator act) {
        Move m = moves.get(index);
        Stance cell = m.placeCell();

        if (placedSince(act, cell)) {
            blocksSpent++;
            beginWalk(m);
            // Design 2.B #8, at the site that makes the claim. `placedSince` asks "is the cell no
            // longer what it held"; this sentence says "the floor is confirmed", which is a different
            // proposition, and the method's own javadoc names the residual -- fluid spreading into
            // the cell, or a falling block arriving, reads as a confirmation too. INFERRED with the
            // derivation named is the honest grade, and what the value buys a caller is the
            // distinction they could not previously draw: "floor confirmed" is a claim about a CELL
            // CHANGING, not a read of a floor existing.
            return ActOutcome.running("floor confirmed at " + cellOf(cell) + "; walking onto it",
                    ActOutcome.DERIVED_FROM_READS);
        }

        if (placeAttempts >= PLACE_RETRIES) {
            // Both causes are named and NEITHER is ranked. An earlier version said "most likely"
            // the reach -- and the first live run to hit this path had an empty inventory, so the
            // ranking pointed at the wrong one. This controller cannot tell them apart:
            // ActActuator exposes heldSlot() but not the stack, so it does not know whether
            // anything placeable is held. Guessing an order is the same defect as a deadline that
            // blamed the server for a pause the client caused -- a plausible cause, named
            // confidently, sending the reader to the wrong place.
            //
            // The grade is READ_CAME_BACK_EMPTY and it is the load-bearing claim of the whole wave
            // in miniature. `placedSince` asks whether the cell CHANGED, and it comes back false for
            // a cell that did not change AND for a cell this client cannot read -- `blockAt` returns
            // null for air, for an unloaded chunk and for a failed read alike. So "the block this
            // move needs never appeared" is not a statement about the world; it is a statement about
            // what a comparison over possibly-empty reads could distinguish, which is nothing
            // between those two causes. UNKNOWN says exactly that, and `mayActOn()` refuses it.
            return finish(act, ActOutcome.failed("the placement at " + cellOf(cell) + " was refused "
                    + placeAttempts + " times and the block this move needs never appeared. A "
                    + "refusal that repeats is not transient, and there are exactly two causes this "
                    + "controller cannot distinguish: either nothing placeable is in hand, or the "
                    + "cell is outside the server's 8-block reach from where the player actually is "
                    + "(" + where(act) + "). Check the held stack first, it is the cheaper of the "
                    + "two to rule out. No block was counted as spent",
                    ActOutcome.READ_CAME_BACK_EMPTY));
        }
        placeAttempts++;

        Aim aim = aimFor(act, cell);
        if (aim == null) {
            // Six `blockPresent` probes came back false, and `blockPresent` is
            // `getMaterial() != Material.air`, which is false for air AND for a cell nothing could
            // read. So "nothing solid next to it" is a statement about six empty answers, not about
            // the neighbourhood -- the same seam ambiguity as the branch above, and the same grade.
            return finish(act, ActOutcome.failed("nothing solid next to " + cellOf(cell)
                    + " to click against, so this placement cannot be aimed at all. The world does "
                    + "accept a floating block, but a player cannot conjure one -- rightClickBlock "
                    + "needs an existing block and a face", ActOutcome.READ_CAME_BACK_EMPTY));
        }
        // Click the EXISTING neighbour, with the face pointing at the cell to fill. Passing the
        // target cell itself is the mistake to avoid: vanilla's onPlayerRightClick takes the block
        // being clicked ON and puts the new block at pos.offset(face), so aiming at the empty cell
        // either gets refused or fills the cell beyond it. Air cannot be clicked.
        act.rightClickBlock(aim.support().x(), aim.support().y(), aim.support().z(), aim.face(),
                0.5D, 0.5D, 0.5D);
        // READ_DIRECTLY, and it is the one placement sentence that earns it: the sentence's claim is
        // about what this controller DID -- it clicked, at these coordinates, on this face, and the
        // click's return value is not being reported here -- and every one of those is a value read
        // this tick. Whether the SERVER honoured it is a different sentence, and this one does not
        // make it. The confirmation above is the sentence that does.
        return ActOutcome.running("placement attempt " + placeAttempts + " of " + PLACE_RETRIES
                + " for " + cellOf(cell) + ": clicking " + cellOf(aim.support()) + " face "
                + aim.face(), ActOutcome.READ_DIRECTLY);
    }

    /**
     * Whether the cell now holds a DIFFERENT block than the one it held before the first click.
     *
     * <p>{@code blockPresent} answers "is this air" -- {@code getMaterial() != Material.air} -- and
     * is true for water, flowing water, lava, gravel, tall grass and a torch: measured on a live
     * client, everything except air. As a confirmation it therefore reports a bridge that was never
     * built for every cell the planner deliberately picks, because a bridge is needed where there
     * is no FLOOR and water, lava and tall grass are all floorless -- and then it debits a block
     * that is still in the inventory and walks the player onto the water or lava it was supposed to
     * have covered. This is the same conflation {@code DigController.targetGone} ends for digging,
     * one question over: the question is about OUR block, not about occupancy.
     *
     * <p>The identity available on this seam is the block NAME, compared against a sample taken
     * before the first click. {@link ActActuator} exposes {@code blockAt} but not the held stack, so
     * "is this the item I am holding" is not askable -- and deriving floor-ness from a name would be
     * the block-name taxonomy this repo has already implemented six times and paid for. What is
     * askable, and what a successful placement produces, is that the cell changed: water stays
     * water, lava stays lava, and air stays air when the click was refused.
     *
     * <p><b>What it does not separate</b>, stated rather than papered over. The test is "the cell
     * changed", so anything else that changes the cell in the same window reads as a confirmation:
     * a placement that lands and is then replaced, and -- the reachable one -- fluid spreading into
     * the cell or a falling block arriving while the click was being refused. Nothing on this seam
     * can tell those from a placement (the actuator exposes the name, not the held stack, and not
     * solidity), and the alternative -- a hardcoded list of names that "do not count" -- is the
     * block-name taxonomy this repo has had to delete six times. The residual case is not a silent
     * one, which is what makes it survivable: the executor walks forward, the player ends up in the
     * water that arrived, and the fall guard and the arrival check both fail the route with the
     * position named. A cell that already held a block when the move started (a stale plan against
     * a world that moved on) can never read as confirmed, so the move fails after its retries
     * rather than debiting a block for a placement that did not happen -- which is the safe
     * direction, and the one the budget assertion in the tests pins.
     */
    private boolean placedSince(ActActuator act, Stance cell) {
        String now = act.blockAt(cell.x(), cell.y(), cell.z());
        return now != null && !now.equals(placeBaseline);
    }

    /**
     * Steer toward the current move's destination, and ask the world every tick whether the move
     * has landed.
     *
     * <p><b>Why the arrival question is asked on every walking tick.</b> It used to be asked once,
     * after the steering controller had stopped, which made the phase handover a per-BLOCK stop:
     * {@code walk()} published no axes on the tick it switched to {@link Phase#VERIFYING}, the
     * executor published none on the tick {@code verify()} nulled {@link #nav}, and
     * {@code startMove()} published none on the tick it built the next controller. Three dead ticks
     * at every single block boundary, on a 20-block straight line, is a body that stops and starts
     * twenty times -- the same metronome the per-move reaction delay was, one layer up, and the
     * reason a walk with the delay already removed still showed 21 separate runs of zero
     * displacement where a person shows one.
     *
     * <p>A person walking twenty metres does not halt at every metre to check where they are; the
     * check rides along with the walk. So it does here: the tick the body is in the destination cell
     * at the planned height with the world holding it up is the tick the move is credited AND the
     * next move's steering is installed and ticked, which means there is no tick in which this
     * class has nothing to publish between two moves. The C03 position series is a monotonic
     * staircase at the walking rate with a single plateau at the onset, not a staircase with a
     * three-tick flat on every step.
     *
     * <p><b>Nothing is replayed to fill a gap, on purpose.</b> The alternative to a shorter
     * handover is to keep the finished move's axes published across it, and that is worse than
     * either reading of "be short": the last axes of a walk are a forward press toward a cell the
     * move has just been credited for, so replaying them drives the body into the NEXT cell, and
     * the same argument is fatal for every other kind -- a {@link Move.Kind#DROP}'s last axes walk
     * off the far side of the landing, a {@link Move.Kind#CLIMB}'s push against the ladder, a
     * {@link Move.Kind#SWIM}'s into the river. That is why the comment this method used to carry
     * said the change "moved the endpoint of every route": it did, and not because holding a stale
     * axis is free. The fix is to make the handover cost no tick at all rather than to fill one.
     *
     * <p><b>Termination got faster, not slower, and the steering keeps every guard it had.</b>
     * {@link #tickSteering} runs the controller on every walking tick exactly as before, so the
     * steering's own stuck read, its budget and its hazard scan are untouched and still evaluated
     * on the same ticks. The arrival credit simply no longer waits for a second and third tick of
     * bookkeeping afterwards, and a give-up still goes through {@link Phase#VERIFYING} to be
     * reported with its verdict.
     */
    private ActOutcome walk(ActActuator act) {
        if (++moveTicks > MOVE_TICK_BUDGET) {
            // The sentence names a position read this tick and a destination the PLAN named, and
            // the verdict is a comparison between them. Derived, and the weaker of the two
            // derivations involved -- which is the correct grade for a whole sentence.
            return finish(act, ActOutcome.failed("move " + (index + 1) + " of " + moves.size()
                    + " did not arrive within " + MOVE_TICK_BUDGET + " ticks; the player is "
                    + where(act) + " and the destination was " + cellOf(moves.get(index).to()),
                    ActOutcome.DERIVED_FROM_READS));
        }
        ActOutcome navOutcome = tickSteering(act);
        Arrival landed = readArrival(act);
        if (landed != null && landed.holds()) {
            return credit(act, landed);
        }
        return navOutcome.terminal() ? stopSteering() : walking(navOutcome);
    }

    /**
     * Tick the current move's steering controller and keep what it said if it gave up.
     *
     * <p>Split out of {@link #walk} so the handover can tick the NEXT move's controller on the tick
     * the previous one is credited, with the give-up bookkeeping identical in both places. Runs on
     * every walking tick exactly as it always did, which is what keeps the steering's own stuck
     * read, its budget and its hazard scan on the same ticks they were on.
     */
    private ActOutcome tickSteering(ActActuator act) {
        ActOutcome navOutcome = nav.tick(act);
        if (navOutcome.terminal() && !navOutcome.ok()) {
            // Kept, not printed: the steering controller reads the world and this class does not
            // get to throw that away. A wedged body, a jammed stair and a wall the target is behind
            // all reach verify() as the same "did not arrive" arrival mismatch, and only the
            // steering's own words say which. The verdict is quoted into the failure in verify().
            steeringVerdict = navOutcome.message();
            // Latched with the message, and cleared with it in startMove. A verdict quoted into a
            // later sentence brings its grade along: this is the same rule that stops a hazard's
            // REUSED_CELL answer from being laundered into an OBSERVED one by the sentence that
            // renders it, and the arrival failure in verify() is exactly such a renderer.
            steeringVerdictGrade = navOutcome.belief();
        }
        return navOutcome;
    }

    /**
     * The report for a steering controller that is still working, with its hazard FORWARDED and its
     * grade FORWARDED.
     *
     * <p>Not dropped: this wraps the machine that does the scanning, so swallowing the field here
     * would make a routed walk the one walk whose line nobody can be warned about -- and a route is
     * precisely the caller that already knows this terrain is passable, so it is the one least
     * likely to be reading for it.
     *
     * <p><b>The grade is forwarded rather than restated, and that is the load-bearing choice.</b>
     * This method makes no claim of its own: the sentence it returns is the steering's sentence
     * behind a move counter, so the grade of the whole is the grade of the part that carries the
     * content. Inventing one here would be the exact defect this wave exists to fix -- an
     * {@code UNGRADED} that silently became {@code INFERRED} at a wrapper, with no read and no
     * derivation behind the upgrade. Forwarding also means the graded walk's REUSED_CELL and
     * READ_CAME_BACK_EMPTY answers survive the wrapper, which is the whole reason a routed walk can
     * now say its line's warning rests on an earlier sample.
     */
    private ActOutcome walking(ActOutcome navOutcome) {
        return ActOutcome.running("move " + (index + 1) + " of " + moves.size() + ": "
                + navOutcome.message(), navOutcome.hazard(), navOutcome.belief());
    }

    /**
     * The steering controller has stopped and this class does not yet believe the move landed.
     *
     * <p>Publishes nothing on purpose. The finished controller's axes are documented as meaningless
     * once terminal, and the reasoning for not replaying them is on {@link #walk}. Reached far less
     * often than it was, because {@link #walk} normally credits the move on the very tick the
     * steering declares arrival; still not removable, because it is the only thing standing between
     * "the steering gave up" and a route claiming a move it did not make.
     */
    private ActOutcome stopSteering() {
        phase = Phase.VERIFYING;
        // READ_DIRECTLY, and it is the right one for this sentence: "steering finished" is a fact
        // about this machine's own phase field, read on the tick it was written, and "verifying
        // arrival against the world" names an INTENTION rather than a result. Nothing is being
        // claimed about the world here at all, which is exactly what OBSERVED should mean at a site
        // that makes no world claim -- and it is why the arrival verdict below cannot borrow it.
        return ActOutcome.running("steering finished for move " + (index + 1)
                + "; verifying arrival against the world", ActOutcome.READ_DIRECTLY);
    }

    /**
     * Credit the move the world has confirmed, and start the next one on THIS tick.
     *
     * <p>The whole point of asking arrival every walking tick: the move is banked and the next
     * controller is installed and ticked inside the same tick, so the MOVE channel is never handed
     * over through a tick with nothing in it. When the next move needs a placement the axes really
     * are zero for a tick or more, and that is not bookkeeping -- the click is that move's first
     * action, and a player crossing a gap stops to place the block.
     *
     * <p>The finished controller is dropped before the next move is chosen, not after, so a move
     * that has to place cannot inherit the previous walk's forward axis while it clicks. That is
     * the one case where publishing the previous move's held axes would have been actively wrong,
     * and dropping first makes it impossible rather than merely unlikely.
     *
     * <p>No recursion back into {@link #walk}: the new move's first tick is steered here and its
     * own arrival is asked on its next tick like any other move's, so a plan whose two consecutive
     * moves name the same cell still spends a tick on each instead of being banked in one, and the
     * call depth is one.
     *
     * <p>Termination is unchanged by this: a route whose last move lands still finishes through
     * {@link #startMove}'s own end-of-plan exit, and the next move's budget starts at zero because
     * {@link #startMove} resets it, exactly as it did when the next move got a tick of its own.
     */
    private ActOutcome credit(ActActuator act, Arrival landed) {
        settleTicks = 0;
        index++;
        dropSteering();
        ActOutcome setup = startMove(act);
        if (setup.terminal()) {
            return setup;
        }
        // The prefix this opens is a claim ABOUT A VERIFICATION, so it must not be graded more
        // weakly than the outcome it is glued to -- a caller reading the whole sentence acts on both
        // halves. So the prefix's own derivation (a live `where(act)` read plus the plan's cell) sets
        // the floor, and the steering's grade is forwarded DOWN from there when it is weaker: an
        // UNKNOWN or a REUSED_CELL answer from below must not be upgraded by a wrapper that added a
        // prefix in front of it.
        String verified = "move " + index + " of " + moves.size() + " verified at " + where(act)
                + " (" + cellOf(landed.move().to()) + "); ";
        if (phase != Phase.WALKING) {
            // startMove() either finishes, or asks for a placement. Any other phase would be one it
            // can only reach by way of WALKING, which is the branch below.
            return ActOutcome.running(verified + setup.message(),
                    weaker(ActOutcome.DERIVED_FROM_READS, setup.belief()));
        }
        ActOutcome next = tickSteering(act);
        return next.terminal()
                ? ActOutcome.running(verified + stopSteering().message(),
                        weaker(ActOutcome.DERIVED_FROM_READS, next.belief()))
                : ActOutcome.running(verified + walking(next).message(), next.hazard(),
                        weaker(ActOutcome.DERIVED_FROM_READS, next.belief()));
    }

    /**
     * The arrival question, asked of the world. One rule, one place, asked on every walking tick.
     *
     * @param act the actuator to read the position and the support facts from
     * @return the four facts and whether they add up to an arrival, or null if the position is
     *         unreadable, which is a fact about the seam rather than about the world
     */
    private Arrival readArrival(ActActuator act) {
        double[] pos = act.position();
        if (pos == null) {
            return null;
        }
        Move m = moves.get(index);
        // Arrival is proximity to the target CENTRE, not equality of floored block coordinates.
        //
        // Measured on a live client: the player stopped at x=63.94 heading for the centre of block
        // 64 (x=64.5). NavController correctly reported arrival -- 0.56 is inside its 0.6-block
        // tolerance -- while floor(63.94) is 63, so a block-equality check called it a failure and
        // the route died on its first move. The equality test was stricter than the steering can
        // deliver, which makes it wrong rather than strict: it demanded a guarantee no component in
        // the chain offers.
        //
        // The tolerance is deliberately a shade wider than NavController's so that a move the
        // steering considers finished is never rejected by an epsilon this class chose.
        //
        // It is still a fact about the WORLD and not about the steering: the position is read from
        // the actuator, the feet must be in the block the route named, and the world must be holding
        // the player up. The predicate this replaces asked whether ANY block sat under the feet, and
        // presence is neither a floor nor the right cell: a player standing one block below the
        // destination, in a dip with a floor under it, read as "supported" and was credited with an
        // arrival the plan never described -- and that predicate is true for water, a torch or tall
        // grass under the feet as well. The height and the grounding are the two facts that make the
        // claim true, and verify()'s message names which one is missing rather than merging them.
        double dx = (m.to().x() + 0.5D) - pos[0];
        double dz = (m.to().z() + 0.5D) - pos[2];
        double offBy = Math.sqrt(dx * dx + dz * dz);
        // The XZ twin of the height test below, and the check that was missing.
        //
        // The tolerance answers "how close to the centre", which is a question about a POINT; the
        // feet are in a CELL, and a body 0.28 from a stance's centre can be standing a clear cell
        // away from it. Those are different questions and only one of them was being asked, so
        // every move could be credited up to half a cell early and the shortfall was carried
        // against the NEXT move's geometry -- which is how a walk that never left its own
        // starting neighbourhood reported every one of its moves as landed.
        //
        // The measurement that settles it: move 2 of a route was credited at z=-4.24, which is
        // 0.28 from the centre of the stance it claimed to have reached and comfortably inside a
        // 0.7 tolerance, while the feet were still in cell z=-4. The body box spans
        // z [-4.54,-3.94], which overlaps the log at (5,64,-4); clearing it needs z <= -4.3.
        // 0.06 blocks further south. That 0.06 is the whole bug in one number, and it is why a
        // tolerance is not the right instrument: no width of it can distinguish "a body in the
        // cell, a third of a block off the centre" from "a body in the cell next door".
        //
        // It is the same question `inOwnCell` below already answers on the Y axis for CLIMB and
        // SWIM, asked on the two axes where a plan's stance is expressed in blocks. Adding it here
        // WITHOUT adding it to the three steering controllers would have been worse than the
        // defect it fixes: `NavController.toStance` stops on `dist <= 0.6` plus a Y-only cell
        // test, so it hands this method a body in the previous cell, and a stricter verifier over
        // a premature stopmer is a route that dies where it used to (wrongly) succeed. All three
        // now ask the XZ twin as well.
        boolean feetInOwnCell = (int) Math.floor(pos[0]) == m.to().x()
                && (int) Math.floor(pos[2]) == m.to().z();
        // A BAND, not an equality, and the width of the band is the whole point.
        //
        // `Stance.isStandable` asks whether the cell below has a collision box, which a bottom
        // slab, a stair or a ladder does -- so a route onto one of those is legitimately planned
        // with the body occupying cell `y`, while the world rests the feet at the PARTIAL
        // block's top surface instead: 63.5 for a slab, wherever the stair's step is. An integer
        // equality therefore rejected exactly the moves the planner had just approved, and the
        // first version of this fix did that -- a second copy of a rule the steering already
        // imposes, which made the plan layer emit moves its own executor could never credit.
        //
        // The width is the tallest partial top vanilla produces inside one cell: a bottom slab
        // sits 0.5 above the cell floor, and every other non-full block in 1.8.9 is at or below
        // that. 0.55 admits it with room to spare and still refuses a WHOLE block of error,
        // which is the failure this check exists for: a player a full block down in a dip with a
        // floor under it is not where the route said, and `onGround()` being true does not make
        // it so.
        // The height band is per-kind too, for the same reason the support test below is. The 0.55
        // slack exists to admit a bottom SLAB -- a partial block the world rests the feet on half a
        // block into the cell the plan named -- and neither a ladder nor a river ever produces one.
        // For those two the question is simply whether the feet are in the destination's own cell,
        // and a band wider than a cell would accept a player a whole block away.
        boolean inOwnCell = pos[1] >= m.to().y() && pos[1] < m.to().y() + 1.0D;
        boolean belowPlanned = pos[1] < m.to().y();
        boolean atPlannedHeight = switch (m.kind()) {
            case CLIMB, SWIM -> inOwnCell;
            default -> !belowPlanned
                    ? pos[1] < m.to().y() + 1.0D
                    : pos[1] >= m.to().y() - ARRIVE_HEIGHT_SLACK;
        };
        // What counts as "the world is holding this player up" depends on the move, and the
        // difference is the whole reason CLIMB and SWIM are not walks. A ladder holds a player
        // without ever setting onGround (EntityLivingBase.moveEntityWithHeading:1637-1662 clamps
        // the body and zeroes fallDistance instead), and water is not a floor at all
        // (Entity.handleWaterMovement:1111-1130 is what holds a swimmer). Asking onGround() for
        // either would make the move unfinishable, and -- worse -- asking it for a WALK while
        // special-casing the others would let a player standing in a river be reported as arrived
        // on dry land.
        boolean supported = switch (m.kind()) {
            case CLIMB -> act.onClimbable();
            case SWIM -> act.inWater();
            default -> act.onGround();
        };
        return new Arrival(m, offBy, feetInOwnCell, atPlannedHeight, supported);
    }

    /**
     * The four facts {@link #readArrival} reads, kept together so the rule has one answer and
     * verify()'s report can name which of the four was missing.
     */
    private record Arrival(Move move, double offBy, boolean feetInOwnCell,
                           boolean atPlannedHeight, boolean supported) {
        /** The same conjunction the failure branch tests, in one place so the two cannot drift. */
        boolean holds() {
            return offBy <= ARRIVE_TOLERANCE && feetInOwnCell && atPlannedHeight && supported;
        }
    }

    /**
     * The steering stopped and the world has not confirmed the step: settle a landing, or fail.
     *
     * <p>Arrival is a position question and it is asked of the actuator, not inferred from the fact
     * that the steering controller stopped. Those are different facts, and this repo has paid for
     * conflating exactly that kind of pair more than once.
     *
     * <p><b>Reached far less often than it used to be, and that is the point rather than a
     * regression.</b> {@link #walk} asks the same question -- {@link #readArrival}, the same rule --
     * on every walking tick and hands a landed move straight to its successor, so the ordinary
     * move never arrives here at all. What is left is the case the phase exists for: the steering
     * stopped and the world disagrees, which is a settle-a-landing or name-the-mismatch decision
     * rather than a step of every route. The predicate below is the same one, so the disagreement
     * is settled on the same evidence it always was.
     */
    private ActOutcome verify(ActActuator act) {
        Arrival arrival = readArrival(act);
        if (arrival == null) {
            // Design 2.C #17, the second of its two sites. Same argument as checkNotFalling's and the
            // same grade: the position did not arrive, so nothing was derived from it, and the only
            // honest description of "arrival could not be verified" is a statement about this client.
            return finish(act, ActOutcome.failed("arrival could not be verified: the player position "
                    + "is unreadable, and a route that reports progress on an unread position is "
                    + "reporting something it never observed", ActOutcome.READ_CAME_BACK_EMPTY));
        }
        Move m = arrival.move();
        double[] pos = act.position();
        double offBy = arrival.offBy();
        boolean feetInOwnCell = arrival.feetInOwnCell();
        boolean atPlannedHeight = arrival.atPlannedHeight();
        boolean supported = arrival.supported();
        // One failure, one message, all three facts. It used to be split so the landing wait could
        // have its own branch, and the split dropped the support clause whenever the height clause
        // was the one that fired -- so a climb refused for a missing ladder reported only the
        // height and named nothing the caller could act on. The wait is a precondition on the
        // ORIGINAL condition, not a replacement for it.
        if (!arrival.holds()) {
            // Everything the plan asked for is true -- the body is over the destination cell and at
            // its height -- and the one thing missing is that the world has not caught it yet. For
            // a DROP that is the ordinary last tick of the descent, not a defect, so it is waited
            // out rather than reported. See SETTLE_TICKS for why the wait is bounded.
            if (!supported && feetInOwnCell && atPlannedHeight
                    && stillLanding(act, pos) && settleTicks++ < SETTLE_TICKS) {
                // Two live reads -- `fallDistance` and the position -- against the plan's cell, and
                // the sentence concludes "the world has not caught it yet". That conclusion IS the
                // derivation: `fallDistance` is positive while falling and zeroed on landing, on a
                // ladder's clamp and in water, so "still falling" is read off it rather than
                // observed. Derived, and `stillLanding`'s own javadoc names the derivation.
                return ActOutcome.running("move " + (index + 1) + " of " + moves.size()
                        + ": the body is over (" + m.to().x() + "," + m.to().y() + "," + m.to().z()
                        + ") at the planned height but is still falling (" + settleTicks + "/"
                        + SETTLE_TICKS + " ticks spent), so the world has not caught it yet",
                        ActOutcome.DERIVED_FROM_READS);
            }
            // The verdict clause is last because it is the only part that is sometimes absent, and
            // a reader who stops at the arrival mismatch is reading a true sentence and the wrong
            // one. What stopped the body is the steering's report; the arrival mismatch is only how
            // far short of the cell it ended.
            //
            // The grade is the WEAKER of this class's own derivation and the quoted verdict's. This
            // sentence quotes an earlier tick's sentence -- quoting is how a stale grade enters a
            // fresh row -- so a quoted UNKNOWN ("no block could be read inside the body box") has to
            // keep refusing after it is quoted. Without this the row would read DERIVED_FROM_READS
            // while carrying a cause this class never established.
            return finish(act, ActOutcome.failed(String.format(
                    "move %d of %d ended %.2f blocks from the centre of (%d,%d,%d)%s%s%s. The "
                            + "steering stopped, which is a fact about the steering; arriving is a "
                            + "fact about the world, and it did not happen%s",
                    index + 1, moves.size(), offBy, m.to().x(), m.to().y(), m.to().z(),
                    feetInOwnCell
                            ? ""
                            : String.format(Locale.ROOT,
                                    ", with the feet in cell (%d,%d) rather than in the "
                                            + "destination's own cell (%d,%d)",
                                    (int) Math.floor(pos[0]), (int) Math.floor(pos[2]),
                                    m.to().x(), m.to().z()),
                    atPlannedHeight
                            ? ""
                            : String.format(Locale.ROOT,
                                    ", with the feet at y=%.2f rather than in cell y=%d",
                                    pos[1], m.to().y()),
                    supported ? "" : unsupportedBecause(m),
                    steeringVerdict == null ? ""
                            : " -- and the steering's own verdict was: " + steeringVerdict),
                    weaker(ActOutcome.DERIVED_FROM_READS, steeringVerdictGrade)));
        }
        settleTicks = 0;
        index++;
        dropSteering();
        phase = Phase.CHECKING;
        // The arrival test IS the claim here, and it was this tick's `readArrival` over four live
        // facts compared against the plan's cell -- derived, with nothing stale in it. This is the
        // sentence a routed walk's "verified" actually rests on.
        return ActOutcome.running("move " + index + " of " + moves.size() + " verified at "
                + where(act), ActOutcome.DERIVED_FROM_READS);
    }

    /**
     * The weaker of two grades, and the ordering that makes "weaker" mean anything.
     *
     * <p>{@code strength} is written out rather than {@code ordinal()}, for the reason
     * {@code DigController.withoutUpgrade} writes it out: reordering the enum constants would
     * silently reverse the rule. UNKNOWN is weakest because it is the one that refuses; OBSERVED is
     * strongest because it is the only one with no derivation at all.
     *
     * <p>A null second argument means "there is no second claim", not "there is a weaker claim" --
     * which is why the common case, a sentence with nothing quoted into it, needs no branch at the
     * call site. A null FIRST argument would be a caller's own bug: this class only ever passes a
     * constant.
     */
    private static net.marcloud.mcp.core.util.Belief weaker(
            net.marcloud.mcp.core.util.Belief a, net.marcloud.mcp.core.util.Belief b) {
        if (b == null) {
            return a;
        }
        return strength(b) < strength(a) ? b : a;
    }

    private static int strength(net.marcloud.mcp.core.util.Belief b) {
        if (b == net.marcloud.mcp.core.util.Belief.UNKNOWN) {
            return 0;
        }
        if (b == net.marcloud.mcp.core.util.Belief.INFERRED) {
            return 1;
        }
        return 2;
    }

    /**
     * Is this body on its way down INTO the cell it was just credited as reaching?
     *
     * <p>Two conditions and both are needed. {@code fallDistance} is the descent itself -- vanilla
     * maintains it locally ({@code Entity.updateFallState:1052-1055}) and zeroes it on the landing
     * tick, so it is positive exactly while the body is falling and never while it is standing,
     * climbing (the clamp at {@code EntityLivingBase:1642}) or swimming
     * ({@code Entity.handleWaterMovement}). The height term excludes a body that is falling but has
     * already dropped BELOW the destination, which is a fall out of the route rather than a landing:
     * {@link #checkNotFalling} owns that case and this must not pre-empt it with a tidier message.
     */
    private boolean stillLanding(ActActuator act, double[] pos) {
        return act.fallDistance() > 0.0D && pos[1] >= moves.get(index).to().y();
    }

    /**
     * Why the world is not holding this player up, in the words that fit the move.
     *
     * <p>"nothing holding the player up" is true and useless for a climb: the player IS held up,
     * just not by anything underneath, and a caller reading that would go looking for a hole in the
     * ground under a ladder. Each kind gets the fact that is actually missing, which is the whole
     * reason the arrival test is per-kind rather than a single boolean.
     */
    private static String unsupportedBecause(Move m) {
        return switch (m.kind()) {
            case CLIMB -> ", and the player is no longer on a ladder or vine, so nothing is holding "
                    + "them up";
            case SWIM -> ", and the player is no longer in water, so nothing is holding them up";
            default -> ", and nothing holding the player up";
        };
    }

    private void beginWalk(Move m) {
        // Aim at the block CENTRE. Targeting the corner leaves the player straddling two cells, and
        // the arrival check floors the position -- so it would verify against the wrong block.
        //
        // The steering gets a STRICTLY SHORTER deadline than the move, and that gap is load-bearing:
        // with the two equal, the outer budget fired first and reported "did not arrive within N
        // ticks" for a steering controller that had already given up -- so the arrival check was
        // unreachable whenever steering timed out, and the message named the wrong cause. Measured:
        // a stuck player ended at tick 62 on the move timeout, never entering VERIFYING at all. The
        // outer budget is meant to be a backstop for a steering controller that hangs, not the
        // primary deadline.
        //
        // toStance, not the point constructor: a route's destination is a block the FEET end in, so
        // the steering may use its Y -- to hold arrival until the body is in that block rather than
        // merely above it (a drop passes its destination while still in the air), and to ask for a
        // jump when the block is exactly one above the stance the player is standing on, which 0.6
        // of step height cannot walk.
        nav = steeringFor(m);
        phase = Phase.WALKING;
    }

    /**
     * The steering controller this move's kind calls for.
     *
     * <p>Three cases, and the split is forced by what holds the player up rather than chosen for
     * tidiness. {@link Move.Kind#WALK}, {@link Move.Kind#STEP_UP}, {@link Move.Kind#DROP} and
     * {@link Move.Kind#BRIDGE} all end with the player standing on something, which is the one
     * arrival question {@link NavController} already answers honestly. {@link Move.Kind#CLIMB} ends
     * with the player on a ladder, which never sets {@code onGround}, and {@link Move.Kind#SWIM}
     * ends with the player in water, which is not a floor at all -- a walker handed either move
     * would steer correctly and then never be able to declare arrival, and the route would die on
     * its first ladder with a message blaming geometry.
     *
     * <p>Direction is read off the move's own two stances rather than passed in. A second source
     * of truth about which way a move goes is a second thing that can be wrong, and these two
     * stances are what the planner wrote and what {@link #verify} checks the world against.
     */
    private LocomotionController steeringFor(Move m) {
        double x = m.to().x() + 0.5D;
        double y = m.to().y();
        double z = m.to().z() + 0.5D;
        boolean rising = m.to().y() > m.from().y();
        return switch (m.kind()) {
            case CLIMB -> new ClimbSteering(x, y, z, rising, NAV_TICK_BUDGET);
            case SWIM -> new SwimSteering(x, y, z, rising, NAV_TICK_BUDGET);
            default -> NavController.toStance(x, y, z, NAV_TICK_BUDGET, standable);
        };
    }

    /**
     * Which existing block to click, and on which face, to fill {@code cell}.
     *
     * @param support the block that already exists and will be clicked
     * @param face    the face of {@code support} pointing at {@code cell}
     */
    record Aim(Stance support, ActActuator.Face face) { }

    /**
     * Find an existing neighbour of {@code cell} and the face of it that points at {@code cell}.
     *
     * <p>Returns the SUPPORT block, not the cell: the direction of that relationship is the bug this
     * method exists to make impossible to get wrong at the call site. A block placed against the
     * west neighbour appears to its EAST, so the face named here is the one facing our cell -- and
     * {@code FakeActuator} models exactly that offset, which is how the inverted version was caught.
     *
     * <p>Horizontal neighbours are tried before the one below because during a bridge chain the cell
     * under the target is the void being crossed; the block that actually carries the chain is the
     * one the player is standing on, beside it.
     */
    static Aim aimFor(ActActuator act, Stance cell) {
        if (act.blockPresent(cell.x() - 1, cell.y(), cell.z())) {
            return new Aim(new Stance(cell.x() - 1, cell.y(), cell.z()), ActActuator.Face.EAST);
        }
        if (act.blockPresent(cell.x() + 1, cell.y(), cell.z())) {
            return new Aim(new Stance(cell.x() + 1, cell.y(), cell.z()), ActActuator.Face.WEST);
        }
        if (act.blockPresent(cell.x(), cell.y(), cell.z() - 1)) {
            return new Aim(new Stance(cell.x(), cell.y(), cell.z() - 1), ActActuator.Face.SOUTH);
        }
        if (act.blockPresent(cell.x(), cell.y(), cell.z() + 1)) {
            return new Aim(new Stance(cell.x(), cell.y(), cell.z() + 1), ActActuator.Face.NORTH);
        }
        if (act.blockPresent(cell.x(), cell.y() - 1, cell.z())) {
            return new Aim(new Stance(cell.x(), cell.y() - 1, cell.z()), ActActuator.Face.UP);
        }
        if (act.blockPresent(cell.x(), cell.y() + 1, cell.z())) {
            return new Aim(new Stance(cell.x(), cell.y() + 1, cell.z()), ActActuator.Face.DOWN);
        }
        return null;
    }

    /**
     * The single terminal funnel.
     *
     * <p>Every terminal exit goes through here, for the reason {@code CraftController}'s javadoc
     * gives about its own: abandoning midway leaves state the player cannot recover from. Here that
     * means a player still being steered, possibly standing on half a bridge -- so dropping the
     * steering and releasing the use key is part of the outcome rather than tidying afterwards.
     */
    private ActOutcome finish(ActActuator act, ActOutcome outcome) {
        finished = true;
        phase = Phase.DONE;
        // Dropping the reference IS the release: forward()/strafe() read through it, so a null nav
        // stops the MOVE channel on the same tick. There was a nav.requestCancel() here and it was
        // removed rather than kept -- cancelling an object discarded on the next line cannot be
        // observed, and mutation confirmed it: deleting the call changed nothing anywhere. A line
        // that looks protective and is not is worse than no line, because the next reader trusts it.
        //
        // The capture is inside dropSteering() and therefore happens BEFORE the null, on the tick
        // the caller reads tactic() on. That ordering is the whole defect: this method is called
        // from every terminal exit, it returns to MoveApplier, and the applier's next act is to ask
        // this object for the terminal tactic. Nulling first and asking afterwards is how every
        // routed record came to say NOTHING with no lane.
        dropSteering();
        try {
            act.releaseUseKey();
        } catch (Throwable ignored) {
            // Cleanup must not replace the real outcome with a cleanup failure: the caller needs to
            // know why the route ended, and "release failed" would bury it.
        }
        return outcome;
    }

    /**
     * The one place a steering controller is discarded, and therefore the one place its last
     * decision is latched into {@link #lastTactic}.
     *
     * <p><b>Why a funnel and not a capture repeated at each call site.</b> There are three places
     * that used to say {@code nav = null} -- {@link #finish}, {@link #credit} and {@link #verify} --
     * and the capture is worth nothing at any of them unless it happens BEFORE the assignment. That
     * is a two-line ordering that a later edit can reverse silently: a reader who sees
     * {@code nav = null} in a new early return has no way to know the field is now load-bearing.
     * With the funnel, "nav is dropped" has exactly one spelling, so the capture cannot be missed at
     * the next exit added -- which is the failure mode this whole defect is: a correct value, wired
     * up, tested through the wrong seam, and unreachable in production.
     *
     * <p><b>Re-wrapped rather than passed through, and the reason is the axes.</b> The keys are up
     * by the time any reader sees this, so the latched value carries {@link MoveTactic#hold}'s zeros
     * with the machine's own cost and lane -- never its forward axis, which by then describes a tick
     * that has ended. A machine that gave up has already produced exactly this shape from its own
     * {@code stop()}, so the latch is a value equal to what that walk published rather than a new
     * one; a machine that was cancelled mid-lane has not, and {@code hold(givenUp, lane)} is the
     * only record that can say "this is the road it died on" with no key claims attached.
     *
     * <p><b>A machine that has not chosen yet has no tactic, and that is not an error.</b>
     * {@link LocomotionController#tactic()} is null until the machine has run one tick, and the
     * guards at the top of {@link #tick} can end the route before the first one: the fall check
     * fires on the tick a move's machine was installed, so a route that falls on its very first
     * walking tick releases a machine that has published nothing at all. Reading through that null
     * is how this method threw on the two fall-guard tests, and the fix is the honest one rather
     * than a default: nothing was chosen, so nothing is latched and {@link #tactic()} keeps
     * answering {@link MoveTactic#hold} with {@code NOTHING}, which is true -- no keys went down,
     * because the body never got a tick of steering.
     *
     * <p><b>No machine is also not an error.</b> Every non-walking phase runs with none, and a route
     * that never installed one has spent nothing and owes no capture; leaving {@link #lastTactic}
     * alone in that case is what keeps a placement from being reported as a sacrifice.
     */
    private void dropSteering() {
        MoveTactic spent = nav == null ? null : nav.tactic();
        if (spent != null) {
            lastTactic = MoveTactic.hold(spent.givenUp(), spent.lane());
        }
        nav = null;
    }

    private static String cellOf(Stance s) {
        return s == null ? "(none)" : "(" + s.x() + "," + s.y() + "," + s.z() + ")";
    }

    private static String where(ActActuator act) {
        double[] p = act.position();
        return p == null ? "at an unreadable position"
                : String.format("at (%.2f,%.2f,%.2f)", p[0], p[1], p[2]);
    }
}
