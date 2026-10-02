package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A clock, a ladder and a current are three different reasons for a body to stop, and a caller can
 * only respond correctly if the record says which one it was.
 *
 * <p><b>The defect.</b> {@code ClimbSteering} and {@code SwimSteering} both stamped
 * {@code GivenUp.LIMITS_REACHED} -- "every option, spent" -- at four sites: the tick budget and the
 * stall test in each. The field javadoc in each file said so in as many words, and the argument it
 * gave was that "both mean every option this controller has was spent". That argument was false in
 * the way this enum exists to prevent. A climb and a swim have no lane and no recovery, so they
 * never had an option to spend in the first place; and the two branches are not even the same fact
 * as each other.
 *
 * <p><b>Why the two branches must come apart.</b> The budget is the clock: the body was still on the
 * ladder, still pressing the one key the controller has, and ran out of ticks. Raising the budget
 * fixes that. The stall is the world: {@code STUCK_TICKS} is 8 against a budget of tens, so the
 * budget is demonstrably not what ended it, and the body was on a climbable / in water the whole
 * time. Raising the budget buys the same eight ticks again. One of those is "try longer" and the
 * other is "this ladder ends here, route around it", and a value that cannot tell them apart sends
 * a caller to do the wrong one.
 *
 * <p><b>These are driven directly rather than through a route.</b> A {@code RouteExecutor} only
 * builds these two controllers for a plan containing a {@code CLIMB} or {@code SWIM} move, which
 * means manufacturing a ladder column or a river in a {@link net.marcloud.mcp.core.drivers.world}
 * view before the controller under test ever runs. The route-path seam is covered for the walker in
 * {@code AWalkThatSpentNothingSaysSoOnTheRecordItCarriesTest}, which drives the applier; what these
 * tests add is that the gaits are not lumped together, and driving the gait is the only way to put
 * a body on a ladder at all.
 */
public final class AClimbAndASwimThatRanOutOfClockAreNotAWedgeTest {

    private static final double START_Y = 64.0D;

    /** A body on a ladder, at a height, in a world the controller can read. */
    private static FakeActuator onALadder(double y) {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                act.putBlock(x, 63, z);
            }
        }
        act.setPosition(0.5D, y, 0.5D);
        act.onGround = false;
        act.onClimbable = true;
        act.setRotation(0f, 0f);
        return act;
    }

    /** A body in water, at a height, in a world the controller can read. */
    private static FakeActuator inWater(double y) {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) {
                act.putBlock(x, 63, z);
            }
        }
        act.setPosition(0.5D, y, 0.5D);
        act.onGround = false;
        act.inWater = true;
        act.setRotation(0f, 0f);
        return act;
    }

    /**
     * Run a climb to its terminal outcome.
     *
     * @param risePerTick what the body gains in Y each tick, so a timeout and a stall can be told
     *                     apart by how the body behaved rather than by how the clock was set
     */
    private static MoveTactic.GivenUp climb(boolean makeProgress, int timeoutTicks) {
        ClimbSteering climb = new ClimbSteering(0.5D, START_Y + 40.0D, 0.5D, true, timeoutTicks);
        FakeActuator act = onALadder(START_Y);
        for (int i = 0; i < 400; i++) {
            ActOutcome out = climb.tick(act);
            if (out.terminal()) {
                break;
            }
            if (makeProgress) {
                act.setPosition(act.pos[0], act.pos[1] + 0.2D, act.pos[2]);
            }
        }
        return climb.tactic().givenUp();
    }

    /** Run a swim to its terminal outcome, on the same two terms. */
    private static MoveTactic.GivenUp swim(boolean makeProgress, int timeoutTicks) {
        SwimSteering swim = new SwimSteering(10.5D, START_Y, 0.5D, false, timeoutTicks);
        FakeActuator act = inWater(START_Y);
        for (int i = 0; i < 400; i++) {
            ActOutcome out = swim.tick(act);
            if (out.terminal()) {
                break;
            }
            if (makeProgress) {
                act.setPosition(act.pos[0] + 0.2D, act.pos[1], act.pos[2]);
            }
        }
        return swim.tactic().givenUp();
    }

    /**
     * A climb that ran out of ticks is the clock, and a climb that stalled is the world.
     *
     * <p>Both walks are made on the same body in the same place. The only difference is whether the
     * body rose, which is exactly the difference a caller would need to act on.
     */
    @Test
    public void aClimbTimeoutAndAClimbStallAreDistinguishable() {
        MoveTactic.GivenUp byClock = climb(true, 60);
        MoveTactic.GivenUp byWorld = climb(false, 60);

        assertEquals("a climb that was still rising when its budget ended was stopped by the "
                        + "clock, and the response to that is a bigger budget",
                MoveTactic.GivenUp.OUT_OF_TICKS, byClock);
        assertEquals("a climb hanging on a ladder that will not carry it was stopped by the world, "
                        + "and no budget fixes that",
                MoveTactic.GivenUp.THE_UNMOVABLE_MEDIUM, byWorld);
        assertNotEquals("so the two climbs must not report the same thing, or the split bought "
                        + "nothing", byClock, byWorld);
    }

    /**
     * The same two stories for a swim, and the two gaits agree on them.
     *
     * <p>One value for the climb stall and the swim stall is deliberate: they are one fact. A caller
     * cannot reroute around a ladder that stops rising or a current that will not let go, and it
     * cannot tell either from a budget it could have raised. Splitting them would multiply the
     * vocabulary without giving a reader anything new to act on.
     */
    @Test
    public void aSwimTimeoutAndASwimStallAreDistinguishableAndMatchTheClimb() {
        MoveTactic.GivenUp swimByClock = swim(true, 20);
        MoveTactic.GivenUp swimByWorld = swim(false, 20);

        assertEquals("a swim still making headway when its budget ended is the clock",
                MoveTactic.GivenUp.OUT_OF_TICKS, swimByClock);
        assertEquals("a swimmer held by a current or a wall is the world",
                MoveTactic.GivenUp.THE_UNMOVABLE_MEDIUM, swimByWorld);
        assertNotEquals("and the two swims must not read as the same record",
                swimByClock, swimByWorld);

        assertEquals("and the gait is not part of the story: the same four verdicts cover both",
                swimByClock, climb(true, 60));
        assertEquals("stall included -- a current and a ladder that ends are one fact to a caller",
                swimByWorld, climb(false, 60));
    }

    /**
     * Neither gait's two failures may claim exhaustion.
     *
     * <p>The sentence {@code LIMITS_REACHED} carries is "every option, spent", and a climb or a swim
     * has no option to spend -- no lane, no recovery, no escalation. A body that stalls on a ladder
     * has pressed the only key the controller has and the world refused to answer. Nothing was
     * exhausted, because there was nothing to exhaust, and a caller reading that value is sent to
     * reroute a climb that a larger budget would not have helped.
     */
    @Test
    public void noClimbOrSwimFailureClaimsEveryOptionWasSpent() {
        MoveTactic.GivenUp[] all = {
            climb(true, 60), climb(false, 60), swim(true, 20), swim(false, 20)};
        String[] names = {"climb timeout", "climb stall", "swim timeout", "swim stall"};

        for (int i = 0; i < all.length; i++) {
            assertNotEquals("the " + names[i] + " reported " + all[i] + ", which claims every option "
                            + "was spent. A gait with no lane and no recovery never had an option to "
                            + "spend, so the sentence is false there whatever else is true",
                    MoveTactic.GivenUp.LIMITS_REACHED, all[i]);
        }
    }

    /**
     * A climb that ARRIVED still reports nothing spent.
     *
     * <p>The other half of the claim, and the one a careless fix breaks: raising the budget on every
     * failure would make a successful climb say it had spent something, which is the same error in
     * the opposite direction.
     */
    @Test
    public void aClimbThatArrivedSpendsNothing() {
        ClimbSteering climb = new ClimbSteering(0.5D, START_Y + 3.0D, 0.5D, true, 60);
        FakeActuator act = onALadder(START_Y);
        ActOutcome out = null;
        for (int i = 0; i < 200; i++) {
            out = climb.tick(act);
            if (out.terminal()) {
                break;
            }
            act.setPosition(act.pos[0], act.pos[1] + 0.2D, act.pos[2]);
        }

        assertTrue("premise: the climb must actually have arrived, or this is measuring nothing. "
                + "It said " + (out == null ? "<no outcome>" : out.message()),
                out != null && out.ok());
        assertEquals("a column that carried the body where the plan asked cost nothing but holding "
                        + "forward, and saying otherwise would be the same lie told about a success",
                MoveTactic.GivenUp.NOTHING, climb.tactic().givenUp());
    }
}