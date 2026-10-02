package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * The enclosure is measured on EVERY tick of the night, and it costs the walk nothing.
 *
 * <p><b>This file exists because the question is a real one.</b> The obvious way to answer "is the
 * player sheltered" is to ask the world, and the obvious way to ask the world from inside the eval
 * is {@code ActActuator.blockAt} -- the one seam a controller has, and the one
 * {@code TheBeliefLayerCostsNoWorldReadTest} and {@code TheClockCostsTheWalkNoWorldReadTest} pin at
 * exactly 25 reads for a walk. An enclosure predicate that went through that seam would be read
 * once per tick for 8,386 ticks of every night: it would push the exact number those two tests
 * guard by thirty thousand, and it would look entirely reasonable in a diff.
 *
 * <p><b>So the answer is: yes, every tick, and here is what that costs.</b>
 * {@link Enclosure.CellGrid} is a method reference onto {@code SimWorld}'s own grid rather than
 * onto the seam, so the scan is a hash lookup on data the world already holds. The comparison
 * below is an A/B between two runs that differ in exactly one thing -- whether the enclosure is
 * observed on each tick -- over the same walk and the same clock, so both halves do identical
 * controller work. The claim is that the difference is zero, written as an equality rather than a
 * bound for the reason {@code TheBeliefLayerCostsNoWorldReadTest} gives: the property is ZERO, and
 * a bound would let a regression of ten reads pass.
 *
 * <p><b>An equality alone would be satisfied by blindness</b> -- a predicate that reads nothing
 * and measures nothing also costs zero -- so the last two tests assert that the seam was used and
 * that the sampler was not skipping.
 */
public final class TheEnclosureCostsTheWalkNoWorldReadTest {

    private static final int X = EvalSuite.T25ShelterThroughTheNight.SHELTER_X;
    private static final int Z = EvalSuite.T25ShelterThroughTheNight.SHELTER_Z;
    private static final int FEET_Y = EvalSuite.T25ShelterThroughTheNight.FEET_Y;
    private static final int OPEN_X = EvalSuite.T25ShelterThroughTheNight.OPEN_X;
    private static final int OPEN_Z = EvalSuite.T25ShelterThroughTheNight.OPEN_Z;

    /** One whole night, so the expensive half of the A/B is the one actually measured. */
    private static final int NIGHT = NightEnclosure.nightTicks();

    /** What one A/B run produced: the seam's bill and the enclosure's ledger. */
    private record Run(int seamReads, NightEnclosure enclosure) {
    }

    /**
     * One night of ticks with the body standing on the open plain, optionally folding the
     * enclosure in on each of them.
     *
     * @param observeEnclosure the only variable between the two halves of the A/B
     */
    private static Run night(boolean observeEnclosure) {
        SimWorld w = EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .standOn(OPEN_X, FEET_Y, OPEN_Z)
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        NightEnclosure enclosure = new NightEnclosure();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            if (observeEnclosure) {
                Stance feet = w.stance();
                enclosure.observe(w.enclosureGrid(), feet.x(), feet.y(), feet.z(), w.worldTime());
            }
        }
        return new Run(w.seamReads(), enclosure);
    }

    /**
     * The A/B: 8,387 ticks of a night, one of them measuring the enclosure on every night tick
     * and one not, and the seam read counts come out the same.
     */
    @Test
    public void samplingTheEnclosureOnEveryTickOfANightCostsTheSeamNothing() {
        Run without = night(false);
        Run with = night(true);

        assertEquals("the two runs are the same night: the run that did not measure took no"
                + " samples at all", 0, without.enclosure().samples());
        assertEquals("and the one that did took one on every one of the " + NIGHT + " night ticks",
                NIGHT, with.enclosure().samples());
        assertEquals("THE CLAIM. " + with.enclosure().samples() + " enclosure measurements cost"
                + " the controller seam the same number of reads as none, because the scan reads"
                + " SimWorld's grid directly and never the ActActuator.blockAt seam. Without the"
                + " sampler the run made " + without.seamReads() + " reads; with it, "
                + with.seamReads(), without.seamReads(), with.seamReads());
    }

    /**
     * The mirror: a run that read nothing would also pass a cost assertion, so the seam is shown
     * to be in use. A walk with a nav intent still plans and still probes, so the count is not
     * zero.
     */
    @Test
    public void theWalkStillReadsTheWorldSoTheZeroAboveIsNotBlindness() {
        SimWorld w = EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        h.runtime().submitNav(new NavIntent(OPEN_X - 0.5D, FEET_Y, OPEN_Z, 600));
        h.runMove(60);
        assertTrue("the walk made " + w.seamReads() + " seam reads, so the zeros above are a"
                + " statement about the sampler's cost and not about a harness that never reads"
                + " anything", w.seamReads() > 0);
    }

    /**
     * And the mirror of the enclosure half: the sampler was not skipping its work.
     *
     * <p>An observer that read the clock, decided it was day, and returned without ever asking the
     * grid would also cost zero. So the ledger has to show that the grid was asked: every sample
     * on the open plain came back unsheltered, and that is only knowable by scanning the column and
     * the eight neighbours of each one.
     */
    @Test
    public void theEnclosureWasActuallyMeasuredOnEveryTick() {
        Run with = night(true);
        assertEquals(NIGHT, with.enclosure().samples());
        assertEquals("every sample on the open plain came back unsheltered, which is only knowable"
                + " by scanning the column above the head and the eight horizontal neighbours of"
                + " the two cells the body occupies", NIGHT, with.enclosure().openSamples());
        assertTrue("so the run reports NOT sheltered rather than nothing at all: "
                + with.enclosure(), with.enclosure().measured() && !with.enclosure().sheltered());
    }

    /**
     * An enclosed body is cheaper to scan than an exposed one, and neither spends a seam read.
     *
     * <p>{@link Enclosure#skyClosed} stops at the first light-blocking cell above the head, so a
     * body under a roof resolves in one or two cells, while a body on an open plain scans all 191
     * cells between its head and y=255. Neither is a seam read -- both are grid lookups -- but the
     * ratio is the honest cost of the default sampling period of one, and it is why the default is
     * a period rather than a scan of everything every tick forever.
     */
    @Test
    public void anEnclosedBodyIsCheaperToScanThanAnExposedOneAndNeitherIsASeamRead() {
        SimWorld exposed = EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .standOn(OPEN_X, FEET_Y, OPEN_Z);
        NightEnclosure openLedger = new NightEnclosure();
        openLedger.observe(exposed.enclosureGrid(), OPEN_X, FEET_Y, OPEN_Z,
                NightEnclosure.duskTick());

        SimWorld boxed = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        NightEnclosure boxedLedger = new NightEnclosure();
        boxedLedger.observe(boxed.enclosureGrid(), X, FEET_Y, Z, NightEnclosure.duskTick());

        assertTrue("the two cells really do disagree, or the comparison below means nothing: open"
                + " is " + openLedger + " and boxed is " + boxedLedger,
                openLedger.openSamples() == 1 && boxedLedger.openSamples() == 0);
        assertEquals("and neither of them spent a read through the seam while doing it",
                0, exposed.seamReads());
        assertEquals(0, boxed.seamReads());
    }
}