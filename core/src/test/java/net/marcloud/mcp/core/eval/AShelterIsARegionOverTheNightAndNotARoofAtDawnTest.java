package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.Stance;
import org.junit.Test;

/**
 * A shelter is a region over a NIGHT. A roof at dawn is not one.
 *
 * <p><b>The defect this closes is the second-order one.</b> {@link Enclosure} measures one cell at
 * one instant, and the instant a naive task would pick is the end of the run: the body is standing
 * somewhere at dawn, the sun is up, and a roof over that cell reports a shelter for a player who
 * spent the entire night in the open. The measurement is not wrong -- it is answered about the
 * wrong question, which is worse, because the answer is true.
 *
 * <p>So the unit of the claim is a window, and this file pins three properties of it that a point
 * measurement cannot have:
 *
 * <ul>
 *   <li>{@link #aRoofForOneTickOfAnEightThousandTickNightIsNotAShelter} -- the headline. One
 *       unsheltered tick out of 8,386 is enough to say the player was exposed, because one instant
 *       is all it takes to be reached. The window is not a majority and not a sample.</li>
 *   <li>{@link #removingThatOneTickIsTheOnlyThingThatSeparatesTheTwoNights} -- the control pair.
 *       Plant the single missing roof tick, get a negative, remove the plant, get the positive.</li>
 *   <li>{@link #aNightThatNeverHappenedIsNotAShelterEither} -- the zero-sample case, which is the
 *       one that makes an accumulator dangerous. {@code allMatch} over an empty stream is true, so a
 *       night that was never measured would report a shelter. A clock with the daylight cycle off
 *       is exactly that night, and it has to come back NOT MEASURED.</li>
 * </ul>
 *
 * <p><b>Where the window comes from.</b> Not from a tick count written in this file.
 * {@link NightEnclosure#duskTick()} and {@link NightEnclosure#nightTicks()} ask the production
 * {@code Daylight} helper, which is the same call {@code SimWorld.isNight()} and T24 make, so there
 * is one rule about when night begins and this file is not a second one. The figures those two
 * methods return on this tree -- dusk 13807 and 8,386 night ticks -- are the ones
 * {@code DaylightMatchesTheVanillaCurveTest} pins against {@code World.calculateSkylightSubtracted},
 * and {@link #theWindowIsTheCurvesOwnAndNotATickCountWrittenHere} checks that here as well so a
 * change to the curve cannot leave the window quietly stale.
 */
public final class AShelterIsARegionOverTheNightAndNotARoofAtDawnTest {

    private static final int X = EvalSuite.T25ShelterThroughTheNight.SHELTER_X;
    private static final int Z = EvalSuite.T25ShelterThroughTheNight.SHELTER_Z;
    private static final int FEET_Y = EvalSuite.T25ShelterThroughTheNight.FEET_Y;
    private static final int ROOF_Y = EvalSuite.T25ShelterThroughTheNight.ROOF_Y;

    /**
     * Runs a whole night inside the shaft, with the roof removed for exactly {@code openTicks}
     * consecutive night ticks starting {@code openFrom} ticks into the night.
     *
     * <p>The window is driven rather than asserted, so the two halves of this file compare two
     * runs that differ in one argument.
     */
    private static NightEnclosure nightWithRoofHole(int openFrom, int openTicks) {
        SimWorld w = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        int night = NightEnclosure.nightTicks();
        w.atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(w);
        NightEnclosure enclosure = new NightEnclosure();
        for (int tick = 0; tick <= night; tick++) {
            h.tick();
            // The clock started one tick before dusk, so after iteration `tick` the world's
            // worldTime is exactly duskTick() + tick. `into` is therefore the offset from dusk and
            // not an index with an off-by-one hiding in it.
            int into = tick;
            boolean holed = into >= openFrom && into < openFrom + openTicks;
            w.remove(X, ROOF_Y, Z);
            if (!holed) {
                w.put(X, ROOF_Y, Z, "stone");
            }
            Stance feet = w.stance();
            enclosure.observe(w.enclosureGrid(), feet.x(), feet.y(), feet.z(), w.worldTime());
        }
        return enclosure;
    }

    /**
     * One unsheltered tick out of a whole night is enough to say the player was exposed.
     *
     * <p>The body does not move and nothing else about the run changes: the roof is over the head
     * on 8,385 of the 8,386 night ticks. If this row were satisfied by a majority rule, by an
     * average, or by a sample taken at dawn, it would report a shelter. It reports the opposite,
     * and it says how long the exposure was, because "not a shelter" without a duration is a
     * sentence nobody can act on.
     */
    @Test
    public void aRoofForOneTickOfAnEightThousandTickNightIsNotAShelter() {
        NightEnclosure holed = nightWithRoofHole(4000, 1);

        assertTrue("the night was measured at all, which is the first thing to check: " + holed,
                holed.measured());
        assertEquals("and it was measured across EVERY night tick of the window, not a sample of"
                + " it: " + holed, NightEnclosure.nightTicks(), holed.samples());
        assertEquals("8,385 of those ticks had a roof over the head, so a majority rule, an average"
                + " or any kind of sampling would call this a shelter: " + holed,
                NightEnclosure.nightTicks() - 1, holed.enclosedSamples());
        assertEquals("and exactly one did not", 1, holed.openSamples());

        assertFalse("THE CLAIM. A roof on 8,385 ticks out of 8,386 is a cover that arrived too"
                + " late to be a shelter, because one instant standing in the open is all it takes"
                + " to be reached. " + holed, holed.sheltered());
        assertEquals("and the ledger names how long the exposure was, in ticks rather than as a"
                + " ratio nobody can picture: " + holed, 1L, holed.longestUnreachableTicks());
        assertTrue("and it says WHEN, so the exposure is a place on the clock rather than a"
                + " total: " + holed,
                holed.firstOpenTick() == NightEnclosure.duskTick() + 4000L);
        assertTrue("and the sample window is the curve's own night from end to end: " + holed,
                holed.firstNightTick() == 13807L && holed.lastNightTick() == 22192L);
    }

    /**
     * The control pair: the same run with the single missing tick present again.
     *
     * <p>Two runs differing in one argument -- {@code openTicks} of 1 against 0 -- on the same
     * world geometry, the same clock and the same body. A control whose negative half cannot be
     * made to pass is not a control, it is a second assertion of the same thing.
     */
    @Test
    public void removingThatOneTickIsTheOnlyThingThatSeparatesTheTwoNights() {
        NightEnclosure negative = nightWithRoofHole(4000, 1);
        NightEnclosure positive = nightWithRoofHole(4000, 0);

        assertFalse("the planted run is exposed: " + negative, negative.sheltered());
        assertTrue("and the identical run with the plant removed is not: " + positive,
                positive.sheltered());
        assertEquals("the two runs agree on every count except the one the plant moved: negative"
                + " openSamples=" + negative.openSamples() + " against positive openSamples="
                + positive.openSamples(), 0, positive.openSamples());
        assertEquals("and the plant moved exactly one of them", 1, negative.openSamples());
        assertEquals("so the windows really are the same window, tick for tick",
                negative.samples(), positive.samples());
        assertEquals("and both were measured over the curve's own night length, not a hardcoded"
                + " number", NightEnclosure.nightTicks(), positive.samples());
    }

    /**
     * A night that never happened is not a shelter.
     *
     * <p>This is the accumulator's dangerous case and the reason {@link NightEnclosure} carries
     * {@code measured()} separately from {@code sheltered()}. "Every sample was enclosed" is
     * vacuously true of no samples at all, so an accumulator written as
     * {@code samples.stream().allMatch(...)} reports a perfect shelter for a run in which night
     * never arrived. With {@code doDaylightCycle} off and the clock pinned in the morning, that is
     * a real world: 8,387 ticks pass and the curve is never crossed.
     */
    @Test
    public void aNightThatNeverHappenedIsNotAShelterEither() {
        SimWorld frozen = EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .doDaylightCycle(false)
                .atWorldTime(NightEnclosure.duskTick() - 1L);
        EvalHarness h = new EvalHarness(frozen);
        NightEnclosure enclosure = new NightEnclosure();
        int night = NightEnclosure.nightTicks();
        for (int tick = 0; tick <= night; tick++) {
            h.tick();
            Stance feet = frozen.stance();
            enclosure.observe(frozen.enclosureGrid(), feet.x(), feet.y(), feet.z(),
                    frozen.worldTime());
        }

        assertEquals("the clock never moved, so the whole night was spent on one tick: " + frozen,
                NightEnclosure.duskTick() - 1L, frozen.worldTime());
        assertFalse("and the run is NOT MEASURED rather than measured-and-sheltered",
                enclosure.measured());
        assertEquals("with no samples to average or to all-match: " + enclosure, 0,
                enclosure.samples());
        assertFalse("THE CLAIM. A window measured over zero ticks reports no shelter, because"
                + " 'every sample was enclosed' over nothing at all is true of a night the player"
                + " never had. " + enclosure, enclosure.sheltered());
        assertTrue("and the row says NOT MEASURED in words rather than leaving a reader to infer"
                + " it from a zero: " + enclosure, enclosure.fact().contains("NOT MEASURED"));
    }

    /**
     * The window is the curve's, and not a tick count written into this file.
     *
     * <p>Hardcoding 13807 and 8386 here would be a second copy of a rule that already exists in one
     * place. The assertions below are what the derived figures come out as on this tree, so a
     * change to the curve goes red with a number in it instead of silently moving the window.
     */
    @Test
    public void theWindowIsTheCurvesOwnAndNotATickCountWrittenHere() {
        assertEquals("the first tick vanilla's own curve calls night, as measured by"
                + " DaylightMatchesTheVanillaCurveTest against World.calculateSkylightSubtracted",
                13807L, NightEnclosure.duskTick());
        assertEquals("and the number of ticks it calls night, which is what makes the loop a night"
                + " rather than an arbitrary budget", 8386, NightEnclosure.nightTicks());
    }

    /**
     * The body stood still for the whole window.
     *
     * <p>Without this, every row above would also be consistent with the player having walked into
     * the sealed cell late and out of it early, and the roof hole would not be the variable.
     */
    @Test
    public void theBodyStoodStillForEveryTickItWasSampledOn() {
        NightEnclosure sealed = nightWithRoofHole(4000, 0);
        assertTrue("the sealed run measured " + sealed.samples() + " night ticks", sealed.measured());
        assertTrue("and every one of them was a shelter, so the body never left: " + sealed,
                sealed.sheltered());
    }
}