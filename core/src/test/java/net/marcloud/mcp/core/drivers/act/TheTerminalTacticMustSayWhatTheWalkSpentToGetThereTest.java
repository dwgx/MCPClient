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
 * The terminal tactic has to say what the walk SPENT, not merely that it stopped.
 *
 * <p><b>The defect.</b> The arrival branch stamped {@code GivenUp.NOTHING} unconditionally. But
 * {@code NOTHING} is documented as "the direct line, and no option was spent to take it", and the
 * arrival tick is the one tick a walk ends on: the log at {@code (3,64,0)}, fourteen ticks of
 * {@code THE_DIRECT_LINE} sidestep and one cleared jam later, the walk arrives and publishes the
 * one value that says nothing was ever spent to get there. The walk's own message is still true --
 * "arrived within 0.38 blocks after 53 ticks" -- so the lie is only visible to a reader of the
 * tactic field, which is the reader this type was built for.
 *
 * <p><b>And a second one, on the same record.</b> {@code NavController.stop()} documented that the
 * lane survives the stop so a caller can see which lane the walk died on, and
 * {@code MoveTactic.hold(GivenUp)} hardcoded the lane to null -- so the promise was kept in the
 * field and never delivered in the value. A walk that dies mid-sidestep, and a walk that arrives
 * while still beside the log, both published "direct line".
 *
 * <p>Every test drives a real controller against a real world. The distinction under test is the
 * difference between two walks that both end, and a scripted position cannot produce either of
 * them: a wedge is the axes held and the world refusing the step.
 */
public class TheTerminalTacticMustSayWhatTheWalkSpentToGetThereTest {

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

    /** The planner's own standability, as the seam the route executor uses. */
    private static Standable standableOf(FakeActuator act) {
        BlockView view = BodySim.blockView(act);
        return (x, y, z) -> new net.marcloud.mcp.core.drivers.plan.Stance(x, y, z)
                .isStandable(view);
    }

    private static ActOutcome drive(NavController nav, FakeActuator act, int maxTicks) {
        ActOutcome out = null;
        for (int i = 0; i < maxTicks && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
            if (!out.terminal()) {
                BodySim.step(act, nav.forward(), nav.strafe());
            }
        }
        return out;
    }

    /**
     * The primary property, and the one that was false: a walk that spent a recovery lane to get
     * there must not publish the value that says it spent nothing.
     */
    @Test
    public void aWalkThatSpentARecoveryLaneMustNotArriveSayingItSpentNothing() {
        FakeActuator act = flatCorridor();
        act.putBlock(3, FEET, 0, "log");
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(act));

        ActOutcome out = drive(nav, act, 400);

        assertNotNull("premise: the walk must have reached a terminal outcome", out);
        assertTrue("premise: this walk must have ARRIVED, or the arrival site was never reached: "
                + out.message(), out.ok());
        assertTrue("premise: it must have spent recovery ticks, or there was no spend to report: "
                + "unwedgeTicks=" + nav.unwedgeTicks(), nav.unwedgeTicks() > 0);
        assertTrue("premise: it must have cleared a side-step, or no lane was ever taken: "
                        + "unwedgeSteps=" + nav.unwedgeSteps(),
                nav.unwedgeSteps() > 0);

        MoveTactic terminal = nav.tactic();
        assertNotNull(terminal);
        assertEquals("a walk that wedged, spent " + nav.unwedgeTicks() + " ticks of lane and "
                        + "cleared " + nav.unwedgeSteps() + " jam(s) DID spend an option, and the "
                        + "terminal tactic must name it rather than read 'nothing: the direct line, "
                        + "and no option was spent to take it'",
                MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE, terminal.givenUp());
        assertNotEquals("and it must not be the value that means nothing was spent",
                MoveTactic.GivenUp.NOTHING, terminal.givenUp());
        assertNotEquals("nor the one that means every option was spent -- this walk arrived",
                MoveTactic.GivenUp.LIMITS_REACHED, terminal.givenUp());
    }

    /**
     * The other half, and the reason the first one is not just "always claim something": a walk that
     * never needed a recovery must still read as the honest, ordinary case.
     *
     * <p>A refusal site must not start claiming it spent an option it did not. Changing the arrival
     * stamp to a constant would pass the test above and lie here instead.
     */
    @Test
    public void aWalkThatNeverNeededARecoveryStillSaysSo() {
        FakeActuator act = flatCorridor();
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(act));

        ActOutcome out = drive(nav, act, 400);

        assertTrue("premise: this walk must have arrived: " + out.message(), out.ok());
        assertEquals("premise: and it must never have entered a recovery: unwedgeTicks="
                        + nav.unwedgeTicks() + " unwedgeSteps=" + nav.unwedgeSteps(),
                0, nav.unwedgeTicks());
        assertEquals("nothing was given up because nothing needed giving up",
                MoveTactic.GivenUp.NOTHING, nav.tactic().givenUp());
        assertNotEquals("and that must stay a different report from a walk that spent a lane",
                MoveTactic.GivenUp.LIMITS_REACHED, nav.tactic().givenUp());
    }

    /**
     * The second lie: {@code stop()}'s javadoc promises the lane survives so a caller can see which
     * lane the walk died on, and {@code MoveTactic.hold} hardcoded it to null.
     *
     * <p>A frozen body against a log is the only way to reach a walk that dies WHILE side-stepping:
     * a body that can move frees itself and arrives instead, and an arrival has no lane to report.
     */
    @Test
    public void aWalkThatDiesOnALaneSaysWhichLaneItDiedOn() {
        FakeActuator act = flatCorridor();
        act.putBlock(1, FEET, 0, "log");
        act.setPosition(0.9D, FEET, 0.5D);
        act.collidedHorizontally = true;
        NavController nav = NavController.toStance(4.5D, FEET, 0.5D, 300, standableOf(act));

        // The body is never moved: it is frozen against the log, so both lanes run out of ticks
        // and the walk ends mid-recovery rather than after one.
        ActOutcome out = null;
        for (int i = 0; i < 300 && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
        }

        assertNotNull("premise: the walk must have reached a terminal outcome", out);
        assertFalse("premise: this walk must have FAILED, or there is no lane it died on: "
                + out.message(), out.ok());
        MoveTactic terminal = nav.tactic();
        assertEquals("premise: every option was spent", MoveTactic.GivenUp.LIMITS_REACHED,
                terminal.givenUp());
        assertNotNull("the walk died on a lane and the terminal tactic has to name it -- "
                        + "stop()'s javadoc promises exactly this, and hold() was throwing it away",
                terminal.lane());
        assertFalse("so the walk cannot also read as being on the direct line",
                terminal.onTheDirectLine());
    }

    /** A walk that recovered before arriving is NOT on a lane, and saying so is true, not missing. */
    @Test
    public void aWalkThatClearedItsJamBeforeArrivingIsBackOnTheDirectLine() {
        FakeActuator act = flatCorridor();
        act.putBlock(3, FEET, 0, "log");
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(act));

        drive(nav, act, 400);

        assertTrue("premise: the walk must have arrived", nav.tactic() != null);
        assertNull("the lane is dropped the moment the side-step lands, so an arrival after a "
                        + "recovery is on the direct line -- and that is a fact, not a gap",
                nav.tactic().lane());
        assertTrue("which is the only reason the terminal stamp has to come from the recovery "
                + "history and not from the lane field", nav.unwedgeSteps() > 0);
    }
}