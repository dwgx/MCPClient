package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.NavHazard;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import org.junit.Test;

/**
 * A river is routed as SWIM moves, swum by holding forward (and jump to rise), and the drowning
 * warning survives the capability that made it look obsolete.
 *
 * <p><b>The defect this pins, part one: the gap.</b> A cell in a river has no solid block under it
 * and no ladder in it, so every one of the four original generators refused it and the planner
 * answered "no route" for water a player swims across in four seconds. That is a claim about the
 * world it has not earned, and it is the same shape of defect the whole planner exists to remove.
 *
 * <p><b>The defect this pins, part two, and it is the subtle one: the warning.</b> The obvious
 * response to "the agent can now swim" is to stop warning about water -- the route obviously means
 * to be in it. That would be a silent regression in the safety layer, and it would leave
 * {@link NavHazard.Kind#WATER} true only of a controller nothing routes through any more. So
 * {@code SwimSteering} publishes a hazard of the SAME kind on every tick, and what changed is the
 * detail: the consequence of swimming is not "this controller cannot swim" any more, it is a
 * countdown on the air. A caller that learned "cancel on WATER" keeps cancelling, which is the
 * correct response to a river, and now it cancels with a number to act on. That is what
 * {@link #theDrowningWarningSurvivesTheAbilityToSwim} asserts, and it is the assertion that would
 * fail if someone "fixed" the warning by deleting it.
 *
 * <p><b>Why the fake cannot cheat.</b> The physics is vanilla's, not the controller's opinion of
 * it: the body crosses only on a tick where the forward axis was published, rises only on a tick
 * where JUMP was published ({@code EntityLivingBase.onLivingUpdate:2010-2013} routes
 * {@code isJumping} into {@code updateAITick}, which adds to {@code motionY}), and sinks slightly
 * on every other tick because that is what the water branch's drag does. Both axes are read back
 * from {@link ActRuntime}, where vanilla's own {@code MovementInput} reads them.
 */
public class WaterIsSwumAndTheDrowningWarningSurvivesTest {

    /** A river four cells wide at y=63..64, with solid ground on both banks. */
    private static FakeWorld river() {
        FakeWorld w = new FakeWorld(0);
        // The near bank: solid floor at y=63, so a stance at (0,64,0) is dry ground.
        w.floor(-3, 0, 63, -2, 2);
        // The river itself: water at both body rows and no floor anywhere under it, which is what
        // makes every pre-existing generator refuse these cells.
        for (int x = 1; x <= 4; x++) {
            w.water(x, 63, 0);
            w.water(x, 64, 0);
        }
        // The far bank.
        w.floor(5, 8, 63, -2, 2);
        return w;
    }

    @Test
    public void thePlannerRoutesARiverAsSwims() {
        Planner.Plan plan = new Planner(river()).plan(new Stance(0, 64, 0), new Stance(5, 64, 0));

        assertTrue("four blocks of open water is a route a player can swim: " + plan.failure(),
                plan.found());
        List<Move> swims = plan.moves().stream()
                .filter(m -> m.kind() == Move.Kind.SWIM).toList();
        assertEquals("and all four blocks of it are swum -- the river has no floor, so this is the "
                + "only kind that can cross it", 4, swims.size());
        for (Move m : swims) {
            assertTrue("a swim puts the player in water, so it spends air: vanilla drains one per "
                            + "submerged tick (EntityLivingBase:301) and a plan that does not count "
                            + "that is a plan that drowns",
                    m.airTicks() > 0);
        }
    }

    @Test
    public void aSwimIsRefusedWhenTheAirBudgetCannotCoverIt() {
        // A river 40 blocks across is not a swim, it is a drowning. The generator must refuse the
        // moves rather than price them expensively: "expensive" and "kills the player" are
        // different facts, and a cost cannot say the second one.
        FakeWorld w = new FakeWorld(0);
        w.floor(-2, 0, 63, -2, 2);
        for (int x = 1; x <= 40; x++) {
            w.water(x, 63, 0);
            w.water(x, 64, 0);
        }
        w.floor(41, 44, 63, -2, 2);

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(41, 64, 0));

        assertTrue("vanilla gives 300 ticks of air and a block of swim costs 13 (Move: the water "
                + "branch moves a body 0.08 blocks a tick, so 12.5 ticks, rounded away from zero), "
                + "so 40 blocks is 520 ticks -- more than the player has", !plan.found());
        assertTrue("and the refusal must say it is the air, not the terrain: a caller that reads "
                + "'no route' as 'load the chunks' would go and load chunks that are already there. "
                + "Message was: " + plan.failure(), plan.failure().contains("air"));
    }

    @Test
    public void aSwimIsCompletedBecauseAMovementKeyIsPublished() {
        // One block of river, so the assertions are about the mechanics rather than a long route.
        Move swim = Move.swim(new Stance(0, 64, 0), new Stance(1, 64, 0));
        FakeActuator act = inWater(0, 64, 0);

        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(
                    new Planner.Plan(List.of(swim), null, 0), ri.blockBudget());
            return machine[0];
        });

        SlotRecord rec = SlotRecord.submitted(new RouteIntent(1, 64, 0, 0), 0L, 1L, "submitted");
        boolean sawAMovementKey = false;
        int guard = 0;
        while (!rec.phase().isTerminal() && guard++ < 200) {
            rec = applier.apply(rec.stampTick(guard));
            // A key, not specifically FORWARD. At the fake's yaw of 0 the river runs across the
            // player's right, and vanilla's own rotation makes the correct press a strafe --
            // NavController.keysForBearing for a due-east bearing at yaw 0 returns (0, +1).
            // Asserting "forward" here would be asserting that the controller ignores the camera,
            // which is the opposite of what the axis code is for.
            boolean moved = runtime.moveForward() != 0f || runtime.moveStrafe() != 0f;
            sawAMovementKey |= moved;
            swimPhysics(act, moved, false);
        }

        assertTrue("a movement key must have been published: crossing water IS holding a direction "
                + "(EntityLivingBase:1700-1733), and this fake crosses on nothing else",
                sawAMovementKey);
        assertEquals("and the route must finish: " + rec.message(), ActPhase.COMPLETE, rec.phase());
        assertTrue("and the player across, within the arrival tolerance this class documents "
                        + "(0.6 blocks, RouteExecutor.ARRIVE_TOLERANCE / ClimbSteering's own) rather "
                        + " than exactly on the centre: a discrete 0.08-blocks-a-tick swim arrives "
                        + "wherever it lands inside the epsilon, and demanding the exact centre "
                        + "would be demanding a precision vanilla's own drag does not offer",
                Math.abs(act.position()[0] - 1.5D) <= 0.6D);
    }

    @Test
    public void aSwimUpIsCompletedBecauseTheJumpAxisIsPublished() {
        // Two water cells stacked: rising out of the bottom one is the move that makes any of this
        // survivable, and it is a different vanilla action from crossing -- jump, not just forward.
        Move up = Move.swim(new Stance(0, 63, 0), new Stance(0, 64, 0));
        assertEquals("the move must name itself a swim", Move.Kind.SWIM, up.kind());
        assertEquals("and rising must cost more air than crossing, because vanilla's own constants "
                + "make it slower (0.06 blocks a tick against 0.08)", 17, up.airTicks());

        FakeActuator act = inWater(0, 63, 0);
        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(new Planner.Plan(List.of(up), null, 0), ri.blockBudget());
            return machine[0];
        });

        SlotRecord rec = SlotRecord.submitted(new RouteIntent(0, 64, 0, 0), 0L, 1L, "submitted");
        boolean sawJump = false;
        int guard = 0;
        while (!rec.phase().isTerminal() && guard++ < 200) {
            rec = applier.apply(rec.stampTick(guard));
            boolean jumpAxis = runtime.jump();
            sawJump |= jumpAxis;
            swimPhysics(act, runtime.moveForward() != 0f, jumpAxis);
        }

        assertTrue("the jump axis must have been published for a rising swim. Holding jump in water "
                + "is the whole of vanilla's swim-up (EntityLivingBase:2010-2013 into "
                + "updateAITick:1591), and this fake rises on nothing else -- without it the player "
                + "sinks and the route can never surface", sawJump);
        assertEquals("and the route must finish: " + rec.message(), ActPhase.COMPLETE, rec.phase());
        assertTrue("and the player at the surface -- in the destination's own cell, which is the "
                        + "arrival the executor actually credits, rather than at one exact instant: "
                        + "the fake applies one more tick of vanilla's drag after the terminal tick, "
                        + "so demanding y==64.0 exactly asserts the test's tick ordering rather than "
                        + "the route's behaviour. Was " + act.position()[1],
                act.position()[1] >= 63.9D && act.position()[1] < 65.0D);
    }


    @Test
    public void theDrowningWarningSurvivesTheAbilityToSwim() {
        Move swim = Move.swim(new Stance(0, 64, 0), new Stance(1, 64, 0));
        FakeActuator act = inWater(0, 64, 0);
        act.air = 90;
        RouteExecutor ex = new RouteExecutor(new Planner.Plan(List.of(swim), null, 0), 0);

        NavHazard seen = null;
        for (int i = 0; i < 30; i++) {
            ActOutcome out = ex.tick(act);
            if (out.hazard() != null) {
                seen = out.hazard();
            }
            swimPhysics(act, ex.forward() != 0f, false);
            if (out.terminal()) {
                break;
            }
        }

        assertNotNull("a swim must carry a hazard on every tick. Deleting the water warning because "
                + "'the route obviously means to be in it' is the silent capability regression this "
                + "test exists to catch: a caller that cancels on WATER would stop being told, and "
                + "the player would drown with nothing on the status line", seen);
        assertEquals("and it must be the SAME kind the straight-line walker has been publishing, so "
                + "a caller written against WATER keeps working: inventing a new kind would "
                + "silently disarm every existing caller",
                NavHazard.Kind.WATER, seen.kind());
        assertTrue("the detail must name the air that is actually left, so a caller can size a "
                + "reaction: " + seen.detail(), seen.detail().contains("90"));
        assertTrue("and it must not claim the old thing, which is no longer the consequence of being "
                + "in water: " + seen.detail(),
                !seen.detail().contains("does not swim"));
    }

    @Test
    public void aSwimIsRefusedWhenThePlayerIsNotInWater() {
        // Same shape as the climb refusal, and the same reason: the plan said water, the world is
        // the authority on whether the player is in any.
        Move swim = Move.swim(new Stance(0, 64, 0), new Stance(1, 64, 0));
        FakeActuator act = new FakeActuator();
        act.setPosition(0.5D, 64.0D, 0.5D);
        act.onGround = true;
        act.inWater = false;
        RouteExecutor ex = new RouteExecutor(new Planner.Plan(List.of(swim), null, 0), 0);

        ActOutcome out = null;
        for (int i = 0; i < 40 && (out == null || !out.terminal()); i++) {
            out = ex.tick(act);
        }
        assertNotNull("the route must end", out);
        assertTrue("and it must FAIL: the player is not in water, so there is nothing to swim: "
                + out.message(), out.terminal() && !out.ok());
        assertTrue("and it must say so: " + out.message(), out.message().contains("water"));
    }

    @Test
    public void aSwimBelowTheRoutesLineIsNotAFall() {
        // A swimmer drifts DOWN in vanilla -- the water branch damps motionY by 0.8 and subtracts
        // 0.02 every tick (EntityLivingBase:1726-1728) -- and handleWaterMovement zeroes
        // fallDistance, so there is no damage and no void. The route's fall guard has to know that,
        // or it aborts a healthy swim with a message about a bridge that may not exist.
        Move swim = Move.swim(new Stance(0, 64, 0), new Stance(1, 64, 0));
        FakeActuator act = inWater(0, 64, 0);
        RouteExecutor ex = new RouteExecutor(new Planner.Plan(List.of(swim), null, 0), 0);

        ex.tick(act);
        act.setPosition(0.5D, 60.0D, 0.5D);   // four blocks low, well past FALL_SLACK
        ActOutcome out = ex.tick(act);

        assertTrue("a swimmer below the line is in the river the plan put them in, so the route must "
                + "keep going and let the swim's own arrival and stall tests own what happens: "
                + out.message(), !out.terminal());
    }

    @Test
    public void aWalkOnDryLandCarriesNoWaterHazard() {
        // The other half of "the warning stays true": a hazard a caller learns to ignore is worse
        // than no hazard, so publishing WATER on a walk across a field would be its own regression.
        Move walk = Move.walk(new Stance(0, 64, 0), new Stance(1, 64, 0));
        FakeActuator act = new FakeActuator();
        for (int gx = -2; gx <= 4; gx++) {
            for (int gz = -2; gz <= 2; gz++) {
                act.putBlock(gx, 63, gz);
            }
        }
        act.setPosition(0.5D, 64.0D, 0.5D);
        act.onGround = true;
        RouteExecutor ex = new RouteExecutor(new Planner.Plan(List.of(walk), null, 0), 0);

        NavHazard seen = null;
        for (int i = 0; i < 30; i++) {
            ActOutcome out = ex.tick(act);
            if (out.hazard() != null) {
                seen = out.hazard();
            }
            double[] p = act.position();
            act.setPosition(p[0] + 0.2D, p[1], p[2]);
            if (out.terminal()) {
                break;
            }
        }
        assertNull("a walk across dry ground must not invent a hazard: " + seen, seen);
    }

    private static FakeActuator inWater(int x, int y, int z) {
        FakeActuator act = new FakeActuator();
        // MID-CELL, and that is not decoration. A body placed exactly on the cell floor starts one
        // tick below the row the plan named, because vanilla's water drag subtracts 0.02 of motionY
        // every tick whatever the input (EntityLivingBase:1726-1728) and the first tick is spent
        // binding the route rather than moving. A real player in water is not standing on the
        // integer boundary either, and a fake that starts them there makes arrival depend on which
        // tick the executor happens to publish its first axis.
        act.setPosition(x + 0.5D, y + 0.5D, z + 0.5D);
        act.onGround = false;   // a swimmer is never on the ground
        act.inWater = true;
        act.air = 300;
        return act;
    }


    /**
     * Vanilla's swim, reduced to the two rules under test.
     *
     * <p>Rising is JUMP ALONE, and that is not a simplification. {@code onLivingUpdate:2008-2013}
     * tests {@code isJumping} against {@code isInWater()} with no reference to forward at all, so a
     * player holding only jump rises in vanilla. An earlier version of this fake required forward as
     * well and the swim-up test failed against correct code -- the fake was stricter than the game,
     * which is the direction that turns a passing test into a meaningless one.
     *
     * <p>Anything else sinks a little, because the water branch damps {@code motionY} by 0.8 and
     * subtracts 0.02 every tick regardless of input ({@code 1726-1728}) -- and that sink is what
     * {@link #aSwimBelowTheRoutesLineIsNotAFall} has to be honest about.
     */
    private static void swimPhysics(FakeActuator act, boolean forwardAxis, boolean jumpAxis) {
        double[] p = act.position();
        if (jumpAxis) {
            act.setPosition(p[0], p[1] + 0.06D, p[2]);
        } else if (forwardAxis) {
            act.setPosition(p[0] + 0.08D, p[1], p[2]);
        } else {
            act.setPosition(p[0], p[1] - 0.02D, p[2]);
        }
        act.onGround = false;
    }
}
