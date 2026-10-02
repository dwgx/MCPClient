package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import org.junit.Test;

/**
 * "Ran out" and "spent everything" are different walks, and the terminal record used to say they
 * were the same one.
 *
 * <p><b>The defect.</b> {@code GivenUp.LIMITS_REACHED} is declared "every option, spent", and
 * {@code NavController} stamped it at three sites. One of them is that claim: {@code wedged()},
 * reached only after both lanes have been tried, or after the step bound, or after every candidate
 * cell has been measured and rejected. The other two are not, and they are the two a plain walk runs
 * into:
 *
 * <ol>
 *   <li><b>The tick budget.</b> A walk toward a target it can never reach on a straight line runs
 *       out of ticks having spent NOTHING -- no jam, no lane, no recovery tick. "Every option,
 *       spent" is false there: there was no option to spend, and the walk did not choose to stop.
 *       The budget ran out from under it.</li>
 *   <li><b>The stall nobody can read.</b> {@code readJam} returned null, so the walk stops before
 *       {@code beginUnwedge} is ever reached and no lane is ever offered. The controller could not
 *       name what is in the way, which is a capability limit rather than an exhausted one, and it
 *       is exactly the case the surrounding comment says must not be dressed up as certainty.</li>
 * </ol>
 *
 * <p><b>Why three values and not one weakened sentence.</b> The alternative was to widen
 * {@code LIMITS_REACHED}'s description to "out of options OR out of ticks", which is true and
 * cheap and costs a caller the one thing this enum was created to give it. A caller that reads
 * "every option, spent" knows retrying with a bigger budget is pointless and the world is in the
 * way. A caller that reads "out of ticks" knows the opposite: the walk was making progress and the
 * budget was too small. Collapsing those two tells a route executor to reroute a walk that only
 * needed more time.
 *
 * <p>Every test drives a real controller against a real world, because the two walks that must be
 * told apart are not distinguishable by a scripted position: one is a body walking a clear corridor
 * until the clock runs out, and the other is a body that fought a log, cleared it, and then ran
 * out of corridor.
 */
public class AWalkThatRanOutIsNotAWalkThatSpentEveryOptionTest {

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
     * The primary property: a walk that runs out of ticks on a clear corridor spent no option, and
     * the record has to say so rather than claim it spent every one.
     */
    @Test
    public void aTimeoutOnAClearCorridorSpentNothingAndMustNotClaimItSpentEverything() {
        FakeActuator act = flatCorridor();
        NavController nav = NavController.toStance(11.5D, FEET, 0.5D, 20, standableOf(act));

        ActOutcome out = drive(nav, act, 200);

        assertNotNull("premise: the walk must have reached a terminal outcome", out);
        assertTrue("premise: it must have TERMINAL rather than arrived: " + out.message(),
                out.terminal());
        assertFalse("and a timeout must still FAIL -- retyping the value cannot turn it into a "
                + "success: " + out.message(), out.ok());
        assertEquals("premise: and it must never have entered a recovery, or there was something "
                        + "to spend: unwedgeTicks=" + nav.unwedgeTicks(),
                0, nav.unwedgeTicks());
        assertTrue("premise: the walk must have been walking, not standing: ticks=" + nav.ticks(),
                nav.ticks() > 1);

        MoveTactic terminal = nav.tactic();
        assertNotNull("a terminal tick publishes a terminal tactic", terminal);
        assertEquals("this walk ran out of TICKS having spent no option, and the value that says "
                        + "so is the only honest one here",
                MoveTactic.GivenUp.OUT_OF_TICKS, terminal.givenUp());
        assertNotEquals("and it must not read as 'every option, spent', which is the opposite "
                        + "claim: nothing was spent and nothing was chosen",
                MoveTactic.GivenUp.LIMITS_REACHED, terminal.givenUp());
        assertEquals("a walk that has stopped publishes no keys",
                0f, terminal.forward(), 0f);
    }

    /**
     * The distinction the third encoding exists to make, on the one tick a caller who did not watch
     * the walk tick by tick ever sees.
     *
     * <p>Two timeouts, one value each way round: if the retyping had collapsed them into a single
     * "out of ticks", this test fails and the enum has thrown away the fact that one of these walks
     * spent 24 ticks of lane fighting a log and the other never met one.
     */
    @Test
    public void aTimeoutThatSpentALaneIsNotTheSameRecordAsOneThatSpentNothing() {
        FakeActuator clean = flatCorridor();
        NavController cleanNav = NavController.toStance(11.5D, FEET, 0.5D, 20, standableOf(clean));
        ActOutcome cleanOut = drive(cleanNav, clean, 200);

        FakeActuator logWorld = flatCorridor();
        logWorld.putBlock(3, FEET, 0, "log");
        NavController logNav = NavController.toStance(11.5D, FEET, 0.5D, 45,
                standableOf(logWorld));
        ActOutcome logOut = drive(logNav, logWorld, 200);

        assertNotNull("premise: the clean walk must have terminated", cleanOut);
        assertNotNull("premise: the walk past a log must have terminated", logOut);
        assertTrue("premise: the clean walk must have timed out: " + cleanOut.message(),
                cleanOut.message().contains("gave up after"));
        assertTrue("premise: the walk past a log must ALSO have timed out, or this test is "
                        + "comparing two different failures: " + logOut.message(),
                logOut.message().contains("gave up after"));
        assertTrue("premise: the walk past a log must have SPENT a lane, or the two records are "
                        + "the same walk twice: unwedgeTicks=" + logNav.unwedgeTicks()
                        + " unwedgeSteps=" + logNav.unwedgeSteps(),
                logNav.unwedgeSteps() > 0);
        assertFalse("premise: and it must have cleared it, or it spent a lane and is still on it: "
                        + "steps=" + logNav.unwedgeSteps(), logOut.ok());

        MoveTactic spentNothing = cleanNav.tactic();
        MoveTactic spentALane = logNav.tactic();
        assertNotNull(spentNothing);
        assertNotNull(spentALane);

        assertEquals("premise: the clean corridor spent nothing",
                MoveTactic.GivenUp.OUT_OF_TICKS, spentNothing.givenUp());
        assertEquals("a walk that recovered from a log and then ran out of ticks has to be "
                        + "distinguishable from one that never met anything",
                MoveTactic.GivenUp.OUT_OF_TICKS_AFTER_A_LANE, spentALane.givenUp());
        assertNotEquals("the two records must not be equal, or the retyping bought nothing",
                spentNothing, spentALane);
        assertNotEquals("and neither may read as the walk that spent every option: one spent "
                        + "nothing and the other still had a second lane unspent when the clock "
                        + "went",
                MoveTactic.GivenUp.LIMITS_REACHED, spentALane.givenUp());
    }

    /**
     * The stall the controller cannot read is a capability limit, and the record has to say that
     * rather than that it ran out of options.
     *
     * <p>{@code blockAtReturnsNull} is the live case rather than a contrivance: the real actuator
     * returns null from its catch block, so presence still reads true and the world still refuses
     * the step, while the name that would identify it is gone. The body is genuinely blocked --
     * {@code BodySim} collides it against the log -- and {@code readJam} genuinely finds nothing
     * it is willing to name.
     */
    @Test
    public void aStallTheControllerCannotNameSaysSoRatherThanClaimingItRanOutOfOptions() {
        FakeActuator act = flatCorridor();
        act.putBlock(3, FEET, 0, "log");
        act.blockAtReturnsNull = true;
        NavController nav = NavController.toStance(11.5D, FEET, 0.5D, 300, standableOf(act));

        ActOutcome out = drive(nav, act, 200);

        assertNotNull("premise: the walk must have reached a terminal outcome", out);
        assertTrue("premise: it must have reached the unreadable-stall branch: " + out.message(),
                out.message().contains("no block could be read"));
        assertFalse("and a refused recovery must still FAIL: " + out.message(), out.ok());
        assertEquals("premise: and no lane may have been offered, because the walk is terminal "
                        + "before beginUnwedge is reached: unwedgeTicks=" + nav.unwedgeTicks(),
                0, nav.unwedgeTicks());

        MoveTactic terminal = nav.tactic();
        assertNotNull(terminal);
        assertEquals("nothing was spent here -- not because every option was tried, but because "
                        + "no option could be chosen, and those are opposite facts",
                MoveTactic.GivenUp.THE_UNNAMED_STALL, terminal.givenUp());
        assertNotEquals("so it must not claim every option was spent",
                MoveTactic.GivenUp.LIMITS_REACHED, terminal.givenUp());
        assertNotEquals("nor may it borrow the clock's value: this walk stopped on the ninth tick "
                        + "with 290 of its budget untouched",
                MoveTactic.GivenUp.OUT_OF_TICKS, terminal.givenUp());
    }

    /**
     * The value that WAS true keeps its one honest site, and the walk that publishes it still
     * fails.
     *
     * <p>Without this the change would be a straight trade: two sites fixed by making the word
     * mean less everywhere. A body boxed in with no standable cell beside it has measured every
     * candidate and rejected it, and that walk is the one {@code LIMITS_REACHED} was written for.
     */
    @Test
    public void aWalkThatMeasuredEveryOptionAndRejectedItStillReadsEveryOptionSpent() {
        FakeActuator boxed = flatCorridor();
        boxed.putBlock(3, FEET, 0, "log");
        for (int x = 1; x <= 11; x++) {
            boxed.removeBlock(x, GROUND, 1);
            boxed.removeBlock(x, GROUND, -1);
        }
        NavController nav = NavController.toStance(8.5D, FEET, 0.5D, 300, standableOf(boxed));

        ActOutcome out = drive(nav, boxed, 400);

        assertNotNull("premise: the walk must have reached a terminal outcome", out);
        assertTrue("premise: it must have been refused a step rather than timed out: " + out.message(),
                out.message().contains("nowhere to step"));
        assertFalse("and a refused recovery must still FAIL: " + out.message(), out.ok());
        assertEquals("both candidate cells were measured and neither would do, so this is the one "
                        + "site where 'every option, spent' is the truth",
                MoveTactic.GivenUp.LIMITS_REACHED, nav.tactic().givenUp());
    }
}
