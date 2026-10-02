package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * The other direction of the same comparison: a per-tick republication of the same goal must NOT
 * read as a fresh walk.
 *
 * <p><b>What this is guarding.</b> {@link ActPlanInterpreter} asks two questions about a step's
 * slot. "Are these the same WALK?" is {@link ActRuntime#sameIntent} and "is this still OURS?" is
 * {@code takenByAnother}, and the fix for the teardown defect could have been paid for here: an
 * interpreter that answered the ownership question with {@code ==} would abort its own step on the
 * first stamped tick with "superseded ... racing act_set" while nothing superseded it, and would
 * decline to tear down its own walk. Both are wrong in the direction that looks like safety.
 *
 * <p><b>Driven through production.</b> {@link MoveApplier} -&gt; {@link RouteExecutor} -&gt;
 * {@link NavController} over a plan the real {@link Planner} produced, with the body advanced from
 * the axes the runtime published, and the sequencer stepped through {@code ActTickLoop} the way
 * it runs on a live client. Every assertion below is about a walk that is genuinely in progress,
 * so none of it can pass by the plan never having started.
 */
public class AReStampedWalkIsStillOursToThePlanTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    private static final int BLOCKS = 12;

    /**
     * Well past the longest reaction wait (8 ticks) so many stamped ticks are seen, and well inside
     * {@link ActRuntime#DEFAULT_MAX_IDLE_TICKS} so the lease is never legitimately reclaimed for
     * idleness -- this is about identity, and an idle reclaim would be a different defect wearing
     * the same red.
     */
    private static final int TICKS = 30;

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;
    private FakeActuator act;

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

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
        act = corridor();

        BlockView world = BodySim.blockView(act);
        Planner.Plan route = new Planner(world)
                .plan(new Stance(0, FEET, 0), new Stance(0, FEET, BLOCKS));
        assertTrue("premise: the planner must find the corridor, or there is no walk to hold a "
                + "lease for. It said " + route.failure(), route.found());

        runtime.registerApplier(ActSlot.MOVE,
                new MoveApplier(act, runtime, ri -> new RouteExecutor(route, ri.blockBudget(), world)));
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
    }

    /**
     * A plan step's walk survives its own re-stamping: same lease every tick, never aborted as
     * superseded, and the plan advances on arrival.
     */
    @Test
    public void aStampedWalkIsNeverReadAsAFreshOneByThePlan() {
        runtime.submitPlan(ActPlan.parse(List.of(
                Map.of("move", Map.of("go_to", List.of(0.0, (double) FEET, (double) BLOCKS),
                        "blockBudget", 0)),
                Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3)))));
        RouteIntent asked = (RouteIntent) runtime.record(ActSlot.MOVE).intent();
        assertNotNull("premise: the plan must hold the MOVE channel", asked);
        assertEquals("premise: and the lease is the plan's",
                ActRuntime.PLAN_OWNER, runtime.lease(ActSlot.MOVE).owner());
        ActRuntime.Lease held = runtime.lease(ActSlot.MOVE);
        assertSame("premise: and it names the intent the plan submitted", asked, held.intent());

        int restamped = 0;
        for (int tick = 1; tick <= TICKS; tick++) {
            loop.onTick(new TickEvent(clock.advance()));
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());

            ActPlanStatus st = runtime.planStatus();
            if (st.phase() != ActPlanStatus.Phase.RUNNING) {
                // Arrived, or failed for a reason of its own. Both are the plan's own outcome and
                // neither is what this test is about.
                assertTrue("the plan ended on tick " + tick + " saying '" + st.message()
                                + "', which is not a walk completing: a re-stamped walk must not be "
                                + "read as a fresh one",
                        st.phase() == ActPlanStatus.Phase.COMPLETE
                                || st.message().toLowerCase().contains("fail"));
                assertTrue("premise: the run must have been long enough to see stamping happen. It "
                        + "saw " + restamped + " re-stamped tick(s) out of " + TICKS
                        + ", and a run of zero means the assertions below proved nothing",
                        restamped > 0);
                return;
            }

            assertEquals("tick " + tick + ": the plan still holds the MOVE channel, so the walk it "
                            + "is replaying was not read as a foreign one (" + st.message() + ")",
                    ActRuntime.PLAN_OWNER, runtime.lease(ActSlot.MOVE).owner());
            assertSame("tick " + tick + ": and it is the SAME lease, not a fresh one", held,
                    runtime.lease(ActSlot.MOVE));
            assertTrue("tick " + tick + ": the plan reports waiting on the walk it is replaying, "
                            + "which is what a running step looks like. It said " + st.waitingOn(),
                    st.waitingOn().contains("move"));
            assertFalse("tick " + tick + ": and not on the NEXT step's channel, which nothing has "
                            + "been submitted on yet. It said " + st.waitingOn(),
                    st.waitingOn().contains("interact"));

            ActIntent carried = runtime.record(ActSlot.MOVE).intent();
            if (carried instanceof RouteIntent route && route.tactic() != null) {
                restamped++;
                assertNotSame("premise, and it is the whole premise: on tick " + tick + " the "
                                + "record must be carrying a FRESH intent object, because the applier "
                                + "stamps the chosen tactic by building a new one. If this ever stops "
                                + "being true the test below has stopped testing anything",
                        asked, carried);
                assertTrue("tick " + tick + ": and the fresh object is still the same WALK, which is "
                                + "what the plan compares", ActRuntime.sameIntent(asked, carried));
            }
        }

        assertTrue("premise: the loop must have run long enough to see stamping happen. It saw "
                + restamped + " re-stamped tick(s) out of " + TICKS
                + ", and a run of zero means the assertions above proved nothing", restamped > 0);
    }

    /**
     * The other half: an interpreter that reads a re-stamped walk as a fresh one would also
     * decline to tear down its own walk on teardown, leaving the player walking a plan the caller
     * just cancelled. Same goal, no rival owner, so the lease says this is ours.
     */
    @Test
    public void aStampedWalkIsStillTornDownWhenThePlanIsCancelled() {
        runtime.submitPlan(ActPlan.parse(List.of(
                Map.of("move", Map.of("go_to", List.of(0.0, (double) FEET, (double) BLOCKS),
                        "blockBudget", 0)))));
        RouteIntent asked = (RouteIntent) runtime.record(ActSlot.MOVE).intent();

        int stamped = 0;
        for (int tick = 1; tick <= TICKS; tick++) {
            loop.onTick(new TickEvent(clock.advance()));
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
            if (!runtime.record(ActSlot.MOVE).isLive()) {
                break;
            }
            if (runtime.record(ActSlot.MOVE).intent() instanceof RouteIntent r && r.tactic() != null) {
                stamped++;
            }
        }

        assertTrue("premise: the teardown has to land on a re-stamped walk, or this proves nothing "
                + "about re-stamping. It saw " + stamped + " stamped tick(s)", stamped > 0);
        assertTrue("premise: and the walk is still live going into the cancel",
                runtime.record(ActSlot.MOVE).isLive());
        assertTrue("premise: and the intent on the record is no longer the object the plan "
                        + "submitted, so an identity test would have called this a foreign walk",
                runtime.record(ActSlot.MOVE).intent() != asked);

        runtime.cancelPlan();

        SlotRecord rec = runtime.record(ActSlot.MOVE);
        assertEquals("the plan reports itself cancelled",
                ActPlanStatus.Phase.CANCELLED, runtime.planStatus().phase());
        assertTrue("and its own re-stamped walk is torn down rather than left walking: "
                        + rec.phase() + " / " + rec.message(),
                rec.cancelRequested() || rec.phase().isTerminal());
    }
}
