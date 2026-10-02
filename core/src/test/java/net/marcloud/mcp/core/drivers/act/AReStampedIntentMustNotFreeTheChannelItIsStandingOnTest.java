package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.marcloud.mcp.core.ke.GameClock;
import org.junit.Test;

/**
 * A lease must not be released by the walk it is holding re-stamping the intent underneath it.
 *
 * <p><b>The defect.</b> {@code MoveApplier} republishes the slot record's intent every tick to stamp
 * the chosen {@link MoveTactic} onto it, and {@link RouteIntent#withTactic} returns a new intent
 * rather than mutating one. {@code ActRuntime.tickLeases} decides a lease is still in use by asking
 * whether the record still carries <i>the intent object</i> the lease was granted for. On the first
 * stamped tick that is a different object, so the lease reads as RELEASED and the channel is freed
 * while the player is still standing on it. A second consumer then walks onto the MOVE channel, the
 * two machines publish the same axes, and neither walk is the walk anybody asked for.
 *
 * <p><b>Why this has to be a test and not a reading of the code.</b> The fix is a comparison, and a
 * comparison that is wrong in the direction of "too permissive" still looks correct in every test
 * that only checks a walk completes. What distinguishes the two is the assertion below: the record's
 * intent is asserted to be a DIFFERENT object from the one the lease holds, on every single tick,
 * while the lease is asserted to be the same lease. A test that skipped the first half would pass
 * against the defect it exists to catch, and would keep passing if somebody removed the stamping
 * that causes it -- which is why both halves are here and the first one is the premise.
 *
 * <p><b>Driven through production.</b> {@link MoveApplier} -&gt; {@link RouteExecutor} -&gt;
 * {@link NavController} over a plan the real {@link Planner} produced, with the body advanced from
 * the axes the runtime published. The lease is taken through {@code submit}, which is the DIRECT
 * path every caller in the tree uses, and upkeep runs through {@code tickLeases} exactly as
 * {@code ActTickLoop} calls it.
 */
public final class AReStampedIntentMustNotFreeTheChannelItIsStandingOnTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    private static final int BLOCKS = 12;

    /**
     * Well past the longest reaction wait (8 ticks) so many stamped ticks are seen, and well inside
     * {@link ActRuntime#DEFAULT_MAX_IDLE_TICKS} so the lease is never legitimately reclaimed for
     * idleness -- this test is about identity, and an idle reclaim would be a different defect
     * wearing the same red.
     */
    private static final int TICKS = 30;

    private static FakeActuator corridor() {
        FakeActuator act = new FakeActuator();
        for (int z = -3; z <= BLOCKS + 3; z++) {
            for (int x = -3; x <= 3; x++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.setRotation(0f, 0f);
        return act;
    }

    /**
     * The lease survives every tick of a walk whose intent is a new object on every tick.
     */
    @Test
    public void theLeaseIsHeldForEveryTickOfAReStampedWalk() {
        FakeActuator act = corridor();
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = new Planner(world)
                .plan(new Stance(0, FEET, 0), new Stance(0, FEET, BLOCKS));
        assertTrue("premise: the planner must find the corridor, or there is no walk to hold a "
                + "lease for. It said " + plan.failure(), plan.found());

        GameClock clock = new GameClock();
        clock.reset();
        ActRuntime runtime = new ActRuntime(clock);
        MoveApplier applier = new MoveApplier(act, runtime,
                ri -> new RouteExecutor(plan, ri.blockBudget(), world));
        runtime.registerApplier(ActSlot.MOVE, applier);

        RouteIntent asked = new RouteIntent(0, FEET, BLOCKS, 0);
        runtime.submit(asked);
        ActRuntime.Lease held = runtime.lease(ActSlot.MOVE);
        assertNotNull("premise: submitting must take the lease, or this proves nothing", held);
        assertSame("premise: and the lease names the intent that was submitted", asked,
                held.intent());

        int restamped = 0;
        for (int tick = 1; tick <= TICKS; tick++) {
            clock.advance();
            SlotRecord rec = runtime.record(ActSlot.MOVE);
            if (rec.intent() == null || rec.phase().isTerminal()) {
                break;
            }
            runtime.compareAndStore(ActSlot.MOVE, rec, applier.apply(rec.stampTick(tick)));
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());

            ActIntent carried = runtime.record(ActSlot.MOVE).intent();
            if (carried instanceof RouteIntent route && route.tactic() != null) {
                restamped++;
                assertNotSame("premise, and it is the whole premise: on tick " + tick + " the record "
                                + "must be carrying a FRESH intent object, because the applier "
                                + "stamps the chosen tactic by building a new one. If this ever "
                                + "stops being true the test below has stopped testing anything",
                        asked, carried);
            }

            runtime.tickLeases(clock.lastCompletedTick());
            assertNotNull("tick " + tick + ": the lease was released while the body was still "
                            + "walking. The record carries a re-stamped copy of the same goal, and "
                            + "the MOVE channel has just been handed to somebody else mid-walk",
                    runtime.lease(ActSlot.MOVE));
            assertSame("tick " + tick + ": and it must be the SAME lease, not a fresh one -- a "
                    + "channel that changed hands is not a channel that was held", held,
                    runtime.lease(ActSlot.MOVE));
        }

        assertTrue("premise: the loop must have run long enough to see the stamping happen. It saw "
                + restamped + " re-stamped tick(s) out of " + TICKS + ", and a run of zero "
                + "means the assertions above proved nothing", restamped > 0);
        assertTrue("premise: and the walk must still be live, or there is no mid-walk lease to "
                        + "hold: " + runtime.record(ActSlot.MOVE).phase(),
                runtime.record(ActSlot.MOVE).isLive());
        assertEquals("the walk is still the one that was asked for: the goal on the record is the "
                        + "goal the lease was granted for, whatever object is carrying it",
                ((RouteIntent) runtime.record(ActSlot.MOVE).intent()).targetZ(), BLOCKS);
    }
}
