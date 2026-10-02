package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * The terminal verdict has to be the one the walk EARNED, and it has to survive the trip to the
 * record a caller actually reads.
 *
 * <p><b>The defect.</b> {@code GivenUp.LIMITS_REACHED} means "every option, spent". A completed
 * worker retyped it because that sentence was false at two of the three sites that stamped it, and
 * left three more families stamping it while having surveyed nothing, pressed nothing and spent
 * nothing: {@code NavController.wedged} reached with no standability view, and the timeout and
 * stall branches of {@code ClimbSteering} and {@code SwimSteering}.
 *
 * <p><b>Why this file drives the applier rather than the controller.</b> The tests already in this
 * package drive {@link NavController} directly and read {@code nav.tactic()}. That proves the
 * controller computes the right value and says nothing about whether any caller ever sees it --
 * and the unreachable-values defect in this project shipped through exactly that seam: correct
 * values, wired up, tested through the wrong seam. Everything here goes
 * {@link MoveApplier} -&gt; {@link RouteExecutor} -&gt; {@link NavController} and reads the value off
 * the {@link SlotRecord} the game layer and the model both read.
 *
 * <p><b>Two facts about the harness that are the point, not the plumbing.</b> First, the PLAN is
 * computed from a view with no log in it, while the body meets a log: a planner that can see an
 * obstacle routes around it, so the only way to make a routed walk meet one is for the plan and the
 * body to disagree. That disagreement is not a contrivance -- it is what a stale or partial world
 * view looks like from the inside, and it is the ordinary reason a routed walk wedges. Second, the
 * executor's standability authority is varied independently of the plan, because that authority is
 * the single fact under test.
 */
public final class AWalkThatSpentNothingSaysSoOnTheRecordItCarriesTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    private static final int BLOCKS = 12;
    private static final int MAX_TICKS = 600;

    /** Open ground down the +X lane, three cells wide either side. */
    private static FakeActuator corridor() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.setRotation(0f, 0f);
        return act;
    }

    /**
     * The view the PLAN is searched against: open corridor, nothing in the lane.
     *
     * <p>Kept separate from the body so the plan routes straight and the body then meets a log the
     * plan never saw. {@link Planner} routing around a visible obstacle is correct behaviour and
     * makes this file untestable, which is a fact about the test harness and not about the defect.
     */
    private static FakeActuator plannerView() {
        return corridor();
    }

    /**
     * The body: a log standing in the lane at feet height, which is a wedge the body can walk into
     * and press against.
     *
     * <p>At {@code FEET} rather than {@code GROUND} because the wedge test is about the body BOX: a
     * body at y=64 with the log at y=63 is standing on it, and one with the log at y=64 is pressed
     * against its face. This is the second, and it is the one that produces a jam at all.
     */
    private static FakeActuator bodyWorld() {
        FakeActuator act = corridor();
        act.putBlock(3, FEET, 0, "log");
        return act;
    }

    /** The tactic production stamped onto the record the slot is carrying, or null. */
    private static MoveTactic tacticOn(SlotRecord rec) {
        assertNotNull("premise: the record must still carry an intent", rec.intent());
        assertTrue("premise: this path is a RouteIntent, and it is the only intent MoveApplier "
                        + "stamps a tactic onto. A NavIntent has no tactic field and stamp() drops "
                        + "it, which is a shape limit of that record rather than a bug here",
                rec.intent() instanceof RouteIntent);
        return ((RouteIntent) rec.intent()).tactic();
    }

    /**
     * Drive a whole route through the production path and hand back the terminal record.
     *
     * @param executorView what the executor may ask about standability, or null for "no world view".
     *                     {@code RouteExecutor} turns null into {@code standable == null} for the
     *                     steering controller, and that is the family under test.
     */
    private static SlotRecord drive(BlockView executorView) {
        FakeActuator planWorld = plannerView();
        BlockView planView = BodySim.blockView(planWorld);
        Planner.Plan plan = new Planner(planView).plan(new Stance(0, FEET, 0),
                new Stance(BLOCKS, FEET, 0));
        assertTrue("premise: the planner must find the straight corridor, or this is not measuring "
                        + "a walk. It said " + plan.failure(), plan.found());

        FakeActuator act = bodyWorld();
        ActRuntime runtime = new ActRuntime();
        MoveApplier applier = new MoveApplier(act, runtime,
                ri -> new RouteExecutor(plan, ri.blockBudget(), executorView));

        SlotRecord rec = SlotRecord.submitted(new RouteIntent(0, FEET, BLOCKS, 0), 0L, 1L,
                "submitted");
        for (int tick = 1; tick <= MAX_TICKS && !rec.phase().isTerminal(); tick++) {
            rec = applier.apply(rec.stampTick(tick));
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
        }
        return rec;
    }

    /**
     * A routed walk whose executor has no standability view wedges into a log it can NAME and must
     * not claim every option was spent.
     *
     * <p><b>Red before the fix.</b> The terminal record carried
     * {@link MoveTactic.GivenUp#LIMITS_REACHED} while its own message, on that same record, read
     * "This walk was given no world view, so there is no way to ask whether a neighbouring cell is
     * standable -- the wedge is reported rather than recovered". The record contradicted itself: a
     * survey that never ran, reported as a survey that exhausted everything.
     *
     * <p>This is the common path, not an edge case. {@code MoveApplier} builds every
     * {@code NavIntent} as exactly this controller -- a point walk with no view -- and
     * {@code RouteExecutor} built without a {@code BlockView} hands the same null down.
     */
    @Test
    public void aWedgeWithNoWorldViewReportsTheUnsurveyedVerdictNotTheExhaustedOne() {
        SlotRecord rec = drive(null);

        assertTrue("premise: the route must actually have failed, or this is measuring nothing. "
                + "Phase was " + rec.phase(), rec.phase().isTerminal());
        assertTrue("premise: and it must have failed rather than arrived, or the log did not stop "
                        + "the body", rec.phase() == ActPhase.FAILED);
        assertTrue("premise: the record must say the walk was stopped by a wedge in a log it named, "
                        + "or the harness is not reaching the site under test. It said: "
                        + rec.message(), rec.message().contains("wedged"));

        MoveTactic terminal = tacticOn(rec);
        assertNotNull("the terminal record must carry the walk's own last decision. A null here is "
                        + "the record losing the tactic, which is a different defect and would make "
                        + "every assertion below vacuous", terminal);

        assertEquals("no option was ever offered to this walk, so it may not report that every "
                        + "option was spent -- the honest verdict is that it could not survey one",
                MoveTactic.GivenUp.THE_UNSURVEYED_WEDGE, terminal.givenUp());
        assertNotEquals("and this is the claim being corrected: the walk surveyed nothing, so "
                        + "'every option, spent' is false here in the plainest way available",
                MoveTactic.GivenUp.LIMITS_REACHED, terminal.givenUp());
    }

    /**
     * The split is a real distinction, not a blanket downgrade: give the executor the authority back
     * and the SAME wedge still reports exhaustion.
     *
     * <p>This is the half that keeps the change honest. A fix that made every wedge report
     * {@code THE_UNSURVEYED_WEDGE} would pass the test above and delete the meaning of
     * {@code LIMITS_REACHED} entirely. Here the executor can ask, asks, is told neither cell beside
     * the log will hold a body, and has genuinely run out of things to try -- so the survey ran and
     * came back empty, which is the only sense in which the sentence was ever true.
     */
    @Test
    public void theSameWedgeWithAWorldViewStillReportsEveryOptionSpent() {
        FakeActuator authority = corridor();
        for (int x = 1; x <= 11; x++) {
            authority.removeBlock(x, GROUND, 1);
            authority.removeBlock(x, GROUND, -1);
        }
        SlotRecord rec = drive(BodySim.blockView(authority));

        assertTrue("premise: and it must fail by having measured the cells beside the jam and "
                        + "found nowhere to step, or the split is not being exercised. It said: "
                        + rec.message(), rec.message().contains("cells beside the body is standable"));

        MoveTactic terminal = tacticOn(rec);
        assertNotNull("premise: and the record must still carry the decision", terminal);

        assertEquals("with a standability view the executor measures both cells beside the jam and "
                        + "rejects both, so exhaustion is the true verdict here -- this is the one "
                        + "shape in which 'every option, spent' is honest",
                MoveTactic.GivenUp.LIMITS_REACHED, terminal.givenUp());
        assertNotEquals("and the two walks differ on exactly one fact -- whether a survey was "
                        + "possible -- so they must not read as the same record",
                MoveTactic.GivenUp.THE_UNSURVEYED_WEDGE, terminal.givenUp());
    }

    /**
     * The verdicts are not several names for one thing, and none of them is decoration.
     *
     * <p>Four failures that all end a walk, and a caller cannot respond to them the same way:
     * reroute, raise the budget, stop trying to read the cell, or stop trying to survey at all. If
     * any two of these collapsed, the enum would be a rename and this whole slice would be
     * cosmetic -- which is the test this file exists to fail.
     */
    @Test
    public void theFourTerminalStoriesAreFourValues() {
        MoveTactic.GivenUp unsurveyed = MoveTactic.GivenUp.THE_UNSURVEYED_WEDGE;
        MoveTactic.GivenUp unreadable = MoveTactic.GivenUp.THE_UNNAMED_STALL;
        MoveTactic.GivenUp outOfTicks = MoveTactic.GivenUp.OUT_OF_TICKS;
        MoveTactic.GivenUp limits = MoveTactic.GivenUp.LIMITS_REACHED;

        assertNotEquals("the obstruction could not be READ and the neighbourhood could not be "
                        + "SURVEYED. One is an unreadable cell inside the body box, the other is a "
                        + "missing authority to ask about the cells beside it, and wedged() "
                        + "reports the second having named the first",
                unsurveyed, unreadable);
        assertNotEquals("neither of those is the clock", unsurveyed, outOfTicks);
        assertNotEquals("and none of them is exhaustion", unsurveyed, limits);
        assertNotEquals("the clock and exhaustion are opposites: one had options left, the other "
                        + "had none", outOfTicks, limits);
        assertNotEquals("and an unreadable cell is not an unsurveyed neighbourhood either",
                unreadable, outOfTicks);
    }
}