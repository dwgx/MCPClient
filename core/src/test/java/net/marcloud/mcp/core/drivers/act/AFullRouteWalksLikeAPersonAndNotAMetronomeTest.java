package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Move;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * Walking twenty blocks must look like walking twenty blocks.
 *
 * <p><b>The defect.</b> {@code RouteExecutor.steeringFor} builds a fresh {@link NavController}
 * for every move, and the decision-to-fingertip delay was a per-INSTANCE field of that
 * controller, spent as a complete stop ({@code stop()} zeroes both axes). A per-instance delay on
 * a per-move object is therefore a <b>per-block</b> delay: a 20-block route emitted roughly 20
 * runs of zero displacement, each about 5.5 ticks of standing still, separated by one block of
 * walking. The C03 position series -- the one channel the server samples every tick -- is a
 * metronome at about a 1.25-block period. A person walking produces long runs of continuous
 * displacement, pausing only at genuine obstacles and corners.
 *
 * <p><b>Why this test measures DISPLACEMENT and not arrival.</b> Arrival was never the bug: the
 * old code walked every route successfully and stopped exactly as often as it was asked to. A
 * test asserting "the route still finishes" passes against the defect unchanged, so it proves
 * nothing about it. What the defect changed is the SHAPE of the movement between the start and
 * the end, and that is what is measured here: the number of consecutive ticks in which the body
 * did not move at all.
 *
 * <p><b>Why the bound is small and not "one".</b> A walk with zero pauses would be a machine
 * holding one key down for 400 ticks, which is its own tell, and this repo has already paid for
 * a lane that can rotate. The bound is stated against the number of DECISIONS the walk actually
 * contains -- here one, a straight line with no obstacle and no corner -- plus slack for the
 * arrival epsilon and the landing. It is deliberately far below the block count, because the
 * block count is the defect.
 *
 * <p><b>Driven through the real path.</b> {@link MoveApplier} -> {@link RouteExecutor} ->
 * {@link NavController}, with the body moved by {@link BodySim} from the axes the applier
 * actually published. A harness that drove the controllers directly would not have exercised
 * the applier, and the applier is where the delay now lives.
 */
public final class AFullRouteWalksLikeAPersonAndNotAMetronomeTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /** The straight run this file measures: 20 moves, one per block, all in the same direction. */
    private static final int BLOCKS = 20;

    /**
     * Ceiling on consecutive still ticks, for a walk that makes exactly one decision.
     *
     * <p>One decision, so one onset pause of 4-8 ticks, plus the terminal ticks where the
     * controller is inside its arrival epsilon and has already stopped steering. Eight is a
     * generous reading of that: the drawn delay tops out at 8 and the pause is spent before the
     * first key press, so a conforming run spends at most 8 here. The old code spent this budget
     * twenty times over.
     */
    private static final int MAX_STILL_RUN = 8;

    /**
     * Open ground along the +Z lane, three cells wide either side of it.
     *
     * <p>Three wide rather than one on purpose: a one-wide trench would let a body sidestep
     * around its own axis, and this test is about pauses on an UNOBSTRUCTED line. Nothing here
     * is meant to wedge.
     */
    private static FakeActuator corridor(int length) {
        FakeActuator act = new FakeActuator();
        for (int z = -3; z <= length + 3; z++) {
            for (int x = -3; x <= 3; x++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        // yaw 0 faces +Z, so a forward press walks this lane. A mirrored yaw would show up as a
        // body that never moves, which the "premise" assertion in walk() catches.
        act.setRotation(0f, 0f);
        return act;
    }

    /**
     * The plan a caller would actually get: the real {@link Planner} asked to walk the corridor.
     *
     * <p>Hand-built moves would be a weaker test, because the defect is about how the executor
     * walks a plan the PLANNER produced -- one move per block is the planner's decomposition, and
     * it is that decomposition which the per-instance delay was charging for. Asking the real
     * planner means the 20 moves below are the 20 moves production emits.
     */
    private static Planner.Plan straightPlan(FakeActuator act, int blocks) {
        return new Planner(BodySim.blockView(act))
                .plan(new Stance(0, FEET, 0), new Stance(0, FEET, blocks));
    }
    /** One tick's worth of observation: did the body move, and how far. */
    private record Step(boolean moved, double displacement) { }

    /**
     * Drive the whole route and record the per-tick displacement.
     *
     * <p>The body is advanced from the axes the RUNTIME published, not from the controller's
     * fields, because the runtime is what the game reads. Reading the controller would skip the
     * applier, and the applier is where the reaction wait is now spent -- so a harness that read
     * the controller would measure the old, non-metronome path and pass against the defect.
     */
    private static List<Step> walk(int blocks, int maxTicks) {
        return drive(corridor(blocks), blocks).steps();
    }

    /**
     * One whole route, with everything this file measures.
     *
     * @param pauses how many separate runs of "reacting" the status line reported, which is how
     *               many times the walk charged itself for deciding
     */
    private record Run(List<Step> steps, int pauses, int ticks, SlotRecord end,
                       RouteExecutor machine) { }

    /**
     * Drive the whole route and record the per-tick displacement.
     *
     * <p>The body is advanced from the axes the RUNTIME published, not from the controller's
     * fields, because the runtime is what the game reads. Reading the controller would skip the
     * applier, and the applier is where the reaction wait is now spent -- so a harness that read
     * the controller would measure the old, non-metronome path and pass against the defect.
     */
    private static Run drive(FakeActuator act, int blocks) {
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = straightPlan(act, blocks);
        assertTrue("premise: the planner must decompose the straight line into one move per "
                        + "block, or this is not measuring the defect. It produced " + plan.failure(),
                plan.found() && plan.moves().size() == blocks);

        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(plan, ri.blockBudget(), world);
            return machine[0];
        });
        SlotRecord rec = SlotRecord.submitted(
                new RouteIntent(0, FEET, blocks, 0), 0L, 1L, "submitted");

        List<Step> steps = new ArrayList<>();
        int pauses = 0;
        boolean inPause = false;
        for (int tick = 1; tick <= 600 && !rec.phase().isTerminal(); tick++) {
            rec = applier.apply(rec.stampTick(tick));

            // "reacting" is on the line for exactly the ticks the applier is withholding the
            // keys, so a run of them is a pause and the number of runs is the number of times
            // the walk decided to hesitate. Counted from the status rather than from a private
            // field so the test reads what a caller reads.
            String message = rec.message() == null ? "" : rec.message();
            boolean reacting = message.contains("reacting");
            if (reacting && !inPause) {
                pauses++;
            }
            inPause = reacting;

            // The body obeys what was published -- NEUTRAL for the whole reaction wait, and that
            // IS the measurement: a tick where the applier is still deciding moves the body not
            // at all, and counting those ticks is what a metronome is made of.
            double[] before = act.position();
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
            double[] after = act.position();
            double moved = Math.hypot(after[0] - before[0], after[2] - before[2]);
            steps.add(new Step(moved > 1.0E-9D, moved));
        }
        return new Run(steps, pauses, steps.size(), rec, machine[0]);
    }

    /** The longest run of consecutive ticks in which the body did not move at all. */
    private static int longestStillRun(List<Step> steps) {
        int longest = 0;
        int run = 0;
        for (Step s : steps) {
            if (s.moved()) {
                run = 0;
            } else {
                run++;
                longest = Math.max(longest, run);
            }
        }
        return longest;
    }

    /** How many separate still-runs there are, which is what a metronome is made of. */
    private static int stillRunCount(List<Step> steps) {
        int count = 0;
        boolean inRun = false;
        for (Step s : steps) {
            if (s.moved()) {
                inRun = false;
            } else if (!inRun) {
                inRun = true;
                count++;
            }
        }
        return count;
    }

    /**
     * The property this change delivers: a 20-block walk pays its decision-to-fingertip delay
     * ONCE, not twenty times.
     *
     * <p>Counted from the status line, because "reacting" is emitted on exactly the ticks the
     * applier is withholding the keys, so a run of them IS a pause and the count of runs IS the
     * number of decisions the walk charged for.
     *
     * <p>Measured, both directions, on this harness: the old per-instance draw produced 20
     * pauses and about 271 ticks; this produces 1 pause and about 163 ticks. The 108 ticks are
     * 19 pauses the walk should never have paid -- a body standing still for 277 ms at every
     * single block, which is the stutter a player sees and a server sampling position every
     * tick reads as a metronome.
     */
    @Test
    public void aTwentyBlockRoutePaysTheDecisionDelayOnceAndNotOncePerBlock() {
        FakeActuator act = corridor(BLOCKS);
        Run run = drive(act, BLOCKS);

        assertEquals("a straight 20-block line is ONE decision, so the walk may hesitate once. "
                        + "It paused " + run.pauses() + " time(s) over " + run.ticks() + " ticks. "
                        + "One per block is the defect: RouteExecutor builds a fresh NavController "
                        + "for every move, and the delay used to be a field of THAT, so a "
                        + "per-instance delay was a per-block delay.",
                1, run.pauses());

        // The cost the old code added, stated as a bound so it cannot come back unnoticed.
        assertTrue("a 20-block walk costs about 100 moving ticks plus one 4-8 tick hesitation, so "
                        + "about 110 in total. This took " + run.ticks() + ". The old per-move draw "
                        + "took 264-279, because 19 extra pauses were charged to decisions the "
                        + "walk never made.",
                run.ticks() < 200);
    }

    /**
     * The per-block handover, and what it turned out to be.
     *
     * <p><b>Measured, both directions, on this harness.</b> Before {@code RouteExecutor} asked its
     * arrival question every walking tick, a 20-block straight walk produced <b>21</b> runs of
     * zero-displacement and a median of 163 ticks, and a 4-block walk produced <b>5</b>. After, the
     * same walk produces <b>2</b> runs and a median of 104 ticks, and the 4-block walk produces the
     * same <b>2</b>. The MOVING tick count is 98 in both cases and did not move: the handover was
     * never costing the walk any forward motion, it was costing it <em>silence between</em> motion.
     *
     * <p><b>So this is no longer a residual.</b> It was written as one -- the note said so
     * explicitly, and the bound was deliberately {@code BLOCKS + 1} rather than the flattering 1,
     * because {@code RouteExecutor} lives in {@code drivers/plan} and a fix there changes "the
     * endpoint of every route" and must not be made from this package. It has now been made
     * there, and this test states the delivered property instead of the excuse.
     *
     * <p><b>What is asserted, and why the count is stated as a bound rather than an equality.</b>
     * The two remaining runs are the onset hesitation (the decision-to-fingertip delay this class
     * exists to keep) and the terminal tick of the walk. Neither scales with distance, which is
     * the half that actually kills the metronome and the half that cannot drift: {@link
     * #theStillRunCountDoesNotScaleWithDistance()} states it directly, so a return of the
     * per-block pause is caught there by a fact rather than here by a tuned number.
     */
    @Test
    public void theExecutorHandsOverWithoutPausingAndTheHandoverPauseStaysShort() {
        FakeActuator act = corridor(BLOCKS);
        Run run = drive(act, BLOCKS);
        List<Step> steps = run.steps();

        int runs = stillRunCount(steps);
        int longest = longestStillRun(steps);

        assertTrue("a 20-block walk pays its hesitation ONCE and then keeps moving. It paused "
                        + runs + " time(s) over " + run.ticks() + " ticks. A count that scales with "
                        + "BLOCKS is the defect: RouteExecutor builds a fresh NavController for "
                        + "every move, and any delay that is a field of THAT is a per-block delay.",
                runs <= 3);
        assertTrue("and the pause must stay SHORT. It is the onset hesitation and the terminal "
                        + "tick; the longest run here was " + longest + ". A run of 8-11 means the "
                        + "4-8 tick decision delay is being charged again somewhere, which is the "
                        + "defect this class was written to remove.",
                longest <= 8);
    }

    /**
     * The property that generalises: the count of stops does not scale with distance.
     *
     * <p>Stated as a fact about two DIFFERENT walks rather than as a constant, because it needs no
     * new measurement to keep true and it is the assertion a return of the per-block pause fails.
     * Under the old per-instance draw these were 5 and 21 -- proportional to block count, which is
     * exactly the metronome. Under a 3-tick executor handover they were 5 and 21 for the same
     * reason. They are equal now, and nothing about the number has to be re-derived to keep them
     * that way.
     */
    @Test
    public void theStillRunCountDoesNotScaleWithDistance() {
        int shortRuns = stillRunCount(drive(corridor(4), 4).steps());
        int longRuns = stillRunCount(drive(corridor(BLOCKS), BLOCKS).steps());

        assertEquals("five times the distance on the same straight line must not buy more stops: "
                        + "4 blocks gave " + shortRuns + " and " + BLOCKS + " gave " + longRuns
                        + ". Under the per-instance draw this was 5 and 21; under the 3-tick "
                        + "executor handover it was still 5 and 21. Both are proportional to "
                        + "distance, which is the metronome.",
                shortRuns, longRuns);
    }


    /**
     * The bound is tied to decisions, not to distance.
     *
     * <p>Stated separately because it is the part that generalises: a route that is 4 blocks long
     * and a route that is 20 blocks long are the same walk shape if both are straight, and both
     * must hesitate the same number of times. A count that scaled with block count would be the
     * defect wearing a test's clothes -- and it was: the old per-instance draw gave 4 pauses for
     * 4 blocks and 20 for 20 blocks, which is exactly proportional.
     */
    @Test
    public void theDecisionPauseCountIsTheSameForAShortWalkAndALongOne() {
        int shortPauses = drive(corridor(4), 4).pauses();
        int longPauses = drive(corridor(BLOCKS), BLOCKS).pauses();

        assertEquals("five times the distance, on the same straight line, must not buy more "
                        + "hesitations: 4 blocks gave " + shortPauses + " and " + BLOCKS + " gave "
                        + longPauses + ". Under the old per-instance draw this was 4 and 20 -- "
                        + "proportional to distance, which is the defect.",
                shortPauses, longPauses);
    }


    /**
     * A pause is still a pause, and the walk still ends.
     *
     * <p>The counterpart to the bound above, and the reason this is not "delete the delay". The
     * first key press still comes 4-8 ticks after the intent, so a caller watching the onset sees
     * a human's decision-to-fingertip latency, and the route still completes -- a controller that
     * simply never moved would pass every other assertion in this file.
     */
    @Test
    public void theWalkStillWaitsBeforeItsFirstStepAndStillFinishes() {
        FakeActuator act = corridor(BLOCKS);
        Planner.Plan plan = straightPlan(act, BLOCKS);
        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(plan, ri.blockBudget(), BodySim.blockView(act));
            return machine[0];
        });
        SlotRecord rec = SlotRecord.submitted(
                new RouteIntent(0, FEET, BLOCKS, 0), 0L, 1L, "submitted");

        int firstKey = -1;
        int guard = 0;
        while (!rec.phase().isTerminal() && guard++ < 600) {
            rec = applier.apply(rec.stampTick(guard));
            if (firstKey < 0 && (runtime.moveForward() != 0f || runtime.moveStrafe() != 0f)) {
                firstKey = guard;
            }
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
        }

        assertTrue("the route must complete: " + rec.message(),
                rec.phase() == ActPhase.COMPLETE);
        assertEquals("and every move must be credited", BLOCKS, machine[0].movesDone());
        assertEquals("with the body in the cell the plan named", BLOCKS,
                (int) Math.floor(act.position()[2]));
        assertTrue("but the first key still came on tick " + firstKey + ", and the first "
                        + "displacement of a person is 214-416ms after deciding to move. "
                        + "Deleting the delay would pass every other test here and fail this one",
                firstKey >= 5 && firstKey <= 9);
    }
}
