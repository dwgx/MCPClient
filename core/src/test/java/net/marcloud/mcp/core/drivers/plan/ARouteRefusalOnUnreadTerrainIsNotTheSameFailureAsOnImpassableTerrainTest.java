package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;
import org.junit.Test;

/**
 * "I could not see" and "there is no route" are different failures, and this is the test that says
 * so in a type rather than in a sentence.
 *
 * <p><b>The gap this closes.</b> Before the belief layer, {@code RoutePlanning} built a refusal
 * sentence and handed it to {@code ActOutcome.failed(String)}. The unread-cell count was already
 * being read -- it is in the message, verbatim -- but it was spent on PROSE. A caller receiving
 * "route not planned: no route from (0,64,0) to (0,64,9) ... -- and 412 cell(s) could not be read
 * at all" and a caller receiving "route not planned: no route from (0,64,0) to (0,64,9) ...
 * every reachable stance was explored" received the SAME TYPE. One of those is a claim about the
 * terrain and one is a claim about chunk loading, and the difference decides whether the reader
 * goes and loads chunks or goes and re-picks the goal. Nothing downstream could ask, because there
 * was no seam with the shape to carry the answer.
 *
 * <p><b>Why this is a CONTROL PAIR and not a one-sided assertion.</b> Two assertions that both say
 * UNKNOWN would pass under an implementation that hardcodes UNKNOWN, and would prove nothing. The
 * two halves below are the same refusal, over the same impassable path, differing only in whether
 * the search could read the cells it looked at:
 *
 * <ul>
 * <li>unread &gt; 0 &rarr; UNKNOWN. Part of the searched area was never seen, so "no route" is a
 *     statement about this client.
 * <li>unread == 0 &rarr; OBSERVED. Every cell the search asked about was read, so the failure is a
 *     fact about terrain.
 * </ul>
 *
 * The cheap mapping -- {@code unreadCells() > 0} to OBSERVED, on the grounds that "I did run the
 * search" -- fails the first. Mapping everything to UNKNOWN fails the second. Only the honest
 * mapping passes both, and the design's own argument is that the cheap one is what twelve of its
 * eighteen sites would silently take.
 *
 * <p><b>Why {@code noRouteMessage} is driven directly rather than through
 * {@code RoutePlanning.executorFor}.</b> The same reason {@code BlockProbeSeparatesUnreadFromAirTest}
 * drives {@code decide} instead of {@code at(World, ...)}: {@code World} cannot be constructed in
 * {@code core/src/test} and {@code GameAccess} reads a live singleton, so the only refusal reachable
 * headlessly through the public entry point is the degenerate "not in a world" one, which never
 * reaches the unread count at all. The rule under test is the MAPPING, and the mapping is a pure
 * function of the unread count -- which is exactly why it was extracted. What that leaves unverified
 * is the wiring from a real {@code LiveBlockView} into that count; {@link #theGradedRefusalReaches
 * TheSlotAsATerminalOutcome} covers the half that is verifiable, and the wiring is called out here
 * rather than assumed.
 */
public class ARouteRefusalOnUnreadTerrainIsNotTheSameFailureAsOnImpassableTerrainTest {

    /** The impassable path both halves share, so nothing but the unread count differs. */
    private static final Stance START = new Stance(0, 64, 0);
    private static final Stance GOAL = new Stance(0, 64, 9);
    private static final String SOURCE = "CLIENT world (no integrated server: a plan built on the "
            + "client's prediction can be reverted by the server)";
    private static final String FAILURE = "every reachable stance was explored and the goal was not "
            + "among them; with 0 block(s) of budget and 30 ticks of air there is no route";

    @Test
    public void unreadCellsMeanTheRefusalIsAStatementAboutThisClient() {
        Graded<String> refusal = RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 412);

        assertEquals("a refusal that counted unread cells is a statement about what this client "
                        + "could not see, not about the terrain",
                Belief.UNKNOWN, refusal.belief());
    }

    /**
     * The control half, and the reason the first assertion means anything: the identical refusal
     * over the identical impassable path, with every cell the search asked about actually read.
     *
     * <p>If the mapping were "everything is UNKNOWN", this goes red. If it were "everything is
     * OBSERVED", the assertion above goes red. There is no constant that satisfies both.
     */
    @Test
    public void aFullyReadRefusalIsAStatementAboutTheTerrain() {
        Graded<String> refusal = RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 0);

        assertEquals("with nothing unread, the search saw every cell it asked about, so this "
                        + "refusal IS a fact about the terrain and grading it UNKNOWN would be its "
                        + "own kind of lie",
                Belief.OBSERVED, refusal.belief());
    }

    /**
     * The two halves are genuinely distinguishable, asserted as a value rather than argued.
     *
     * <p>Cheap to run and it catches a class of defect the two assertions above share: a mapping
     * that returns the right constant for these two inputs but has collapsed the field to something
     * constant in between, or a {@code Graded} that is graded on construction rather than per call.
     */
    @Test
    public void theSamePathGradesDifferentlyAtTheBoundary() {
        assertNotEquals("one unread cell is enough to move the refusal off OBSERVED: the search "
                        + "proves nothing about a cell it could not read",
                RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 0).belief(),
                RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 1).belief());
    }

    /**
     * The grade is a value a CALLER can act on, not a label. UNKNOWN fails safe: the same shape
     * {@code BlockProbe.Solidity} already uses, where an unread cell is neither walkable nor
     * standable so a caller asking only the practical question behaves safely without knowing a
     * third state exists.
     */
    @Test
    public void unknownRefusesToActAndObservedDoesNot() {
        assertTrue("a refusal over unread terrain must not read as permission to act on it",
                !RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 1).mayActOn());
        assertTrue("a refusal over fully read terrain is earned, so a caller may act on it",
                RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 0).mayActOn());
    }

    /**
     * A non-OBSERVED grade carries its provenance, and the constructor refuses to build one
     * without. Without this the type permits a confident-looking UNKNOWN whose reason nobody wrote
     * down, which is the same conflation as {@code MoveTactic}'s {@code givenUp} and exactly as
     * hard to notice later.
     */
    @Test
    public void anUnearnedGradeCannotBeConstructed() {
        try {
            new Graded<>("some message", Belief.UNKNOWN, null);
            throw new AssertionError("a Graded with belief UNKNOWN and no why must not be "
                    + "constructible: a reader cannot tell a derivation from an invention without "
                    + "it, and an invention is worse than an absence because it gets acted on");
        } catch (NullPointerException expected) {
            assertTrue("the message must name the invariant, because a bare NPE sends the next "
                            + "maintainer looking at the wrong place",
                    String.valueOf(expected.getMessage()).contains("why is required"));
        }
    }

    /**
     * The grade survives the trip to the slot.
     *
     * <p>The refusal travels out through the existing anonymous {@link LocomotionController}, whose
     * first tick is a terminal {@link ActOutcome}. If the grade were dropped anywhere between
     * {@code noRouteMessage} and the outcome, this is where it would show -- and dropping it there
     * is the most likely way the whole layer would quietly become decorative, because every other
     * assertion above would still pass.
     */
    @Test
    public void theGradedRefusalReachesTheSlotAsATerminalOutcome() {
        LocomotionController machine = RoutePlanning.refusalFor(
                RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 412));
        ActOutcome out = machine.tick(null);

        assertTrue("a planning failure still arrives as a terminal outcome, so one reporting path "
                + "covers planning and execution alike", out.terminal() && !out.ok());
        assertEquals("and the grade comes with it", Belief.UNKNOWN, out.belief());
        assertTrue("the prose is unchanged: " + out.message(),
                out.message().startsWith("route not planned: no route from (0,64,0) to (0,64,9) "));
        assertTrue("including the unread clause that was already there: " + out.message(),
                out.message().contains("412 cell(s) could not be read at all"));
    }

    /**
     * The prose did not move. A golden string, written out longhand here rather than compared
     * against the production expression, so agreement means two independent derivations produced
     * the same bytes rather than one expression agreeing with itself.
     *
     * <p>This is the acceptance criterion the change could most easily have broken. The grade is
     * meant to be something a reader can now ask a question of; if it arrived with a rewording,
     * every existing reader's understanding of the refusal prose would shift under them in the
     * same commit that added the type meant to make the prose safer.
     */
    @Test
    public void theUnreadRefusalProseIsUnchanged() {
        assertEquals("no route from (0,64,0) to (0,64,9) using the CLIENT world (no integrated "
                        + "server: a plan built on the client's prediction can be reverted by the "
                        + "server): every reachable stance was explored and the goal was not among "
                        + "them; with 0 block(s) of budget and 30 ticks of air there is no route "
                        + "-- and 412 cell(s) could not be read at all, so this may be unloaded "
                        + "chunks rather than impassable ground",
                RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 412).value());
    }

    /** And the fully-read half, which must NOT carry the unread clause at all. */
    @Test
    public void theFullyReadRefusalProseIsUnchangedAndOmitsTheUnreadClause() {
        assertEquals("no route from (0,64,0) to (0,64,9) using the CLIENT world (no integrated "
                        + "server: a plan built on the client's prediction can be reverted by the "
                        + "server): every reachable stance was explored and the goal was not among "
                        + "them; with 0 block(s) of budget and 30 ticks of air there is no route",
                RoutePlanning.noRouteMessage(START, GOAL, SOURCE, FAILURE, 0).value());
    }
}
