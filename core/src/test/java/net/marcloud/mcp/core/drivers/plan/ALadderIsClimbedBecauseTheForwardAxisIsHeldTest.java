package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import org.junit.Test;

/**
 * A ladder column is routed as CLIMB moves and climbed by holding forward, end to end.
 *
 * <p><b>The defect this pins.</b> Four move kinds and none of them is vertical. {@code Stance} could
 * not even NAME a ladder cell, because a ladder is solid to a collision query and clear to a body
 * at the same time -- and a planner that reports "no route" for a mineshaft ladder is making a
 * claim about the world it has not earned. Adding the word is only half of it: a plan-time kind the
 * executor cannot perform is worse than no kind, because the plan now promises something the
 * executor will refuse. So this file drives both halves -- the planner must emit CLIMB, and the
 * executor must actually climb -- and the fake below is built so that it cannot pass on the second
 * without the real thing happening.
 *
 * <p><b>Why the fake is deliberately unable to cheat.</b> The physics modelled here is vanilla's,
 * not the controller's opinion of it: the body rises only on a tick where the FORWARD axis the
 * applier published was non-zero ({@code EntityLivingBase.moveEntityWithHeading:1659-1662} raises
 * the body when the player is on a ladder and pressed into it), and it stops rising the moment the
 * axis is released. The axis is read back from {@link ActRuntime}, which is where vanilla's own
 * {@code MovementInput} reads it, so this is the published value rather than a private field. A
 * controller that published no forward would leave the player on the bottom rung forever.
 *
 * <p><b>Why {@code onGround} is false throughout.</b> A ladder never sets it. The climb arrival
 * test therefore cannot reuse the walker's "the world is holding the player up from below", which is
 * the specific reason {@code CLIMB} exists as a kind and not as a mode of the walker.
 */
public class ALadderIsClimbedBecauseTheForwardAxisIsHeldTest {

    /** Flat ground at y=63, so a stance is the cell at y=64. */
    private static final int GROUND = 63;

    /**
     * A five-rung ladder column at x=0, z=0, standing on that ground, with a ledge at the top the
     * player can step off onto -- a real mineshaft, not a ladder floating in the sky.
     */
    private static FakeWorld shaft() {
        FakeWorld w = new FakeWorld(0).floor(-3, 6, GROUND, -3, 3);
        w.ladderColumn(0, 64, 68, 0);
        // The top rung is at y=68, and there is a solid block beside it at (1,68,0) whose own floor
        // is (1,67,0): that is what a player steps onto when they come off the top of a ladder.
        w.solid(1, 67, 0);
        return w;
    }

    @Test
    public void thePlannerRoutesALadderColumnAsClimbsAndNotAsJumps() {
        Planner.Plan plan = new Planner(shaft()).plan(new Stance(0, 64, 0), new Stance(0, 67, 0));

        assertTrue("three blocks up a ladder is a route a player can walk: " + plan.failure(),
                plan.found());
        assertEquals("and it is three moves, one per block", 3, plan.moves().size());
        for (int i = 0; i < plan.moves().size(); i++) {
            Move m = plan.moves().get(i);
            assertEquals("move " + (i + 1) + " must be the climb the terrain calls for. A STEP_UP here "
                            + "is the specific lie this file exists to rule out: vanilla only jumps "
                            + "from the ground (EntityLivingBase:2018-2022) and a player on a ladder "
                            + "is not on the ground, so the executor would refuse to jump and the "
                            + "route would die naming geometry that is not the problem",
                    Move.Kind.CLIMB, m.kind());
            assertEquals("and each move is straight up the same column", 1,
                    m.to().y() - m.from().y());
        }
    }

    @Test
    public void aClimbIsCompletedBecauseTheForwardAxisIsPublished() {
        Planner.Plan plan = new Planner(shaft()).plan(new Stance(0, 64, 0), new Stance(0, 66, 0));
        assertTrue(plan.found());

        FakeActuator act = onALadder(0, 64, 0);
        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(plan, ri.blockBudget());
            return machine[0];
        });

        SlotRecord rec = SlotRecord.submitted(new RouteIntent(0, 66, 0, 0), 0L, 1L, "submitted");

        boolean sawForward = false;
        int guard = 0;
        while (!rec.phase().isTerminal() && guard++ < 200) {
            rec = applier.apply(rec.stampTick(guard));
            boolean forwardAxis = runtime.moveForward() != 0f;
            sawForward |= forwardAxis;
            ladderPhysics(act, forwardAxis);
        }

        assertTrue("the forward axis must have been published. A climb IS holding forward into the "
                + "ladder (EntityLivingBase:1659-1662), and this fake rises on nothing else, so "
                + "without the axis the run below ends with the player on the bottom rung", sawForward);
        assertEquals("and the route must finish: " + rec.message(), ActPhase.COMPLETE, rec.phase());
        assertEquals("with both climbs credited", 2, machine[0].movesDone());
        assertEquals("and the player two blocks up the ladder", 66.0D, act.position()[1], 0.0001D);
    }

    @Test
    public void aClimbIsRefusedWhenThePlayerIsNotOnALadder() {
        // The world says there is a ladder here; the PLAYER is standing in the open next to it. A
        // climb that trusted the plan instead of the player would report arrival for a position the
        // player was never in.
        Move climb = Move.climb(new Stance(0, 64, 0), new Stance(0, 65, 0));
        FakeActuator act = new FakeActuator();
        act.setPosition(0.5D, 64.0D, 0.5D);
        act.onGround = true;
        act.onClimbable = false;   // not on the ladder, however the plan was built
        RouteExecutor ex = new RouteExecutor(
                new Planner.Plan(List.of(climb), null, 0), 0);

        net.marcloud.mcp.core.drivers.act.ActOutcome out = null;
        for (int i = 0; i < 40 && (out == null || !out.terminal()); i++) {
            out = ex.tick(act);
        }
        assertNotNull("the route must end", out);
        assertTrue("and it must FAIL: the player is not on a ladder, so nothing can be climbed: "
                + out.message(), out.terminal() && !out.ok());
        assertTrue("and the message must say the ladder is missing, because 'stuck' would send the "
                + "caller looking at the wrong thing entirely: " + out.message(),
                out.message().contains("ladder"));
    }

    /**
     * The ladder has to be REACHABLE from the ground beside it, not only from inside it.
     *
     * <p>This is the test that catches a defect the other three could not. An earlier version of
     * {@code NeighborGen.addWalk} refused any destination whose cell held a ladder, on the reasoning
     * that a body in a ladder cell is climbed into rather than walked to. That is false: walking
     * into the bottom of a ladder column is precisely how a player gets onto a ladder, and that cell
     * is genuinely standable because there is ground under it. The guard made every ladder
     * reachable only by a caller who happened to start the route already standing inside one, and
     * the tests above never saw it because they all start at {@code (0,64,0)} -- inside the column.
     *
     * <p>So this one starts on the bank at {@code (1,64,0)} and has to walk in before it can climb.
     * The plan it produces is therefore a WALK followed by CLIMBs, and a planner that refuses the
     * entry edge returns "no route" or routes the long way round.
     */
    @Test
    public void aLadderIsReachedByWalkingIntoItFromTheBank() {
        Planner.Plan plan = new Planner(shaft()).plan(new Stance(1, 64, 0), new Stance(0, 66, 0));

        assertTrue("a ladder on a wall is a thing a player walks up to: " + plan.failure(),
                plan.found());
        assertEquals("the first move is the step into the column", Move.Kind.WALK,
                plan.moves().get(0).kind());
        assertEquals("and it lands in the ladder's own cell -- a player stands IN a ladder cell, "
                + "which is what EntityLivingBase.isOnLadder:1136-1138 reads",
                new Stance(0, 64, 0), plan.moves().get(0).to());
        for (int i = 1; i < plan.moves().size(); i++) {
            assertEquals("and everything after it is a climb", Move.Kind.CLIMB,
                    plan.moves().get(i).kind());
        }
    }

    /**
     * A ladder cell one block up is not something a player JUMPS onto, and the planner must not
     * offer it. This is the edge the two ladder guards actually protect -- {@code addStepUp}
     * produces {@code from.offset(dx, 1, dz)}, a CARDINAL neighbour one block up, never straight up
     * -- so it is invisible to a fixture that only ever climbs the column itself, which is why this
     * geometry is a separate case rather than an extra assertion on the climb above.
     *
     * <p>Without the refusal the planner emits a {@code STEP_UP} into the ladder cell at
     * {@code (0,65,0)} from the stance at {@code (-1,64,0)}, and the executor would drive a jump
     * that vanilla only takes from the ground ({@code EntityLivingBase.onLivingUpdate:2018-2022})
     * on a player who is not on the ground. The plan would name a move the executor cannot perform.
     */
    @Test
    public void aLadderCellIsNotOfferedAsAJumpTarget() {
        FakeWorld w = new FakeWorld(0).floor(-3, 6, GROUND, -3, 3);
        w.ladderColumn(0, 64, 68, 0);
        NeighborGen gen = new NeighborGen(w);

        for (Move m : gen.movesFrom(new Stance(-1, 64, 0), 0, 0)) {
            if (m.to().equals(new Stance(0, 65, 0))) {
                assertEquals("a one-block rise into a ladder cell is a climb, never a jump: a player "
                                + "on a ladder is not on the ground and vanilla takes a jump only "
                                + "from there (EntityLivingBase:2018-2022)",
                        Move.Kind.CLIMB, m.kind());
                return;
            }
        }
        // Not offering the edge at all is equally correct -- a jump onto a ladder is not a thing,
        // and a CLIMB from (-1,64,0) is impossible because the player is not on a ladder there.
        // What must never happen is the edge existing as anything other than a CLIMB.
    }

    /**
     * The fake, standing where a player stands at the foot of a ladder: on the ground, and on the
     * ladder, which are different facts.
     */
    private static FakeActuator onALadder(int x, int y, int z) {
        FakeActuator act = new FakeActuator();
        act.setPosition(x + 0.5D, y, z + 0.5D);
        act.onGround = true;
        act.onClimbable = true;
        return act;
    }

    /**
     * Vanilla's climb, reduced to the one rule under test.
     *
     * <p>Up: the body rises 0.2 a tick while the forward key is held
     * ({@code EntityLivingBase:1659-1662}), and not at all without it. {@code onGround} stays
     * FALSE throughout, because a ladder never sets it -- which is exactly the condition that makes
     * this test fail if the arrival check ever goes back to asking {@code onGround()} for a climb.
     */
    private static void ladderPhysics(FakeActuator act, boolean forwardAxis) {
        double[] p = act.position();
        if (forwardAxis) {
            act.setPosition(p[0], p[1] + 0.2D, p[2]);
        }
        act.onGround = false;
    }
}
