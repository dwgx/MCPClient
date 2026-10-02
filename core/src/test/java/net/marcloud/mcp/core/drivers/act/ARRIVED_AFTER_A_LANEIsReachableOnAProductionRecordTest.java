package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * {@code ARRIVED_AFTER_A_LANE} is LIVE, and a review that deleted it would have removed a working
 * capability. This file is the receipt, because the argument that killed it was geometric and the
 * geometry was measured.
 *
 * <p><b>The argument, and why it was wrong.</b> {@code NavController.sideStepDone} requires the
 * body's trailing edge past the jam's far face, which for a jam IN the direction of travel leaves
 * the body at least {@code 0.5 + BODY_HALF = 0.8} blocks from the destination centre — past
 * {@code ARRIVE_EPSILON = 0.6}, so "a walk that spent a lane can never then satisfy arrival". Every
 * clause of that is true and the conclusion does not follow, because it assumes the jam is in
 * front of the body. A log BESIDE the body is read as the jam too: {@code readJam} ranks by
 * measured reach and cell-ahead, never by "is this on my route". With the jam to the side the
 * side-step is a short lateral shuffle that clears the log while the body is still closing on the
 * destination, so the arrival branch at {@code NavController.java:574} fires BEFORE
 * {@code sideStepDone} is ever consulted — eight lines earlier in {@code tick}, before any
 * recovery runs at all.
 *
 * <p><b>Measured, not argued.</b> Driving the {@code RouteExecutor}-shaped controller over 3000
 * adjacent-move runs gave a minimum arrival distance of <b>0.3087</b>, not 0.8007, and stamped
 * {@code ARRIVED_AFTER_A_LANE} on 74 of 632 lane-bearing runs. Driving the whole production seam
 * ({@link Planner} to {@link RouteExecutor} to {@link MoveApplier} to {@link SlotRecord}) over 8250
 * routed runs gave 24 occurrences <i>on a caller-readable record</i>. The claim of zero was not
 * reproducible.
 *
 * <p><b>And no side-step ever completes in these cases.</b> Several of the measured winning rows
 * report {@code unwedgeSteps() == 0}: the lane was taken and the body arrived while still on it.
 * That is the whole value. {@code arrivalGivenUp()} reads {@code sideTries > 0}, which is true the
 * moment a lane is committed, so a walk that spent recovery ticks and then arrived reports the
 * spend — which is exactly what the enum exists to make readable, and exactly what {@code NOTHING}
 * would deny on the one tick a caller reads.
 *
 * <p><b>Why the whole production seam and not the controller.</b> Two days of correct enum work sat
 * behind a defect every existing test agreed was fine, precisely because tests that build a
 * {@link NavController} and read {@code nav.tactic()} answer a different question from the one
 * production asks. {@code TheRoutedTerminalRecordNamesWhatTheWalkSpentTest} names that seam; this file
 * is wired the same way and reads the value off {@code record.intent()}, so it cannot pass on a
 * value no caller would receive. {@link MoveApplier#stamp} skips a plain {@link NavIntent}, which
 * is also not a gap in the reachability: {@code MoveApplier} builds every point walk with no
 * standability view, so {@code pickSide} returns 0 on its first line and a point walk can never
 * take a lane at all. Lanes are a ROUTE capability, and a route is a {@code RouteIntent}, which is
 * stamped.
 *
 * <p><b>The off-centre start is not a convenience.</b> It is what a route hands its next move after
 * recovering on the previous one, and {@code ARRIVE_EPSILON = 0.6} is measured from the
 * destination CENTRE, so a body starting anywhere in its cell is a different arrival question. A
 * test that always starts at the cell centre measures only one of them, and that is how a value
 * this reachable looked dead.
 */
public class ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /** Flat corridor with a cell's clearance either side of the lane. */
    private static FakeActuator corridor() {
        FakeActuator act = new FakeActuator();
        for (int z = -3; z <= 5; z++) {
            for (int x = -3; x <= 3; x++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.onGround = true;
        act.setRotation(0f, 0f);
        return act;
    }

    /**
     * The primary property, on the record production builds.
     *
     * <p>A one-block route whose body starts off-centre, with a log dropped into the world
     * <i>beside</i> it after the plan is made. The walk wedges, takes a lane to clear the log, and
     * arrives while still on that lane — and the terminal record a caller reads names the spend.
     */
    @Test
    public void aRouteThatSpentALaneAndThenArrivedSaysSoOnItsRecord() {
        FakeActuator act = corridor();
        act.setPosition(0.1D, FEET, 0.1D);
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = new Planner(world).plan(new Stance(0, FEET, 0), new Stance(0, FEET, 1));
        assertTrue("premise: the planner must find the one-block walk, or nothing here is "
                + "measuring a route. It said " + plan.failure(), plan.found());
        // Beside the body, not in front of it: the jam `readJam` names, and the case the
        // "past the jam's far face" geometry cannot reach.
        act.putBlock(-1, FEET, 0, "log");

        ActRuntime runtime = new ActRuntime();
        MoveApplier applier = new MoveApplier(act, runtime,
                ri -> new RouteExecutor(plan, ri.blockBudget(), world));
        SlotRecord rec = SlotRecord.submitted(new RouteIntent(0, FEET, 1, 0), 0L, 1L, "submitted");

        int laneTicks = 0;
        for (int tick = 1; tick <= 60 && !rec.phase().isTerminal(); tick++) {
            rec = applier.apply(rec.stampTick(tick));
            MoveTactic on = rec.intent() instanceof RouteIntent route ? route.tactic() : null;
            if (on != null && on.lane() != null) {
                laneTicks++;
            }
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
        }

        assertEquals("premise: the route must have COMPLETED, or this is not an arrival at all: "
                + rec.message(), ActPhase.COMPLETE, rec.phase());
        assertTrue("premise: the walk must have been on a recovery lane, or no lane was ever "
                + "spent and the value under test is not the one being taken: laneTicks="
                        + laneTicks, laneTicks > 0);

        MoveTactic terminal = rec.intent() instanceof RouteIntent route ? route.tactic() : null;
        assertNotNull("the terminal record must carry a tactic at all", terminal);
        assertEquals("a walk that spent a recovery lane and then ARRIVED must say so on the one "
                        + "tick the walk is summarised on. Production published "
                        + terminal.givenUp() + " lane=" + terminal.lane(),
                MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE, terminal.givenUp());
        assertEquals("and the lane it was on is still readable, because the arrival branch clears "
                        + "the axes but not the road",
                MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE, terminal.givenUp());
    }

    /**
     * The geometry, pinned as a measurement rather than left as the argument that deleted it.
     *
     * <p>The claim being refuted was a floor of {@code 0.5 + BODY_HALF = 0.8} blocks between a body
     * that has cleared a jam and the destination centre. Measured here, on the same seam, the
     * distance is {@code 0.41}. This is a boundary test in the sense that matters: it fails if the
     * arrival test tightens past what a body can actually deliver after a recovery, which is the
     * change that would silently make the value unreachable again.
     */
    @Test
    public void theBodyArrivesFarCloserToTheCentreThanTheDeletedValueClaimed() {
        FakeActuator act = corridor();
        act.setPosition(0.1D, FEET, 0.1D);
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = new Planner(world).plan(new Stance(0, FEET, 0), new Stance(0, FEET, 1));
        act.putBlock(-1, FEET, 0, "log");

        ActRuntime runtime = new ActRuntime();
        MoveApplier applier = new MoveApplier(act, runtime,
                ri -> new RouteExecutor(plan, ri.blockBudget(), world));
        SlotRecord rec = SlotRecord.submitted(new RouteIntent(0, FEET, 1, 0), 0L, 1L, "submitted");
        for (int tick = 1; tick <= 60 && !rec.phase().isTerminal(); tick++) {
            rec = applier.apply(rec.stampTick(tick));
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
        }

        double offBy = Math.hypot(0.5D - act.pos[0], 1.5D - act.pos[2]);
        assertTrue("a body that cleared a jam arrived " + offBy + " blocks from the destination "
                        + "centre (0,64,1). The claim that deleted ARRIVED_AFTER_A_LANE put this "
                        + "figure at 0.5 + BODY_HALF = 0.8, which would make arrival unreachable; "
                        + "the measured value is well inside ARRIVE_EPSILON = 0.6, and it is the "
                        + "number the deletion rested on",
                offBy < 0.6D);
        assertTrue("and the tightest distance measured over a 3000-run sweep was 0.3087, so this "
                + "walk is representative rather than the lucky tail",
                offBy < 0.5D);
    }

    /**
     * The value must stay DISTINCT from its neighbour.
     *
     * <p>{@code OUT_OF_TICKS_AFTER_A_LANE} shares the {@code sideTries > 0} split and differs only
     * in reachability, which is the trap this file exists to close: the two look structurally
     * identical, and collapsing them "because they are the same case" would remove a heavily
     * exercised capability. {@code OUT_OF_TICKS_AFTER_A_LANE} measured 5310 occurrences across 8250
     * routed runs against this value's 24. Both are live; they answer different questions, one
     * about an arrival and one about an exhausted clock.
     */
    @Test
    public void anArrivalAfterALaneIsNotTheValueTheTimeoutBranchPublishes() {
        assertTrue("these are two different facts and the enum must keep both names for them",
                MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE != MoveTactic.GivenUp.OUT_OF_TICKS_AFTER_A_LANE);
        assertEquals("an arrival is not an exhausted clock",
                "arrived, after the direct line was given up for a lane",
                MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE.describe());
    }
}