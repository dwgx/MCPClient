package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.block.Block;
import org.junit.Test;

/**
 * The fourth north-star criterion answers both ways, and each half can be the one that fails.
 *
 * <p><b>Why this file exists.</b> "A box standing at dawn" is the last of the Owner's four
 * north-star criteria and the only one that was still listed as a known gap
 * ({@code SimWorld.KNOWN_GAPS}, declared at {@code SimWorld.java:110}, with the chest sentence at
 * {@code :166-167}: <em>"a chest, a furnace and an enchanting table all still return a plain
 * placement refusal"</em>). A predicate added to close such a gap is worthless unless it can say no,
 * and the obvious way to write it -- "is a chest in the world when the night ends" -- cannot say no
 * in any world where the player never touches the chest. This file is the proof that it can.
 *
 * <p><b>Why one world.</b> Two worlds differing in a chest could differ in anything else as well,
 * and a failure would not say which. One world read two ways over the same clock has exactly one
 * variable, and here that variable is the agent's own dig -- the only thing in this substrate that
 * can remove a chest at all.
 *
 * <ul>
 *   <li>{@link #theSameWorldGivesBothVerdictsWithAndWithoutTheDig} -- the headline.</li>
 *   <li>{@link #removingTheDigIsTheOnlyThingThatSeparatesThem} -- the control pair: plant the case
 *       the predicate must reject, prove it is rejected, remove the plant, prove it is accepted.</li>
 *   <li>{@link #aChestBuiltAtThreeInTheMorningIsNotAChestThatStoodThroughout} -- the point reading
 *       and the region reading must not stand in for each other. This is the {@code
 *       AShelterIsARegionOverTheNightAndNotARoofAtDawnTest} argument transplanted onto a box.</li>
 *   <li>{@link #aNightThatNeverHappensIsNotMeasuredRatherThanEmpty} -- the symmetric error: a zero
 *       sample window must not report "no box", it must report "not measured".</li>
 *   <li>{@link #theChestIsAskedAboutByBlockTypeAndNotByName} -- a trapped chest is a box.</li>
 *   <li>{@link #aVerdictNamesBothHalvesRatherThanPrintingOneBoolean} -- a failure message that says
 *       only "no box" is a sentence a reader cannot act on.</li>
 * </ul>
 *
 * <p><b>No mutation numbers are asserted here.</b> The mutation counts are reported in
 * {@code .ai-notes/docs/audits/2026-10-02-wave13-chest.md}, measured against the full
 * {@code core} suite, because a mutation count asserted inside the test it constrains would be a
 * number that can be edited to match whatever the mutation happened to do.
 */
public final class TheDawnChestPredicateHasBothVerdictsFromOneWorldTest {

    /** The cell the box occupies. Arbitrary: the criterion says nothing about WHERE a box goes. */
    private static final int BOX_X = 0;
    private static final int BOX_Y = 64;
    private static final int BOX_Z = 0;

    /** One whole night, so the expensive half of the A/B is the one actually measured. */
    private static final int NIGHT = NightEnclosure.nightTicks();

    /**
     * One night of ticks over one world, optionally digging the chest out halfway through.
     *
     * @param digMidNight the only variable between the two halves of the A/B
     */
    private static DawnChest night(boolean digMidNight) {
        SimWorld w = new SimWorld().put(BOX_X, BOX_Y, BOX_Z, "chest")
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
            if (digMidNight && tick == NIGHT / 2) {
                // The only thing that can remove a chest in this substrate: the agent's own dig.
                w.instantBreak(BOX_X, BOX_Y, BOX_Z, net.marcloud.mcp.core.drivers.act.ActActuator
                        .Face.UP);
            }
        }
        return box;
    }

    /**
     * The headline claim.
     *
     * <p>Same world shape, same clock, same grid, same cell. The only difference is whether the
     * agent dug its own box out at the midpoint. Both verdicts have to come back, or the predicate
     * is a constant that agrees with any world it is handed.
     */
    @Test
    public void theSameWorldGivesBothVerdictsWithAndWithoutTheDig() {
        DawnChest kept = night(false);
        DawnChest dug = night(true);

        assertTrue("a chest nobody dug is standing at dawn", kept.standingAtDawn());
        assertTrue("and it stood throughout, because the cell never lacked one",
                kept.heldThroughout());
        assertEquals("and the window really was a whole night", NIGHT, kept.samples());

        assertFalse("a chest the agent dug out is not standing at dawn", dug.standingAtDawn());
        assertFalse("and it certainly did not stand throughout", dug.heldThroughout());
        assertEquals("but the night was still measured -- a dig is a fact, not a missing window",
                NIGHT, dug.samples());
        // The dig happens AFTER the midpoint tick's own sample, so that tick still saw the chest --
        // which is the honest reading of a ledger that observes the world once per tick.
        assertEquals("and every sample after the dig lacked the box", NIGHT - (NIGHT / 2 + 1),
                dug.missingSamples());
    }

    /**
     * The control pair: plant the case the predicate must reject, then remove the plant.
     *
     * <p>Two runs rather than one mutated world, because the dig is a world mutation and mutating
     * the world under a live ledger would leave the ledger reading a world that no longer exists.
     */
    @Test
    public void removingTheDigIsTheOnlyThingThatSeparatesThem() {
        assertFalse("the planted case must be rejected first, or the second half proves nothing",
                night(true).heldThroughout());
        assertTrue("and removing the plant must be accepted, or the first half proved nothing",
                night(false).heldThroughout());
    }

    /**
     * A chest built at 3am is standing at dawn and did NOT stand throughout.
     *
     * <p>This is the whole reason {@link DawnChest} reports two booleans. A predicate with one would
     * either pass this world -- and then be satisfied by a box that existed for one tick -- or fail
     * it, and then be reporting the region claim under the name of the point one without saying so.
     */
    @Test
    public void aChestBuiltAtThreeInTheMorningIsNotAChestThatStoodThroughout() {
        SimWorld w = new SimWorld().atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
            if (tick == NIGHT / 2) {
                w.put(BOX_X, BOX_Y, BOX_Z, "chest");
            }
        }

        assertTrue("the POINT reading passes: there is a chest on the last night tick",
                box.standingAtDawn());
        assertFalse("the REGION reading does not, and that difference is the entire point",
                box.heldThroughout());
        assertEquals("so the report can name the tick the box appeared at",
                NightEnclosure.duskTick() + NIGHT / 2L, box.firstNightTick() + NIGHT / 2L);
    }

    /**
     * A clock that never reaches night is NOT MEASURED, not "no box".
     *
     * <p>The symmetric error to {@code heldThroughout()}: {@code missingSamples == 0} over zero
     * samples is vacuously true, so {@link DawnChest#standingAtDawn()} requires
     * {@link DawnChest#measured()} first.
     */
    @Test
    public void aNightThatNeverHappensIsNotMeasuredRatherThanEmpty() {
        SimWorld w = new SimWorld().put(BOX_X, BOX_Y, BOX_Z, "chest").atWorldTime(NightEnclosure.duskTick() - 1L)
                .doDaylightCycle(false);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
        }

        assertFalse("no night tick was sampled, so nothing was measured", box.measured());
        assertFalse("and an unmeasured window must not report a box", box.standingAtDawn());
        assertFalse("nor the region reading", box.heldThroughout());
        assertTrue("the fact has to say NOT MEASURED rather than print a bare false",
                box.fact().contains("NOT MEASURED"));
    }

    /**
     * The chest is asked about by block type, so a trapped chest counts.
     *
     * <p>{@code Blocks.trapped_chest} is a {@code BlockChest(1)} ({@code Block.java:1408}) and
     * opens a {@code ContainerChest} exactly like a plain one
     * ({@code BlockChest.java:427-453}). A predicate that asked for {@code Blocks.chest} by identity
     * would silently drop half of every chest pair in the game.
     */
    @Test
    public void theChestIsAskedAboutByBlockTypeAndNotByName() {
        assertTrue("a plain chest is a box", DawnChest.isChest(SimWorld.block("chest")));
        assertTrue("and so is a trapped one", DawnChest.isChest(SimWorld.block("trapped_chest")));
        assertFalse("but a bench is not", DawnChest.isChest(SimWorld.block("crafting_table")));
        assertFalse("nor is air", DawnChest.isChest(null));

        // And the whole night is measured the same way for a trapped chest, which is the claim a
        // name-based predicate would have quietly failed.
        SimWorld w = new SimWorld().put(BOX_X, BOX_Y, BOX_Z, "trapped_chest")
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
        }
        assertTrue("a trapped chest stands throughout an undisturbed night", box.heldThroughout());
    }

    /**
     * The failure message names both halves and the two unmeasured claims.
     *
     * <p>A row that printed only {@code standing=true} would read as "a box survived the night" in
     * a world where nothing but the agent can destroy a chest, over contents that do not exist.
     * Both of those caveats have to be in the string a caller prints.
     */
    @Test
    public void aVerdictNamesBothHalvesRatherThanPrintingOneBoolean() {
        String fact = night(false).fact();

        assertTrue("the point reading must be in the sentence", fact.contains("at the last sampled"
                + " tick: standing"));
        assertTrue("and so must the region reading", fact.contains("throughout: standing"));
        assertTrue("and the agent-dig caveat, which is the honest scope of this row",
                fact.contains("AGENT dug out its own box"));
        assertTrue("and the contents caveat, or the row reads as a claim about storage",
                fact.contains("CONTENTS are not"));
    }

    /**
     * The window is {@link Daylight}'s, not a tick count written into this test.
     *
     * <p>Asserting the count here would pin the number rather than the rule, so a curve that moved
     * would leave this test green and the window silently stale. What is pinned instead is that the
     * ledger's own boundaries agree with the production clock's.
     */
    @Test
    public void theWindowIsTheProductionClockRatherThanACountWrittenHere() {
        DawnChest box = night(false);

        assertEquals("the first sample is the first tick Daylight calls night",
                NightEnclosure.duskTick(), box.firstNightTick());
        assertEquals("and the last is the last one", NightEnclosure.duskTick() + NIGHT - 1L,
                box.lastNightTick());
        assertEquals("so the window really is the production one", NIGHT, box.samples());
    }

    /**
     * The cell lookup is the substrate's own grid, not the controller seam.
     *
     * <p>Asserted here as well as in the cost test because this is the file that would notice if
     * {@link DawnChest#observe} were handed {@code ActActuator.blockAt} by a later caller: the
     * predicate's input is one method reference and this checks what it resolves to.
     */
    @Test
    public void thePredicateReadsTheGridTheWorldAlreadyHolds() {
        SimWorld w = new SimWorld().put(BOX_X, BOX_Y, BOX_Z, "chest");
        Block at = w.enclosureGrid().at(BOX_X, BOX_Y, BOX_Z);

        assertTrue("enclosureGrid() must resolve to the chest this file placed", DawnChest.isChest(at));
        assertEquals("and reading it must not have spent a seam read", 0, w.seamReads());
    }
}
