package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.eval.EvalSuite.Result;
import net.marcloud.mcp.core.eval.SurvivalDamage.Source;
import org.junit.Test;

/**
 * The eval can fail.
 *
 * <p><b>This is the test the whole slice exists to make possible, and it is not a test about
 * damage.</b> Before this slice, no task in {@code EvalSuite} could fail on its survival row:
 * {@code w.health() > 0.0D} read a fixture value nothing in the loop could write, so it evaluated
 * {@code 20.0 > 0.0} in every world. A gate that cannot go red is not a gate, and a suite whose
 * rows are all constants is not measuring the controllers -- it is printing them. T20 was the
 * sharpest case: a four-block lava band across the route, and the only assertion in the whole task
 * that worked was {@code inLava == 0} read off a {@code posTrace} the same code appends
 * unconditionally. There was no world in which T20 failed, which means there was no world in which
 * it passed either.
 *
 * <p><b>What a negative control is and is not.</b> It is not "the damage code has a test" -- that
 * is {@code ALavaBandThatKillsThePlayerIsVanillasArithmeticTest}, and it too can pass while nothing
 * observes the result. A negative control is a test that plants the hazard the task is supposed to
 * survive and proves the TASK reports failure, with the right reason, in the same shape it would
 * report a pass. If the suite's survival rows are constants this test cannot be written at all.
 *
 * <p><b>It is written to be broken.</b> Every claim below is a claim about the eval's output, so
 * the mutation that kills it is a mutation of the eval: break the damage application and the
 * hazard becomes decorative, the planted run survives, and every assertion in
 * {@link #aRunThatIgnoresTheHazardIsReportedAsAFailureWithTheRightReason} inverts at once. That
 * is the number the slice reports, and a control that has never been seen red is not a control.
 *
 * <p><b>Why T20 and not another task.</b> T20 is the task whose survival row was the most vacuous,
 * so its row is the one worth proving has teeth. The hazard is planted by removing the one thing
 * that makes the task easy -- {@code GoalPolicy}'s route around the band -- and walking the
 * straight line instead. Everything else about the world is T20's own, cell for cell.
 */
public final class TheSurvivalRowDetectsAPlantedHazardTest {

    private static final int SPAWN_X = -8;
    private static final int SPAWN_Z = -8;
    private static final int LAVA_X0 = 4;
    private static final int LAVA_X1 = 7;
    private static final int LAVA_Z0 = -24;
    private static final int LAVA_Z1 = 0;

    /**
     * T20's world, cell for cell, with the same band and the same outcrop.
     *
     * <p>Identical to the fixture inside {@code T20TheDirectLineIsLava.run()}. Duplicated rather
     * than extracted on purpose: a shared builder is a second thing that can drift, and the claim
     * this test makes is about <em>that</em> world's geometry, so the geometry is written out
     * here where a reader can check it against the band the numbers describe.
     */
    private static SimWorld bandBetweenSpawnAndOre() {
        SimWorld w = new SimWorld().plain(64, "dirt", -24, 23, LAVA_Z0, 20)
                .standOn(SPAWN_X, 64, SPAWN_Z).facing(0f).atHealth(20.0D);
        w.box(LAVA_X0, 64, LAVA_Z0, LAVA_X1, 64, LAVA_Z1, "lava");
        w.box(9, 64, 2, 10, 64, 3, "stone");
        w.give(0, "stone_pickaxe", 1);
        return w;
    }

    /**
     * The control itself: walk the straight line T20 refuses to walk, and the run must end dead.
     *
     * <p>What this asserts, precisely: with the hazard planted, the world's own damage rules take
     * the bar to zero, the band was genuinely entered, and the task's survival comparison --
     * {@code EvalSuite}'s replacement for the old constant -- reports false. The last clause is
     * the one that matters and it is the one that could not be written before this slice.
     */
    @Test
    public void aRunThatIgnoresTheHazardIsReportedAsAFailureWithTheRightReason() {
        SimWorld w = bandBetweenSpawnAndOre();
        EvalHarness h = new EvalHarness(w);
        // The goal is on the FAR side of the band and the intent names it directly, so the route
        // has no southern detour to take: this is the straight line T20 exists to refuse.
        h.runtime().submitNav(new NavIntent(20.5D, 64.0D, 2.0D, 600));
        h.runMove(900);

        assertTrue("the straight line from the spawn to the far side crosses the band, so the"
                + " walk had to enter it or the plant did nothing: ticksInLava="
                + w.ticksInLava() + " end pos=(" + w.posX() + "," + w.posY() + "," + w.posZ() + ")",
                w.ticksInLava() > 0);
        assertEquals("walking into the band kills a full bar, so the planted run ends at 0",
                0.0D, w.health(), 0.0001D);
        assertTrue("the bar went all the way down, not merely below the floor", w.survival().dead());
        assertEquals("and the cause is named, so a failure message points at the lava and not at"
                + " a controller", 20.0D, w.survival().damageFrom(Source.LAVA), 0.0001D);

        // THE CLAIM. This is the comparison the old suite could not make.
        assertFalse("this is the assertion that used to be `w.health() > 0.0D` against a fixture"
                + " value: it now reads a bar the world moved, so a run that walked into lava is"
                + " reported as a failure rather than as a pass",
                EvalSuite.survived(w));
        assertFalse("and the north-star row fails with it -- 20 - 20 = 0 is not 'never below 18',"
                + " and this is the project's own claim rather than a bare survival check",
                EvalSuite.neverBelowNorthStar(w));
    }

    /**
     * The mirror, and the reason the control above is a measurement rather than a demonstration:
     * the SAME world, the SAME walk, with the band replaced by the dirt it replaced.
     *
     * <p>Two runs differing in one cell of geometry. If the hazard is what separates them, then
     * removing it has to restore the pass, and a control whose negative half cannot be made to
     * pass is not a control either -- it is a second assertion of the same thing.
     */
    @Test
    public void removingTheBandIsTheOnlyThingThatSeparatesTheTwoRuns() {
        SimWorld withLava = bandBetweenSpawnAndOre();
        EvalHarness hl = new EvalHarness(withLava);
        hl.runtime().submitNav(new NavIntent(20.5D, 64.0D, 2.0D, 600));
        hl.runMove(900);

        SimWorld withoutLava = new SimWorld().plain(64, "dirt", -24, 23, LAVA_Z0, 20)
                .standOn(SPAWN_X, 64, SPAWN_Z).facing(0f).atHealth(20.0D);
        withoutLava.box(9, 64, 2, 10, 64, 3, "stone");
        withoutLava.give(0, "stone_pickaxe", 1);
        EvalHarness hn = new EvalHarness(withoutLava);
        hn.runtime().submitNav(new NavIntent(20.5D, 64.0D, 2.0D, 600));
        hn.runMove(900);

        assertTrue("the planted world was lethal", !EvalSuite.survived(withLava));
        assertTrue("and the same walk over the same ground with the band removed is not",
                EvalSuite.survived(withoutLava));
        assertTrue("so the pass and the fail are the same code path reading the same world with"
                + " one variable changed, which is the only shape in which a control is evidence",
                withLava.ticksInLava() > 0 && withoutLava.ticksInLava() == 0);
        assertEquals("the control world also reports no hazard reached, so a task that ran there"
                + " is told plainly that it did not measure survival", false,
                withoutLava.anyHazard());
    }

    /**
     * The task row itself, not the world under it.
     *
     * <p>The two tests above prove the world can kill a player and that the comparison notices.
     * This one proves the SUITE's own task object reports the failure, in the shape a reader sees
     * in the eval report, with the reason attached -- because a control that only the test class
     * can see is not a control on the suite.
     *
     * <p>T20 is run as-is and is expected to PASS (the policy routes around the band). The
     * negative half is a task with T20's world and T20's goal and no detour, and it is expected to
     * FAIL with the death named in the fact string.
     */
    @Test
    public void theTaskRowItselfReportsTheFailureRatherThanAPass() {
        // Half one: T20 unchanged, because the point of the suite is that a competent policy
        // gets around. If this ever fails, the band is not where the report says it is.
        Result asShipped = new EvalSuite.T20TheDirectLineIsLava().run();
        assertTrue("T20 must still pass when the policy routes around the band: " + asShipped.fact(),
                asShipped.pass());
        assertTrue("and its report carries the north-star claim rather than a bare health number,"
                + " because that is the row this slice put there: " + asShipped.fact(),
                asShipped.fact().contains("the health bar never went below 18.0"));

        // Half two: the same world, the same goal, and the straight line is the only route. This
        // is the negative control in the shape the runner consumes it.
        EvalSuite.Result planted = EvalSuite.T20TheDirectLineIsLava.plantedHazardRun();
        assertFalse("a run that walks the lethal straight line must be reported as a failure, or"
                + " the suite's survival rows are still constants: " + planted.fact(), planted.pass());
        assertTrue("and the failure has to NAME the death, so a reader is not left guessing which"
                + " of the world's facts disagreed: " + planted.fact(),
                planted.fact().contains("the player died"));
    }

    /**
     * T20's own world is the one task in the suite where a hazard is lethal AND on the route, so
     * it is the one task that has to carry the north-star claim rather than an isolation check.
     *
     * <p>Written as a test rather than a comment because the claim is the project's: "a weak LLM
     * plus a system prompt finishes a night safely -- a shelter, health never below 18, a box
     * standing at dawn". A suite that measures that has to be able to produce a run that ends
     * below 18, and this is the run it produces.
     */
    @Test
    public void theSuiteCanProduceBothARunThatEndsBelowEighteenAndOneThatDoesNot() {
        // The same world twice, with the SAME band standing in both. The only variable is the
        // goal: the safe run is sent to a cell on the spawn side of the band, the fatal run to a
        // cell on the far side. So the two figures come out of one fixture, and the band is not
        // the difference -- the route is.
        SimWorld safe = bandBetweenSpawnAndOre();
        EvalHarness hs = new EvalHarness(safe);
        hs.runtime().submitNav(new NavIntent(-20.5D, 64.0D, -8.0D, 600));
        hs.runMove(900);

        SimWorld fatal = bandBetweenSpawnAndOre();
        EvalHarness hf = new EvalHarness(fatal);
        hf.runtime().submitNav(new NavIntent(20.5D, 64.0D, 2.0D, 600));
        hf.runMove(900);

        assertTrue("the safe run ended at " + safe.minimumHealth() + " and the planted run at "
                        + fatal.minimumHealth() + "; the band stood in both worlds, so the two"
                        + " figures are the route and nothing else",
                safe.minimumHealth() >= 18.0D && fatal.minimumHealth() < 18.0D);
        assertEquals("the safe run never touched the band, so the floor held for all 900 ticks",
                0, safe.ticksInLava());
        assertEquals("and the bar never left full", 20.0D, safe.minimumHealth(), 0.0001D);
        assertEquals("the planted run walked into the band and died there", 0.0D,
                fatal.minimumHealth(), 0.0001D);
        assertTrue("so the instrument produces both answers from one world, which is what makes"
                + " 18 a threshold rather than a constant", fatal.ticksInLava() > 0);
    }
}
