package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.BodySim;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.NavController;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import org.junit.Test;

/**
 * A twenty-block route must not stop twenty times.
 *
 * <p><b>The defect.</b> {@link RouteExecutor}'s arrival question was asked ONCE, after the steering
 * controller for a move had already stopped. That made the phase handover a per-BLOCK stop rather
 * than a per-ROUTE one: the tick {@code walk()} switched to {@code VERIFYING} published no axes, the
 * tick {@code verify()} nulled {@code nav} published no axes, and the tick {@code startMove()}
 * built the next controller published no axes. Three dead ticks at every block boundary, which on a
 * server sampling position every tick is a body that stops and starts twenty times in a row.
 *
 * <p><b>Why displacement and not arrival.</b> Arrival was never broken: the old code walked every
 * route to the end and stopped exactly as often as it was asked to. A test asserting "the route
 * still finishes" passes against the defect unchanged. What the defect changed is the SHAPE of the
 * movement between the start and the end, so that is what is measured: how many separate runs of
 * ticks the body did not move at all, and how long the longest one was.
 *
 * <p><b>Driven through the real path.</b> {@link MoveApplier} -&gt; {@link RouteExecutor} -&gt;
 * {@link NavController}, with the body moved by {@link BodySim} from the axes the applier actually
 * published. A harness driving the controllers directly would not have exercised the applier, and
 * the applier is the only thing standing between this class's return value and vanilla's input.
 *
 * <p><b>The bound is a property, not a number guessed in advance.</b> The count of still-runs has to
 * be independent of distance, because a straight corridor contains exactly one decision however long
 * it is. That is the assertion that actually kills the metronome: the old code gave 4 runs for 4
 * blocks and 21 for 20, exactly proportional, and any bound written as "one per block" would pass
 * against it.
 */
public final class ARouteHandsOverToTheNextMoveWithoutStoppingTheBodyTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /** The straight run this file measures: one move per block, all in the same direction. */
    private static final int BLOCKS = 20;

    /**
     * Ceiling on the number of separate still-runs, whatever the distance.
     *
     * <p>One is the onset hesitation the decision-to-fingertip delay is for, which is a decision and
     * a decision belongs to a walk rather than to a block. Two is the allowance for the terminal
     * tick, where the controller is inside its arrival epsilon and has already stopped steering. The
     * number is deliberately a ceiling and not an equality: the property being pinned is that the
     * count does not GROW with the block count, and {@code theHandoverPauseCountIsTheSameForAShort
     * WalkAndALongOne} is what checks that directly.
     */
    private static final int MAX_STILL_RUNS = 3;

    /**
     * Open ground along the +Z lane, three cells wide either side of it.
     *
     * <p>Three wide rather than one on purpose: a one-wide trench would let a body sidestep around
     * its own axis, and this file is about pauses on an UNOBSTRUCTED line. Nothing here is meant to
     * wedge.
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
        // body that never moves, which the "premise" assertion in drive() catches.
        act.setRotation(0f, 0f);
        return act;
    }

    /**
     * The plan a caller would actually get: the real {@link Planner} asked to walk the corridor.
     *
     * <p>Hand-built moves would be a weaker test, because the defect is about how the executor walks
     * a plan the PLANNER produced -- one move per block is the planner's decomposition, and the
     * handover is charged once per move, so the planner's decomposition is the multiplier.
     */
    private static Planner.Plan straightPlan(FakeActuator act, int blocks) {
        return new Planner(BodySim.blockView(act))
                .plan(new Stance(0, FEET, 0), new Stance(0, FEET, blocks));
    }

    /** One tick's worth of observation: did the body move, and how far. */
    private record Step(boolean moved, double displacement) { }

    /**
     * One whole route, with everything this file measures.
     *
     * @param cellEntered the first tick the feet were in each move's destination cell
     * @param credited    the tick each move was credited on
     */
    private record Run(List<Step> steps, int stillRuns, int longestStillRun, int ticks,
                       int[] cellEntered, int[] credited, SlotRecord end,
                       RouteExecutor machine) { }

    /**
     * Drive the whole route and record the per-tick displacement.
     *
     * <p>The body is advanced from the axes the RUNTIME published, not from the controller's fields,
     * because the runtime is what the game reads.
     */
    private static Run drive(FakeActuator act, int blocks) {
        Planner.Plan plan = straightPlan(act, blocks);
        assertTrue("premise: the planner must decompose the straight line into one move per "
                        + "block, or this is not measuring the defect. It produced " + plan.failure(),
                plan.found() && plan.moves().size() == blocks);

        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] machine = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            machine[0] = new RouteExecutor(plan, ri.blockBudget(), BodySim.blockView(act));
            return machine[0];
        });
        SlotRecord rec = SlotRecord.submitted(
                new RouteIntent(0, FEET, blocks, 0), 0L, 1L, "submitted");

        int[] cellEntered = new int[blocks];
        int[] credited = new int[blocks];
        java.util.Arrays.fill(cellEntered, -1);
        java.util.Arrays.fill(credited, -1);

        List<Step> steps = new ArrayList<>();
        for (int tick = 1; tick <= 600 && !rec.phase().isTerminal(); tick++) {
            // The position the executor is about to read: the applier ticks the machine before the
            // body is advanced, so this is the world as this tick's tick() will see it.
            double[] seen = act.position();
            int doneBefore = machine[0] == null ? 0 : machine[0].movesDone();
            if (doneBefore < blocks && cellEntered[doneBefore] < 0) {
                Move m = plan.moves().get(doneBefore);
                if ((int) Math.floor(seen[0]) == m.to().x()
                        && (int) Math.floor(seen[2]) == m.to().z()) {
                    cellEntered[doneBefore] = tick;
                }
            }
            rec = applier.apply(rec.stampTick(tick));
            if (machine[0] != null && machine[0].movesDone() > doneBefore && doneBefore < blocks) {
                credited[doneBefore] = tick;
            }

            double[] before = act.position();
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
            double[] after = act.position();
            double moved = Math.hypot(after[0] - before[0], after[2] - before[2]);
            steps.add(new Step(moved > 1.0E-9D, moved));
        }
        return new Run(steps, stillRuns(steps), longestStillRun(steps), steps.size(),
                cellEntered, credited, rec, machine[0]);
    }

    /** The longest run of consecutive ticks in which the body did not move at all. */
    private static int longestStillRun(List<Step> steps) {
        int longest = 0;
        int run = 0;
        for (Step s : steps) {
            run = s.moved() ? 0 : run + 1;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /** How many separate still-runs there are, which is what a metronome is made of. */
    private static int stillRuns(List<Step> steps) {
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
     * The property this change delivers: the number of times the body stands still is a property of
     * the DECISIONS the walk contains, not of its length.
     *
     * <p>A twenty-block straight line is one decision, so it may hesitate once. The old code
     * hesitated once per block because the handover between two moves was a full stop, which is the
     * same metronome the per-move reaction delay was and one layer further out.
     */
    @Test
    public void aStraightRouteStandsStillOnceAndNotOncePerBlock() {
        Run run = drive(corridor(BLOCKS), BLOCKS);

        assertEquals("the route must finish: " + run.end().message(),
                ActPhase.COMPLETE, run.end().phase());
        assertEquals("with every move credited", BLOCKS, run.machine().movesDone());

        assertTrue("a straight 20-block line is ONE decision, so the walk may stand still at most a "
                        + "couple of times over " + run.ticks() + " ticks. It stood still "
                        + run.stillRuns() + " separate time(s), longest run " + run.longestStillRun()
                        + " tick(s). One run per move is the defect: RouteExecutor used to ask its "
                        + "arrival question only after the steering had stopped, and the three "
                        + "ticks of phase handover in between published nothing.",
                run.stillRuns() <= MAX_STILL_RUNS);
        assertTrue("and no single stop may be long. The longest run was " + run.longestStillRun()
                        + " tick(s); a run of 8 or more means the 4-8 tick decision delay is being "
                        + "charged again somewhere.",
                run.longestStillRun() <= 8);
    }

    /**
     * The bound stated as the thing that generalises: distance must not buy extra stops.
     *
     * <p>Kept as its own test because it is the one that fails against the old code for the right
     * reason. A bound written as "at most 21" would also have passed against a defect that stopped
     * once per block; equality between a 4-block and a 20-block walk cannot.
     */
    @Test
    public void theHandoverPauseCountIsTheSameForAShortWalkAndALongOne() {
        int shortRuns = drive(corridor(4), 4).stillRuns();
        int longRuns = drive(corridor(BLOCKS), BLOCKS).stillRuns();

        assertEquals("five times the distance, on the same straight line, must not buy more stops: "
                        + "4 blocks gave " + shortRuns + " and " + BLOCKS + " gave " + longRuns
                        + ". A count that scales with the block count is the per-move handover "
                        + "wearing a test's clothes.",
                shortRuns, longRuns);
    }

    /**
     * Arrival is noticed on the tick the world says so, and not a tick later.
     *
     * <p>This is the half of the change that is about SAFETY rather than appearance. The executor
     * must be able to notice it arrived, and must be able to notice it is stuck, because those are
     * the two things that terminate a route. The old handover spent a tick in {@code VERIFYING}
     * and another in {@code CHECKING} after the body was already standing on the destination, so
     * the credit for a landed move always arrived one tick after the world had earned it.
     *
     * <p>Stated as a lower bound rather than by re-implementing the arrival rule: the feet being in
     * the destination's own cell is NECESSARY for this class to credit a move, and nothing weaker
     * is. Copying the tolerance and the support test here would be a second copy of a rule that
     * lives in one place on purpose, and a second copy is what makes the two drift.
     */
    @Test
    public void everyMoveIsCreditedOnTheTickTheFeetReachItsDestinationCell() {
        Run run = drive(corridor(BLOCKS), BLOCKS);

        for (int k = 0; k < BLOCKS; k++) {
            assertTrue("move " + (k + 1) + " was never credited", run.credited()[k] > 0);
            assertTrue("move " + (k + 1) + " was credited on tick " + run.credited()[k]
                            + " but the feet did not reach cell " + run.cellEntered()[k] + "'s "
                            + "destination until tick " + run.cellEntered()[k] + ". A credit later "
                            + "than the world earned is a tick of handover between two moves, which "
                            + "is exactly the per-block stop this file is about.",
                    run.credited()[k] == run.cellEntered()[k]);
        }
    }

    /**
     * A wedged body still ends the route, at the same tick it used to.
     *
     * <p>The counterpart to the bound above, and the reason the arrival question can be asked early
     * at all: a fix that lets the executor keep walking after a move has failed is worse than a
     * stutter. This drives the boxed-in wedge -- a log ahead and stone on both sides, so there is no
     * cell to step to -- and pins the exact tick the route gives up on, so a future change that
     * inserted a tick of patience between "the steering gave up" and "the route admits it" would
     * show up here as a number rather than as a route that takes longer to fail.
     */
    @Test
    public void aWedgedBodyStillEndsTheRouteOnTheSameTickItUsedTo() {
        FakeActuator act = wedgedCorridor();
        RouteExecutor ex = new RouteExecutor(
                new Planner.Plan(List.of(Move.walk(new Stance(0, FEET, 0), new Stance(3, FEET, 0))),
                        null, 0),
                64);

        ActOutcome out = null;
        int tick = 0;
        for (; tick < 200; tick++) {
            out = ex.tick(act);
            if (out.terminal()) {
                break;
            }
            BodySim.step(act, ex.forward(), ex.strafe());
        }

        assertTrue("the route must end rather than walk forever against a wall: " + out.message(),
                out.terminal());
        assertFalse("and it must not claim the route it did not walk: " + out.message(), out.ok());
        assertEquals("no move may be credited", 0, ex.movesDone());
        assertEquals("and it must give up on the same tick it always did -- the tick after the "
                        + "steering reported the wedge, which is when the route's own arrival check "
                        + "runs. A larger number means the give-up now waits on something it did not "
                        + "used to wait on.",
                11, tick);
        assertTrue("the failure must still carry the steering's own verdict, because '3.20 blocks "
                        + "from the centre' is true of a route that is fine: " + out.message(),
                out.message().contains("the steering's own verdict was"));
    }

    /** Open ground with a log dead ahead and stone on both sides: nowhere to step to. */
    private static FakeActuator wedgedCorridor() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.putBlock(1, FEET, 0, "log");
        act.putBlock(0, FEET, 1, "stone");
        act.putBlock(0, FEET, -1, "stone");
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.setRotation(0f, 0f);
        return act;
    }
}
