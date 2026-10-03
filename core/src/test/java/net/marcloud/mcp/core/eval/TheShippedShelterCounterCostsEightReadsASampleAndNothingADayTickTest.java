package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.block.Block;
import net.marcloud.mcp.core.drivers.world.Daylight;

import org.junit.Test;

/**
 * What one night of the shipped shelter counter ACTUALLY costs, measured rather than argued.
 *
 * <p><b>Why this file exists at all.</b> Every number the wave-22 report carries was arithmetic on
 * paper, and the report says so itself in its own §7: "All derived on paper from files cited by
 * line. None measured by running anything." A cost that is only described is a cost nobody can
 * decide about, so this file replaces the description with a count. If the derivation was wrong
 * then these numbers and that report's numbers disagree, and the disagreement is the finding —
 * not something to paper over by loosening an assertion until it agrees.
 *
 * <p><b>What is counted, and what "a read" means here.</b> {@link Enclosure.CellGrid#at} is the
 * one method on the seam that touches a block, so the counter is on {@code at} and nothing else.
 * That is the same unit {@code SimWorld.seamReads()} uses and the same unit the
 * {@code TheEnclosureCostsTheWalkNoWorldReadTest} family pins at 25 for a walk, so these numbers
 * are comparable with the rest of the tree rather than being a private currency.
 *
 * <p><b>The live-shaped grid is the load-bearing part.</b> {@link WorldBlockView} overrides
 * {@link Enclosure.CellGrid#skyClosedAt} to ask {@code Chunk.canSeeSky} — one height-map
 * comparison — instead of walking the column. {@link LiveShapedGrid} reproduces exactly that
 * override. A grid that did not would be measuring the SUBSTRATE's column walk while claiming
 * to report the CLIENT's cost, and the number would be wrong by an order of magnitude.
 * {@link #theDefaultColumnWalkIsWhatTheLiveOverrideAvoids()} pins the size of that gap rather
 * than leaving it an adjective, because the override is the reason the figure is small at all,
 * and an override nobody has measured is an assumption.
 *
 * <p><b>Where the "8" comes from, so a reader can check the arithmetic rather than trust it.</b>
 * {@code Enclosure.openSides} asks {@code solidAt} for {@code SIDES.length * BODY_CELLS} cells =
 * 4 x 2 = 8, and the default {@code solidAt} is exactly one {@code at} call. The sky half adds
 * zero {@code at} calls under the override. So one sample costs 8 — and that is a coincidence
 * worth pinning, because both halves of the number can move independently.
 */
public final class TheShippedShelterCounterCostsEightReadsASampleAndNothingADayTickTest {

    /** A feet cell on the plain, matching the one {@code SimWorld} uses for its own shelters. */
    private static final int X = 0;
    private static final int Z = 0;
    private static final int FEET_Y = 64;

    /**
     * A {@link Enclosure.CellGrid} that counts {@code at} calls and behaves like the live view.
     *
     * <p>{@link #skyClosedAt} is overridden to a constant on purpose. That is what
     * {@code WorldBlockView} does — the sky question is asked of the chunk height map vanilla
     * already maintains — and a counter that let the default column walk run would be counting
     * the SUBSTRATE's cost while claiming to report the CLIENT's.
     */
    private static final class LiveShapedGrid implements Enclosure.CellGrid {

        private int atCalls;
        private int skyCalls;

        @Override
        public Block at(int bx, int by, int bz) {
            atCalls++;
            return null;
        }

        /** Zero {@code at} calls: the height-map answer, exactly as {@code WorldBlockView} gives. */
        @Override
        public boolean skyClosedAt(int bx, int headY, int bz) {
            skyCalls++;
            return true;
        }

        int atCalls() {
            return atCalls;
        }
    }

    /**
     * A grid that does NOT override the sky seam, i.e. {@code SimWorld}'s situation: no chunk,
     * therefore no height map, therefore the column walk. {@code skyClosedAt} is deliberately
     * absent so the interface default runs, which is the whole point of this fixture.
     */
    private static final class ColumnWalkingGrid implements Enclosure.CellGrid {

        private int atCalls;

        @Override
        public Block at(int bx, int by, int bz) {
            atCalls++;
            return null;
        }

        int atCalls() {
            return atCalls;
        }
    }

    /** One sample's {@code at} calls, through the shipped {@link NightEnclosure} and {@link Enclosure}. */
    private static int readsForOneSample(Enclosure.CellGrid grid, long worldTime) {
        int before = grid instanceof LiveShapedGrid
                ? ((LiveShapedGrid) grid).atCalls()
                : ((ColumnWalkingGrid) grid).atCalls();
        assertTrue("the sample must actually have been taken, or '0 reads' would be vacuous",
                new NightEnclosure().observe(grid, X, FEET_Y, Z, worldTime));
        int after = grid instanceof LiveShapedGrid
                ? ((LiveShapedGrid) grid).atCalls()
                : ((ColumnWalkingGrid) grid).atCalls();
        return after - before;
    }

    /**
     * THE HEADLINE NUMBER: one night sample costs exactly 8 block reads.
     *
     * <p>Not 8 "approximately", and not 8 for a grid that happens to be friendly: the counter
     * sits on the one method that touches a block, and 8 is what {@code SIDES.length(4) x
     * BODY_CELLS(2)} side probes costs when each is one {@code at} call. The mutation this has to
     * survive is {@code Enclosure.openSides} probing a fifth side or a third body cell — both
     * plausible "improvements" to the definition of enclosure, and both would quietly change a
     * cost nobody had measured.
     */
    @Test
    public void oneNightSampleCostsEightBlockReadsAndNoMore() {
        LiveShapedGrid grid = new LiveShapedGrid();
        long dusk = NightEnclosure.duskTick();
        assertEquals("the dusk tick the production clock actually reports on this tree, so the"
                        + " samples below are genuinely inside the night rather than assumed to be",
                13807L, dusk);

        assertEquals("a night sample must cost exactly 8 block reads: four horizontal sides at the"
                        + " feet cell and the same four at the head cell, one at() each, and zero"
                        + " for the sky half because WorldBlockView asks the chunk height map"
                        + " instead of walking the column",
                8, readsForOneSample(grid, dusk));
        assertEquals("and it is the same cost on the next night tick — no cache amortises a"
                        + " sample, because the eight probes are eight different cells",
                16, atCallsOf(grid) + readsForOneSample(grid, dusk + 1L));
    }

    /**
     * A daylight tick costs ZERO block reads.
     *
     * <p>The gate is {@code NightEnclosure.observe}'s {@code Daylight.isNight(worldTime)} test,
     * which runs before the grid is touched at all. This is the number that decides whether the
     * counter can ship: 15,614 day ticks a day is either free or the reason this feature cannot
     * exist, and only a measurement says which.
     *
     * <p>The mutation this has to survive is the gate being inverted or dropped — the classic
     * "just measure it every tick so the first sample is never missed", which would cost
     * 8 x 15,614 extra reads a day to buy a number the night already supplies.
     */
    @Test
    public void aDaylightTickCostsNoBlockReadsAtAll() {
        for (long t : new long[] {0L, 1000L, 6000L, 12000L}) {
            assertFalse("t=" + t + " must not be night, or this assertion means nothing",
                    Daylight.isNight(t));
            LiveShapedGrid grid = new LiveShapedGrid();
            assertEquals("a daylight tick must cost ZERO block reads — the clock is asked before"
                    + " the grid is, so 15,614 day ticks a day cost nothing. t=" + t,
                    0, readsForOneSampleIfTaken(grid, t));
        }
    }

    /** A daylight tick takes no sample at all, so the read count must be untouched. */
    private static int readsForOneSampleIfTaken(LiveShapedGrid grid, long worldTime) {
        int before = grid.atCalls();
        boolean sampled = new NightEnclosure().observe(grid, X, FEET_Y, Z, worldTime);
        assertFalse("a daylight tick must report that it did NOT sample — that is what makes the"
                + " zero on the line above a measurement rather than a tautology", sampled);
        return grid.atCalls() - before;
    }

    /**
     * A whole day, counted tick by tick: {@code 8 x nightTicks()} on the night half and zero on
     * the day half.
     *
     * <p>Driven through the real {@link NightEnclosure} over the real {@link Daylight} curve, one
     * call per tick across a full 24,000, so the total is a measurement of the shipped path and
     * not 8 multiplied by a remembered constant. The expected side is written as the product so
     * the two numbers can only disagree if one of them is wrong — which is the property that
     * makes this a test rather than a restatement.
     */
    @Test
    public void aWholeDayCostsEightTimesTheNightAndNothingElse() {
        LiveShapedGrid grid = new LiveShapedGrid();
        NightEnclosure ledger = new NightEnclosure();
        int nightTicks = NightEnclosure.nightTicks();

        for (long t = 0L; t < 24000L; t++) {
            ledger.observe(grid, X, FEET_Y, Z, t);
        }

        assertEquals("the window this tree actually has: the night's tick count is derived from"
                + " Daylight and never written down, and the paper figure was 8,386",
                8386, nightTicks);
        assertEquals("every night tick of a full day must be sampled, or the ledger is reporting"
                + " a shorter window than the clock says happened",
                nightTicks, ledger.samples());
        assertEquals("and the day's whole block-read bill must be 8 per NIGHT tick and 0 per day"
                + " tick — this is the assertion that would go red if the daylight gate were"
                + " dropped. Measured: " + grid.atCalls(),
                8L * nightTicks, grid.atCalls());
        assertEquals("which is the figure the paper arithmetic produced (8 x 8,386), so the"
                + " derivation and the measurement agree rather than merely coexisting",
                67088L, grid.atCalls());
    }

    /**
     * The clock-derived denominator agrees with the ledger it is subtracted from.
     *
     * <p>{@code NightEnclosure.ticksIntoNight} is what lets a live reader subtract the night
     * ticks nobody looked at from the ones that were counted. If the two disagreed, a reader
     * would compute a negative gap on a perfectly healthy run — so this asserts that the two
     * INDEPENDENT derivations (the ledger counting samples, the clock counting elapsed night
     * ticks) describe the same window, tick by tick across all 24,000 of them.
     */
    @Test
    public void theClockDerivedDenominatorAgreesWithTheLedgerItIsSubtractedFrom() {
        LiveShapedGrid grid = new LiveShapedGrid();
        NightEnclosure ledger = new NightEnclosure();
        long dusk = NightEnclosure.duskTick();
        int nightTicks = NightEnclosure.nightTicks();

        for (long t = 0L; t < 24000L; t++) {
            ledger.observe(grid, X, FEET_Y, Z, t);
            int into = NightEnclosure.ticksIntoNight(t);
            if (into > 0) {
                assertEquals("midnight-to-dusk the clock and the ledger must count the same night"
                        + " ticks, or a reader cannot subtract the unobserved ones. t=" + t,
                        into, ledger.samples());
            }
        }
        assertEquals("in daylight ticksIntoNight is 0, so a reader in the late afternoon cannot"
                + " mistake 'late in the afternoon' for 'deep into the night'",
                0, NightEnclosure.ticksIntoNight(0L));
        assertEquals("and at the last night tick it reads the whole window",
                nightTicks, NightEnclosure.ticksIntoNight(dusk + nightTicks - 1L));
        assertTrue("and the two must not merely be equal at the end — the difference is the"
                        + " number of night ticks nobody looked at, so it has to be real arithmetic:"
                        + " ledger=" + ledger.samples() + " intoNight=" + nightTicks,
                ledger.samples() == nightTicks);
    }

    /**
     * The override is what makes the number small, and the size of what it avoids is measured
     * here rather than described.
     *
     * <p>On an open plain at y=64 the default column walk asks {@code at} about every cell from
     * the head up to the top of the world, so the comparison is against the worst case: nothing
     * in the column closes it. The paper put that at 191 cells for the walk alone; it is
     * reproduced here from {@link Enclosure#SKY_TOP} rather than trusted.
     *
     * <p>This is the assertion that stops {@code WorldBlockView}'s override being deleted as
     * "redundant with the default". The default is not a slower version of the same cost, it is
     * another order of magnitude, and the only way to know that is to have measured both.
     */
    @Test
    public void theDefaultColumnWalkIsWhatTheLiveOverrideAvoids() {
        long dusk = NightEnclosure.duskTick();

        LiveShapedGrid live = new LiveShapedGrid();
        assertEquals("the live path, as above", 8, readsForOneSample(live, dusk));

        ColumnWalkingGrid substrate = new ColumnWalkingGrid();
        int walked = readsForOneSample(substrate, dusk);
        int headY = FEET_Y + Enclosure.BODY_CELLS - 1;
        int columnCells = Enclosure.SKY_TOP - headY + 1;
        assertEquals("the substrate's walk is the 8 side probes plus one column walk from the head"
                + " cell to the top of the world, on an open plain where nothing closes it: "
                + walked,
                8 + columnCells, walked);
        assertEquals("which is the 191 the paper put on the walk alone (255 - 65 + 1), computed"
                + " from the world's own top rather than remembered",
                191, columnCells);
        assertEquals("and 199 with the eight side probes the paper did not fold into that figure",
                199, walked);
        assertTrue("so the override is a factor of " + (walked / 8) + ", not a micro-optimisation."
                + " Deleting it as redundant with the default would turn a cheap instrument into"
                + " an expensive one on every night tick — which is why it is pinned here and not"
                + " left as a comment.",
                walked > 20 * 8);
    }

    private static int atCallsOf(Enclosure.CellGrid grid) {
        return grid instanceof LiveShapedGrid
                ? ((LiveShapedGrid) grid).atCalls()
                : ((ColumnWalkingGrid) grid).atCalls();
    }
}
