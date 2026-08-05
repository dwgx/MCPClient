package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import org.junit.Test;

/**
 * The four things {@link RouteExecutor} must report honestly, each driven at the point where the
 * dishonest version would look identical from outside.
 *
 * <p>Every assertion here exists because a plausible implementation passes without it. Counting
 * issued placements instead of confirming blocks, taking the steering controller's COMPLETE as
 * arrival, reporting "out of blocks" without saying where the player is left, or ticking on after a
 * fall -- all four produce a green run against a naive test and a player stranded over a void against
 * a real client. That gap is what this file is for.
 *
 * <p>{@code FakeActuator} is shared from the act package rather than re-implemented: a second fake
 * forks the way the probe socket client forked, where a framing fix landed in one copy and the bug
 * lived on in the other for a whole round.
 */
public class RouteExecutorReportsWhatTheWorldSaysTest {

    /** Flat ground at y=63 so stances sit at y=64, matching the planner tests. */
    private static FakeActuator groundedAt(int x, int y, int z) {
        FakeActuator act = new FakeActuator();
        for (int gx = x - 6; gx <= x + 12; gx++) {
            for (int gz = z - 6; gz <= z + 6; gz++) {
                act.putBlock(gx, y - 1, gz);
            }
        }
        act.setPosition(x + 0.5D, y, z + 0.5D);
        act.onGround = true;
        return act;
    }

    private static Planner.Plan planOf(Move... moves) {
        return new Planner.Plan(List.of(moves), null, 0);
    }

    /** Drive until terminal or the tick ceiling, teleporting the player as the moves complete. */
    private static ActOutcome driveWithPerfectMovement(RouteExecutor ex, FakeActuator act,
                                                      List<Move> moves, int maxTicks) {
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = ex.tick(act);
            if (last.terminal()) {
                return last;
            }
            // Stand in for movement: whenever the executor is steering, put the player where that
            // move ends. This is NOT the executor arriving on its own -- it isolates the reporting
            // rules under test from NavController's steering, which has its own tests and its own
            // real-client verification.
            if (ex.phase() == RouteExecutor.Phase.WALKING && ex.movesDone() < moves.size()) {
                Stance to = moves.get(ex.movesDone()).to();
                act.setPosition(to.x() + 0.5D, to.y(), to.z() + 0.5D);
            }
        }
        return last;
    }

    @Test
    public void anEmptyPlanCompletesWithoutTouchingAnything() {
        FakeActuator act = groundedAt(0, 64, 0);
        RouteExecutor ex = new RouteExecutor(planOf(), 64);

        ActOutcome out = ex.tick(act);
        assertTrue("an empty plan is already satisfied", out.terminal() && out.ok());
        assertEquals("and it must not have clicked anything", 0, act.rightClickCalls);
    }

    @Test
    public void aWalkOnlyRouteSpendsNoBlocks() {
        FakeActuator act = groundedAt(0, 64, 0);
        List<Move> moves = List.of(
                Move.walk(new Stance(0, 64, 0), new Stance(1, 64, 0)),
                Move.walk(new Stance(1, 64, 0), new Stance(2, 64, 0)));
        RouteExecutor ex = new RouteExecutor(planOf(moves.toArray(new Move[0])), 64);

        ActOutcome out = driveWithPerfectMovement(ex, act, moves, 200);
        assertTrue("walking existing ground must succeed: " + out.message(),
                out.terminal() && out.ok());
        assertEquals("and place nothing", 0, ex.blocksSpent());
        assertEquals("both moves are verified", 2, ex.movesDone());
    }

    /**
     * Rule 1 and rule 2 together: a refused placement is not progress, however the click reported.
     *
     * <p>{@code rightClickResult} is true here and {@code rightClickPlacesBlock} is false, which is
     * precisely the live failure being modelled: the client issues the click, the server refuses it
     * out of reach and returns the item, and no block exists. An executor that trusted the return
     * value would step forward onto nothing and report a bridge.
     */
    @Test
    public void aClickThatIssuedButPlacedNothingIsNotABridge() {
        FakeActuator act = groundedAt(0, 64, 0);
        act.removeBlock(1, 63, 0);
        act.rightClickResult = true;
        act.rightClickPlacesBlock = false;

        Move bridge = Move.bridge(new Stance(0, 64, 0), new Stance(1, 64, 0), new Stance(1, 63, 0));
        RouteExecutor ex = new RouteExecutor(planOf(bridge), 64);

        ActOutcome out = driveWithPerfectMovement(ex, act, List.of(bridge), 200);

        assertTrue("it must end", out.terminal());
        assertFalse("and it must FAIL: the click succeeded and the block does not exist, which is "
                + "exactly what an out-of-reach refusal looks like from the client's side. Reporting "
                + "success here claims a bridge the server already declined to build: " + out.message(),
                out.ok());
        assertEquals("no block may be counted for a placement that never landed", 0, ex.blocksSpent());
        assertTrue("and it must have actually retried rather than giving up on the first refusal",
                act.rightClickCalls >= RouteExecutor.PLACE_RETRIES);
        assertTrue("the message must name BOTH causes it cannot distinguish -- nothing in hand, or "
                + "out of reach. Ranking them was wrong: the first live run to hit this path had an "
                + "empty inventory while the message pointed at the reach: " + out.message(),
                out.message().contains("nothing placeable is in hand")
                        && out.message().contains("reach"));
        assertFalse("and it must not rank them, because it cannot tell: ActActuator exposes "
                + "heldSlot() but not the stack: " + out.message(),
                out.message().contains("most likely"));
    }

    @Test
    public void aConfirmedPlacementIsWalkedOntoAndCounted() {
        FakeActuator act = groundedAt(0, 64, 0);
        act.removeBlock(1, 63, 0);
        act.rightClickPlacesBlock = true;

        Move bridge = Move.bridge(new Stance(0, 64, 0), new Stance(1, 64, 0), new Stance(1, 63, 0));
        RouteExecutor ex = new RouteExecutor(planOf(bridge), 64);

        ActOutcome out = driveWithPerfectMovement(ex, act, List.of(bridge), 200);

        assertTrue("a placement that really landed must let the route continue: " + out.message(),
                out.terminal() && out.ok());
        assertEquals("and it costs exactly one block", 1, ex.blocksSpent());
        assertTrue("the block must exist in the world afterwards", act.blockPresent(1, 63, 0));
    }

    /**
     * Rule 3: out of blocks names WHERE, not just what.
     *
     * <p>The caller is standing on a partial bridge. "Out of blocks" alone sends it to check its
     * inventory when the urgent fact is its position -- the same defect class as a deadline that
     * blamed the server for a pause the client caused.
     */
    @Test
    public void runningOutOfBlocksFailsAndSaysWhereThePlayerIsLeft() {
        FakeActuator act = groundedAt(0, 64, 0);
        act.removeBlock(1, 63, 0);
        act.removeBlock(2, 63, 0);
        act.rightClickPlacesBlock = true;

        List<Move> moves = List.of(
                Move.bridge(new Stance(0, 64, 0), new Stance(1, 64, 0), new Stance(1, 63, 0)),
                Move.bridge(new Stance(1, 64, 0), new Stance(2, 64, 0), new Stance(2, 63, 0)));
        RouteExecutor ex = new RouteExecutor(planOf(moves.toArray(new Move[0])), 1);

        ActOutcome out = driveWithPerfectMovement(ex, act, moves, 200);

        assertFalse("one block cannot pay for two bridges", out.ok());
        assertEquals("the first bridge was paid for and must be counted", 1, ex.blocksSpent());
        assertEquals("and exactly one move completed", 1, ex.movesDone());
        assertTrue("the failure must name the budget: " + out.message(),
                out.message().contains("block"));
        assertTrue("and it must say where the player is standing, because it is on a partial bridge "
                + "and its caller's next decision depends on that: " + out.message(),
                out.message().contains("at ("));
    }

    /**
     * Rule 4: falling is terminal and named.
     *
     * <p>Reporting COMPLETE while the player is in free fall is the worst lie available here, and a
     * machine that merely kept ticking would spend its budget steering in mid-air and then blame the
     * timeout -- naming the wrong cause, which is its own defect.
     */
    @Test
    public void fallingOffTheRouteIsATerminalFailureThatNamesTheFall() {
        FakeActuator act = groundedAt(0, 64, 0);
        List<Move> moves = List.of(
                Move.walk(new Stance(0, 64, 0), new Stance(1, 64, 0)),
                Move.walk(new Stance(1, 64, 0), new Stance(2, 64, 0)));
        RouteExecutor ex = new RouteExecutor(planOf(moves.toArray(new Move[0])), 64);

        assertFalse("first tick just starts the route", ex.tick(act).terminal());
        act.setPosition(1.5D, 55.0D, 0.5D); // the floor was mined out from under it
        ActOutcome out = ex.tick(act);

        assertTrue("a fall must end the route immediately", out.terminal());
        assertFalse("and it must not be reported as success", out.ok());
        assertEquals("the phase must be FAILED, not CANCELLED: nobody asked for this",
                ActPhase.FAILED, out.state());
        assertTrue("and the message must say it fell, with the height, because 'stuck' or 'timeout' "
                + "would point the caller at the wrong thing entirely: " + out.message(),
                out.message().contains("fell"));
    }

    /**
     * A stuck player must end, and must not be credited with the move.
     *
     * <p>This assertion is kept but it is NOT the arrival check -- measured, the stuck player ends on
     * the move-tick backstop at tick 62 and never enters VERIFYING. The first version of this test
     * was named for arrival and asserted the timeout, which is precisely the hollow shape this repo
     * keeps finding: a name describing a path the test does not take. The arrival check is driven
     * separately below.
     */
    @Test
    public void aStuckPlayerEndsOnTheBackstopWithoutBeingCreditedTheMove() {
        FakeActuator act = groundedAt(0, 64, 0);
        Move walk = Move.walk(new Stance(0, 64, 0), new Stance(1, 64, 0));
        RouteExecutor ex = new RouteExecutor(planOf(walk), 64);

        ActOutcome out = null;
        for (int i = 0; i < 400 && (out == null || !out.terminal()); i++) {
            out = ex.tick(act); // never reposition: the player is stuck
        }

        assertTrue("it must end rather than tick forever", out.terminal());
        assertFalse("a player that never moved has not arrived: " + out.message(), out.ok());
        assertEquals("and no move may be counted", 0, ex.movesDone());
        // The message must be the ARRIVAL mismatch, not the backstop timeout, and the difference is
        // what proves the steering deadline is strictly shorter than the move budget. With the two
        // equal, this same run reported "did not arrive within 60 ticks" -- true but useless: it
        // named a hang when the steering had already given up, and the arrival check never ran.
        assertTrue("a steering controller that gave up must surface as an off-plan POSITION, not as "
                + "a hang: " + out.message(), out.message().contains("the plan required"));
        assertFalse("and it must NOT be the backstop message: reaching that means the outer budget "
                + "masked the steering's own verdict again: " + out.message(),
                out.message().contains("did not arrive within"));
        assertTrue("it must also end well before the backstop, or the gap is nominal: ticks="
                + ex.ticks(), ex.ticks() < RouteExecutor.MOVE_TICK_BUDGET);
    }

    /**
     * Rule 1 for movement: arrival is a fact about the WORLD, not about the steering stopping.
     *
     * <p>Driven by letting the steering genuinely COMPLETE -- the player is moved to within
     * NavController's own arrival epsilon -- while still being in the WRONG BLOCK. That combination
     * is reachable on a real client, because the steering's tolerance is 0.6 blocks and the plan's
     * unit is a whole block, so "the steering is satisfied" and "the player is on the planned cell"
     * are different statements. An executor that took the nav COMPLETE as arrival would count a move
     * onto a block the player is not standing on, and every later move would be planned from a
     * position it does not occupy.
     */
    @Test
    public void arrivalIsAskedOfTheWorldEvenWhenTheSteeringSaysItFinished() {
        FakeActuator act = groundedAt(0, 64, 0);
        Move walk = Move.walk(new Stance(0, 64, 0), new Stance(2, 64, 0));
        RouteExecutor ex = new RouteExecutor(planOf(walk), 64);

        ActOutcome out = null;
        boolean nudged = false;
        for (int i = 0; i < 400 && (out == null || !out.terminal()); i++) {
            out = ex.tick(act);
            if (!nudged && ex.phase() == RouteExecutor.Phase.WALKING) {
                // Just inside the steering's tolerance of the target centre (2.5), but still in
                // block x=1 rather than the planned x=2.
                act.setPosition(1.95D, 64.0D, 0.5D);
                nudged = true;
            }
        }

        assertTrue("it must reach a terminal state", out.terminal());
        assertFalse("the player is in block 1 and the plan required block 2, so this is not an "
                + "arrival however satisfied the steering was: " + out.message(), out.ok());
        assertEquals("and the move must not be counted", 0, ex.movesDone());
        assertTrue("the message must contrast where it ended with where the plan required, because "
                + "that is what tells the caller its position is off-plan rather than merely slow: "
                + out.message(),
                out.message().contains("the plan required"));
    }

    @Test
    public void cancelReleasesThePlayerAndReportsCancelledNotFailed() {
        FakeActuator act = groundedAt(0, 64, 0);
        Move walk = Move.walk(new Stance(0, 64, 0), new Stance(1, 64, 0));
        RouteExecutor ex = new RouteExecutor(planOf(walk), 64);

        ex.tick(act);
        ex.requestCancel();
        ActOutcome out = ex.tick(act);

        assertTrue("cancel takes effect on the next tick", out.terminal());
        assertEquals("a cancelled route is not a failed one -- the caller asked for this, and "
                + "conflating them makes act_status unable to tell a bug from an instruction",
                ActPhase.CANCELLED, out.state());
        assertEquals("and steering must stop, or the player keeps walking on a dead route",
                0f, ex.forward(), 0.0001f);
        assertTrue("the use key must be released on the way out: " + act.calls,
                act.calls.stream().anyMatch(c -> c.startsWith("releaseUseKey")));
    }

    @Test
    public void aFinishedRouteRefusesToBeDrivenAgain() {
        FakeActuator act = groundedAt(0, 64, 0);
        RouteExecutor ex = new RouteExecutor(planOf(), 64);
        assertTrue("first tick finishes an empty plan", ex.tick(act).terminal());

        ActOutcome again = ex.tick(act);
        assertFalse("re-driving a terminal controller must not report success: its cleanup already "
                + "ran, so anything it did now would be on a player it has released", again.ok());
    }

    /**
     * Regression for the inverted aim, which the first version of this executor got wrong.
     *
     * <p>It passed the cell to FILL as the clicked position. Vanilla's {@code onPlayerRightClick}
     * takes the block being clicked ON and puts the new block at {@code pos.offset(face)}, so aiming
     * at the empty cell either gets refused outright or fills the cell beyond it. Air cannot be
     * clicked.
     *
     * <p>Pinned directly rather than left to the end-to-end tests because the end-to-end symptom was
     * "the placement was refused three times", which reads as a reach problem and sends the next
     * reader to the wrong file entirely.
     */
    @Test
    public void theAimClicksTheExistingBlockWithTheFacePointingAtTheCellToFill() {
        FakeActuator act = new FakeActuator();
        act.putBlock(0, 63, 0); // the only solid block: west of the cell we want to fill

        RouteExecutor.Aim aim = RouteExecutor.aimFor(act, new Stance(1, 63, 0));

        assertEquals("the clicked block must be the one that EXISTS, never the empty target",
                new Stance(0, 63, 0), aim.support());
        assertEquals("and the face must point from that block toward the cell: a block placed "
                + "against the west neighbour appears to its EAST",
                net.marcloud.mcp.core.drivers.act.ActActuator.Face.EAST, aim.face());
    }

    @Test
    public void theAimPrefersASideBlockOverTheOneBelowBecauseABridgeSpansAVoid() {
        FakeActuator act = new FakeActuator();
        act.putBlock(1, 62, 0); // below the target
        act.putBlock(0, 63, 0); // beside it: the block the player stands on mid-chain

        RouteExecutor.Aim aim = RouteExecutor.aimFor(act, new Stance(1, 63, 0));

        assertEquals("during a bridge chain the cell under the target is the void being crossed, so "
                + "the side block is the one that carries the chain", new Stance(0, 63, 0),
                aim.support());
    }

    @Test
    public void noSolidNeighbourMeansNoAimAtAll() {
        FakeActuator act = new FakeActuator();
        assertEquals("a floating cell is legal for the WORLD but unaimable for a player, and "
                + "returning some face anyway would make the executor click empty space and blame "
                + "the reach", null, RouteExecutor.aimFor(act, new Stance(1, 63, 0)));
    }



    @Test
    public void steeringIsZeroBeforeTheFirstMoveBegins() {
        RouteExecutor ex = new RouteExecutor(
                planOf(Move.walk(new Stance(0, 64, 0), new Stance(1, 64, 0))), 64);
        assertEquals("an executor that has not ticked must not be pushing the player anywhere -- "
                + "MoveApplier reads these every tick", 0f, ex.forward(), 0.0001f);
        assertEquals(0f, ex.strafe(), 0.0001f);
    }
}
