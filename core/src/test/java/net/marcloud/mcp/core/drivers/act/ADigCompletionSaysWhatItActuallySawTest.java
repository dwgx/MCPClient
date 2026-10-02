package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.util.Belief;
import org.junit.Test;

/**
 * A dig completion is three claims, not one -- and the third is the one the old code was making.
 *
 * <p><b>What was false before.</b> {@code DigController} returned
 * {@code ActOutcome.done("stone (0,1,0) broken after 3 ticks")} in every case, and every caller
 * read "broken" as a fact. It is a fact in one of the three: when the target simply emptied into
 * air, {@code blockAt} returns null for air, for an unloaded chunk, for no world AND -- in
 * {@code LivePlayerActuator}'s catch block -- for a read that threw. The seam cannot tell those
 * apart, so the sentence and the evidence behind it were the same value, and {@code belief()} was
 * {@link Belief#UNKNOWN} on every one of them.
 *
 * <p><b>Why each case carries a control rather than standing alone.</b> A one-sided assertion
 * ("the unread completion is UNKNOWN") passes just as happily against an implementation that grades
 * EVERYTHING UNKNOWN, and that implementation is the one the design's section 5.2 names as the
 * cheapest lie available. So every grade below is asserted next to the case that must produce a
 * DIFFERENT one:
 *
 * <ul>
 *   <li>a block replaced by a named block (OBSERVED) beside the same break into air (UNKNOWN),
 *   <li>an unreadable start sample (INFERRED) beside the same dig with a readable one (not
 *       INFERRED, and specifically OBSERVED).
 * </ul>
 *
 * <p><b>The message beside each site stays truthful,</b> which is why the hedge is asserted too:
 * an UNKNOWN sentence that still reads "broken" with nothing after it is the same lie wearing a
 * new type. The observable-when-observed case carries no hedge at all, because a hedge added to a
 * claim that earned none teaches every later reader to skip the hedges.
 */
public class ADigCompletionSaysWhatItActuallySawTest {

    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 0;

    private static FakeActuator stoneInReach() {
        FakeActuator act = new FakeActuator();
        act.putBlock(X, Y, Z, "stone");
        return act;
    }

    private static DigController dig() {
        return new DigController(InteractIntent.dig(X, Y, Z, 1), 5);
    }

    /** Tick once to leave RESOLVING and take the baseline sample. */
    private static void startDig(DigController c, FakeActuator act) {
        ActOutcome out = c.tick(act);
        assertFalse("the dig must be running after the start tick, not terminal: " + out.message(),
                out.terminal());
    }

    @Test
    public void aBlockReplacedByAnotherNamedBlockIsAnObservation() {
        FakeActuator act = stoneInReach();
        act.breakAfterPumps = 1;
        act.fillsWith = "water";
        DigController c = dig();
        startDig(c, act);

        ActOutcome out = c.tick(act);

        assertTrue("the dig must still COMPLETE on the breaking tick: " + out.message(),
                out.terminal() && out.ok());
        assertEquals("both sides of the identity comparison were read this tick -- the baseline was "
                        + "\"stone\" and the position now reads \"water\" -- so this is a direct "
                        + "observation of the world and nothing more",
                Belief.OBSERVED, out.belief());
    }

    /**
     * The control for the case above, and the reason the case above is not decoration: the same
     * dig, the same ticks, the same terminal outcome, a different grade.
     */
    @Test
    public void aBlockThatBreaksIntoAirIsNotTheSameClaimAsOneReplacedByWater() {
        FakeActuator act = stoneInReach();
        act.breakAfterPumps = 1;
        DigController c = dig();
        startDig(c, act);

        ActOutcome out = c.tick(act);

        assertTrue("behaviour is unchanged: a break into air still COMPLETEs: " + out.message(),
                out.terminal() && out.ok());
        assertEquals("the target read back as no name. blockAt answers null for air, out of range, "
                        + "no world and for a read that threw, so at this seam this client cannot "
                        + "say it watched the block break",
                Belief.UNKNOWN, out.belief());
    }

    @Test
    public void aDigWhoseStartSampleCouldNotBeNamedRestsOnTheEmptinessTest() {
        FakeActuator act = stoneInReach();
        act.blockAtReturnsNull = true;      // the baseline sample cannot be read
        DigController c = dig();
        startDig(c, act);

        act.removeBlock(X, Y, Z);           // the block goes, and the only test left is emptiness
        ActOutcome out = c.tick(act);

        assertTrue("the dig still COMPLETEs, exactly as it did before the grade existed: "
                + out.message(), out.terminal() && out.ok());
        assertEquals("with no baseline to compare, the completion rests on the emptiness fallback. "
                        + "That is a real observation of an empty space and an INFERRED claim about "
                        + "the block we were breaking, which is a different claim",
                Belief.INFERRED, out.belief());
    }

    /**
     * The message beside the site, not the grade beside it.
     *
     * <p>A grade nobody reads is a decoration, and a sentence that outlives its own evidence is the
     * defect this whole layer is for -- so the prose has to move with the type.
     */
    @Test
    public void onlyTheCompletionThatEarnedNoHedgeCarriesTheHedge() {
        FakeActuator observed = stoneInReach();
        observed.breakAfterPumps = 1;
        observed.fillsWith = "water";
        DigController a = dig();
        startDig(a, observed);
        String observedMessage = a.tick(observed).message();

        FakeActuator air = stoneInReach();
        air.breakAfterPumps = 1;
        DigController b = dig();
        startDig(b, air);
        String unknownMessage = b.tick(air).message();

        assertTrue("the OBSERVED message is the sentence that was always there, byte for byte: "
                + observedMessage,
                observedMessage.startsWith("stone (0,1,0) broken after 1 ticks, now water"));
        assertFalse("and it carries no hedge at all, because a caveat bolted onto a claim that "
                + "earned none teaches every later reader to skip the caveats: " + observedMessage,
                observedMessage.contains("--"));
        assertTrue("the UNKNOWN one says what it could not see, so a reader of the sentence alone "
                + "is not lied to: " + unknownMessage,
                unknownMessage.contains("broken") && unknownMessage.contains("--")
                        && unknownMessage.contains("no name"));
    }

    // ===== per-tick monotonicity =====

    /**
     * The rule itself: grade may go down, never up, and an upgrade needs a world read behind it.
     *
     * <p>Asserted on both sides of every case. A test that only asserted the refusals would pass
     * against an implementation that refuses everything; one that only asserted the acceptances
     * would pass against one that refuses to learn.
     */
    @Test
    public void aGradeMayOnlyGetWeakerAndOnlyWithNothingReadBehindIt() {
        // The acceptance that earns the refusal: a read happened, so the stronger claim is earned.
        assertEquals("a read happened since the last grade, so the claim may be restated: " + Belief.OBSERVED,
                Belief.OBSERVED, DigController.withoutUpgrade(Belief.INFERRED, Belief.OBSERVED, true));

        // The refusal: same pair, no read.
        try {
            DigController.withoutUpgrade(Belief.INFERRED, Belief.OBSERVED, false);
            fail("an INFERRED completion upgraded to OBSERVED with no world read in between must be "
                    + "refused: that is a stronger claim about the world manufactured out of a "
                    + "cached value, which is a code defect and not an optimisation");
        } catch (IllegalStateException expected) {
            assertTrue("the refusal must name both grades and the missing read, or it is a shrug: "
                    + expected.getMessage(), expected.getMessage().contains("INFERRED")
                    && expected.getMessage().contains("OBSERVED")
                    && expected.getMessage().contains("no world read"));
        }

        // Getting WEAKER is always allowed, with or without a read: an OBSERVED completion that
        // later reports INFERRED is the layer working, not a regression.
        assertEquals("OBSERVED down to INFERRED needs no read to justify it",
                Belief.INFERRED, DigController.withoutUpgrade(Belief.OBSERVED, Belief.INFERRED, false));
        assertEquals("and all the way down",
                Belief.UNKNOWN, DigController.withoutUpgrade(Belief.OBSERVED, Belief.UNKNOWN, false));
        assertEquals("the same grade twice is not an upgrade",
                Belief.UNKNOWN, DigController.withoutUpgrade(Belief.UNKNOWN, Belief.UNKNOWN, false));
        assertEquals("the first grade a dig reports cannot be an upgrade of anything",
                Belief.OBSERVED, DigController.withoutUpgrade(null, Belief.OBSERVED, false));

        // A second refusal with its own acceptance, so the rule is not one special case: an
        // UNKNOWN completion cannot become INFERRED on cached evidence either.
        assertEquals("the acceptance for this pair too: a read earns the stronger claim",
                Belief.INFERRED, DigController.withoutUpgrade(Belief.UNKNOWN, Belief.INFERRED, true));
        try {
            DigController.withoutUpgrade(Belief.UNKNOWN, Belief.INFERRED, false);
            fail("UNKNOWN upgraded to INFERRED with no world read in between must be refused as "
                    + "surely as INFERRED upgraded to OBSERVED: the rule is about evidence, not "
                    + "about which pair of constants is involved");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("UNKNOWN")
                    && expected.getMessage().contains("INFERRED"));
        }
    }

    /**
     * The rule as the controller actually walks into it.
     *
     * <p>The start sample is read exactly once per dig, so a dig that could not name its target on
     * that tick can never name it later -- and its completion grade can therefore never become
     * OBSERVED, however many more ticks it is asked. This is the property an implementation that
     * "helpfully" re-sampled the baseline would break.
     */
    @Test
    public void aDigWithAnUnreadableBaselineStaysInferredOnEveryLaterTick() {
        FakeActuator act = stoneInReach();
        act.blockAtReturnsNull = true;
        DigController c = dig();
        startDig(c, act);
        act.removeBlock(X, Y, Z);

        for (int later = 1; later <= 4; later++) {
            ActOutcome out = c.tick(act);
            assertTrue("tick " + later + " must still report the completion: " + out.message(),
                    out.terminal() && out.ok());
            assertEquals("tick " + later + ": the baseline is never re-read, so nothing has changed "
                    + "that could earn a stronger claim than the first one",
                    Belief.INFERRED, out.belief());
        }
    }
}