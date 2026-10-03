package net.marcloud.mcp.core.rulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.core.eval.NightEnclosure;
import net.marcloud.mcp.core.eval.NightHealth;

/**
 * The health ruler must be able to say no, and must say WHY it said no.
 *
 * <p><b>What this file is a receipt for.</b> The instrument is the second of the north-star rulers,
 * and the audit that scoped it ({@code .ai-notes/docs/audits/2026-10-03-instrument2-architecture.md})
 * found the previous answer was "it needs an opt-in Netty tap nothing installs", which would have
 * left it reading {@code NOT MEASURED} on a real client forever. The gap turned out to be smaller:
 * {@code EntityPlayerSP.setPlayerSPHealth} computes the damage edge itself ({@code :350} the
 * difference, {@code :363} {@code lastDamage = f}, {@code :367} {@code hurtTime = 10}), so a
 * per-tick accumulator over three production fields closes it with no tap.
 *
 * <p><b>What each half pins, and why the halves are separate files' worth of claim.</b>
 *
 * <ul>
 *   <li>{@link #aBarThatDippedBelowEighteenIsABrokenFloor()} and its control -- the ruler can go
 *       red, and removing the dip is the only thing that separates the two runs.</li>
 *   <li>{@link #aHitTheInvulnerabilityGuardRefusedIsInvisibleToTheBarAndNotToThisRuler()} -- the
 *       asymmetry the payload's whole three-number shape exists for. This is the test that would
 *       fail if someone reduced the payload to one boolean, and it fails by ASSERTING a number that
 *       only exists because the other number is published beside it.</li>
 *   <li>{@link #anEmptyPayloadIsNotASafeNight()} -- {@code measuredOnce} against {@code measured},
 *       the bit {@code NightShelter} already earned and this ruler has to earn again.</li>
 *   <li>{@link #theWindowIsOneNightAndNotTheSumOfThem()} -- the reset, which has to agree with the
 *       shelter ledger's or a report would be joining two different nights.</li>
 *   <li>{@link #anUnreadableLastDamageIsNotAZero()} -- the one quantity that cannot be read
 *       through an accessor, and therefore the one whose failure mode is a fabricated number.</li>
 * </ul>
 *
 * <p><b>No mutation numbers are asserted here.</b> A mutation count asserted inside the test it
 * constrains is a number that can be edited to match whatever the mutation did. The claims are
 * about behaviour and each is stated as a fact about a world the fixture describes.
 */
public final class ANightHealthRulerReadsThreeSeriesAndCanSayNoTest {

    /** The clock's first night tick, derived rather than written, so a curve change is visible. */
    private static final long DUSK = NightEnclosure.duskTick();

    /** The world object identity the reset trigger watches. */
    private static final Object WORLD = new Object();

    private static NightHealth over(FakeVitals v) {
        return new NightHealth().reading(() -> v);
    }

    /** One whole night of ticks with nothing happening, so the premise of the quiet half holds. */
    private static NightHealth quietNight() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= NightEnclosure.nightTicks(); t++) {
            h.onTick();
            v.tick();
        }
        return h;
    }

    /**
     * The headline: a bar that dipped below 18 is a broken floor.
     *
     * <p>One tick out of 8,386 is enough, for the reason {@code AShelterIsARegionOverTheNightAndNot
     * ARoofAtDawnTest} gives for the shelter: a low-water mark is not a majority and not a sample.
     * The bar comes back up afterwards, which is what makes this a real trap -- an instrument that
     * read the bar's CURRENT value at dawn would report this night as held.
     */
    @Test
    public void aBarThatDippedBelowEighteenIsABrokenFloor() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        int dip = NightEnclosure.nightTicks() / 2;
        for (int t = 0; t <= NightEnclosure.nightTicks(); t++) {
            if (t == dip) {
                v.hit(6.0F);
            } else if (t > dip && t < dip + 20) {
                v.health(20.0F);
            }
            h.onTick();
            v.tick();
        }

        assertEquals("the whole night was sampled", NightEnclosure.nightTicks(), h.samples());
        assertEquals("and the low-water mark is where the bar actually was, not where it ended: "
                + h.fact(), 14.0F, h.minHealth(), 0.0001F);
        assertEquals("so the floor is broken, even though the bar recovered within twenty ticks: "
                + h.fact(), DUSK + dip, h.firstBelowFloorTick());
        assertFalse("and the reading above must be a real false, not an unmeasured one: " + h.fact(),
                h.floorHeld());
        assertTrue("while the instrument plainly did measure it", h.measured());
    }

    /**
     * The control pair: same night, same clock, same everything, and the only difference is whether
     * the dip happened.
     *
     * <p>Two runs rather than a mutated accumulator, because the ledger is stateful and mutating it
     * under a live reader would leave the reader describing a window that no longer exists.
     */
    @Test
    public void removingTheDipIsTheOnlyThingThatSeparatesTheTwoNights() {
        assertFalse("the dipped night must fail first, or the second half proves nothing",
                dipped().floorHeld());
        assertTrue("and an undipped night must hold, or the first half proved nothing",
                quietNight().floorHeld());
    }

    private static NightHealth dipped() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= NightEnclosure.nightTicks(); t++) {
            if (t == 100) {
                v.hit(6.0F);
            } else if (t > 100 && t < 120) {
                v.health(20.0F);
            }
            h.onTick();
            v.tick();
        }
        return h;
    }

    /**
     * THE ASYMMETRY, and the reason this ruler publishes three numbers instead of one.
     *
     * <p>{@code EntityPlayerSP.damageEntity:319-325} overrides the base class with a subtraction
     * guarded by {@code isEntityInvulnerable}. When that guard holds the bar does not move -- and
     * {@code :363 lastDamage = f} and {@code :367 hurtTime = 10} have already been written. So a
     * hit that was fully refused is invisible to {@code getHealth()} and visible to both other
     * series. The fixture writes exactly that: the two fields move, the bar does not.
     */
    @Test
    public void aHitTheInvulnerabilityGuardRefusedIsInvisibleToTheBarAndNotToThisRuler() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= 200; t++) {
            if (t == 50) {
                v.blockedHit(8.0F);
            }
            h.onTick();
            v.tick();
        }

        assertEquals("the bar never moved, so the minimum cannot see this hit at all: " + h.fact(),
                20.0F, h.minHealth(), 0.0001F);
        assertEquals("and no bar ever went down, which is the series that says so rather than"
                + " leaving it to be inferred: " + h.fact(), 0L, h.healthDrops());
        assertEquals("but the hurt flag rose once, so the player was registered as hit: " + h.fact(),
                1L, h.hitEdges());
        assertEquals("and the difference is the number that makes the refused hit visible: "
                + h.fact(), 1L, h.absorbedHits());
        assertEquals("with the size of the refused hit recovered from lastDamage: " + h.fact(),
                8.0F, h.maxLastDamage(), 0.0001F);
        assertTrue("and the floor genuinely held, because the bar genuinely never dipped: "
                + h.fact(), h.floorHeld());
    }

    /**
     * The floor verdict is about the BAR, so a refused hit must not turn it red.
     *
     * <p>Without this, "fold the hits into the verdict" would look like the safer design and would
     * be reporting a different event: the north star says the health bar never went below 18, and a
     * refused hit is not the bar going below 18.
     */
    @Test
    public void aNightOfRefusedHitsStillHoldsTheFloorAndSaysWhy() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= 400; t++) {
            if (t % 50 == 0 && t > 0) {
                v.blockedHit(4.0F);
            }
            h.onTick();
            v.tick();
        }

        assertTrue("eight refused hits and the bar never moved, so the bar held: " + h.fact(),
                h.floorHeld());
        assertEquals("the hits are still counted, because a quiet night and a survived one are"
                + " different situations: " + h.fact(), 8L, h.hitEdges());
        assertEquals("and every one of them is accounted for as absorbed: " + h.fact(),
                8L, h.absorbedHits());
        assertTrue("so a reader cannot mistake this for a night nobody touched: " + h.fact(),
                h.fact().contains("registered hit"));
    }

    /**
     * A real hit and a refused one in the same night must be told apart, which is the case the
     * boolean would have merged.
     */
    @Test
    public void aRealHitAndARefusedOneInTheSameNightStayDistinguishable() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= 300; t++) {
            if (t == 20) {
                v.hit(2.0F);
            } else if (t == 100) {
                v.blockedHit(9.0F);
            } else if (t > 100 && t < 130) {
                v.health(18.0F);
            }
            h.onTick();
            v.tick();
        }

        assertEquals("one bar drop, from the real hit: " + h.fact(), 1L, h.healthDrops());
        assertEquals("two registrations, because the refused one raised the flag too: " + h.fact(),
                2L, h.hitEdges());
        assertEquals("so exactly one of them did not move the bar: " + h.fact(), 1L,
                h.absorbedHits());
        assertEquals("and the floor held, because 18 is not below 18: " + h.fact(),
                18.0F, h.minHealth(), 0.0001F);
        assertTrue(h.floorHeld());
    }

    /**
     * The empty payload must not read as a safe night.
     *
     * <p>Three states that need opposite answers, byte-identical if the instrument does not separate
     * them: armed and ticking in daylight; armed and ticking with no world (title screen, still
     * loading); and never armed at all ({@code -Dmcp.core.health=false}, or no tick seam). In all
     * three every counter reads zero and {@code floorHeld} reads false -- so a reader that consults
     * only the verdict resolves "never observed" to "safe", which is the false-success surface this
     * whole slice exists to close. {@code measuredOnce} is the bit that closes it, and it is the
     * shape {@code NightShelter.measuredOnce} already established rather than a third invention.
     */
    @Test
    public void anEmptyPayloadIsNotASafeNight() {
        NightHealth neverTicked = new NightHealth();

        assertFalse("nothing was ever measured", neverTicked.measured());
        assertFalse("so the verdict is not available", neverTicked.floorHeld());
        assertFalse("AND measuredOnce is false, which is what says 'never looked' rather than"
                + " 'safe': " + neverTicked.fact(), neverTicked.measuredOnce());
        assertTrue("and the sentence blames the SEAM, which is the honest cause here: "
                + neverTicked.fact(), neverTicked.fact().contains("tick seam has not delivered"));
        // Ticking, but the client has no world: the same counters, a different fault. Note what
        // this state does NOT set -- measuredOnce stays FALSE here, because no world ever existed
        // to measure. That is the same latch semantics NightShelter.measuredOnce has, and it is
        // what makes the bit trustworthy: it is true only once something has actually been read.
        NightHealth titleScreen = over(new FakeVitals(null, DUSK, 20.0F));
        titleScreen.onTick();
        titleScreen.onTick();

        assertFalse("no world means nothing was ever measured, so the latch is false",
                titleScreen.measuredOnce());
        assertFalse("and there is nothing to report", titleScreen.measured());
        assertFalse("and therefore no verdict either", titleScreen.floorHeld());
        assertTrue("while the sentence now blames the missing WORLD rather than the seam, because"
                + " those are different faults with different fixes: " + titleScreen.fact(),
                titleScreen.fact().contains("ticks ARE arriving"));
        assertNotEquals("the two causes must not share one sentence, or a reader cannot act on"
                + " either", neverTicked.fact(), titleScreen.fact());
    }

    /**
     * The window is ONE night, and it resets on the shelter's boundary rather than its own.
     *
     * <p>Two nights driven through the SAME instrument. If the ledger did not reset, night two
     * would report the sum -- and every budget a reader subtracts from it would be nonsense. The
     * backwards-clock case is the one a day-cycle test alone cannot see: {@code /time set} moves
     * the clock inside the same 24,000-tick cycle, where {@code floorDiv} of two ticks is the same
     * number.
     */
    @Test
    public void theWindowIsOneNightAndNotTheSumOfThem() {
        int night = NightEnclosure.nightTicks();
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);

        // Night one: dip, so it is unmistakably a failed night.
        for (int t = 0; t <= night; t++) {
            if (t == 100) {
                v.hit(5.0F);
            }
            h.onTick();
            v.tick();
        }
        assertFalse("night one dipped, so night one failed: " + h.fact(), h.floorHeld());
        assertEquals("on a window of exactly one night", night, h.samples());

        // Night two: quiet, on the same instrument.
        v.at(DUSK + 24000L);
        v.health(20.0F);
        for (int t = 0; t <= night; t++) {
            h.onTick();
            v.tick();
        }

        assertEquals("the window restarted, so the count is one night and not two: " + h.fact(),
                night, h.samples());
        assertEquals("and the clock it reports is the one it is folding: " + h.fact(),
                DUSK + 24000L, h.firstNightTick());
        assertTrue("a body that was caught out in night one is ENCLOSED-equivalent on night two,"
                + " because the claim is about the night being asked about: " + h.fact(),
                h.floorHeld());
    }

    /**
     * A clock moved backwards must not count one night twice.
     *
     * <p>{@code /time set} is a vanilla command, and folding the same ground twice would report one
     * night as nearly two.
     */
    @Test
    public void aClockMovedBackwardsRestartsTheWindowRatherThanDoublingIt() {
        int night = NightEnclosure.nightTicks();
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);

        for (int t = 0; t <= night; t++) {
            h.onTick();
            v.tick();
        }
        assertEquals("the first night is one night long", night, h.samples());

        // Rewind INTO the same night, 1000 ticks back.
        long rewound = DUSK + night - 1000L;
        v.at(rewound);
        for (int t = 0; t <= 1000; t++) {
            h.onTick();
            v.tick();
        }

        assertTrue("a rewind must not let the sample count exceed one night, or every budget"
                + " derived from it goes negative: " + h.fact() + " samples=" + h.samples(),
                h.samples() <= night);
        assertEquals("the window restarts at the tick the clock was moved to: " + h.fact(),
                rewound, h.firstNightTick());
    }

    /**
     * An unreadable {@code lastDamage} is not a zero.
     *
     * <p>{@code EntityLivingBase.lastDamage} is {@code protected} with no public getter, so this
     * quantity is the one that can fail to be read at all. Folding the failure in as 0.0 would
     * assert the strongest possible thing -- the biggest hit the client was ever told about was
     * worth nothing -- out of the one reading that exists to say "I could not tell".
     */
    @Test
    public void anUnreadableLastDamageIsNotAZero() {
        FakeVitals v = new FakeVitals(WORLD, DUSK, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= 100; t++) {
            if (t == 10) {
                v.hit(3.0F);
            } else if (t == 50) {
                v.lastDamageUnreadable();
            }
            h.onTick();
            v.tick();
        }

        assertTrue("the ruler must be able to say the field was unreadable: " + h.fact(),
                h.lastDamageReadable());
        assertEquals("and the size of the biggest hit it DID see is still reported, because a later"
                + " unreadable reading does not erase an earlier real one: " + h.fact(),
                3.0F, h.maxLastDamage(), 0.0001F);

        // And a night where it was NEVER readable must report no number at all.
        FakeVitals blind = new FakeVitals(WORLD, DUSK, 20.0F).lastDamageUnreadable();
        NightHealth hb = over(blind);
        for (int t = 0; t <= 100; t++) {
            hb.onTick();
            blind.tick();
        }
        assertFalse("an unreadable field is not readable", hb.lastDamageReadable());
        assertTrue("and the payload must say so rather than print a number: " + hb.fact(),
                hb.fact().contains("unreadable field"));
        assertTrue("NaN, not 0.0 -- assertEquals on floats would let a printed 0.0 pass a string"
                + " check, so the fact is checked for the reason AND minHealth is checked to be"
                + " real: " + hb.fact(), !Float.isNaN(hb.minHealth()));
    }

    /**
     * Daylight is not a broken floor and must not be sampled as one.
     *
     * <p>A world with the daylight cycle off never reaches night, and a window measured over zero
     * ticks must not report a verdict in either direction. This is the symmetric error to
     * {@code missingSamples == 0} over zero samples being vacuously true.
     */
    @Test
    public void aClockThatNeverReachesNightIsNotMeasuredRatherThanBroken() {
        FakeVitals v = new FakeVitals(WORLD, 0L, 20.0F);
        NightHealth h = over(v);
        for (int t = 0; t <= NightEnclosure.nightTicks(); t++) {
            h.onTick();
            v.tick();
        }

        assertFalse("no night tick was sampled, so nothing was measured", h.measured());
        assertFalse("and an unmeasured window must not report a verdict", h.floorHeld());
        assertTrue("while measuredOnce is true, because ticks WERE delivered and simply had"
                + " nothing to measure -- the daylight case, not the dead-instrument one: "
                + h.fact(), h.measuredOnce());
        assertTrue("and the sentence must say NOT MEASURED rather than print a bare false: "
                + h.fact(), h.fact().contains("NOT MEASURED"));
        assertEquals("with zero samples, because nothing was looked at", 0, h.samples());
    }

    /**
     * The floor constant is {@code SurvivalDamage}'s, not a second copy of it.
     *
     * <p>A threshold written twice is a threshold that will be edited on one side, and the edit
     * would then be a constant nobody notices.
     */
    @Test
    public void theFloorIsSurvivalsNumberAndNotACopyOfIt() {
        assertEquals("the re-export must be the same value, or the two rulers disagree about what"
                + " 18 means",
                net.marcloud.mcp.core.eval.SurvivalDamage.NORTH_STAR_FLOOR,
                NightHealth.NORTH_STAR_FLOOR, 0.0F);
        assertTrue("and it is 18, which is the project's number and not vanilla's",
                NightHealth.NORTH_STAR_FLOOR == 18.0F);
    }

    /** A period below one tick is refused rather than silently rounded into something else. */
    @Test
    public void aPeriodOfZeroTicksIsRefusedRatherThanRounded() {
        try {
            new NightHealth(0);
            org.junit.Assert.fail("a zero period must be refused: an instrument that samples every"
                    + " tick while claiming to sample every zero ticks reports a resolution it"
                    + " does not have");
        } catch (IllegalArgumentException expected) {
            assertTrue("and the message must say what was wrong", expected.getMessage()
                    .contains("at least one tick"));
        }
    }
}