package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.ActStatus;
import net.marcloud.mcp.core.drivers.act.ActTickLoop;
import net.marcloud.mcp.core.drivers.act.BodySim;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.MoveIntent;
import net.marcloud.mcp.core.drivers.act.MoveTactic;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Planner;
import net.marcloud.mcp.core.drivers.plan.RouteExecutor;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

import org.junit.Test;

/**
 * A decision the walk made must be readable by the only component that makes decisions about it, and
 * that component is the model.
 *
 * <p><b>The two values this closes, and why they were invisible.</b> {@link MoveTactic} was
 * recorded on the {@link SlotRecord} and on {@link net.marcloud.mcp.core.drivers.act.ActStatus}, and
 * neither was ever projected onto the {@code act_status} wire form -- so the field existed, the
 * value existed, the projection was one line wide, and nothing reached the model. The same was true
 * of the belief that arrived with it. Two components in this repository already knew how to read
 * both; the one that reads {@code act_status} could not, and it is the one with no other way to ask.
 *
 * <p><b>Why this drives the real applier over a real corridor with a log in it.</b> A hand-built
 * {@link ActStatus} would pass on a projection that production never produces.
 * {@code TheChosenTacticReachesTheRecordTheWalkIsCarryingTest} and
 * {@code TheRoutedTerminalRecordNamesWhatTheWalkSpentTest} already established that the record
 * carries the right tactic; what was missing is that the row carries it. So the walk here is driven
 * exactly as production drives it: a real {@link Planner} plan, a real {@link RouteExecutor}, the
 * applier wired the way {@code McpCore} wires it, the body advanced by {@link BodySim} from the axes
 * the runtime actually published, and the value read off both {@code runtime.status()} and the real
 * {@code act_status} handler.
 *
 * <p><b>The log goes in AFTER the plan, and that is the whole setup.</b> A planner handed a
 * corridor with a log in it routes around the log, so a routed walk never wedges and never spends a
 * lane. Putting the log in the world after planning is a real production event -- a block placed, a
 * chunk loaded, another player building -- and it is the only way a routed walk reaches the recovery
 * code at all.
 *
 * <p><b>Asserted through both doors on purpose.</b> {@code runtime.status()} and the wire form are
 * separate projections of the same record, and either can be wrong while the other is right. If only
 * the projection were asserted, a handler that emitted an empty object would pass; if only the wire
 * form were asserted, the projection could be dead code that nothing reads.
 */
public class ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;
    /** Ten, not twelve: the flush-contact arithmetic documented on {@code TheRoutedTerminal...}. */
    private static final int BLOCKS = 10;
    private static final int MAX_TICKS = 900;

    /**
     * Everything a reader could have used, from one driven walk: the body, the runtime that
     * committed its records, and the terminal record itself.
     *
     * @param logZ the Z cell filled with a log AFTER planning, or -1 for an untouched world
     */
    private record Walk(FakeActuator actuator, ActRuntime runtime, SlotRecord end) { }


    /** Open ground down the +Z lane, three cells wide either side, so a lane beside the body is open. */
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

    private static Walk drive(int logZ) {
        FakeActuator act = corridor();
        BlockView world = BodySim.blockView(act);
        Planner.Plan plan = new Planner(world).plan(new Stance(0, FEET, 0),
                new Stance(0, FEET, BLOCKS));
        assertTrue("premise: the planner must find the straight corridor, or nothing here is "
                + "measuring a route. It said " + plan.failure(), plan.found());
        if (logZ >= 0) {
            act.putBlock(0, FEET, logZ, "log");
        }

        // Its OWN clock, not the global one: the tick a submit is gated on is read from this
        // runtime's clock, and GameClock.INSTANCE is shared process-wide, so a test that ran before
        // this one would leave the slot gated behind a tick id nothing here reaches.
        GameClock clock = new GameClock();
        clock.reset();
        ActRuntime runtime = new ActRuntime(clock);
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime,
                ri -> new RouteExecutor(plan, ri.blockBudget(), world)));
        runtime.submit(new RouteIntent(0, FEET, BLOCKS, 0));

        // Driven through the REAL tick loop rather than by calling applier.apply() directly: the
        // loop is what commits each record, and a driver that kept the record itself would be
        // reading a value the runtime may never have stored. A synthetic TickEvent is the
        // documented driveable form of the same seam the game thread uses.
        ActTickLoop loop = new ActTickLoop(runtime);
        for (long tick = 1; tick <= MAX_TICKS; tick++) {
            loop.onTick(new TickEvent(tick));
            BodySim.step(act, runtime.moveForward(), runtime.moveStrafe());
            if (runtime.record(ActSlot.MOVE).phase().isTerminal()) {
                break;
            }
        }
        return new Walk(act, runtime, runtime.record(ActSlot.MOVE));
    }

    // ===== the real act_status handler =====

    private static CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), args));
    }

    private static String text(CallToolResult r) {
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                return t.text();
            }
        }
        fail("no text content in result");
        return null;
    }

    /** The MOVE row exactly as the model receives it: through the handler, over JSON. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> moveRowOverTheWire(FakeActuator act, ActRuntime runtime) {
        Map<String, Object> out = Json.readObject(text(call(new ActTools(runtime).actStatus(),
                Map.of())));
        assertNotNull("act_status must return a JSON object", out);
        return (Map<String, Object>) ((List<Object>) out.get("slots")).get(ActSlot.MOVE.ordinal());
    }

    /** The projection, off the same runtime the wire form is read from. */
    private static ActStatus.SlotStatus moveStatus(ActRuntime runtime) {
        return runtime.status().slots().get(ActSlot.MOVE.ordinal());
    }

    // ===== the tests =====

    /**
     * A walk that spent a recovery lane must report that it did, on the row a model reads.
     *
     * <p>The lane is dropped the moment a side-step lands, so the running ticks that spent it are
     * gone by the time the walk ends -- which makes the terminal row the only place the cost can
     * still be read. If the row says {@code NOTHING} here, the caller has a walk that visibly
     * failed to get anywhere and no account whatsoever of what it tried.
     */
    @Test
    public void aWalkThatSpentALaneSaysSoOnTheRowAModelReads() {
        Walk walk = drive(1);

        assertTrue("premise: the slot must have reached a terminal phase, or nothing was "
                + "summarised: " + walk.end().phase(), walk.end().phase().isTerminal());

        ActStatus.SlotStatus status = moveStatus(walk.runtime());
        MoveTactic projected = status.tactic();
        assertNotNull("runtime.status() must project the tactic, or the projection is dead code "
                + "nothing reads", projected);
        assertNotEquals("a walk that gave up the direct line for a lane must not report the value "
                + "that says it spent nothing -- production stamped NOTHING here, which is what "
                + "made this invisible from outside the executor",
                MoveTactic.GivenUp.NOTHING, projected.givenUp());

        // Door two: the wire form.
        Map<String, Object> row = moveRowOverTheWire(walk.actuator(), walk.runtime());
        assertNotNull("and the act_status row must carry it too, because the row is the only door "
                + "the model has", row.get("tactic"));
        @SuppressWarnings("unchecked")
        Map<String, Object> overWire = (Map<String, Object>) row.get("tactic");
        assertEquals("the wire form must carry the same decision the projection does, as the enum "
                + "NAME a caller branches on", projected.givenUp().name(), overWire.get("givenUp"));
        assertNotEquals("so the lane-spending value has to survive to the JSON, not merely exist "
                + "on the record",
                MoveTactic.GivenUp.NOTHING.name(), overWire.get("givenUp"));

        // Every field, not just the one under test. The projection is hand-written rather than
        // handed to Json as the record, so a field the writer forgets is silently absent from the
        // wire form -- and the only way a model learns that is by finding the key missing, which
        // it cannot report as a defect because it does not know the key should be there.
        for (String key : List.of("forward", "strafe", "jump", "yawChange", "lane", "givenUp",
                "describe")) {
            assertTrue("the tactic object must carry '" + key + "' on the wire, or a model cannot "
                    + "ask for it at all: " + overWire.keySet(), overWire.containsKey(key));
        }
        assertNotNull("and the description is what a model reads first, so it cannot be the field "
                + "that went missing", overWire.get("describe"));
    }

    /**
     * The control, and the reason the assertion above is worth anything.
     *
     * <p>The same corridor, the same plan, the same executor, with no log dropped in afterwards --
     * so the walk never meets an obstruction and genuinely spends nothing. If the row said a
     * lane-spending value here, the field would be decoration and every caller would have learned
     * to distrust the one thing this row was added to say.
     */
    @Test
    public void aWalkThatSpentNothingSaysNothingOverTheWireToo() {
        Walk walk = drive(-1);

        assertEquals("premise: the route must have completed: " + walk.end().message(),
                ActPhase.COMPLETE, walk.end().phase());

        ActStatus.SlotStatus status = moveStatus(walk.runtime());
        assertEquals("a walk that never met an obstruction spent no option, and a row claiming "
                        + "otherwise would make every other value on this enum unreadable",
                MoveTactic.GivenUp.NOTHING, status.tactic().givenUp());

        @SuppressWarnings("unchecked")
        Map<String, Object> overWire =
                (Map<String, Object>) moveRowOverTheWire(walk.actuator(), walk.runtime())
                        .get("tactic");
        assertEquals("and the wire form must agree with the projection rather than being written "
                        + "separately and drifting",
                MoveTactic.GivenUp.NOTHING.name(), overWire.get("givenUp"));
    }

    /**
     * The two walks are genuinely different rows, asserted as values rather than argued.
     *
     * <p>Cheap, and it catches the class of defect the two assertions above share: a projection
     * that returns the right constant for these two inputs but is constant in between.
     */
    @Test
    public void theTwoWalksReadAsDifferentDecisionsOverTheWire() {
        String spent = givenUpOverTheWire(drive(1));
        String unspent = givenUpOverTheWire(drive(-1));

        assertNotEquals("a log in the world must change what the row reports, or the field is not "
                + "reporting anything", unspent, spent);
        assertEquals("and the unspent walk is the one that says nothing was given up", "NOTHING",
                unspent);
    }

    @SuppressWarnings("unchecked")
    private static String givenUpOverTheWire(Walk walk) {
        Map<String, Object> tactic = (Map<String, Object>) moveRowOverTheWire(walk.actuator(),
                walk.runtime()).get("tactic");
        return (String) tactic.get("givenUp");
    }

    /**
     * A walk that never chose a tactic must say so with a null, and {@code walk_straight} is that
     * case on the real path.
     *
     * <p>Honest rather than missing, and the distinction matters because an empty object would be
     * readable as "a tactic that chose nothing". Only a {@code RouteIntent} has anywhere to stamp a
     * decision -- {@code MoveApplier.stamp} skips every other intent type -- so a point walk
     * publishes none and the row must report the absence rather than invent a decision.
     */
    @Test
    public void aWalkStraightPublishesNoTacticAndTheRowSaysSo() {
        FakeActuator act = corridor();
        GameClock clock = new GameClock();
        clock.reset();
        ActRuntime runtime = new ActRuntime(clock);
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime, null));
        runtime.submit(new MoveIntent(1f, 0f, false, false, false, 5));
        new ActTickLoop(runtime).onTick(new TickEvent(1));

        assertTrue("premise: the point walk must have been applied at least once, or the row is "
                + "reporting an unsubmitted slot: " + runtime.record(ActSlot.MOVE).message(),
                runtime.record(ActSlot.MOVE).ticksActive() > 0);
        assertEquals("premise: and the slot must hold a plain MoveIntent, which is the whole reason "
                        + "no tactic exists to report: " + runtime.record(ActSlot.MOVE).intent(),
                true, runtime.record(ActSlot.MOVE).intent() instanceof MoveIntent);
        assertEquals("the projection must report the absence rather than inventing a decision, "
                + "because only a RouteIntent has anywhere to stamp one",
                null, moveStatus(runtime).tactic());
        assertEquals("and the row must carry that same null, not an empty object a caller could "
                + "read as 'chose nothing'", null,
                moveRowOverTheWire(act, runtime).get("tactic"));
    }

    /**
     * The grade is on the wire too, and the two absent/UNKNOWN cases stay distinguishable there.
     *
     * <p>The projection being non-null on the record was never the question; whether a model can
     * read it off {@code act_status} is. And a row that collapsed them back into one value would
     * undo the fix at exactly the point it was supposed to become visible.
     */
    @Test
    public void theGradeIsOnTheWireAndUngradedIsNotUnknown() {
        Walk walk = drive(1);
        Map<String, Object> row = moveRowOverTheWire(walk.actuator(), walk.runtime());

        assertTrue("the row must carry a belief key, since act_status quotes the sentence above it "
                + "and a sentence with nothing to weigh it by is the defect: " + row.keySet(),
                row.containsKey("belief"));
        // CHANGED by the grading wave, and the change is the point rather than a regression: this
        // walk's refusals are now GRADED at their sites, so the honest wire value is a grade. The
        // assertion below was "this row is null"; it is now "this row carries a grade AND that
        // grade is not UNKNOWN", which is the same distinction aimed at a line that has one.
        //
        // What is NOT re-pinned is the null-vs-UNKNOWN distinction itself. A graded world has almost
        // no null rows left to demonstrate it on, which is the improvement; the distinction is now
        // asserted against the one line that IS still unexamined -- a freshly submitted slot.
        assertNotNull("a walk's refusals are graded at their sites now, so the row must carry a grade "
                + "rather than the null it carried when no site examined it: " + row, row.get("belief"));
        assertNotEquals("and it must not be UNKNOWN by accident -- UNKNOWN means somebody looked and "
                + "could not see, and a refusal that was merely never graded is not that",
                "UNKNOWN", row.get("belief"));
        assertTrue("the count must travel beside the grade all the way to the wire: " + row.keySet(),
                row.containsKey("unreadCells"));
        assertNull("an absent count must be null and never 0 -- 0 claims a search ran and read "
                + "everything, which is a stronger claim about the world than any site made",
                row.get("unreadCells"));
    }
}