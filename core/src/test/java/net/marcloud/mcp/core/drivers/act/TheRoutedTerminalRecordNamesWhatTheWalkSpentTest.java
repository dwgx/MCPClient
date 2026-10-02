package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * The terminal record a ROUTE carries must name what the walk spent, and the seam that decides it is
 * the record, not the controller.
 *
 * <p><b>Why this file exists and why it never drives a controller.</b> Two days of correct enum work
 * sat behind a defect every existing test agreed was fine.
 * {@code MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE}, {@code OUT_OF_TICKS_AFTER_A_LANE} and the
 * lane-preserving {@code hold(givenUp, lane)} were all asserted green -- by tests that build a
 * {@link NavController}, tick it and read {@code nav.tactic()}. That is a real answer to a real
 * question, and it is not the question production asks. In production the walk is a
 * {@link RouteExecutor}, the caller never holds the {@code NavController}, and the only value that
 * reaches a {@link SlotRecord} is whatever the executor's own {@code tactic()} returns.
 *
 * <p>And the executor answered {@code hold(NOTHING)} on every terminal tick, because
 * {@code finish()} released the machine on the same tick it returned the outcome -- so the applier's
 * stamp, which is the very next act, found no machine to ask. The record said the walk spent nothing
 * on the one tick the walk is ever summarised on, for every route, forever.
 *
 * <p><b>So every assertion here is about the record production built.</b> {@link MoveApplier} and
 * {@link RouteExecutor} are wired the way {@code McpCore} wires them, the plan comes from the real
 * {@link Planner}, the body is advanced by {@link BodySim} from the axes the runtime actually
 * published, and the value under test is read off {@code record.intent()}. This file never calls
 * {@code NavController.tactic()} and never builds a tactic by hand, so it cannot pass on a value no
 * caller would ever receive.
 *
 * <p><b>The world changes after the plan is made, which is the whole setup.</b> A planner given a
 * corridor with a log in it routes around the log, so a routed walk never wedges and never spends a
 * lane -- which is why the defect was invisible from the outside as well as from the inside. Putting
 * the log in the world <i>after</i> planning is a real production event (a block placed, a chunk
 * loaded, another player building) and it is the only way a routed walk reaches the recovery code at
 * all. The {@link BlockView} is the live adapter over the same actuator the body collides with, so
 * the plan, the collision model and the standability seam all read one world.
 */
public class TheRoutedTerminalRecordNamesWhatTheWalkSpentTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    /** Ten, not twelve: see the flush-contact note on {@link #corridor()}. */
    private static final int BLOCKS = 10;
    private static final int MAX_TICKS = 900;

    /**
     * Open ground down the +Z lane, three cells wide either side, so a lane beside the body is open.
     *
     * <p><b>Why the length is a constant rather than a convenience.</b> A body walking a straight
     * line into a block stops one step short of penetrating it, and {@code NavController.readJam}
     * names the obstruction only when the body box REACHES that cell's span -- contact at exactly
     * zero counts, a hair short of it does not. Which of the two happens is decided by accumulated
     * floating point, not by the geometry: stepping 0.2 at a time from 0.5, the body halts with
     * span exactly 0.0 before cells 1, 3 and 10, and with span -2e-16 before cell 2 and -7e-15
     * before cell 12. A test that wedges on the wrong cell does not fail as a wedge, it fails as
     * {@code THE_UNNAMED_STALL} -- the controller truthfully reporting that it could not read a
     * block its body is flush against. Both log cells below are ones where contact is named, and
     * that is a property of the arithmetic, stated here so the next reader is not surprised by it.
     */
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

    /** What a whole driven route left behind: the record it ended on, and the machine behind it. */
    private record Run(SlotRecord end, RouteExecutor executor, List<MoveTactic> stamped,
                       int laneTicks) { }

    /**
     * Drive a real route through the applier, optionally dropping a log into the world afterwards,
     * cancelling the executor the first tick a recovery lane is on the record, and optionally
     * dropping the body off the route on a given tick.
     *
     * @param logZ    the Z cell to fill with a log after planning, or -1 to leave the world alone
     * @param onLane  when true, {@code RouteExecutor.requestCancel()} fires on the first tick the
     *                stamped tactic carries a lane, which is a terminal exit reached with a live
     *                machine -- the configuration in which the capture is load-bearing
     * @param fallAt  the tick on which the body is found below the route, or -1. Tick 2 is the
     *                first tick of the first walk, which is the tick the machine is installed and
     *                has therefore never run: the exit where there is no decision to capture
     */
    private static Run drive(int logZ, boolean onLane, int fallAt) {
        FakeActuator act = corridor();
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = new Planner(world).plan(new Stance(0, FEET, 0),
                new Stance(0, FEET, BLOCKS));
        assertTrue("premise: the planner must find the straight corridor, or nothing here is "
                + "measuring a route. It said " + plan.failure(), plan.found());
        if (logZ >= 0) {
            act.putBlock(0, FEET, logZ, "log");
        }

        ActRuntime runtime = new ActRuntime();
        RouteExecutor[] built = new RouteExecutor[1];
        MoveApplier applier = new MoveApplier(act, runtime, ri -> {
            built[0] = new RouteExecutor(plan, ri.blockBudget(), world);
            return built[0];
        });

        SlotRecord rec = SlotRecord.submitted(
                new RouteIntent(0, FEET, BLOCKS, 0), 0L, 1L, "submitted");
        List<MoveTactic> stamped = new ArrayList<>();
        int laneTicks = 0;
        boolean cancelled = false;
        for (int tick = 1; tick <= MAX_TICKS && !rec.phase().isTerminal(); tick++) {
            // BEFORE the apply, and that ordering is the test: the fall guard runs at the top of the
            // executor's tick, so a body found below the route on the first walking tick ends the
            // route with a machine that was installed last tick and has never run. Setting the
            // position after the apply would move the fall one tick later, by which time the machine
            // has chosen something, and the case under test would quietly stop existing.
            if (tick == fallAt) {
                act.setPosition(act.pos[0], FEET - 6.0D, act.pos[2]);
            }
            rec = applier.apply(rec.stampTick(tick));
            MoveTactic on = rec.intent() instanceof RouteIntent route ? route.tactic() : null;
            if (on != null) {
                stamped.add(on);
                if (on.lane() != null) {
                    laneTicks++;
                }
            }
            if (onLane && !cancelled && on != null && on.lane() != null) {
                built[0].requestCancel();
                cancelled = true;
            }
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
        }
        return new Run(rec, built[0], stamped, laneTicks);
    }

    /** The tactic the production record ended on, read off the intent the slot is carrying. */
    private static MoveTactic terminalTactic(SlotRecord rec) {
        return rec.intent() instanceof RouteIntent route ? route.tactic() : null;
    }

    /**
     * The primary property, and the one that was false on every route ever walked: a walk stopped
     * while it is ON a recovery lane must leave the caller the lane it died on.
     *
     * <p>A cancel is the cheapest terminal exit to reach on purpose. It needs no planner quirk, no
     * budget and no arrival race, and it goes through {@code finish()} with a live machine under
     * it -- which is exactly the configuration in which the capture is load-bearing and the old code
     * answered {@code NOTHING}. Before the fix the record claimed the walk was on the direct line
     * and had spent nothing, on the same record that had just published the lane fourteen times.
     */
    @Test
    public void aRouteCancelledOnARecoveryLaneNamesThatLaneInItsRecord() {
        Run run = drive(1, true, -1);

        assertTrue("premise: the slot must have reached a terminal phase, or nothing was summarised: "
                + run.end().phase(), run.end().phase().isTerminal());
        assertEquals("premise: and it must be a cancellation, or this is not the exit under test: "
                        + run.end().message(), ActPhase.CANCELLED, run.end().phase());
        assertTrue("premise: the walk must have been on a lane, or there is no spend to report: "
                + "laneTicks=" + run.laneTicks(), run.laneTicks() > 0);

        MoveTactic terminal = terminalTactic(run.end());
        assertNotNull("the terminal record must carry a tactic at all", terminal);
        assertNotNull("a walk cancelled while on a recovery lane must leave the caller the lane it "
                + "was on, which is the one fact the mid-walk ticks cannot reconstruct once the walk "
                + "ends. Production published " + terminal.givenUp() + " lane=" + terminal.lane(),
                terminal.lane());
        assertNotEquals("and the cost must not read as 'nothing was spent to take the direct line', "
                + "which is the claim that made this defect invisible from outside the executor",
                MoveTactic.GivenUp.NOTHING, terminal.givenUp());
        // The other half of the same record, and the reason the latch re-wraps instead of passing
        // the machine's own value through: the applier has already published null on this tick, so
        // the keys are up. A terminal record claiming a forward press would be a record beside the
        // fingers saying otherwise, which MoveTactic documents as worse than no record at all.
        assertEquals("a terminal record must claim no keys: they were released on this tick",
                0f, terminal.forward(), 0f);
        assertEquals("nor a strafe", 0f, terminal.strafe(), 0f);
    }

    /**
     * The same defect on the exit a walk-time failure takes, which is a different road into
     * {@code finish()}: the machine gives up mid-move, the executor routes the failure through
     * {@code verify()} to check the arrival against the world, and only then does the route end.
     *
     * <p>Worth its own test because the mid-walk tick and the terminal tick are one tick apart and
     * disagree. The running tick reads the live machine and names the give-up; the terminal tick
     * used to read the released one and said {@code NOTHING}. A caller that polls {@code act_status}
     * watched the correct value and then watched it vanish, which is the shape of bug that looks
     * like a race and is a pure ordering.
     */
    @Test
    public void aRouteThatFailsOnARecoveryNamesTheGiveUpOnTheRecordItEndsOn() {
        Run run = drive(BLOCKS, false, -1);

        assertTrue("premise: the slot must have reached a terminal phase: " + run.end().phase(),
                run.end().phase().isTerminal());
        assertEquals("premise: the route must have FAILED, or the give-up never happened: "
                        + run.end().message(), ActPhase.FAILED, run.end().phase());
        assertTrue("premise: and the steering must have taken a lane, or this is not a give-up: "
                + "laneTicks=" + run.laneTicks(), run.laneTicks() > 0);

        MoveTactic terminal = terminalTactic(run.end());
        assertNotNull("the terminal record must carry a tactic at all", terminal);
        assertNotEquals("a walk that spent a recovery lane and then failed must not publish the "
                + "value that says it spent nothing, on the one tick the failure is reported",
                MoveTactic.GivenUp.NOTHING, terminal.givenUp());
        assertNotEquals("nor may it publish the value that means it ARRIVED after the lane: this "
                + "move failed, and a record claiming an arrival would send the caller to reroute a "
                + "walk that stopped for a reason it can name",
                MoveTactic.GivenUp.ARRIVED_AFTER_A_LANE, terminal.givenUp());
    }

    /**
     * The half that keeps the fix from becoming "always claim something".
     *
     * <p>The default {@code hold(NOTHING)} for a walk with no machine is not the bug. It is exactly
     * right for a placement and for a corridor walked straight through, and the latched value is
     * cleared when the next move begins precisely so a move-4 placement cannot inherit move 3's
     * lane. A route that never needed a recovery must still say it did not, or a caller has learned
     * to distrust the one field this enum was built to make readable.
     */
    @Test
    public void aRouteThatNeverRecoveredStillSaysItSpentNothing() {
        Run run = drive(-1, false, -1);

        assertEquals("premise: the route must have completed: " + run.end().message(),
                ActPhase.COMPLETE, run.end().phase());
        assertEquals("premise: every move must have been credited: " + run.end().message(),
                BLOCKS, run.executor().movesDone());
        assertEquals("premise: and it must never have entered a recovery: laneTicks="
                + run.laneTicks(), 0, run.laneTicks());

        MoveTactic terminal = terminalTactic(run.end());
        assertNotNull(terminal);
        assertEquals("a walk that never met an obstruction spent no option, and a record saying "
                + "otherwise would make every other value on this enum unreadable",
                MoveTactic.GivenUp.NOTHING, terminal.givenUp());
        assertTrue("and with no lane in force there is no lane to report", terminal.lane() == null);
    }

    /**
     * The exit where there is no decision to capture, and where the record must still say so.
     *
     * <p>The fall guard runs at the TOP of {@code tick()}, before the phase switch, so a route that
     * falls on the first tick of a walk releases a machine that has never run and has therefore
     * chosen nothing. Reading its tactic anyway is not a small mistake: the first version of this
     * fix did exactly that and threw {@code NullPointerException} on both of the repository's
     * fall-guard tests, which is the whole argument for running the suite rather than the new file.
     *
     * <p>What the caller needs here is a value, not a null. {@code MoveApplier} skips stamping on a
     * null, so a null tactic would leave the terminal record carrying whatever the previous tick put
     * there. A record that is right by coincidence is a record nothing can rely on, so the value has
     * to be published explicitly.
     */
    @Test
    public void aRouteThatFallsBeforeItsMachineHasRunStillPublishesATactic() {
        Run run = drive(-1, false, 2);

        assertEquals("premise: the route must have failed on the fall guard, and on the FIRST "
                        + "walking tick, or the machine had already chosen something: "
                        + run.end().message(), ActPhase.FAILED, run.end().phase());
        assertTrue("premise: and the message must be the fall, not something later: "
                + run.end().message(), run.end().message().contains("fell out of the route"));

        MoveTactic terminal = terminalTactic(run.end());
        assertNotNull("the terminal record must carry a tactic rather than no value at all, because "
                + "a null is skipped by the stamp and the slot keeps the previous tick's", terminal);
        assertEquals("and a machine that never ran published no keys and spent no option, which is "
                + "the one thing that IS true about this walk", MoveTactic.GivenUp.NOTHING,
                terminal.givenUp());
    }

}
