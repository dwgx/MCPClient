package net.marcloud.mcp.core.drivers.plan;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.NavController;

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
 *       the item, so counting queued clicks reports a bridge that was reverted.</li>
 *   <li>Running out of blocks mid-route is FAILED <i>and names where the player is standing</i>. The
 *       caller is somewhere it did not plan to stop and needs to know where.</li>
 *   <li>Falling is a terminal failure that names the reason. Reporting COMPLETE while the player is
 *       in free fall is the worst available lie here.</li>
 * </ul>
 *
 * <p>All terminal exits funnel through {@link #finish}, for {@code CraftController}'s reason: an
 * abandoned run leaves the player standing on half a bridge over a drop, so releasing keys and
 * dropping the steering is not optional cleanup, it is part of the outcome.
 */
public final class RouteExecutor
        implements net.marcloud.mcp.core.drivers.act.LocomotionController {

    /** Where the machine is. Public so a status tool can report it without inference. */
    public enum Phase {
        /** Validating the plan against the world before touching anything. */
        CHECKING,
        /** Placing the block this move needs before the step is possible. */
        PLACING,
        /** Steering toward the destination of the current move. */
        WALKING,
        /** Asking the world whether the step actually landed. */
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
     * <p>Wider than {@code NavController.ARRIVE_EPSILON} (0.6) on purpose, and the gap is the whole
     * point: the steering decides it has arrived at 0.6, so anything tighter here rejects moves the
     * steering considers complete and the route dies on its first step. Measured live -- a player
     * stopped 0.56 blocks from centre, which is arrival to the steering and a different BLOCK to a
     * floored-coordinate test.
     *
     * <p>Kept small enough that error cannot accumulate into a wrong plan: each move re-targets an
     * absolute block centre rather than an offset from wherever the last one ended, so a 0.7-block
     * shortfall is corrected by the next move instead of being carried forward.
     */
    public static final double ARRIVE_TOLERANCE = 0.7D;

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
     * How far below the route the player may be before it is called a fall.
     *
     * <p>One block of slack absorbs the normal case: a step down inside a move, and the momentary dip
     * a jump's descent produces. Past that the player is somewhere the plan did not put it, and no
     * amount of further ticking recovers the route.
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
    private boolean cancelRequested;
    private boolean finished;
    private NavController nav;

    public RouteExecutor(Planner.Plan plan, int blockBudget) {
        this.moves = plan == null ? List.of() : List.copyOf(plan.moves());
        this.blockBudget = Math.max(0, blockBudget);
    }

    /** Forward axis for the MOVE channel, in the same units {@code NavController} produces. */
    public float forward() {
        return nav == null ? 0f : nav.forward();
    }

    /** Strafe axis for the MOVE channel. */
    public float strafe() {
        return nav == null ? 0f : nav.strafe();
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
                    + "state must not be re-driven, because its cleanup has already run");
        }
        totalTicks++;

        if (cancelRequested) {
            return finish(act, ActOutcome.cancelled("route cancelled after " + index + " of "
                    + moves.size() + " move(s), " + blocksSpent + " block(s) spent; the player was "
                    + "released " + where(act)));
        }
        if (!act.inWorld()) {
            return finish(act, ActOutcome.failed("the world went away mid-route after " + index
                    + " of " + moves.size() + " move(s)"));
        }
        if (moves.isEmpty()) {
            return finish(act, ActOutcome.done("nothing to do: the plan was empty, so the player is "
                    + "already where it asked to be"));
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
            case DONE -> ActOutcome.failed("unreachable: DONE is terminal and finished was false");
        };
    }

    /**
     * Has the player left the route downward?
     *
     * <p>Compared against the CURRENT move's own height rather than a fixed floor, because a route
     * may legitimately descend. Returning a terminal failure here rather than trying to recover is
     * deliberate: once the player is off a bridge there is nothing under it to walk back onto, and a
     * machine that kept ticking would spend its budget steering in mid-air and then report "stuck",
     * naming the wrong cause.
     */
    private ActOutcome checkNotFalling(ActActuator act) {
        if (index >= moves.size()) {
            return null;
        }
        double[] pos = act.position();
        if (pos == null) {
            return ActOutcome.failed("the player position could not be read, so whether the route is "
                    + "still being followed is unknown -- reporting progress on an unread position "
                    + "would be inventing it");
        }
        double expectedY = moves.get(index).from().y();
        if (pos[1] < expectedY - FALL_SLACK) {
            return ActOutcome.failed(String.format(
                    "fell out of the route: expected to be near y=%.1f for move %d of %d but the "
                    + "player is at y=%.2f. This is terminal on purpose -- there is nothing under a "
                    + "bridge to climb back onto, and %d block(s) were already spent",
                    expectedY, index + 1, moves.size(), pos[1], blocksSpent));
        }
        return null;
    }

    /** Set up the next move, or finish the route. */
    private ActOutcome startMove(ActActuator act) {
        if (index >= moves.size()) {
            return finish(act, ActOutcome.done("route complete: " + moves.size() + " move(s), "
                    + blocksSpent + " block(s) spent, ending " + where(act)));
        }
        Move m = moves.get(index);
        moveTicks = 0;
        placeAttempts = 0;

        if (m.requiresPlacement()) {
            if (blocksSpent >= blockBudget) {
                // The caller is standing somewhere it did not plan to stop, and its next decision
                // depends on knowing where. "Out of blocks" alone sends it looking at its inventory
                // when the urgent fact is that it is on a partial bridge.
                return finish(act, ActOutcome.failed("out of blocks after " + index + " of "
                        + moves.size() + " move(s): the route needs another placement and the budget "
                        + "of " + blockBudget + " is spent. The player is " + where(act)
                        + " -- on a partial bridge, not at the goal"));
            }
            phase = Phase.PLACING;
            return ActOutcome.running("placing the floor for move " + (index + 1) + " of "
                    + moves.size() + " at " + cellOf(m.placeCell()));
        }
        beginWalk(m);
        return ActOutcome.running("walking move " + (index + 1) + " of " + moves.size() + " ("
                + m.kind() + ") toward " + cellOf(m.to()));
    }

    /**
     * Place the block this move stands on, then CONFIRM it against the world.
     *
     * <p>The confirmation is the whole point. {@code rightClickBlock} returning true means the click
     * was issued, not that a block exists: the server refuses out-of-reach placements and returns the
     * item to the inventory, so a machine that trusted the return value would step onto nothing and
     * report a bridge it does not have. Asking {@code blockPresent} afterwards is the same discipline
     * that turned dig completion from "the cell is empty" into "my block is gone".
     */
    private ActOutcome place(ActActuator act) {
        Move m = moves.get(index);
        Stance cell = m.placeCell();

        if (act.blockPresent(cell.x(), cell.y(), cell.z())) {
            blocksSpent++;
            beginWalk(m);
            return ActOutcome.running("floor confirmed at " + cellOf(cell) + "; walking onto it");
        }

        if (placeAttempts >= PLACE_RETRIES) {
            // Both causes are named and NEITHER is ranked. An earlier version said "most likely"
            // the reach -- and the first live run to hit this path had an empty inventory, so the
            // ranking pointed at the wrong one. This controller cannot tell them apart:
            // ActActuator exposes heldSlot() but not the stack, so it does not know whether
            // anything placeable is held. Guessing an order is the same defect as a deadline that
            // blamed the server for a pause the client caused -- a plausible cause, named
            // confidently, sending the reader to the wrong place.
            return finish(act, ActOutcome.failed("the placement at " + cellOf(cell) + " was refused "
                    + placeAttempts + " times and the block never appeared. A refusal that repeats "
                    + "is not transient, and there are exactly two causes this controller cannot "
                    + "distinguish: either nothing placeable is in hand, or the cell is outside the "
                    + "server's 8-block reach from where the player actually is (" + where(act)
                    + "). Check the held stack first, it is the cheaper of the two to rule out. No "
                    + "block was counted as spent"));
        }
        placeAttempts++;

        Aim aim = aimFor(act, cell);
        if (aim == null) {
            return finish(act, ActOutcome.failed("nothing solid next to " + cellOf(cell)
                    + " to click against, so this placement cannot be aimed at all. The world does "
                    + "accept a floating block, but a player cannot conjure one -- rightClickBlock "
                    + "needs an existing block and a face"));
        }
        // Click the EXISTING neighbour, with the face pointing at the cell to fill. Passing the
        // target cell itself is the mistake to avoid: vanilla's onPlayerRightClick takes the block
        // being clicked ON and puts the new block at pos.offset(face), so aiming at the empty cell
        // either gets refused or fills the cell beyond it. Air cannot be clicked.
        act.rightClickBlock(aim.support().x(), aim.support().y(), aim.support().z(), aim.face(),
                0.5D, 0.5D, 0.5D);
        return ActOutcome.running("placement attempt " + placeAttempts + " of " + PLACE_RETRIES
                + " for " + cellOf(cell) + ": clicking " + cellOf(aim.support()) + " face "
                + aim.face());
    }

    /** Steer toward the current move's destination, delegating to {@link NavController}. */
    private ActOutcome walk(ActActuator act) {
        if (++moveTicks > MOVE_TICK_BUDGET) {
            return finish(act, ActOutcome.failed("move " + (index + 1) + " of " + moves.size()
                    + " did not arrive within " + MOVE_TICK_BUDGET + " ticks; the player is "
                    + where(act) + " and the destination was " + cellOf(moves.get(index).to())));
        }
        ActOutcome navOutcome = nav.tick(act);
        if (!navOutcome.terminal()) {
            return ActOutcome.running("move " + (index + 1) + " of " + moves.size() + ": "
                    + navOutcome.message());
        }
        // Even a nav COMPLETE is not taken as arrival: verify against the world instead. A steering
        // controller reports that it stopped steering, which is a fact about itself.
        phase = Phase.VERIFYING;
        return ActOutcome.running("steering finished for move " + (index + 1)
                + "; verifying arrival against the world");
    }

    /**
     * Ask the world whether the step landed, then advance.
     *
     * <p>Arrival is a position question and it is asked of the actuator, not inferred from the fact
     * that the steering controller stopped. Those are different facts, and this repo has paid for
     * conflating exactly that kind of pair more than once.
     */
    private ActOutcome verify(ActActuator act) {
        Move m = moves.get(index);
        double[] pos = act.position();
        if (pos == null) {
            return finish(act, ActOutcome.failed("arrival could not be verified: the player position "
                    + "is unreadable, and a route that reports progress on an unread position is "
                    + "reporting something it never observed"));
        }
        // Arrival is proximity to the target CENTRE, not equality of floored block coordinates.
        //
        // Measured on a live client: the player stopped at x=63.94 heading for the centre of block
        // 64 (x=64.5). NavController correctly reported arrival -- 0.56 is inside its 0.6-block
        // tolerance -- while floor(63.94) is 63, so a block-equality check called it a failure and
        // the route died on its first move. The equality test was stricter than the steering can
        // deliver, which makes it wrong rather than strict: it demanded a guarantee no component in
        // the chain offers.
        //
        // This is still a fact about the WORLD and not about the steering: the position is read from
        // the actuator and the floor beneath it is confirmed. What changed is the tolerance, and it
        // is deliberately a shade wider than NavController's so that a move the steering considers
        // finished is never rejected by an epsilon this class chose.
        int by = (int) Math.floor(pos[1]);
        double dx = (m.to().x() + 0.5D) - pos[0];
        double dz = (m.to().z() + 0.5D) - pos[2];
        double offBy = Math.sqrt(dx * dx + dz * dz);
        boolean supported = act.blockPresent(
                (int) Math.floor(pos[0]), by - 1, (int) Math.floor(pos[2]));
        if (offBy > ARRIVE_TOLERANCE || !supported) {
            return finish(act, ActOutcome.failed(String.format(
                    "move %d of %d ended %.2f blocks from the centre of (%d,%d,%d)%s. The steering "
                    + "stopped, which is a fact about the steering; arriving is a fact about the "
                    + "world, and it did not happen",
                    index + 1, moves.size(), offBy, m.to().x(), m.to().y(), m.to().z(),
                    supported ? "" : " and there is no floor under the player")));
        }
        index++;
        nav = null;
        phase = Phase.CHECKING;
        return ActOutcome.running("move " + index + " of " + moves.size() + " verified at "
                + where(act));
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
        nav = new NavController(m.to().x() + 0.5D, m.to().y(), m.to().z() + 0.5D, NAV_TICK_BUDGET);
        phase = Phase.WALKING;
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
        nav = null;
        try {
            act.releaseUseKey();
        } catch (Throwable ignored) {
            // Cleanup must not replace the real outcome with a cleanup failure: the caller needs to
            // know why the route ended, and "release failed" would bury it.
        }
        return outcome;
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
