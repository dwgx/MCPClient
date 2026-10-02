package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The trajectory a look writes must be a hand's, and the rate it writes it at must be a hand's.
 *
 * <p><b>Category: 有收益的代价</b> (ADR-0005 §1) -- it spends time and buys a measurable property.
 * The property, in one sentence: <b>the per-tick rotation a slewing aim writes rises to a peak
 * below the caller's cap and then falls again, with the peak bounded by the saccadic main
 * sequence for that aim's amplitude, so no look is ever executed at one flat rate or faster than
 * a human's peak angular velocity for its size.</b>
 *
 * <p><b>What was actually wrong, in two layers.</b> Both were found by mutation-checking a test
 * that appeared to pass, and neither was visible from the curve.
 *
 * <p>1. The rate was the caller's cap and nothing else, for every amplitude. The minimum-jerk
 * curve fixed the SHAPE -- Flash &amp; Hogan 1985's measured peak/mean of 1.805, pinned by
 * {@code TheLookProfileIsShapedToTheMeasuredVelocityTest} -- but the peak RATE the curve was
 * scaled to was {@code slewDegPerTick}, the same number for a 3-degree aim and a 150-degree one.
 * ADR-0005 §3 called the old linear ramp out for being "neither human-shaped, nor free of a
 * turn-rate limit"; the ramp's shape was disposed of and its rate was not.
 *
 * <p>2. The curve never reached the game. The write added the profile's ABSOLUTE position to a
 * {@code curYaw} that had already been advanced by the previous tick, so the trajectory was the
 * running SUM of a bell -- a monotone accelerator -- which the cap clamp then flattened into
 * exactly the constant-rate ramp the minimum-jerk work existed to remove. Measured before the
 * fix, a 150-degree aim with a 40 deg/tick cap sent the game 1.8, 10.1, 26.0, 40.1, 40.1, 32.1
 * degrees per tick: two ticks pinned flat against the cap. The curve's own tests were right about
 * what they measured, because they called {@code shapedProgress} directly; the defect lived in
 * the seam between the curve and the write, where nothing looked.
 *
 * <p><b>The measurement behind the rate.</b> Gibaldi &amp; Sabatini 2020 (doi
 * 10.3758/s13428-020-01388-2) fitted the saccadic main sequence on nine adults' eye traces and
 * report, as the best of nine models for repeatability,
 * {@code PV(A) = V_A + V*sqrt(A - A_th)} with {@code A_th = 1} degree (the accepted micro-saccade
 * amplitude) and {@code V_A = 40} deg/s (the measured mean peak velocity of a 1-degree saccade).
 * {@code V} was fitted per subject over 45.4-140.2; the median of the nine, 100.4, is the
 * constant shipped. Their Table 5 is the cross-check: at 9 degrees the law predicts 323 deg/s
 * against measured means of 160-414, and the lowest-fitted subject is the one whose measured mean
 * is lowest. The law is held flat above 24 degrees, the biggest eccentricity their protocol
 * presented, because their text says peak velocity "smoothly reaches a saturated value for larger
 * saccades" and an unbounded square root reaches 3150 deg/s at 150 degrees, which no eye produces.
 *
 * <p><b>Why not the other half of the saccade model.</b> Making the look hop between fixations
 * was considered and not built, on a measurement rather than a principle: the same paper's
 * Table 5 puts human saccade DURATION at 31-68 ms, and the game samples the mouse once per frame
 * ({@code EntityRenderer:1094-1100}, {@code Minecraft:223} runs {@code new Timer(20.0F)}), so a
 * saccade is 0.6-1.4 ticks long and its internal structure cannot be written to the game at all.
 * A 200 ms inter-saccadic hold would be 4 ticks of the player standing still, for a shape the
 * channel cannot carry: pure cost. The rate is the part that survives 20 Hz.
 */
public final class ALookTurnsAtTheRateAHumanEyeDoesTest {

    /**
     * A cap no human is bound by, so the rate the look runs at is the law's and not the
     * caller's: 40 deg/tick is 800 deg/s.
     */
    private static final float LOOSE_CAP = 40f;

    /**
     * Amplitudes measured on the controller. All are above {@link #LOOSE_CAP} so none of them
     * can be satisfied by the arrival test -- a look that lands within one step of the cap never
     * enters the profile -- and all are below the 180-degree wrap where the quantiser would fold
     * the answer back on itself.
     */
    private static final double[] SLEWING_ARCS = {45, 60, 75, 90, 105, 120, 135, 150};
    /** Ticks the caller's cap alone allows for the same arc: the behaviour being replaced. */
    private static double ticksAllowedByTheCapOnly(double arcDeg, float cap) {
        return Math.max(1.0, Math.round(arcDeg * LookController.VELOCITY_PEAK_MEAN / cap));
    }

    /**
     * Every per-tick step the controller actually wrote, in degrees. This is the observable: it
     * is what leaves the process and what a server sees as a sequence of rotations.
     */
    private static double[] stepsOf(double arcDeg, float cap) {
        FakeActuator act = new FakeActuator();
        act.yaw = 0f;
        act.pitch = 0f;
        LookController look = new LookController(LookIntent.set((float) arcDeg, 0f, cap));
        double previous = act.yaw;
        List<Double> steps = new ArrayList<>();
        for (int tick = 0; tick < 600 && !look.isDone(); tick++) {
            look.tick(act);
            double now = act.yaw;
            if (Math.abs(now - previous) > 1e-9) {
                steps.add(Double.valueOf(Math.abs(now - previous)));
            }
            previous = act.yaw;
        }
        assertTrue("a " + arcDeg + "-degree aim never finished", look.isDone());
        double[] out = new double[steps.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = steps.get(i).doubleValue();
        }
        return out;
    }

    private static double peakOf(double[] steps) {
        double peak = 0;
        for (double s : steps) {
            peak = Math.max(peak, s);
        }
        return peak;
    }

    private static String show(double[] steps) {
        StringBuilder sb = new StringBuilder();
        for (double s : steps) {
            sb.append(String.format("%.1f ", Double.valueOf(s)));
        }
        return sb.toString();
    }

    @Test
    public void aLongLookPeaksBelowTheCallersCapAndIsBoundedByTheMainSequence() {
        for (double arc : SLEWING_ARCS) {
            double law = LookController.mainSequencePeakDegPerTick(arc);
            assertTrue("the law must be tighter than the cap for this arc to say anything; at "
                            + arc + " degrees it peaks at " + law + " deg/tick against a cap of "
                            + LOOSE_CAP,
                    law < LOOSE_CAP);
            double[] steps = stepsOf(arc, LOOSE_CAP);
            double peak = peakOf(steps);
            assertTrue("a " + arc + "-degree aim peaked at " + peak + " deg/tick, which is the "
                            + LOOSE_CAP + " deg/tick cap: the look was ridden at the caller's flat "
                            + "rate, steps " + show(steps),
                    peak < LOOSE_CAP);
            // A quarter of slack for the rounding of the tick budget: the profile's peak is
            // peak-to-mean times the mean, so rounding the tick count moves the realised peak by
            // at most one tick's share. Without it the assertion would be about arithmetic.
            assertTrue("a " + arc + "-degree aim peaked at " + peak + " deg/tick ("
                            + (peak * 20) + " deg/s) but the saccadic main sequence allows "
                            + law + " deg/tick (" + (law * 20) + " deg/s) at that amplitude, "
                            + "steps " + show(steps),
                    peak <= law * 1.25);
        }
    }

    @Test
    public void aLongLookLeavesFromRestAndTakesStrictlyLongerThanTheCapAloneAllows() {
        for (double arc : SLEWING_ARCS) {
            double[] steps = stepsOf(arc, LOOSE_CAP);
            // The rest-check lives in its own test below, behind a stated resolution floor: one
            // sample per 50 ms cannot resolve it on a three-sample move.
            double capOnly = ticksAllowedByTheCapOnly(arc, LOOSE_CAP);
            assertTrue("a " + arc + "-degree aim moved on " + steps.length + " ticks, which the "
                            + LOOSE_CAP + " deg/tick cap alone allows (" + capOnly + "); the "
                            + "saccadic main sequence peaks at "
                            + LookController.mainSequencePeakDegPerTick(arc)
                            + " deg/tick at that amplitude and the look was turned faster than a "
                            + "hand turns it",
                    steps.length >= capOnly + 1.0);
            // The differential form above is the whole claim, and it is deliberately not
            // strengthened to "at least as many ticks as the law divides the arc into": the
            // arrival bound legitimately ends a move a tick early once the profile has brought
            // the remaining error inside one tick of hand motion, and demanding the full budget
            // would be asserting arithmetic rather than the property.
        }
    }

    @Test
    public void aLookWithEnoughSamplesLeavesFromRest() {
        // Only where the write stream can resolve it. One sample per 50 ms means a 45-degree aim
        // is three samples long, and the first of three is a large fraction of the peak by
        // arithmetic alone -- so "leaves from rest" is asserted only for moves of at least five
        // samples. The continuous property, zero derivative at u=0, is pinned on the curve
        // itself by theProfileIsFlatAtBothEndsAndPeaksInTheMiddle; what this adds is that the
        // curve reaches the game at all, which is the half that used to be broken.
        int checked = 0;
        for (double arc : SLEWING_ARCS) {
            double[] steps = stepsOf(arc, LOOSE_CAP);
            if (steps.length < 5) {
                continue;
            }
            checked++;
            double peak = peakOf(steps);
            assertTrue("a " + arc + "-degree aim left rest at " + steps[0] + " deg/tick against a "
                            + "peak of " + peak + " over " + steps.length + " samples -- steps "
                            + show(steps) + "; a hand accelerates away from rest, it does not"
                            + " start at speed",
                    steps[0] <= peak * 0.25);
        }
        assertTrue("the rest-check examined " + checked + " arcs; below two it is vacuous",
                checked >= 3);
    }

    @Test
    public void theRateLawGrowsWithAmplitudeAndThenSaturates() {
        // The shape of the law, which is the part of the property that survives 20 Hz sampling:
        // a human's peak angular velocity rises with the size of the reorientation and then stops
        // rising. The behaviour being replaced had neither half -- it was one constant.
        double[] growing = {3, 10, 20};
        for (int i = 1; i < growing.length; i++) {
            assertTrue("the main sequence must rise with amplitude, but " + growing[i]
                            + " degrees peaks at "
                            + LookController.mainSequencePeakDegPerTick(growing[i])
                            + " deg/tick against "
                            + LookController.mainSequencePeakDegPerTick(growing[i - 1])
                            + " at " + growing[i - 1] + " degrees",
                    LookController.mainSequencePeakDegPerTick(growing[i])
                            > LookController.mainSequencePeakDegPerTick(growing[i - 1]));
        }
        double atFit = LookController.mainSequencePeakDegPerTick(
                LookController.MAIN_SEQUENCE_FITTED_MAX_DEG);
        assertEquals("the rate must stop growing at the edge of the fitted range",
                atFit, LookController.mainSequencePeakDegPerTick(150), 1e-9);
        // Sublinear, which is what makes it a law rather than a constant. A rate proportional to
        // the arc makes the first ratio equal to the amplitude ratio, 30.
        double ratio = atFit / LookController.mainSequencePeakDegPerTick(3);
        assertTrue("an 8x longer turn may not be turned 8x faster, let alone 30x: the law says "
                + ratio + "x", ratio < 12.0);
        assertTrue("and the held rate must be a rate a hand produces, not a rounding artefact: "
                        + atFit + " deg/tick is " + (atFit * 20) + " deg/s",
                atFit * 20 > 300 && atFit * 20 < 800);
    }

    @Test
    public void theCallersCapStillBoundsTheTurnFromAbove() {
        // The contract half. The law is a CEILING, not a floor: were it used as a floor, a caller
        // asking for 2 deg/tick on a long aim would be given 26, which is thirteen times what it
        // permitted and faster than the agent was allowed to move. Stated as a rate rather than a
        // tick count, because a tick count also carries the profile's own lattice quantisation.
        for (double arc : SLEWING_ARCS) {
            double[] steps = stepsOf(arc, 2f);
            double peak = peakOf(steps);
            assertTrue("a 2 deg/tick cap produced a peak of " + peak + " deg/tick on a " + arc
                    + "-degree aim; slewDegPerTick is documented as a maximum, and the main "
                    + "sequence may only ever lower it",
                    peak <= 2.0 + 1e-9);
        }
    }

    @Test
    public void theCostIsBoundedAndFallsOnlyOnLooksThatSlewAtAll() {
        // The cost half, and it is a BOUND on the damage rather than a claim about the property,
        // which is why it is asserted next to the property instead of on its own: alone it would
        // pass with the mechanism deleted.
        int worst = 0;
        for (double arc : SLEWING_ARCS) {
            int extra = (int) (stepsOf(arc, LOOSE_CAP).length
                    - ticksAllowedByTheCapOnly(arc, LOOSE_CAP));
            worst = Math.max(worst, extra);
        }
        assertTrue("the main sequence and the arrival bound together cost at most " + worst
                + " extra ticks on the arcs measured here; three (150 ms) is the measured worst"
                + " case and anything past four is a regression rather than a cost", worst <= 3);

        // And the tax falls only on looks big enough to slew. An aim whose whole error is within
        // one tick of a hand's motion at that amplitude still lands immediately, which is the
        // micro-correction contract: a 2-degree wrap from 179 to -179 must keep costing one tick.
        assertEquals("a 2-degree aim must still land on its first tick",
                1, stepsOf(2, LOOSE_CAP).length);
        assertEquals("and so must a 5-degree one", 1, stepsOf(5, LOOSE_CAP).length);
    }
}
