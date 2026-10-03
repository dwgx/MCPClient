package net.marcloud.mcp.core.rulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.core.eval.DawnChestRegion;
import net.marcloud.mcp.core.eval.NightEnclosure;

/**
 * The box ruler must find its own cells, and must be able to say no.
 *
 * <p><b>What this file is a receipt for.</b> {@link DawnChest} takes ONE cell, and the audit
 * ({@code .ai-notes/docs/audits/2026-10-03-instrument3-architecture.md}) established that nothing
 * on the north-star path supplies one: the placement path deliberately does not name the cell it
 * acted on ({@code InteractController.java:132-137}), and putting that wording back is what
 * {@code RouteExecutor.java:617-623} records as a shipped defect. So the gap was a missing CELL,
 * and it is closed here by not asking for one -- the census watches a region and judges every chest
 * it finds. {@link #aChestNobodyNamedIsStillJudged()} is the test that says the gap is closed;
 * {@link #aChestTheAgentNeverBuiltStillSatisfiesTheRegionReading()} is the honest price of that,
 * stated rather than hidden.
 *
 * <p><b>The three honest boundaries each get a test, because each one is invisible to the
 * fixture-driven eval suite and that is the whole problem with them.</b>
 *
 * <ul>
 *   <li>{@link #anUnreadableCellIsNotADemolishedChest()} -- the three-way null.</li>
 *   <li>{@link #theSentenceSaysTheThingsThatAreFalseInAFixtureAreTrueOnALiveClient()} -- mobs,
 *       creepers, explosions, fire.</li>
 *   <li>{@link #theSentenceSaysTheRegionReadingIsNotTheAgentsOwnChest()} -- the weakening.</li>
 *   <li>And the async window is named in {@code fact()} and asserted by
 *       {@link #theSentenceNamesTheServerToClientWindowThatNoFixtureHas()}.</li>
 * </ul>
 *
 * <p><b>Why these are separate files from the health ruler's.</b> Because the red sets must be
 * DISJOINT: every assertion here is about blocks and regions, every assertion in
 * {@code ANightHealthRulerReadsThreeSeriesAndCanSayNoTest} is about a health bar. A test that
 * guarded both in one class would go red for one reason and be read as evidence about the other,
 * which is the shape this project's controls exist to prevent.
 */
public final class ADawnChestRegionRulerFindsItsOwnCellsAndCanSayNoTest {

    private static final long DUSK = NightEnclosure.duskTick();
    private static final Object WORLD = new Object();

    /** A census period of 1, so a cell placed mid-night is seen on the very next tick. */
    private static DawnChestRegion everyTick() {
        return new DawnChestRegion(1, 1);
    }

    /**
     * A region holding one chest, with the clock one tick BEFORE dusk.
     *
     * <p>Combined with the {@code advance(1)}-then-{@code onTick()} order every loop in this file
     * uses, that makes the first delivered tick exactly the first tick vanilla's curve calls night
     * -- which is the convention {@code NightShelter} and {@code NightHealth} both use, and the
     * only one under which "the whole night" is a number a reader can check. Getting either half
     * wrong shifts every window by one and makes the tests look like they are pinning an artefact.
     */
    private static FakeArea regionWithChestAt(int x, int y, int z) {
        return new FakeArea(WORLD, DUSK - 1L).feet(x, y, z).put(x, y, z, "chest");
    }

    /**
     * THE GAP-CLOSING CLAIM: a chest nobody named is judged anyway.
     *
     * <p>The cell is never handed to the instrument. It is merely somewhere inside the censused
     * cube, and the census finds it. That is the whole difference from {@link DawnChest}, which
     * takes the cell as an argument and therefore needs a caller who knows it.
     */
    @Test
    public void aChestNobodyNamedIsStillJudged() {
        FakeArea area = regionWithChestAt(7, 64, -3);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 200; t++) {
            area.advance(1);
            r.onTick();
        }

        assertTrue("the census must find a chest it was never told about: " + r.fact(),
                r.chestsSeen() > 0);
        assertTrue("and it must be judged as having stood throughout: " + r.fact(),
                r.heldThroughout());
        assertTrue("including on the last sampled tick: " + r.fact(), r.standingAtDawn());
        assertEquals("with no absence and no unreadable sample anywhere in the window: "
                + r.fact(), 0, r.missingSamples());
        assertEquals(0, r.unreadableSamples());
    }

    /**
     * THE CONTROL, and the isolating half of it: put the chest OUTSIDE the censused cube and the
     * same instrument must find nothing.
     *
     * <p>Without this, {@link #aChestNobodyNamedIsStillJudged()} would be satisfied by a census
     * that reports a chest unconditionally.
     */
    @Test
    public void aChestOutsideTheCensusedCubeIsNotJudged() {
        FakeArea area = new FakeArea(WORLD, DUSK - 1L).feet(0, 64, 0)
                .put(DawnChestRegion.DEFAULT_RADIUS + 5, 64, 0, "chest");
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 200; t++) {
            area.advance(1);
            r.onTick();
        }

        assertEquals("a chest beyond the radius is outside the claim, so nothing was censused: "
                + r.fact(), 0, r.chestsSeen());
        assertFalse("and with no cells judged, the region reading must not report a box: "
                + r.fact(), r.heldThroughout());
        assertFalse("nor the point reading", r.standingAtDawn());
    }

    /**
     * THE HONEST PRICE, asserted rather than buried: a chest the agent never built satisfies this
     * instrument.
     *
     * <p>This is a limitation, not a defect, and the reason the payload's sentence names it. It is
     * also why {@code EvalSuite.T26} keeps a provenance census on the eval side: the eval can
     * demand that the AGENT built the box, and this instrument on a live client cannot.
     */
    @Test
    public void aChestTheAgentNeverBuiltStillSatisfiesTheRegionReading() {
        FakeArea area = regionWithChestAt(2, 64, 2);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 200; t++) {
            area.advance(1);
            r.onTick();
        }

        assertTrue("this instrument cannot tell whose chest it was, and says so in its sentence"
                + " rather than pretending otherwise", r.heldThroughout());
        assertTrue("the sentence must name the weakening explicitly, because a model reading"
                + " heldThroughout:true would otherwise conclude its own box stood: " + r.fact(),
                r.fact().contains("NOT that the chest YOU"));
    }

    /**
     * A chest removed mid-night must go red, and the removal must be attributed to the right
     * cause.
     *
     * <p>Replaced by a DIFFERENT block rather than removed, so this asserts the "definitely some
     * other block" branch. The null branch is the next test, because conflating them is exactly the
     * three-way-null defect.
     */
    @Test
    public void aChestReplacedByAnotherBlockIsADemolitionNotAReadFailure() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 300; t++) {
            if (t == 150) {
                area.put(0, 64, 0, "stone");
            }
            area.advance(1);
            r.onTick();
        }

        assertFalse("a chest that became a stone did not stand throughout: " + r.fact(),
                r.heldThroughout());
        assertEquals("and it was counted as a definite non-chest, not as an unreadable cell: "
                + r.fact(), 0, r.unreadableSamples());
        assertTrue("while the definite count is above zero: " + r.fact(), r.missingSamples() > 0);
        assertEquals("and the clock tick of the first one is reported: " + r.fact(),
                DUSK + 150L, r.firstAbsentTick());
    }

    /**
     * THE THREE-WAY NULL, as a test: an unreadable cell is not a demolished chest.
     *
     * <p>On a live client {@code grid.at} answers null for air, for an unloaded chunk, and for a
     * failed read -- {@code RouteExecutor.java:655-661} says so in its own words -- and the client
     * cannot tell those three apart. So the instrument has to keep them apart from each other in
     * its COUNTS even though it cannot tell them apart in the world, or a failure message cannot
     * say whether a box was demolished or merely walked out of load range. Those are opposite
     * situations: one is a loss, the other is a measurement gap.
     *
     * <p>The direction is a false negative -- something alive reported as not standing -- which is
     * the safe direction, but it leaves the criterion undecided rather than decided, and the
     * payload has to be able to say which of the two happened.
     */
    @Test
    public void anUnreadableCellIsNotADemolishedChest() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 300; t++) {
            if (t == 150) {
                // The chest is STILL THERE. The cell simply cannot be read.
                area.unreadable(0, 64, 0);
            }
            area.advance(1);
            r.onTick();
        }

        assertFalse("a cell that could not be read is not evidence the chest survived, so the"
                + " reading must not claim it did: " + r.fact(), r.heldThroughout());
        assertEquals("and it must be counted as UNREADABLE, not as a demolition: " + r.fact(),
                0, r.missingSamples());
        assertTrue("while the unreadable count is what went up: " + r.fact(),
                r.unreadableSamples() > 0);
    }

    /**
     * The unreadable cell must never be counted as a SURVIVOR either.
     *
     * <p>This is the direction that was fixed earlier this session and must not be re-imported:
     * {@code EmptyChunk} answers {@code canSeeSky} false for a chunk that does not exist, so
     * inverting that gives an unloaded column a roof. The same inversion here would report an
     * unreadable cell as a chest that is definitely still standing -- a false success built out of
     * a read that never happened. {@link #anUnreadableCellIsNotADemolishedChest()} covers the
     * honest half; this covers the dishonest half that a guard written in the other direction
     * would produce.
     */
    @Test
    public void anUnreadableCellIsNeverCountedAsASurvivor() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 300; t++) {
            if (t == 100) {
                area.unreadable(0, 64, 0);
            }
            area.advance(1);
            r.onTick();
        }

        assertFalse("an unreadable cell must not read as 'standing at dawn': " + r.fact(),
                r.standingAtDawn());
        assertFalse("nor as 'stood throughout'", r.heldThroughout());
        assertTrue("while the sentence must name the read failure as a separate cause rather than"
                + " reporting a demolition that did not happen: " + r.fact(),
                r.fact().contains("unreadable samples"));
    }

    /**
     * A world with no chest at all is NOT MEASURED, not a demolished box.
     *
     * <p>The empty-region case, and it is a different answer from the failure case above. With zero
     * cells censused there is nothing to have survived, so reporting "the box did not stand" would
     * be inventing a box in order to report on it.
     */
    @Test
    public void aNightWithNoChestAnywhereIsNotMeasuredRatherThanDemolished() {
        FakeArea area = new FakeArea(WORLD, DUSK - 1L).feet(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 200; t++) {
            area.advance(1);
            r.onTick();
        }

        assertTrue("the window really was sampled, so this is not an unmeasured night",
                r.measured());
        assertEquals("but no chest was ever censused", 0, r.chestsSeen());
        assertFalse("and with no cells, neither reading may report a box", r.heldThroughout());
        assertFalse(r.standingAtDawn());
        assertTrue("so the sentence must say NOT MEASURED and name the cause -- a box that was"
                + " never seen is a different row from a box that did not survive: " + r.fact(),
                r.fact().contains("NOT MEASURED"));
        assertTrue("and it must say so in its own words, not leave it to a zero: " + r.fact(),
                r.fact().contains("never seen"));
    }

    /**
     * A chest censused at 3am has only been judged since 3am, and the payload says which tick.
     *
     * <p>The region口径's cost, made machine-readable. Without {@link
     * DawnChestRegion#latestFirstSeenTick()} a reader would take "a chest stood through the night"
     * to mean from dusk, and a box placed at three in the morning would be quoted as evidence
     * about a night it spent two thirds of outside the ledger.
     */
    @Test
    public void aChestCensusedAtThreeInTheMorningIsOnlyJudgedFromThreeInTheMorning() {
        int night = NightEnclosure.nightTicks();
        FakeArea area = new FakeArea(WORLD, DUSK - 1L).feet(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        int placedAt = night / 2;
        for (int t = 0; t <= night; t++) {
            area.advance(1);
            if (t == placedAt) {
                area.put(0, 64, 0, "chest");
            }
            r.onTick();
        }

        assertTrue("from 3am onwards it did stand throughout the rest of the window, and that is"
                + " true", r.heldThroughout());
        assertEquals("the window really starts at dusk, the first night tick: " + r.fact(),
                DUSK, r.firstNightTick());
        // The ledger's clock for the cell is the tick the CENSUS saw it, and the census runs on
        // the tick AFTER the placement in this loop -- so the reported tick is one past the tick
        // the block was put on. That one-tick lag is real and is exactly why
        // latestFirstSeenTick is published at all: it is the honest upper bound on how stale the
        // ledger can be, and a reader who wants to know how much of the night went unjudged has to
        // subtract it from firstNightTick themselves.
        assertEquals("but the only chest in the ledger was censused one tick after it was placed,"
                + " and that is the clock the payload reports: " + r.fact(),
                DUSK + placedAt + 1L, r.latestFirstSeenTick());
        assertTrue("so a reader can compute how much of the night was never judged at all --"
                + " here " + placedAt + " of " + night + " ticks: " + r.fact(),
                r.latestFirstSeenTick() > r.firstNightTick());
    }

    /**
     * The sentence must not claim a fixture world.
     *
     * <p>Three of the sentences {@link DawnChest#fact()} used to print -- "no fire", "no creeper",
     * "no explosion" -- are true of an eval fixture and FALSE of a running client, which has
     * hostile mobs, creepers, TNT and lava. A model reading "no creeper, no explosion" in this
     * world is reading a false sentence about the world it is standing in, and that is the exact
     * shape this session has already hit three times.
     */
    @Test
    public void theSentenceSaysTheThingsThatAreFalseInAFixtureAreTrueOnALiveClient() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 100; t++) {
            area.advance(1);
            r.onTick();
        }
        String fact = r.fact();

        assertFalse("the fixture-era disclaimer must be gone: " + fact,
                fact.contains("no creeper"));
        assertFalse("and its fire variant too", fact.contains("no fire"));
        assertFalse("and its explosion variant too", fact.contains("no explosion"));
        assertTrue("instead the sentence must say these ARE present on a live client: " + fact,
                fact.contains("mobs, creepers, explosions and lava ARE present"));
        assertTrue("and that nothing in the row rules them out: " + fact,
                fact.contains("nothing here rules any of them out"));
    }

    /**
     * The sentence must name the server-to-client window, because no fixture has one.
     *
     * <p>On a live client the server changes a block and the change arrives a few ticks later, so
     * "held throughout" strictly includes "has just now gone". The direction is a false positive --
     * a chest destroyed moments before dawn can still read as standing -- and an eval fixture can
     * never produce it, which is precisely why it needs to be written down rather than tested.
     */
    @Test
    public void theSentenceNamesTheServerToClientWindowThatNoFixtureHas() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 100; t++) {
            area.advance(1);
            r.onTick();
        }

        assertTrue("the window must be named in the sentence a model reads: " + r.fact(),
                r.fact().contains("few ticks late"));
        assertTrue("including what that does to the claim's direction: " + r.fact(),
                r.fact().contains("has just now gone"));
    }

    /**
     * The sentence must name the load-range ambiguity, which is the third null path.
     *
     * <p>"Moved out of load range" and "demolished" are indistinguishable on a live client, and a
     * row that cannot say which one it saw is a row nobody can act on.
     */
    @Test
    public void theSentenceNamesTheLoadRangeAmbiguity() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);
        for (int t = 0; t <= 100; t++) {
            area.advance(1);
            r.onTick();
        }

        assertTrue(r.fact().contains("left load range"));
        assertTrue("and must say it is indistinguishable from a demolition rather than leaving"
                + " the reader to assume the benign case: " + r.fact(),
                r.fact().contains("indistinguishable from one that was demolished"));
    }

    /**
     * The empty payload must not read as a box that stood.
     *
     * <p>The same three states {@code NightShelter} had to separate: never armed, ticking with no
     * world, and armed in daylight. The first two must not share a sentence, and neither may report
     * a verdict.
     */
    @Test
    public void anEmptyPayloadIsNotABoxThatStood() {
        DawnChestRegion neverTicked = new DawnChestRegion();
        assertFalse(neverTicked.measuredOnce());
        assertFalse(neverTicked.heldThroughout());
        assertFalse(nearTicked_standing());
        assertTrue("and the sentence must blame the SEAM, which is the honest cause: "
                + neverTicked.fact(), neverTicked.fact().contains("tick seam has not delivered"));

        DawnChestRegion titleScreen = everyTick()
                .reading(() -> new FakeArea(null, DUSK));
        titleScreen.onTick();
        titleScreen.onTick();

        assertFalse("ticking with no world has still measured nothing", titleScreen.measured());
        assertFalse("and reports no box", titleScreen.heldThroughout());
        assertTrue("while the sentence blames the missing WORLD instead: " + titleScreen.fact(),
                titleScreen.fact().contains("ticks ARE arriving"));
        assertNotEquals("two causes, two sentences, or a reader cannot act on either",
                neverTicked.fact(), titleScreen.fact());
    }

    private static boolean nearTicked_standing() {
        return new DawnChestRegion().standingAtDawn();
    }

    /**
     * The window is ONE night, and a new night starts with an EMPTY cell set.
     *
     * <p>The cell set is the part a reader would not predict: last night's chest cells are not
     * evidence about this night, and carrying them over would make tonight's reading depend on
     * what was standing last night.
     */
    @Test
    public void theWindowIsOneNightAndTheCellSetIsRebuiltEachNight() {
        int night = NightEnclosure.nightTicks();
        FakeArea area = regionWithChestAt(0, 64, 0);
        DawnChestRegion r = everyTick().reading(() -> area);

        for (int t = 0; t <= night; t++) {
            area.advance(1);
            r.onTick();
        }
        assertEquals("night one sampled one whole night: " + r.fact(), night, r.samples());
        assertEquals("with the window running from dusk to the last night tick and no further",
                DUSK + night - 1L, r.lastNightTick());
        assertTrue("and it found its chest", r.chestsSeen() > 0);

        // Night two: the chest is gone from the world entirely.
        area.at(DUSK + 24000L - 1L).remove(0, 64, 0);
        for (int t = 0; t <= night; t++) {
            area.advance(1);
            r.onTick();
        }

        assertEquals("the window restarted rather than accumulating: " + r.fact(),
                night, r.samples());
        assertEquals("and the cell set was rebuilt, so last night's chest is not evidence here: "
                + r.fact(), 0, r.chestsSeen());
        assertFalse("which means the region reading reports nothing rather than reporting on a"
                + " chest that is no longer in the world", r.heldThroughout());
    }

    /**
     * The census cost is a number, and the per-tick half is the cheap half.
     *
     * <p>A census is a region scan and a scan is expensive; re-reading the cells already in the
     * ledger is cheap and is what has to happen every tick. This asserts the split by counting
     * reads rather than by describing it, because a cost only described is a cost nobody can decide
     * about -- and the census period is the dial that trades latency for cost.
     */
    @Test
    public void theCensusIsPeriodicAndTheReVerificationIsNot() {
        FakeArea area = regionWithChestAt(0, 64, 0);
        // A census every 200 ticks: one scan, then 200 cheap re-verifications.
        DawnChestRegion r = new DawnChestRegion(1, 200).reading(() -> area);
        // The clock moves BEFORE the tick is delivered, which is the order a real client has: the
        // game advances its clock and THEN the tick subscriber runs. Getting this backwards makes
        // every tick read the same worldTime, which trips the instrument's own rewind guard and
        // restarts the window on every tick -- an artefact of the harness, not a cost.
        area.advance(1);
        int first = area.reads();
        r.onTick();
        int afterFirst = area.reads() - first;
        assertEquals("the first tick must census -- one scan of (2*8+1)^3 = 4913 cells plus one"
                + " re-verification -- or the ledger could never start: " + r.fact(),
                4913 + 1, afterFirst);

        int afterCensus = area.reads();
        for (int t = 0; t < 199; t++) {
            area.advance(1);
            r.onTick();
        }
        int cheapTicks = area.reads() - afterCensus;

        // 199 further ticks, each re-reading the ONE cell already in the ledger. The 200th would
        // census again and is deliberately not in this loop -- which is the split being asserted.
        assertEquals("exactly one cell is in the ledger, so each of those ticks costs one read: "
                + r.fact(), 199L, (long) cheapTicks);
        assertTrue("while one census walked the whole cube, which is far more than the whole rest"
                + " of the window cost: census=" + afterFirst + " re-verify=" + cheapTicks,
                afterFirst > cheapTicks);
        assertEquals("and the census period is the dial that says so", 200, r.censusPeriodTicks());
    }

    /** A period below one tick is refused rather than silently rounded into something else. */
    @Test
    public void aPeriodOfZeroTicksIsRefusedRatherThanRounded() {
        try {
            new DawnChestRegion(0, 1);
            org.junit.Assert.fail("a zero period must be refused: an instrument that samples"
                    + " every tick while claiming to sample every zero ticks reports a resolution"
                    + " it does not have");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("at least one tick"));
        }
        try {
            new DawnChestRegion(1, 0);
            org.junit.Assert.fail("a zero census period must be refused for the same reason");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("census period"));
        }
    }
}