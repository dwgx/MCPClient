package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.BodySim;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import org.junit.Test;

/**
 * What a ROUTE owes its caller when the body cannot walk the move: it finishes, or it says what is
 * in the way.
 *
 * <p><b>The defect this file exists for is a report, not a stall.</b> {@code NavController} used to
 * end a wedged walk with "stuck against a wall for 8 ticks, 3.20 blocks short of the target", and
 * {@code RouteExecutor} used to read that as a steering controller that finished, run its own
 * arrival check, and report <i>that</i> -- "ended 1.40 blocks from the centre of (5,64,-5)". Both
 * sentences are true. Neither names the log at (5,64,-4), which is the entire fact a caller needs,
 * and a caller holding one of them goes looking for a route problem. There was no route problem:
 * the route was fine and the body could not fit.
 *
 * <p>So the steering's own verdict is now carried into the route's failure rather than dropped on
 * the floor between the two classes. These tests drive a body that actually walks
 * ({@link BodySim}) through a world that refuses the step, because the only way to produce the state
 * that matters is to produce it.
 */
public class AWedgedRouteFinishesOrNamesTheBlockTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /** Open ground three cells wide either side of the lane the body walks down. */
    private static FakeActuator flatCorridor() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.setRotation(0f, 0f);   // facing +Z, so a log on this line is genuinely in the way
        return act;
    }

    /** Drive the executor with a body that walks, until the route ends or the ceiling is hit. */
    private static ActOutcome drive(RouteExecutor ex, FakeActuator act, int maxTicks) {
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = ex.tick(act);
            if (last.terminal()) {
                return last;
            }
            BodySim.step(act, ex.forward(), ex.strafe());
        }
        return last;
    }

    private static Planner.Plan planOf(Move... moves) {
        return new Planner.Plan(List.of(moves), null, 0);
    }

    /**
     * The whole point of the change: a route whose body wedges on a log finishes the move.
     *
     * <p>A log sits in the lane one block into a two-block walk. The body walks into it, stops, and
     * has to get around it to reach cell (2,64,0) -- there is no line to cell 2 that does not cross
     * x=1. Without a recovery this route reported a failure and the body stood at x=0.7 for the
     * rest of the walk; with one it walks past and the route completes.
     *
     * <p>Asserted on the route's own completion and on the body's position, because "did not fail"
     * is also what a controller that stopped politely would produce.
     */
    @Test
    public void aRouteWhoseBodyWedgesOnALogStillFinishesTheMove() {
        FakeActuator act = flatCorridor();
        act.putBlock(1, FEET, 0, "log");
        Move walk = Move.walk(new Stance(0, FEET, 0), new Stance(2, FEET, 0));

        RouteExecutor ex = new RouteExecutor(planOf(walk), 64,
                BodySim.blockView(act));
        ActOutcome out = drive(ex, act, 200);

        assertTrue("the route must complete rather than stop short of a log it can walk around: "
                + out.message(), out.terminal() && out.ok());
        assertEquals("and the move must be counted", 1, ex.movesDone());
        assertEquals("with the body in the cell the PLAN named, not merely nearer it",
                2, (int) Math.floor(act.position()[0]));
    }

    /**
     * When it cannot be recovered, the route says where the body is and what is in the way.
     *
     * <p>Boxed in: a log ahead and blocks on both sides, so there is no cell to step to and the
     * two-argument executor -- the one built without a world view -- has nothing to ask. What it
     * still has is the world's answer about the obstruction, and the route's failure must carry it.
     *
     * <p>This is the sentence a caller acts on: clear (1,64,0) and the same move works. The
     * sentence it used to get was "ended 3.20 blocks from the centre of (3,64,0)", from which the
     * only conclusion is that the route is wrong.
     */
    @Test
    public void aRouteThatCannotRecoverNamesThePositionAndTheBlockingBlock() {
        FakeActuator act = flatCorridor();
        act.putBlock(1, FEET, 0, "log");
        act.putBlock(0, FEET, 1, "stone");
        act.putBlock(0, FEET, -1, "stone");
        Move walk = Move.walk(new Stance(0, FEET, 0), new Stance(3, FEET, 0));

        RouteExecutor ex = new RouteExecutor(planOf(walk), 64);
        ActOutcome out = drive(ex, act, 200);

        assertTrue("it must end", out.terminal());
        assertFalse("and it must not claim the route it did not walk: " + out.message(), out.ok());
        assertEquals("no move may be credited", 0, ex.movesDone());
        assertTrue("the failure must name the body's position: " + out.message(),
                out.message().contains("wedged at ("));
        assertTrue("and the blocking block and its cell -- the one fact a caller can act on, "
                + "because clearing (1,64,0) makes this exact move work: " + out.message(),
                out.message().contains("log") && out.message().contains("(1,64,0)"));
        assertTrue("and it must say the steering's verdict rather than only its own arrival "
                + "mismatch, because '3.20 blocks from the centre' is true of a route that is "
                + "fine: " + out.message(),
                out.message().contains("the steering's own verdict was"));
    }

    /**
     * A route that does not wedge is charged nothing for the recovery being there.
     *
     * <p>Stated at the route level because that is where the cost would be paid: a recovery that
     * ran a probe or a tick of its own on every move of a long walk would be a capability tax on
     * every successful route, which the Owner's standing constraint forbids. Nothing here is
     * charged, and the reason is structural -- the branch that can spend a recovery tick is guarded
     * on eight consecutive no-progress ticks while in contact, and a body moving 0.2 blocks a tick
     * never satisfies it.
     */
    @Test
    public void aRouteWithNothingToRecoverFromIsUnaffected() {
        FakeActuator act = flatCorridor();
        List<Move> moves = List.of(
                Move.walk(new Stance(0, FEET, 0), new Stance(1, FEET, 0)),
                Move.walk(new Stance(1, FEET, 0), new Stance(2, FEET, 0)),
                Move.walk(new Stance(2, FEET, 0), new Stance(3, FEET, 0)));

        RouteExecutor ex = new RouteExecutor(planOf(moves.toArray(new Move[0])), 64,
                BodySim.blockView(act));
        ActOutcome out = drive(ex, act, 300);

        assertTrue("premise: the route must succeed, or the ticks below are a failed route's: "
                + out.message(), out.terminal() && out.ok());
        assertEquals("all three moves verified", 3, ex.movesDone());
        assertEquals("and the body ended where the plan said", 3, (int) Math.floor(act.position()[0]));
    }
}