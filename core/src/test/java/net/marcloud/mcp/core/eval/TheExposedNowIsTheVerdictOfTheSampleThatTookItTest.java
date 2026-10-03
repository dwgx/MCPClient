package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code exposedNow} is the verdict of the sample that took it, not a difference of two counters.
 *
 * <p><b>The defect this pins.</b> {@code NightShelter.onTick} used to answer "is the body exposed
 * right now" by remembering {@code openSamples()} before the tick and asking whether it had risen:
 *
 * <pre>
 *   int openBefore = ledger.openSamples();
 *   sampled = ledger.observe(...);
 *   exposedNow = sampled &amp;&amp; ledger.openSamples() &gt; openBefore;
 * </pre>
 *
 * <p>That is a movement in a running total, and {@link NightEnclosure} resets the total at every
 * window boundary. So at the FIRST tick of a night the counter went from the whole previous
 * night's open count to {@code 1}, {@code 1} is not more than the old total, and a body that was
 * genuinely standing in the open was reported SAFE. Measured: night 1 ends at
 * {@code openSamples = 100}; night 2's first tick samples and lands on {@code openSamples = 1};
 * {@code exposedNow} answered {@code false} and answered {@code true} again from the next tick.
 *
 * <p>The window reset is not the only thing that breaks a difference. {@code openSamples() > 0} —
 * the other derivation anybody reaches for — answers "was this body exposed at ANY point tonight",
 * which is {@link NightEnclosure#sheltered()}'s question and not this one: a body caught out for
 * three ticks an hour ago and under a roof ever since is safe NOW.
 *
 * <p>So the value is written where the knowledge is: {@link NightEnclosure#observe} records what
 * the sample it took found, and every reader asks for that. That is the same shape
 * {@link DawnChest#presentOnLastSample} already uses for the box instrument, which is why the two
 * instruments can be reasoned about the same way.
 *
 * <p><b>The two guards below, and why only one of them is red.</b>
 * {@link #aBodyExposedAtTheFirstTickOfANightIsReportedExposed()} is the boundary case and goes red
 * on the old derivation. {@link #theVerdictIsTheSamplesOwnAndNotACounterDerived()} is the premise
 * for the fix rather than a witness against it: it pins the property a reader would lose if
 * somebody "simplified" {@code lastSampleOpen()} back into arithmetic, and it is green on the old
 * code too, because the old code got the non-boundary cases right by accident. Together they say
 * what the value is: equal to the world's answer on the tick it was sampled, never a function of
 * how many open samples have piled up.
 */
public final class TheExposedNowIsTheVerdictOfTheSampleThatTookItTest {

    /** The sealed shaft's feet cell, on the geometry the enclosure row and its controls share. */
    private static final int X = EvalSuite.T25ShelterThroughTheNight.SHELTER_X;
    private static final int Z = EvalSuite.T25ShelterThroughTheNight.SHELTER_Z;
    private static final int FEET_Y = EvalSuite.T25ShelterThroughTheNight.FEET_Y;
    /** The cell that closes the shaft's column, i.e. the one block this file moves. */
    private static final int ROOF_Y = EvalSuite.T25ShelterThroughTheNight.ROOF_Y;

    private static final int NIGHT = NightEnclosure.nightTicks();
    private static final long DUSK = NightEnclosure.duskTick();
    private static final int DAY = 24000;

    /** One whole night of the clock, roof gone for {@code openTicks} ticks from {@code openFrom}. */
    private static void night(SimWorld w, NightShelter shelter, int nightIndex,
                              int openFrom, int openTicks) {
        long dusk = DUSK + (long) nightIndex * DAY;
        w.atWorldTime(dusk - 1L);
        EvalHarness h = new EvalHarness(w);
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            roof(w, tick >= openFrom && tick < openFrom + openTicks);
            shelter.onTick();
        }
    }

    /** The roof is what makes the body enclosed; removing it is the only lever this file pulls. */
    private static void roof(SimWorld w, boolean holed) {
        w.remove(X, ROOF_Y, Z);
        if (!holed) {
            w.put(X, ROOF_Y, Z, "stone");
        }
    }

    private static SimWorld world() {
        return EvalSuite.T25ShelterThroughTheNight.buildWorld();
    }

    private static NightShelter shelterOf(SimWorld w) {
        return new NightShelter(1).reading(() -> new NightShelter.Body() {
            @Override
            public Object world() {
                return w;
            }

            @Override
            public long worldTime() {
                return w.worldTime();
            }

            @Override
            public Enclosure.CellGrid grid() {
                return w.enclosureGrid();
            }

            @Override
            public int feetX() {
                return w.stance().x();
            }

            @Override
            public int feetY() {
                return w.stance().y();
            }

            @Override
            public int feetZ() {
                return w.stance().z();
            }
        });
    }

    /**
     * THE HEADLINE, and it is RED ON THE OLD DERIVATION.
     *
     * <p>Night 1 is spent with the roof off for its first 100 ticks, so the ledger carries a
     * hundred open samples into the day. Night 2's FIRST tick is spent with the roof off as well,
     * and that tick is the one that was reported safe: {@code openSamples} went 100 → 1 and
     * {@code 1 > 100} is false.
     *
     * <p>The numbers are pinned before the second tick is driven so that the assertion below is
     * about the tick where the derivation breaks and not about some later one.
     */
    @Test
    public void aBodyExposedAtTheFirstTickOfANightIsReportedExposed() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        night(w, shelter, 0, 0, 100);
        assertEquals("the premise: night 1 left a hundred open samples behind",
                100, shelter.openSamples());

        // Night 2, its first tick only, and that tick is exposed.
        long dusk = DUSK + DAY;
        w.atWorldTime(dusk - 1L);
        EvalHarness h = new EvalHarness(w);
        h.tick();
        roof(w, true);
        shelter.onTick();

        assertTrue("the first tick of the new night really was sampled",
                shelter.sampledLastTick());
        assertEquals("and it really did find the body reachable -- the instrument itself says so,"
                        + " which is the same fact the payload has to report",
                1, shelter.openSamples());
        assertEquals("while night 1's hundred open samples are gone: the window restarted, so the"
                        + " first open tick on the record is night 2's own",
                dusk, shelter.firstOpenTick());
        assertTrue("THE CLAIM. A body standing in the open is EXPOSED, at the first tick of a"
                        + " night and on every other tick. Deriving this from whether a counter"
                        + " moved answers false here, because the counter was reset by the window"
                        + " and moved from 100 to 1. " + shelter.fact(),
                shelter.exposedNow());
    }

    /**
     * THE PREMISE, green on the old code and on the new: the value is the world's answer on the
     * tick it was sampled, and not a function of how many open samples have piled up.
     *
     * <p>Two halves, and they kill the two derivations a reader might reach for:
     *
     * <ul>
     *   <li><b>Equal to the world, tick by tick.</b> The roof is toggled on and off in a pattern
     *       this file knows, and every single night tick's {@code exposedNow} is compared against
     *       what the fixture put in the world on that tick. A value reconstructed from a counter
     *       agrees with the world here too -- that is the point of a control -- but it pins the
     *       claim to the world's answer rather than to another copy of the same arithmetic.</li>
     *   <li><b>Enclosed NOW is not exposed, however many open samples exist.</b> This is the one
     *       that kills {@code openSamples() > 0}: a body exposed for three ticks and sealed for
     *       the rest of the night is sheltered from here on, and a payload that keeps saying
     *       "exposed" because the night's total is non-zero is answering the wrong question with
     *       a field the model will act on.</li>
     * </ul>
     */
    @Test
    public void theVerdictIsTheSamplesOwnAndNotACounterDerived() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);

        // A known pattern: open, open, sealed, sealed, open, then sealed for the rest of a short
        // window. Every tick's expectation is written down here rather than recomputed.
        boolean[] holed = {true, true, false, false, true, false};
        w.atWorldTime(DUSK - 1L);
        EvalHarness h = new EvalHarness(w);
        for (int tick = 0; tick < holed.length; tick++) {
            h.tick();
            roof(w, holed[tick]);
            shelter.onTick();
            assertEquals("tick " + tick + " of the window: exposedNow must be what the world"
                            + " looks like on THIS tick and nothing else. " + shelter.fact(),
                    holed[tick], shelter.exposedNow());
            assertEquals("and the instrument's own verdict for that sample says the same thing,"
                            + " so the published field is not being reconstructed upstream",
                    holed[tick], shelter.lastSampleOpen());
        }

        // The second half: the night now has open samples on the record and the body is sealed.
        for (int tick = holed.length; tick < 200; tick++) {
            h.tick();
            roof(w, false);
            shelter.onTick();
        }
        assertTrue("the premise for this half: the night DOES have open samples on the record, so"
                        + " openSamples() > 0 is available as a wrong answer. " + shelter.fact(),
                shelter.openSamples() > 0);
        assertFalse("and a body that has been under a roof for the last 195 ticks is NOT exposed"
                        + " right now, whatever the night's total says. " + shelter.fact(),
                shelter.exposedNow());
        assertFalse("while the night's verdict still reports the exposure that happened, because"
                        + " that is a different question and it is still true",
                shelter.sheltered());
    }

    /**
     * CONTROL on the period: at a coarser sampling period this field is the last SAMPLE's answer,
     * and the accumulator says so rather than letting a reader assume it is this tick.
     *
     * <p>Green on the old code and the new — neither derivation can tell the difference, because
     * both are gated on a sample having been taken. It is here because it is the premise the
     * staleness judgement rests on: with the shipped default of one tick per sample there is no
     * staleness at all, and with a coarser period the two published fields
     * ({@code exposedNow}, which requires a sample THIS tick, and {@code lastSampleOpen}, which
     * does not) diverge on exactly the skipped ticks, which is what makes the staleness visible
     * to a reader instead of silent.
     */
    @Test
    public void atACoarserPeriodTheTwoPublishedFieldsSayWhichTickWasLookedAt() {
        SimWorld w = world();
        NightShelter shelter = new NightShelter(5).reading(body(w));
        assertEquals("the premise: a period of five, so four ticks in five take no sample",
                5, shelter.periodTicks());

        w.atWorldTime(DUSK - 1L);
        EvalHarness h = new EvalHarness(w);
        h.tick();
        roof(w, true);
        shelter.onTick();
        assertEquals("the first tick of the window is always sampled, period or not",
                true, shelter.sampledLastTick());
        assertTrue("and it saw the body in the open", shelter.exposedNow());

        boolean[] skipped = new boolean[4];
        for (int i = 0; i < skipped.length; i++) {
            h.tick();
            // The world changes on every one of these ticks; only one of them is looked at.
            roof(w, i >= 2);
            shelter.onTick();
            skipped[i] = !shelter.sampledLastTick();
        }
        assertTrue("the premise: four ticks were skipped by the sampler. " + shelter.fact(),
                skipped[0] && skipped[3]);
        assertFalse("so exposedNow is false on them -- not because the body was seen to be safe,"
                        + " but because nothing was looked at, which is what sampledLastTick() is"
                        + " for. A reader that read exposedNow alone would be told 'not exposed'"
                        + " about a body it has no information on",
                shelter.exposedNow());
        assertTrue("while the instrument's own last-SAMPLE verdict is still available and is what"
                        + " it says: the sample that did happen found the body in the open. That is"
                        + " the field that keeps the answer from depending on the period",
                shelter.lastSampleOpen());
    }

    /** The same {@link NightShelter.Body} view, factored out for the coarse-period half above. */
    private static java.util.function.Supplier<NightShelter.Body> body(SimWorld w) {
        return () -> new NightShelter.Body() {
            @Override
            public Object world() {
                return w;
            }

            @Override
            public long worldTime() {
                return w.worldTime();
            }

            @Override
            public Enclosure.CellGrid grid() {
                return w.enclosureGrid();
            }

            @Override
            public int feetX() {
                return w.stance().x();
            }

            @Override
            public int feetY() {
                return w.stance().y();
            }

            @Override
            public int feetZ() {
                return w.stance().z();
            }
        };
    }
}