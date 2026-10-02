package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import org.junit.Test;

/**
 * The tactic a walk chose is a value somebody can read, and it says what it gave up.
 *
 * <p><b>The defect.</b> {@code RouteIntent} carried a GOAL -- where the feet go, what the route may
 * spend, whether to creep -- and nothing else. The TACTIC lived where nobody could see it: four
 * private fields ({@code sideX/sideY/sideZ/sideSign/laneOffX/laneOffZ/laneUx/laneUz}) and two
 * private methods inside {@code NavController}. A caller could read that the body asked to go
 * somewhere and could read where it ended up, and could not read that it had spent a direct line and
 * fourteen ticks to walk around a log, or that it had named a body of water and walked into it
 * anyway. Nothing could learn from a decision, because the decision left nothing behind.
 *
 * <p><b>Why that matters more than it looks.</b> A decision that leaves no trace cannot be held to
 * account. When a walk arrives slowly nobody can say whether the route was long or the walk was
 * indecisive; when a walk walks into water nobody can say it had been warned, because the warning
 * and the walking were the same two floats.
 *
 * <p><b>The invariant every assertion here leans on:</b> a tactic's axes are the axes that were
 * published. Not a plan for them, not a summary of them -- the values, so that a record of
 * intentions cannot drift away from the fingers. {@code NavController} changing anything about how
 * it moves is out of scope for this slice and {@code TheWalkThisSliceChangedMovesAsItDidBeforeTest}
 * pins that it did not.
 *
 * <p>Every test drives a real controller against a real world rather than constructing the values,
 * because a value type that can be built by hand proves only that the constructor works. The point
 * is that a decision a controller made on its own is readable afterwards.
 */
public class TheChosenTacticIsAValueAndNotPrivateStateTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /** Open ground the body walks down, three cells wide either side of its lane. */
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

    private static FakeActuator corridorWithALog(int logX, int logZ) {
        FakeActuator act = flatCorridor();
        act.putBlock(logX, FEET, logZ, "log");
        return act;
    }

    /** The planner's own standability, as the seam {@code RouteExecutor} uses. */
    private static Standable standableOf(FakeActuator act) {
        BlockView view = BodySim.blockView(act);
        return (x, y, z) -> new net.marcloud.mcp.core.drivers.plan.Stance(x, y, z)
                .isStandable(view);
    }

    /**
     * One run of a controller against a world that pushes back, keeping every tactic it published.
     *
     * <p>The body is MOVED, not teleported: a wedge is not a position, it is the axes held and the
     * world refusing the step, so a scripted position cannot produce the state under test.
     */
    private static final class Run {
        final NavController nav;
        final FakeActuator act;
        final java.util.List<MoveTactic> tactics = new java.util.ArrayList<>();
        ActOutcome out;

        Run(NavController nav, FakeActuator act) {
            this.nav = nav;
            this.act = act;
        }

        /** Drive to terminal, recording the tactic published on every tick. */
        Run drive(int maxTicks) {
            for (int i = 0; i < maxTicks && (out == null || !out.terminal()); i++) {
                out = nav.tick(act);
                tactics.add(nav.tactic());
                if (!out.terminal()) {
                    BodySim.step(act, nav.forward(), nav.strafe());
                }
            }
            return this;
        }

        MoveTactic firstWithLane() {
            for (MoveTactic t : tactics) {
                if (t != null && t.lane() != null) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * The primary property: a walk that wedges, recovers, and arrives has a READABLE record of what
     * it chose and what it spent, and that record travels on the intent.
     *
     * <p>Before this slice none of the four assertions in the body of this test could even be
     * written: there was no {@code MoveTactic}, no {@code NavController.tactic()}, no
     * {@code RouteIntent.withTactic}, and the lane was four private ints.
     */
    @Test
    public void theChosenTacticAndWhatItGaveUpAreReadableOffARealIntent() {
        FakeActuator act = corridorWithALog(3, 0);
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(act));
        Run run = new Run(nav, act).drive(400);

        assertTrue("premise: the walk must have had to recover, or there was no decision to read",
                run.firstWithLane() != null);

        MoveTactic chosen = run.firstWithLane();
        // The lane has to be the lane that was WALKED, not merely a non-null object: its direction
        // is frozen along the way the body was travelling (here, +x toward a target eight blocks
        // east) and its offset is a single adjacent cell. A lane pointing anywhere else would
        // satisfy "a lane exists" while describing a recovery that did not happen.
        assertEquals("the lane runs in the direction the body was travelling",
                1.0D, Math.abs(chosen.lane().dirX()) + Math.abs(chosen.lane().dirZ()), 1e-9D);
        assertTrue("and that direction is east, toward the target",
                chosen.lane().dirX() > 0.0D);
        assertTrue("and its offset is exactly one adjacent cell: offX=" + chosen.lane().offX()
                        + " offZ=" + chosen.lane().offZ(),
                Math.abs(chosen.lane().offX()) + Math.abs(chosen.lane().offZ()) == 1);
        assertNotNull("the lane is the decision, and it has to name itself",
                chosen.lane());
        assertEquals("the direct line was the thing given up, and nothing else was",
                MoveTactic.GivenUp.THE_DIRECT_LINE, chosen.givenUp());
        assertFalse("a body on a recovery lane is not on the direct line, and saying so is half "
                + "the reason this type exists", chosen.onTheDirectLine());

        // The intent the caller submitted, and the intent that now carries what the walk chose.
        RouteIntent asked = new RouteIntent(8, FEET, 0, 0);
        assertNull("premise: nobody has walked this intent yet",
                asked.tactic());

        RouteIntent produced = asked.withTactic(chosen);

        assertEquals("the chosen tactic must be readable off the produced intent",
                chosen, produced.tactic());
        assertEquals("and the goal must be carried through untouched: stamping a tactic must not "
                        + "quietly move the destination",
                asked.targetX(), produced.targetX());
        assertEquals(asked.targetY(), produced.targetY());
        assertEquals(asked.targetZ(), produced.targetZ());
        assertEquals(asked.blockBudget(), produced.blockBudget());
        assertEquals(asked.creeping(), produced.creeping());
        assertNull("and the intent the caller still holds must be untouched",
                asked.tactic());
        assertNotEquals("an intent carrying a tactic is not the intent the caller submitted",
                asked, produced);
    }

    /**
     * "Chose not to rotate" and "chose to rotate by zero" are different decisions.
     *
     * <p>This is the {@code yawChange: Float?} shape from the reference, and it exists because a
     * plain float cannot say it. Zero is a real answer -- "re-aim, and the bearing I computed is
     * the one I already had" -- and it is not the same answer as null, which is "the camera is not
     * mine and I am not asking". {@code NavController} answers null on every tick, and that is a
     * decision it makes rather than a field it forgot to fill in.
     */
    @Test
    public void choseNotToRotateIsNotTheSameAsRotatingByZero() {
        FakeActuator act = flatCorridor();
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(act));

        nav.tick(act);
        MoveTactic chosen = nav.tactic();

        assertNotNull("the controller must have decided something", chosen);
        assertNull("it decided NOT to rotate the camera, and null is how it says so",
                chosen.yawChange());
        assertFalse("so it must not report having asked for a rotation",
                chosen.rotatesCamera());

        MoveTactic rotatedByZero = MoveTactic.rotationTo(0f, MoveTactic.GivenUp.NOTHING);
        assertNotNull("rotating by zero is a request, and it must be readable as one",
                rotatedByZero.yawChange());
        assertTrue("not rotating and rotating by zero must not answer the same question the "
                + "same way", rotatedByZero.rotatesCamera());

        assertNotEquals("and the two records must not be equal -- if they were, the distinction "
                        + "this field exists for would be gone",
                MoveTactic.hold(MoveTactic.GivenUp.NOTHING), rotatedByZero);
    }

    /**
     * The third failure encoding: a walk that ran out of options says so, and that is not the same
     * report as a walk that never needed any.
     *
     * <p>The reference has three encodings and this package had one: "a walk that was still
     * running". So a body that had spent everything and a body that had needed nothing looked
     * identical. The boxed-in world here is the first; the clear corridor is the second.
     */
    @Test
    public void aWalkThatRanOutIsNotTheSameReportAsOneThatNeverNeededToTry() {
        FakeActuator boxed = flatCorridor();
        boxed.putBlock(3, FEET, 0, "log");
        for (int x = 1; x <= 11; x++) {
            boxed.removeBlock(x, GROUND, 1);
            boxed.removeBlock(x, GROUND, -1);
        }
        Run spent = new Run(NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(boxed)), boxed)
                .drive(400);

        assertTrue("premise: this walk must have failed", spent.out != null && spent.out.terminal()
                && !spent.out.ok());
        assertEquals("every option was spent, and the tactic has to say which",
                MoveTactic.GivenUp.LIMITS_REACHED, spent.nav.tactic().givenUp());
        assertEquals("a walk that has stopped publishes no keys",
                0f, spent.nav.tactic().forward(), 0f);

        Run clean = new Run(NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(flatCorridor())),
                flatCorridor()).drive(400);

        assertTrue("premise: this one must have arrived",
                clean.out != null && clean.out.ok());
        assertEquals("nothing was given up because nothing needed giving up, and that must not "
                        + "read as 'ran out'",
                MoveTactic.GivenUp.NOTHING, clean.nav.tactic().givenUp());
        assertNotEquals(MoveTactic.GivenUp.NOTHING, MoveTactic.GivenUp.LIMITS_REACHED);
    }

    /**
     * When the first lane runs out of ticks, the second lane's tactic names the first as the thing
     * given up.
     *
     * <p>The body is frozen so the recovery is given its full budget on both sides, which is the
     * only way to reach the escalation: a body that can move frees itself on the first side.
     */
    @Test
    public void theSecondLaneSaysTheFirstOneWasGivenUp() {
        FakeActuator act = corridorWithALog(1, 0);
        act.setPosition(0.9D, FEET, 0.5D);
        act.collidedHorizontally = true;
        NavController nav = NavController.toStance(4.5D, FEET, 0.5D, 300, standableOf(act));

        Run run = new Run(nav, act);
        for (int i = 0; i < 300 && (run.out == null || !run.out.terminal()); i++) {
            run.out = nav.tick(act);
            run.tactics.add(nav.tactic());
        }

        assertTrue("premise: the recovery must have escalated", sawGivenUp(run.tactics,
                MoveTactic.GivenUp.THE_FIRST_LANE));
        assertTrue("and before that it must have taken the first lane",
                sawGivenUp(run.tactics, MoveTactic.GivenUp.THE_DIRECT_LINE));
    }

    private static boolean sawGivenUp(java.util.List<MoveTactic> tactics, MoveTactic.GivenUp g) {
        for (MoveTactic t : tactics) {
            if (t != null && t.givenUp() == g) {
                return true;
            }
        }
        return false;
    }

    /**
     * A hazard named on the line is walked anyway, and the tactic says the way around was given up.
     *
     * <p>The controller reports water and walks into it; that is its contract. What it could not do
     * before was let anyone SEE that it had held to the contract on this tick rather than merely
     * arrived somewhere, because the axes of walking into an ocean and the axes of walking down a
     * clear corridor are the same two numbers.
     */
    @Test
    public void aNamedHazardOnTheLineIsWalkedAnywayAndSaysSo() {
        FakeActuator act = flatCorridor();
        for (int x = 6; x <= 12; x++) {
            act.putBlock(x, FEET, 0, "water");
        }
        NavController nav = NavController.toStance(11.5D, FEET, 0.5D, 300, standableOf(act));

        nav.tick(act);
        MoveTactic chosen = nav.tactic();

        assertNotNull("premise: the hazard must have been found", nav.hazard());
        assertEquals("the hazard was named and the way around it was given up",
                MoveTactic.GivenUp.THE_NAMED_HAZARD, chosen.givenUp());
        assertNull("and no lane was taken, because nothing here routes around anything",
                chosen.lane());
    }

    /**
     * A point walk does not even treat jumping as a question.
     *
     * <p>{@code NavIntent} says "walk toward this point in a straight line", which is all
     * {@code NavController} attempts and all it claims. A {@code false} jump on such a walk would
     * be indistinguishable from a decision that was made and lost, which is the same confusion
     * {@code yawChange} exists to prevent one layer down.
     */
    @Test
    public void aPointWalkDoesNotEvenTreatJumpingAsAQuestion() {
        FakeActuator act = flatCorridor();
        NavController point = new NavController(8.5D, FEET, 0.5D, 300);

        point.tick(act);
        assertFalse("a point walk neither climbs nor jumps, and the tactic has to say the question "
                + "was never put", point.tactic().considersJump());

        FakeActuator stanceAct = flatCorridor();
        NavController stance = NavController.toStance(8.5D, FEET, 0.5D, 300,
                standableOf(stanceAct));
        stance.tick(stanceAct);
        assertTrue("a stance walk IS asked, and 'no' is a real answer there",
                stance.tactic().considersJump());
    }

    /**
     * The invariant: a tactic's axes are the axes that were published, on every tick of every walk.
     *
     * <p>Without this the whole type is a story about the controller rather than a record of it,
     * and a story that drifts from the behaviour is worse than private state because it looks like
     * evidence. Checked over the recovery walk, which is the one with a lane, and the plain walk,
     * which is the one without.
     */
    @Test
    public void theTacticAlwaysMirrorsTheAxesThatWerePublished() {
        // ONE world per walk. Deriving the controller's standability from a different FakeActuator
        // than the one it collides with would be testing two worlds, and the walk could pass for a
        // reason that has nothing to do with the tactic.
        FakeActuator wedged = corridorWithALog(3, 0);
        assertMirrors("recovery walk", wedged,
                NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(wedged)));
        FakeActuator clear = flatCorridor();
        assertMirrors("plain walk", clear,
                NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(clear)));
    }

    private static void assertMirrors(String label, FakeActuator act, NavController nav) {
        int checked = 0;
        for (int i = 0; i < 400; i++) {
            ActOutcome out = nav.tick(act);
            MoveTactic t = nav.tactic();
            assertNotNull(label + ": a tactic must exist on every tick that produced one", t);
            assertEquals(label + ": forward must be the published forward",
                    nav.forward(), t.forward(), 0f);
            assertEquals(label + ": strafe must be the published strafe",
                    nav.strafe(), t.strafe(), 0f);
            assertEquals(label + ": the jump component must mirror the jump axis",
                    Boolean.valueOf(nav.jump()), t.jump());
            checked++;
            if (out.terminal()) {
                break;
            }
            BodySim.step(act, nav.forward(), nav.strafe());
        }
        assertTrue(label + ": the walk must have ticked at all, or nothing was checked", checked > 1);
    }
}