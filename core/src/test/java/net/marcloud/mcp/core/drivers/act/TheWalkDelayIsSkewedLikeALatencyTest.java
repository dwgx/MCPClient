package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The walk-start delay must be a LATENCY, and a latency is not uniform.
 *
 * <p><b>Category: 有收益的代价 with a measured cost of zero</b> (ADR-0005 §1). The property, in
 * one sentence: <b>the reaction delay is a right-skewed draw with a hard floor, so across many
 * walks the delay distribution has a heavier low end than high end and a positive skew, which a
 * uniform interval cannot have.</b>
 *
 * <p><b>Why the shape was a defect and the band was not.</b> The delay itself is justified
 * elsewhere and is not this file's business: it buys "a cancelled and re-submitted walk is not
 * faster than the first", and that property is per-intent stability, not the shape. What ADR-0005
 * §3 listed as unhandled and §6 called "the worst choice" is the SHAPE -- {@code 4 + rand(5)} is
 * a range someone typed, not a sample from any distribution, and a uniform one has skewness
 * exactly zero and puts 20% of its mass on the fastest reaction and 20% on the slowest.
 *
 * <p><b>Why a half-normal on a floor.</b> A latency is a duration, so it cannot be negative, and
 * human reaction times have a hard lower bound set by the refractory period -- which is why RT
 * paradigms window out anything faster than ~110 ms at all. So the shape is "a floor plus a
 * non-negative, long-tailed excess", and {@code |N(0, sigma)|} is the smallest model that says
 * exactly that: no mass below the floor, a spike at it, a tail above it. That is a named
 * distribution fitted at two parameters, not an interval.
 *
 * <p><b>What it costs.</b> Zero ticks. The support is still exactly 4..8, so the band the tool
 * description quotes, the first-key-on-tick-drawn-plus-one guarantee and the per-intent stability
 * guarantee all hold unchanged. The mean moves 6.00 -> 5.55 ticks (300 -> 277 ms), because a
 * right-skewed fit inside a symmetric band has to sit below the band's centre; that is 23 ms
 * FASTER per walk, not slower. The scale was NOT fitted to the published intrasubject SD of
 * 40.0 ms (Woods et al., doi 10.3389/fnhum.2015.00131, n = 1469), because that would be 0.8 ticks
 * against a band whose own SD is 1.29 -- a delay more regular than a person's own is the same
 * tell as no delay at all.
 */
public final class TheWalkDelayIsSkewedLikeALatencyTest {

    /** Enough samples that the sample skewness is stable to well inside the asserted margin. */
    private static final int DRAWS = 6000;

    private static final int[] COUNTS = new int[16];

    private static double[] drawSamples() {
        java.util.Arrays.fill(COUNTS, 0);
        for (int i = 0; i < DRAWS; i++) {
            int d = MoveApplier.drawReactionDelay();
            assertTrue("the delay left the documented 4..8 band: " + d, d >= 4 && d <= 8);
            COUNTS[d]++;
        }
        double[] xs = new double[DRAWS];
        int at = 0;
        for (int d = 4; d <= 8; d++) {
            for (int i = 0; i < COUNTS[d]; i++) {
                xs[at++] = d;
            }
        }
        return xs;
    }

    private static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) {
            s += x;
        }
        return s / xs.length;
    }

    /** The sample third standardised moment. Exactly 0 in expectation for any symmetric law. */
    private static double skewness(double[] xs) {
        double m = mean(xs);
        double m2 = 0;
        double m3 = 0;
        for (double x : xs) {
            double d = x - m;
            m2 += d * d;
            m3 += d * d * d;
        }
        m2 /= xs.length;
        m3 /= xs.length;
        return m2 <= 0 ? 0 : m3 / Math.pow(m2, 1.5);
    }

    @Test
    public void theDelayIsRightSkewedAndNotAnEnumeratedRange() {
        double[] xs = drawSamples();
        double skew = skewness(xs);
        // A uniform 4..8 has skewness 0 in expectation, and 6000 draws put the sample within
        // about +-0.04 of it, so 0.15 is a margin no uniform draw can cross and a real right tail
        // clears by a factor of three.
        assertTrue("sample skewness of the reaction delay was " + skew + "; a uniform 4..8 band is "
                + "0 by construction and this draw is meant to be right-skewed", skew > 0.15);

        // The same statement in a form that needs no moment: a latency is bounded below and not
        // above, so the fast end of the band must be more populated than the slow end. Uniform
        // puts equal mass on both, so this is the assertion a reverted draw cannot pass.
        assertTrue("the slowest draw occurred " + COUNTS[8] + " times against " + COUNTS[4]
                        + " at the fastest; a latency has a floor and a tail, not two equal ends",
                COUNTS[4] > COUNTS[8]);
    }

    @Test
    public void theBandAndItsStabilityAreUnchanged() {
        // The guard the shape must not have moved. Every value in the band stays reachable, so
        // the tool description's "4-8 ticks" and the existing bounds still describe reality.
        double[] xs = drawSamples();
        for (int d = 4; d <= 8; d++) {
            assertTrue("no draw landed on " + d + " ticks, so the band is no longer 4..8",
                    COUNTS[d] > 0);
        }
        // And the mean, which is what the cost of the change actually was: a right-skewed fit in
        // a symmetric band sits below the band's centre, so a walk starts ~23 ms sooner than it
        // did. Pinned so a later "make the delay look human" edit cannot quietly slow the agent.
        double mean = mean(xs);
        assertTrue("the mean delay is " + mean + " ticks (" + (mean * 50) + " ms); a uniform 4..8"
                + " averages 6 and this draw is meant to land near the ADR's 250-300 ms anchor",
                mean > 5.2 && mean < 5.9);
    }

    @Test
    public void theDrawIsStillOnePerIntentAndUnchangedWhileThePlayerWaits() {
        // The property that earns the delay its place, re-checked against the new draw so that
        // changing the shape cannot quietly change the guarantee: one draw per walk, stable
        // across every tick the player spends standing still.
        //
        // Driven through the applier, which is where the draw lives. It was a NavController field
        // until the metronome fix, and moving it is what stops a per-BLOCK draw.
        for (int run = 0; run < 200; run++) {
            FakeActuator act = new FakeActuator();
            act.setPosition(0.5, 64.0, 0.5);
            ActRuntime runtime = new ActRuntime();
            MoveApplier applier = new MoveApplier(act, runtime);
            SlotRecord rec = SlotRecord.submitted(new NavIntent(60.0, 64.0, 0.0, 400), 0L, 1L, "s");

            // Read after the bind tick, which is where the draw happens -- see the note in
            // TheReactionDelayIsOneDrawNotARaceTest. Sampling before the first apply() reads a
            // value the bind then replaces.
            rec = applier.apply(rec.stampTick(1));
            int drawn = applier.drawnReactionDelay();
            for (int tick = 2; tick <= drawn + 1; tick++) {
                rec = applier.apply(rec.stampTick(tick));
                assertEquals("the draw changed while the player stood still", drawn,
                        applier.drawnReactionDelay());
            }
        }
    }
}
