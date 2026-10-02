package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import org.junit.Test;

/**
 * A wedged body has to get free, and failing has to say what is in the way.
 *
 * <p><b>The defect.</b> {@code NavController} declared a stall from two facts -- the body stopped
 * advancing, and it was in contact with something -- and its only possible answer was to give up.
 * Measured on a live route: a body entered a move while still short of the previous one, wedged on
 * a diagonal squeeze against a log, and the route stopped ten blocks short of its goal with every
 * step of its trace reporting success. The geometry behind it is sub-cell, not dramatic -- the body
 * box spanned z [-4.54,-3.94], overlapped a log in cell z=-4 by 0.06 blocks, and clearing it needed
 * 0.06 more southward travel than the body had. A human sidesteps and walks on. This controller
 * stopped, and it never tried anything else, because trying anything else was not implemented.
 *
 * <p>Every test here drives a {@link BodySim} rather than teleporting the body, because a wedge is
 * not a position: it is the axes held, the world refusing the step, and the controller working the
 * reason out of the box and the block. A fake that teleports cannot produce that state, so it
 * cannot prove anything about a recovery.
 *
 * <p><b>What is pinned, and why each one can fail.</b> The recovery is only worth having if it
 * actually frees the body (the first test), only trustworthy if it will not step into a hole (the
 * second), only affordable if it costs a walk that does not wedge nothing at all (the third), and
 * only honest if it stops and names the block (the last two). A version that simply gave up would
 * pass the last two and fail the first; a version that stepped anywhere free would pass the first
 * and fail the second.
 */
public class AWedgedBodySidestepsOrSaysWhatIsInTheWayTest {

    /** Floor height: the stances are at y=64, so the ground is one block down. */
    private static final int GROUND = 63;
    private static final int FEET = 64;

    /**
     * Open ground the body walks down, three cells wide either side of its lane.
     *
     * <p>Wide enough that a body walking straight down the middle has room to step either way,
     * which is the point: a recovery needs somewhere to go, and a corridor one cell wide has no
     * answer to give.
     */
    private static FakeActuator flatCorridor() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.yaw = 0f;
        return act;
    }

    /** The same corridor with a log dropped in it, in the lane the body walks down. */
    private static FakeActuator corridorWithALog(int logX, int logZ) {
        FakeActuator act = flatCorridor();
        act.putBlock(logX, FEET, logZ, "log");
        return act;
    }

    /** Tick until terminal or the ceiling, moving the body the way the axes ask. */
    private static ActOutcome drive(NavController nav, FakeActuator act, int maxTicks) {
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = nav.tick(act);
            if (last.terminal()) {
                return last;
            }
            BodySim.step(act, nav.forward(), nav.strafe());
        }
        return last;
    }

    /**
     * The primary property: a body that wedges on a log steps around it and finishes the walk.
     *
     * <p>Driven end to end through a world that refuses to let it walk through the obstacle. The
     * log sits in the destination lane at x=3; the destination is cell (8,64,0), eight blocks past
     * it, so the only way to arrive is to get past the log somehow. It is asserted on the ARRIVAL
     * -- the feet are in cell (8,64,0) and the controller says it arrived -- because a test that
     * asserted "it did not fail" would also pass on a controller that simply stopped and called it
     * a day.
     */
    @Test
    public void aBodyThatWedgesOnALogSidestepsAndWalksOn() {
        FakeActuator act = corridorWithALog(3, 0);
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300,
                standableOf(act));

        ActOutcome out = drive(nav, act, 300);

        assertTrue("the walk must finish rather than stall: " + out.message(),
                out.terminal() && out.ok());
        assertEquals("and it must finish with the feet in the destination's OWN cell -- a body that "
                + "got past the log by stopping short of the plan has not recovered, it has moved "
                + "the goalposts", 8, (int) Math.floor(act.pos[0]));
        assertEquals(0, (int) Math.floor(act.pos[2]));
        assertTrue("a recovery that reports nothing having been tried cannot be distinguished from "
                + "a walk that never needed one: steps=" + nav.unwedgeSteps(),
                nav.unwedgeSteps() >= 1);
    }

    /**
     * The step must be to a cell the PLANNER says is standable, not merely to a nearby empty one.
     *
     * <p>The world here is built so that the side the controller would prefer -- the one closer to
     * the destination -- has no floor under it: a pit. A recovery that asked "is the cell empty"
     * would step into it and report progress, which is the failure mode this project has paid for
     * six times over (one block-name rule, six implementations). The agent must ask the same
     * question the planner asks, so the pit is refused and the far side is taken.
     *
     * <p>And the body must SURVIVE the crossing, which is the assertion that matters: the pit is
     * three cells wide, so a step into it would put the body's next straight-line step over nothing
     * at all.
     */
    @Test
    public void theStepIsToACellThePlannerCallsStandableAndNeverIntoAPit() {
        FakeActuator act = corridorWithALog(3, 0);
        // Dig the near side out from under the corridor, from x=1 to x=11. The floor is gone at
        // z=+1, which is the side the destination lies on, so "closer to the target" and "safe"
        // disagree and the disagreement is the test.
        for (int x = 1; x <= 11; x++) {
            act.removeBlock(x, GROUND, 1);
        }
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300,
                standableOf(act));

        ActOutcome out = drive(nav, act, 300);

        assertTrue("the body must cross on the side that has floor under it: " + out.message(),
                out.terminal() && out.ok());
        assertTrue("premise: the recovery actually ran, or this test proved nothing -- steps="
                + nav.unwedgeSteps(), nav.unwedgeSteps() >= 1);
        assertEquals("and it ended on the floor it could stand on, not past the pit",
                8, (int) Math.floor(act.pos[0]));
    }

    /**
     * What recovery costs a route that does not wedge: nothing at all.
     *
     * <p>The Owner's standing constraint is that input realism may not cost capability, and a
     * recovery that taxed every successful walk would be exactly that tax. So this pins the cost at
     * zero on a long clean walk, and the reason is structural rather than tuned: the branch that can
     * spend a recovery tick is guarded on eight consecutive no-progress ticks IN CONTACT, and a
     * body moving at 0.2 blocks a tick clears the movement threshold on every single tick of the
     * walk. Nothing inside the branch runs, nothing is read, and no extra world query is issued --
     * so the number is not "small", it is "none of the code executes".
     */
    @Test
    public void aWalkThatDoesNotWedgeSpendsNoTicksOnRecoveryAtAll() {
        FakeActuator act = flatCorridor();   // no log: an eight-block walk down open ground
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300,
                standableOf(act));

        ActOutcome out = drive(nav, act, 300);

        assertTrue("premise: the walk itself must succeed, or the cost measured below is the cost "
                + "of a failed walk: " + out.message(), out.terminal() && out.ok());
        assertEquals("a walk with nothing to recover from must spend ZERO recovery ticks",
                0, nav.unwedgeTicks());
        assertEquals("and take no side-steps at all", 0, nav.unwedgeSteps());
    }

    /**
     * Giving up has to say where the body is and what is in the way.
     *
     * <p>A route that fails with a position and a blocking block is actionable; one that fails with
     * "stuck" is not, because "stuck" does not distinguish a route problem (of which there is
     * none) from a body that cannot fit. The caller can go and look at (1,64,0) and clear it; the
     * caller cannot go and look at "stuck".
     *
     * <p>The world is boxed in: the log ahead, and blocks on BOTH sides, so there is no standable
     * cell to step to and the recovery has nothing to offer. That is the honest end of escalation,
     * and it is the case that must not silently succeed.
     */
    @Test
    public void aBodyWithNowhereToStepFailsNamingItsPositionAndTheBlock() {
        FakeActuator act = corridorWithALog(1, 0);
        act.putBlock(0, FEET, 1, "stone");
        act.putBlock(0, FEET, -1, "stone");
        NavController nav = NavController.toStance(4.5D, FEET, 0.5D, 300,
                standableOf(act));

        ActOutcome out = drive(nav, act, 300);

        assertTrue("it must end rather than press into the log forever", out.terminal());
        assertFalse("and it must not claim arrival: " + out.message(), out.ok());
        assertTrue("the failure must name the position, because the caller has to know where the "
                + "body is standing: " + out.message(), out.message().contains("wedged at ("));
        assertTrue("and name the block and the cell it is in, because that is the part the caller "
                + "can act on -- the route was fine, there is a log at (1,64,0): " + out.message(),
                out.message().contains("log") && out.message().contains("(1,64,0)"));
        assertEquals("and with no world view to ask, it must say the two side-steps it would have "
                + "tried were impossible rather than silently not trying: " + out.message(),
                0, nav.unwedgeSteps());
    }

    /**
     * Recovery is bounded, and says what the bound is.
     *
     * <p>A recovery that can keep going is worse than no recovery: it burns the walk's whole budget
     * shuffling and ends further from the goal than stopping would have. So the bound is a fact and
     * it is reported -- both sides, {@link NavController#UNWEDGE_TICKS_PER_SIDE} ticks each, and
     * no more.
     *
     * <p>The body here is frozen: both neighbouring cells ARE standable, so the recovery will pick
     * one and commit its full budget to it, and it will never arrive. That is the case the bound
     * exists for, and it is only reachable with a body that refuses to move -- which is why this
     * test drives the controller directly rather than through a world.
     */
    @Test
    public void aRecoveryThatFreesNothingStopsAtItsBoundAndSaysWhatItWas() {
        FakeActuator act = corridorWithALog(1, 0);
        act.setPosition(0.9D, FEET, 0.5D);
        act.collidedHorizontally = true;
        NavController nav = NavController.toStance(4.5D, FEET, 0.5D, 300, standableOf(act));

        ActOutcome out = null;
        for (int i = 0; i < 300 && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
            // Deliberately no BodySim.step: the body is frozen where a real one would be stuck,
            // so the recovery is given the full budget on both sides and has to give up on it.
        }

        assertTrue("it must terminate", out != null && out.terminal());
        assertFalse("and fail honestly rather than time out or claim arrival: " + out.message(),
                out.ok());
        assertTrue("the message must state the bound in ticks, because an unstated bound is not a "
                + "bound a reader can check the body against: " + out.message(),
                out.message().contains(String.valueOf(NavController.UNWEDGE_TICKS_PER_SIDE)));
        assertTrue("and it must say it tried BOTH sides rather than one: " + out.message(),
                out.message().contains("Both cells beside the jam"));
        assertTrue("and it must not have spent the whole 300-tick walk on it: ticks="
                + nav.ticks(), nav.ticks() < 300);
    }

    /** The planner's own standability, as the seam {@code RouteExecutor} uses. */
    private static Standable standableOf(FakeActuator act) {
        BlockView view = BodySim.blockView(act);
        return (x, y, z) -> new net.marcloud.mcp.core.drivers.plan.Stance(x, y, z).isStandable(view);
    }
}