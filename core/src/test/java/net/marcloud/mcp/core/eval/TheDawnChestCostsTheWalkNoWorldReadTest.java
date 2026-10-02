package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The fourth criterion is sampled on EVERY tick of the night, and it costs the walk nothing.
 *
 * <p><b>The question is a real one.</b> The obvious way to ask "is there a chest there" from inside
 * the eval is {@code ActActuator.blockAt} -- the one seam a controller has, and the one
 * {@code TheBeliefLayerCostsNoWorldReadTest} and {@code TheClockCostsTheWalkNoWorldReadTest} pin at
 * exactly 25 reads for a walk. A predicate that went through that seam would be read once per tick
 * for 8,386 ticks of every night: it would push the exact number those two tests guard by thirty
 * thousand, and it would look entirely reasonable in a diff.
 *
 * <p><b>So the answer is: yes, every tick, and here is what that costs.</b> {@link DawnChest}
 * takes an {@link Enclosure.CellGrid}, which is a method reference onto {@code SimWorld}'s own grid
 * rather than onto the seam, so the read is a hash lookup on data the world already holds. The
 * comparison below is an A/B between two runs that differ in exactly one thing -- whether the box
 * is observed on each tick -- over the same world and the same clock, so both halves do identical
 * controller work.
 *
 * <p><b>An equality alone would be satisfied by blindness</b> -- a predicate that reads nothing and
 * measures nothing also costs zero -- so the last two tests assert that the sampler actually
 * sampled and that the predicate actually moved when the world did.
 */
public final class TheDawnChestCostsTheWalkNoWorldReadTest {

    private static final int BOX_X = 0;
    private static final int BOX_Y = 64;
    private static final int BOX_Z = 0;

    /** One whole night, so the expensive half of the A/B is the one actually measured. */
    private static final int NIGHT = NightEnclosure.nightTicks();

    /** What one A/B run produced: the seam's bill and the box's ledger. */
    private record Run(int seamReads, DawnChest chest) {
    }

    /**
     * One night of ticks with a chest in the world, optionally folding the box in on each of them.
     *
     * @param observeChest the only variable between the two halves of the A/B
     */
    private static Run night(boolean observeChest) {
        SimWorld w = new SimWorld().put(BOX_X, BOX_Y, BOX_Z, "chest")
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            if (observeChest) {
                box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
            }
        }
        return new Run(w.seamReads(), box);
    }

    /**
     * The A/B: 8,387 ticks of a night, one of them reading the box on every night tick and one not,
     * and the seam read counts come out the same.
     */
    @Test
    public void samplingTheBoxOnEveryTickOfANightCostsTheSeamNothing() {
        Run without = night(false);
        Run with = night(true);

        assertEquals("the two runs must have done identical controller work, or the A/B is not an"
                        + " A/B", without.seamReads(), with.seamReads());
        assertEquals("reading the box on every night tick must cost the walk NOTHING. The property"
                + " is zero and a bound would let a regression of ten reads pass",
                0, with.seamReads() - without.seamReads());
        assertEquals("and the controller's own budget must be untouched by the presence of the"
                + " instrument", 0, with.seamReads());
    }

    /**
     * The equality above is only meaningful if the sampler really ran on every tick.
     *
     * <p>A ledger that skipped its own sampling would also cost zero, so the count is asserted
     * against the number the clock says the night is rather than against a number written here.
     */
    @Test
    public void theSamplerRanOnEveryNightTickRatherThanSkippingItsOwnWork() {
        DawnChest box = night(true).chest();

        assertEquals("every tick vanilla's curve calls night must be a sample", NIGHT, box.samples());
        assertEquals("at the default period of one", 1, box.periodTicks());
        assertEquals("and a chest nobody dug is on all of them", NIGHT, box.presentSamples());
        assertTrue("which is why the ledger can come back with a verdict at all",
                box.standingAtDawn());
    }

    /**
     * A coarser period must be reported in TICKS, not samples, or the undercount is invisible.
     *
     * <p>This is the argument {@code NightEnclosure.longestUnreachableTicks()} makes, and it is
     * worth a number here: sampling every fifth tick of a night and reporting "longest absence 1"
     * would read as one tick when it could have been five.
     */
    @Test
    public void aCoarserPeriodIsReportedInTicksRatherThanInSamples() {
        SimWorld w = new SimWorld().put(BOX_X, BOX_Y, BOX_Z, "chest")
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest(5);
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
            if (tick == NIGHT / 2) {
                w.remove(BOX_X, BOX_Y, BOX_Z);
            }
        }

        int missingSamples = box.missingSamples();
        assertTrue("a chest removed halfway must leave samples without it", missingSamples > 0);
        assertEquals("the longest absence must never be reported as fewer ticks than were"
                        + " actually sampled without the box",
                missingSamples, Math.min(missingSamples, (int) box.longestAbsentTicks()));
        assertTrue("the tick figure must exceed the sample figure whenever the period is above one",
                box.longestAbsentTicks() > missingSamples);
    }
}
