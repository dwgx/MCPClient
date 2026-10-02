package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;
import org.junit.Test;

/**
 * The grade must survive the seam from the {@link ActOutcome} to the {@link SlotRecord}, because the
 * record is the thing a caller reads.
 *
 * <p><b>Why the existing belief test says nothing about this.</b>
 * {@code ARouteRefusalOnUnreadTerrainIsNotTheSameFailureAsOnImpassableTerrainTest} reads
 * {@code out.belief()} off the {@code ActOutcome}, which is where the grade is born -- and it is
 * green, and it is right. It proves the type is correct. It never reads the grade off the record,
 * so it would have stayed green through the entire defect below. A type can be perfect and the seam
 * can still drop it, and the only test that can tell the difference is one that reads the far side.
 *
 * <p><b>The defect, measured before this file existed.</b> {@code MoveApplier} had ZERO
 * {@code withBelief} calls. Its terminal path built the record with {@code markActive(...).withPhase(
 * ...)} and its running path with {@code markActive(...)}, and neither carries a grade -- so every
 * walk published {@code SlotRecord.UNGRADED} no matter what its controller had decided. Worse, that
 * constant was spelled {@code Belief.UNKNOWN}: the same value a refusal over unread terrain earned.
 * So at the record boundary "this client looked and could not see" and "nobody recorded that anyone
 * looked" were the same value, on every walk, which is exactly the conflation {@code Graded}'s own
 * javadoc exists to forbid.
 *
 * <p><b>Why this drives the real applier instead of calling a copy-with.</b> The defect was never in
 * {@code withBelief}; it was that nobody called it. A test that hand-builds the record the way the
 * fixed code builds it would pass with the fix reverted. So {@code MoveApplier} is wired the way
 * {@code McpCore} wires it, the refusal machine comes from the real {@code RoutePlanning}, and the
 * value under test is read off the record the applier returned.
 *
 * <p><b>Both arms, because one of them cannot pass by construction today.</b> The identical refusal
 * over the identical impassable path, differing only in whether the search could read what it looked
 * at: {@code unreadCells > 0} must reach the record as {@link Belief#UNKNOWN}, and
 * {@code unreadCells == 0} as {@link Belief#OBSERVED}. Before the fix both arms read
 * {@code UNGRADED} on the record and the second one failed -- which is the control. There is no
 * constant that satisfies both arms on the unfixed tree, so this file cannot be satisfied by
 * hardcoding either answer.
 */
public class ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest {

    private static final Stance START = new Stance(0, 64, 0);
    private static final Stance GOAL = new Stance(0, 64, 9);
    private static final String SOURCE = "CLIENT world (no integrated server: a plan built on the "
            + "client's prediction can be reverted by the server)";
    private static final String FAILURE = "every reachable stance was explored and the goal was not "
            + "among them; with 0 block(s) of budget and 30 ticks of air there is no route";
    private static final int FEET = 64;

    /**
     * Drive one tick of a real {@link MoveApplier} over a refusal, and return the record it built.
     *
     * <p>{@code refusalFor} is the production machine: an anonymous {@link LocomotionController}
     * whose first and only tick is a terminal {@link ActOutcome}. It reaches the applier through the
     * route factory exactly as a real plan would, so nothing between {@code noRouteMessage} and the
     * record can drop the grade without this going red.
     */
    private static SlotRecord drive(int unreadCells) {
        Graded<String> refusal = RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE,
                unreadCells);
        FakeActuator act = new FakeActuator();
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        LocomotionController machine = RoutePlanning.refusalFor(refusal);

        ActRuntime runtime = new ActRuntime();
        MoveApplier applier = new MoveApplier(act, runtime, ri -> machine);

        SlotRecord rec = SlotRecord.submitted(new RouteIntent(0, FEET, 9, 0), 0L, 1L, "submitted");
        return applier.apply(rec.stampTick(1));
    }

    /**
     * Arm one: a refusal that counted unread cells reaches the record still graded UNKNOWN.
     *
     * <p>This is the case the layer exists for. The search asked about 412 cells and could not read
     * them, so "no route" is a statement about what THIS CLIENT can see, and the caller reading
     * {@code act_status} has to be able to learn that.
     */
    @Test
    public void aRefusalThatCountedUnreadCellsReachesTheRecordAsUnknown() {
        SlotRecord rec = drive(412);

        assertTrue("premise: the refusal must reach the slot as a terminal failure, or there is no "
                + "seam to cross: " + rec.phase(), rec.phase().isTerminal());
        assertEquals("the grade the site chose must be the grade the caller reads off the record, "
                        + "and it is UNKNOWN because 412 cells of the searched area were never seen",
                Belief.UNKNOWN, rec.belief());
    }

    /**
     * Arm two, and the control that makes arm one mean anything.
     *
     * <p>The identical refusal over the identical impassable path, with every cell the search asked
     * about actually read. Now the failure IS a fact about the terrain, so it is
     * {@link Belief#OBSERVED}.
     *
     * <p><b>This assertion could not pass on the tree this file was written against, by
     * construction.</b> {@code MoveApplier} published {@code UNGRADED} for every walk and
     * {@code UNGRADED} was spelled {@code UNKNOWN}, so this arm read {@code UNKNOWN} where it
     * demands {@code OBSERVED}. Mapping everything to UNKNOWN fails here; a record that simply
     * echoed whatever the outcome said also fails here, because the outcome said OBSERVED and
     * nothing was reading it. Only a propagation that actually crosses the seam passes both arms.
     */
    @Test
    public void aFullyReadRefusalReachesTheRecordAsObserved() {
        SlotRecord rec = drive(0);

        assertTrue("premise: the refusal must reach the slot as a terminal failure, or there is no "
                + "seam to cross: " + rec.phase(), rec.phase().isTerminal());
        assertEquals("with nothing unread the search saw every cell it asked about, so this "
                        + "refusal IS a fact about the terrain, and publishing UNKNOWN for it would "
                        + "be its own kind of lie -- on the record, not just on the outcome",
                Belief.OBSERVED, rec.belief());
    }

    /**
     * The two refusals are distinguishable AT THE RECORD, which is the whole property and is not
     * implied by either arm alone.
     *
     * <p>Asserted as a value rather than argued, because the failure this file exists for is
     * precisely the two arriving as one value -- and a test that checked each arm separately would
     * still have been satisfied by an implementation that returned a constant.
     */
    @Test
    public void theTwoRefusalsAreDifferentValuesOnTheRecordItself() {
        assertNotEquals("one unread cell is enough to separate them: the search proves nothing "
                        + "about a cell it could not read, so the record a caller reads must not be "
                        + "the same record either",
                drive(0).belief(), drive(412).belief());
    }

    /**
     * The half of the record boundary that the fix had to change, and the reason arm two is not
     * merely a nice-to-have.
     *
     * <p>"Nobody looked" and "somebody looked and could not see" are now different values, and the
     * constant that used to spell the first one is the same object as the second. A walk that never
     * failed still produces an unexamined line, and that line must not read as evidence.
     */
    @Test
    public void anUngradedLineIsNotTheSameValueAsALookThatFailed() {
        assertEquals("a freshly submitted slot carries a line no site has examined, and that is "
                        + "the absence of a grade rather than a grade",
                null, SlotRecord.submitted(new RouteIntent(0, FEET, 9, 0), 0L, 1L, "s").belief());
        assertNotEquals("and it must not be the value that means somebody looked and could not "
                        + "see, because that is what told a caller to treat an unexamined sentence "
                        + "as a negative observation",
                Belief.UNKNOWN, SlotRecord.UNGRADED);
    }

    /**
     * Both directions refuse, so the honest value did not have to cost the safe one.
     *
     * <p>{@code null} is the honest answer for an unexamined line and {@link Belief#UNKNOWN} is the
     * honest answer for a look that failed, and a bare {@code belief != UNKNOWN} test cannot tell
     * them apart -- it says yes to both, because {@code null != UNKNOWN} is true. So the decision
     * lives behind one accessor that refuses in both cases, which is the shape
     * {@code Graded.mayActOn()} already has inside this layer.
     */
    @Test
    public void nothingThatFailsToLookIsEverActionable() {
        assertTrue("an ungraded line must refuse: there is no evidence behind it at all",
                !SlotRecord.submitted(new RouteIntent(0, FEET, 9, 0), 0L, 1L, "s").mayActOn());
        assertTrue("and a refusal that counted unread cells must refuse too, for the same reason a "
                + "bare belief() != UNKNOWN test cannot be trusted to do",
                !drive(412).mayActOn());
        assertTrue("a fully-read refusal is earned, so a caller may act on it",
                drive(0).mayActOn());
    }

    /**
     * The grade is still published the way it was, and the record still reports the refusal.
     *
     * <p>Not decoration: the fix touches the seam and not the prose, so a reader whose understanding
     * of the refusal sentence is built on slice A must not find it reworded under them in the same
     * commit that made the sentence gradeable.
     */
    @Test
    public void theRefusalProseIsUntouchedByThisChange() {
        SlotRecord rec = drive(412);

        assertTrue("the refusal still arrives as prose, and it is the same prose: " + rec.message(),
                rec.message().startsWith("route not planned: no route from (0,64,0) to (0,64,9) "));
        assertTrue("including the unread clause, which is what the grade is derived from: "
                + rec.message(),
                rec.message().contains("412 cell(s) could not be read at all"));
    }
}