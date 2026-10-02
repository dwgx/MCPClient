package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import org.junit.Test;

/**
 * A one-block rise completes end to end, and it completes on the JUMP AXIS or not at all.
 *
 * <p><b>The defect.</b> {@code NeighborGen} emits {@code STEP_UP} for a one-block rise (vanilla's
 * step height is 0.6, so a full block needs a jump), and the planner happily routes over one. But
 * {@code MoveApplier} published {@code LocomotionAxes(forward, strafe, false, false, false)} with
 * the jump slot hardcoded false and no controller could say otherwise: a 0.6-wide body walking into
 * the step is stopped 0.8 blocks from the destination's centre, outside the 0.7-block arrival
 * tolerance, so the route died on move one and blamed the geometry.
 *
 * <p><b>Why these assertions catch it.</b> The fake below is deliberately unable to cheat: it
 * simulates the body walking until the step's face stops it, and it lifts the player onto the step
 * ONLY on a tick where the jump axis the applier published was true. So the route can complete on
 * the new code and cannot on the old one -- where the run ends FAILED with the player pressed
 * against the step. The axis is read back from the {@code ActRuntime}, which is where vanilla's
 * input actually reads it, so this is the published value and not a private field.
 */
public class AStepUpIsWalkedBecauseTheJumpAxisIsPublishedTest {

    /** Standing on the block at (1,64,0) puts the feet at y=65. */
    private static final double STEP_TOP = 65.0D;

    /**
     * How close the body can get to the destination's centre while still below the step: it is 0.6
     * wide, so the centre stops 0.3 from the step's face, and the block's centre is 0.5 beyond it.
     */
    private static final double PRESSED = 0.8D;

    /** Flat ground at y=63 with a single solid block at (1,64,0): a one-block step, nothing else. */
    private static FakeWorld steppedGround() {
        return new FakeWorld(0).floor(-4, 12, 63, -4, 4).solid(1, 64, 0);
    }

    /**
     * The actuator side. It carries no block store on purpose: a {@code STEP_UP} move reads no
     * blocks -- its destination is already standable in the plan, and the executor's arrival check
     * asks the position and the ground flag -- so blocks here would be scenery the test never
     * consults.
     */
    private static FakeActuator steppedActuator() {
        FakeActuator act = new FakeActuator();
        act.setPosition(0.5D, 64.0D, 0.5D);
        act.onGround = true;
        return act;
    }

    @Test
    public void aRouteWithAOneBlockRiseCompletesBecauseTheJumpAxisIsPublished() {
        Planner.Plan plan = new Planner(steppedGround())
                .plan(new Stance(0, 64, 0), new Stance(1, 65, 0));

        assertTrue("a one-block step is reachable: " + plan.failure(), plan.found());
        assertEquals("and the only way onto it is the step itself", 1, plan.moves().size());
        assertEquals("the planner must emit the STEP_UP that needs the jump: step height is 0.6 and "
                + "the rise is 1.0, so no amount of walking gets the body up",
                Move.Kind.STEP_UP, plan.moves().get(0).kind());

        FakeActuator act = steppedActuator();
        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(plan, ri.blockBudget());
            return machine[0];
        });

        SlotRecord rec = SlotRecord.submitted(new RouteIntent(1, 65, 0, 0), 0L, 1L, "submitted");

        boolean sawJump = false;
        int guard = 0;
        while (!rec.phase().isTerminal() && guard++ < 200) {
            rec = applier.apply(rec.stampTick(guard));
            // What the applier published is what vanilla's input would read this tick.
            boolean jumpAxis = runtime.jump();
            sawJump |= jumpAxis;
            physics(act, jumpAxis);
        }

        assertTrue("the jump axis must have been published. The step cannot be walked into, and "
                + "this fake steps up on nothing else, so without the axis the run below ends with "
                + "the body pressed against the step", sawJump);
        assertEquals("and the route must finish: " + rec.message(),
                ActPhase.COMPLETE, rec.phase());
        assertEquals("with the move credited", 1, machine[0].movesDone());
        assertEquals("and the player standing on top of the step",
                STEP_TOP, act.position()[1], 0.0001D);
    }

    /**
     * The client's side of the tick, reduced to the one rule under test: the body walks toward the
     * destination, is stopped by the step's face while the feet are below it, and clears the lip only
     * on a tick where the jump axis was set.
     */
    private static void physics(FakeActuator act, boolean jumpAxis) {
        double[] p = act.position();
        double dist = Math.sqrt(Math.pow(1.5D - p[0], 2) + Math.pow(0.5D - p[2], 2));
        if (p[1] < STEP_TOP) {
            advance(act, Math.max(0.0D, dist - PRESSED));
            if (jumpAxis && dist <= 0.9D) {
                double[] q = act.position();
                act.setPosition(q[0], STEP_TOP, q[2]);
            }
            act.onGround = true;
        } else {
            advance(act, 0.25D);
            act.onGround = true;
        }
    }

    /** Move toward the destination's centre by at most a tick of travel, snapping when close. */
    private static void advance(FakeActuator act, double step) {
        double[] p = act.position();
        double dx = 1.5D - p[0];
        double dz = 0.5D - p[2];
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= step) {
            act.setPosition(1.5D, p[1], 0.5D);
        } else {
            act.setPosition(p[0] + dx / d * step, p[1], p[2] + dz / d * step);
        }
    }
}
