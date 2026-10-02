package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * A plan teardown must not cancel a command the model issued for itself.
 *
 * <p><b>The defect.</b> {@link ActPlanInterpreter} decided "is this slot still running <i>my</i>
 * walk?" with {@link ActRuntime#sameIntent}, which answers a different question -- "are these the
 * same WALK?" -- and is goal-keyed for a {@link RouteIntent} because the MOVE applier re-stamps
 * the record's intent every tick to carry the chosen {@link MoveTactic} onto it. So a model's own
 * {@code act_set go_to} naming the same target, budget and creep reads as the plan's intent, and
 * {@code cancelWaitingSlots} -- run by {@code act_plan}'s cancel, by a new bind, and by every
 * abort -- cancelled a command the model had been told {@code accepted:true}.
 *
 * <p><b>Why the plan's own status cannot be the receipt.</b> The plan reports itself CANCELLED
 * with a message about the plan, which is true and useless: the casualty is a DIFFERENT command
 * and nothing anywhere names it. The evidence has to be read off the slot and the lease, which is
 * what these tests assert.
 *
 * <p><b>Why the racing intent has the SAME goal on purpose.</b> A racing submit to a different
 * block was never the defect: goal inequality already told the interpreter to keep its hands off.
 * The whole failure lives in the case where the two goals are equal, which is also the realistic
 * one -- a model re-issuing the walk it can see the plan already doing.
 *
 * <p><b>Driven through production.</b> {@link ActPlan#parse} -&gt; {@code submitPlan} -&gt; a real
 * {@code submit} -&gt; the real {@link ActTickLoop} -&gt; {@code cancelPlan}, with a real
 * {@link MoveApplier} over a real {@link RouteExecutor} so the walk is genuinely live and the
 * record is genuinely re-stamped rather than a stub.
 */
public class APlanTeardownMustNotCancelTheRacingActSetTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    private static final int GOAL_X = 5;
    private static final int BUDGET = 64;
    private static final String DIRECT = ActRuntime.DIRECT_OWNER + "/" + ActRuntime.Priority.DIRECT;

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;
    private FakeActuator act;

    /** Open ground, so the walk runs rather than failing on terrain it never reaches. */
    private static FakeActuator corridor() {
        FakeActuator act = new FakeActuator();
        for (int z = -3; z <= 12; z++) {
            for (int x = -3; x <= 12; x++) {
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
                .plan(new Stance(0, FEET, 0), new Stance(GOAL_X, FEET, 0));
        assertTrue("premise: the planner must find the route, or the MOVE slot fails instead of "
                + "walking and this proves nothing. It said " + route.failure(), route.found());

        runtime.registerApplier(ActSlot.MOVE,
                new MoveApplier(act, runtime, ri -> new RouteExecutor(route, ri.blockBudget(), world)));
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(act));
    }

    /** A plan whose step 0 is a route to the same block the model is about to ask for itself. */
    private static ActPlan routePlan() {
        return ActPlan.parse(List.of(
                Map.of("move", Map.of("go_to", List.of((double) GOAL_X, (double) FEET, 0.0),
                        "blockBudget", BUDGET))));
    }

    /**
     * Run one real tick: the applier stamps the record and the body moves, so the slot carries a
     * live walk by the time the teardown runs.
     */
    private void tick() {
        loop.onTick(new TickEvent(clock.advance()));
        BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
    }

    private SlotRecord move() {
        return runtime.record(ActSlot.MOVE);
    }

    /**
     * The plan walks to (5, 64, 0). The model, seeing a walk already under way, issues the same
     * walk itself. Cancelling the plan must leave that command alone.
     */
    @Test
    public void cancellingAPlanLeavesTheRacingActSetRunningAndHoldingItsLease() {
        runtime.submitPlan(routePlan());
        RouteIntent planned = (RouteIntent) move().intent();
        assertNotNull("premise: the plan must hold the MOVE channel, or this proves nothing",
                planned);
        assertEquals("precondition: the goal the plan is walking", GOAL_X, planned.targetX());

        // The model re-issues the same walk. Direct outranks Replay, so it takes the channel.
        RouteIntent raced = new RouteIntent(GOAL_X, FEET, 0, BUDGET, false);
        runtime.submit(raced);
        assertSame("precondition: the racing act_set is what the slot is carrying now",
                raced, move().intent());
        assertEquals("precondition: and the channel is the model's, not the plan's",
                DIRECT, runtime.leaseHolder(ActSlot.MOVE));
        assertTrue("precondition: premise of the whole defect -- the two walks have the SAME goal, "
                        + "so a goal-keyed comparison cannot tell them apart",
                ActRuntime.sameIntent(planned, raced));
        assertTrue("precondition: and they are different objects, so identity could have",
                planned != raced);

        tick();
        assertTrue("precondition: the racing walk is live going into the teardown",
                move().isLive());

        runtime.cancelPlan();

        SlotRecord rec = move();
        assertSame("the racing act_set's intent is still the one in the slot: a plan cancel tore "
                        + "down a command the model was told was accepted. Record reads "
                        + rec.phase() + " / " + rec.message(),
                raced, rec.intent());
        assertEquals("and its lease is intact, so the walk keeps the channel it was granted: "
                        + runtime.leaseHolder(ActSlot.MOVE),
                DIRECT, runtime.leaseHolder(ActSlot.MOVE));
        assertTrue("nothing asked it to stop: cancelRequested is the flag ActRuntime.cancel sets, "
                        + "and the teardown must not have set it (" + rec.message() + ")",
                !rec.cancelRequested());
        assertTrue("and the walk is still live rather than flagged for teardown: " + rec.phase(),
                rec.isLive());
    }

    /**
     * The same walk, torn down by a REBIND rather than by a cancel. {@code bind} runs the same
     * {@code cancelWaitingSlots}, so a fix guarding only the cancel path passes the test above
     * and fails this one.
     */
    @Test
    public void rebindingAPlanLeavesTheRacingActSetRunningToo() {
        runtime.submitPlan(routePlan());
        RouteIntent raced = new RouteIntent(GOAL_X, FEET, 0, BUDGET, false);
        runtime.submit(raced);
        tick();

        // A new plan replaces the old one. The old plan's MOVE slot now belongs to the model.
        runtime.submitPlan(ActPlan.parse(List.of(
                Map.of("look", Map.of("mode", "set", "yaw", 0.0, "pitch", 0.0)))));

        SlotRecord rec = move();
        assertSame("a rebind tore down the racing act_set's walk", raced, rec.intent());
        assertTrue("and asked it to stop: " + rec.message(), !rec.cancelRequested());
        assertEquals("the model's lease survived the rebind: " + runtime.leaseHolder(ActSlot.MOVE),
                DIRECT, runtime.leaseHolder(ActSlot.MOVE));
    }

    /**
     * The third teardown trigger: {@code abort} runs the same {@code cancelWaitingSlots} from
     * inside {@code step}, and a plan that notices it has been superseded aborts there.
     *
     * <p><b>What this pins that the other two cannot.</b> The abort happens INSIDE the tick, with
     * a live foreign walk on the slot, so it is the one path where the plan's own decision to
     * stop and its decision not to harm the racing command are made in the same breath. The
     * message matters as much as the slot: a plan that tears down the model's walk and reports
     * nothing is the failure the reviewer named, and a plan that reports the takeover is the
     * difference between a diagnosable event and a silent one.
     */
    @Test
    public void aSupersededStepAbortsWithoutTearingDownTheWalkThatSupersededIt() {
        runtime.submitPlan(routePlan());
        RouteIntent raced = new RouteIntent(GOAL_X, FEET, 0, BUDGET, false);
        runtime.submit(raced);
        assertEquals("precondition: the channel changed hands", DIRECT,
                runtime.leaseHolder(ActSlot.MOVE));

        loop.onTick(new TickEvent(clock.advance()));

        ActPlanStatus st = runtime.planStatus();
        assertEquals("the plan stops rather than waiting on a walk that is not its own", 
                ActPlanStatus.Phase.CANCELLED, st.phase());
        assertTrue("and it says so, naming the takeover: '" + st.message() + "'",
                st.message().toLowerCase().contains("supersed"));

        SlotRecord rec = move();
        assertSame("the walk that superseded it is still the one in the slot, not torn down by the "
                + "abort that noticed it", raced, rec.intent());
        assertTrue("and not flagged for cancellation: " + rec.message(), !rec.cancelRequested());
        assertEquals("and it kept the lease: " + runtime.leaseHolder(ActSlot.MOVE),
                DIRECT, runtime.leaseHolder(ActSlot.MOVE));
    }

    /**
     * The other half of the same question, and the one a goal-keyed comparison gets right for the
     * wrong reason: a racing submit to a DIFFERENT block was always left alone, and it still is.
     * Pinned so a fix that simply inverted the comparison cannot pass by breaking this.
     */
    @Test
    public void aRacingActSetToADifferentBlockIsStillLeftAlone() {
        runtime.submitPlan(routePlan());
        RouteIntent elsewhere = new RouteIntent(GOAL_X + 4, FEET, 0, BUDGET, false);
        runtime.submit(elsewhere);
        tick();

        runtime.cancelPlan();

        assertSame("a racing act_set to another block must not be torn down either",
                elsewhere, move().intent());
        assertTrue("and must not be flagged: " + move().message(), !move().cancelRequested());
    }

    /**
     * And the plan's own walk is still torn down: the lease check must not turn
     * {@code cancelWaitingSlots} into a no-op. A fix that always answered "not ours" would pass
     * every test above and fail this one.
     */
    @Test
    public void cancellingAPlanStillTearsDownItsOwnUnfinishedWalk() {
        runtime.submitPlan(routePlan());
        tick();
        assertTrue("premise: the plan's own walk is live going into the cancel",
                move().isLive());
        assertEquals("premise: and the channel is the plan's",
                ActRuntime.PLAN_OWNER, runtime.lease(ActSlot.MOVE).owner());

        runtime.cancelPlan();

        assertEquals("the plan reports itself cancelled",
                ActPlanStatus.Phase.CANCELLED, runtime.planStatus().phase());
        assertTrue("and the walk it was replaying is flagged for teardown rather than left "
                        + "walking: " + move().phase() + " / " + move().message(),
                move().cancelRequested() || move().phase().isTerminal());
    }
}
