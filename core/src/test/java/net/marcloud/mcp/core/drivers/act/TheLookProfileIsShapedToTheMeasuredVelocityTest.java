package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The look profile must have the velocity shape a measured human reach has.
 *
 * <p>The controller turns along a warped half-period raised cosine, and the constant that warps
 * it is the point. Flash &amp; Hogan 1985 (doi 10.1523/JNEUROSCI.05-07-01688.1985) fitted the
 * minimum-jerk model to 30 unconstrained point-to-point movements and report
 * {@code C = 1.805 +- 0.153}; the model's 1.875 was accepted at alpha=0.01 and REJECTED at
 * alpha=0.05.
 *
 * <p>This used to be a half-period raised cosine unwarped, whose peak-to-mean velocity ratio is
 * {@code pi/2 = 1.571} -- about 13% flatter than measured, and the comment above it claimed F&amp;H
 * had measured 1.75 (they did not; 1.75 is a line in the Shadmehr course notes) and that the
 * textbook polynomial ratio is 6.0 (which matched nothing). Both numbers were unsourced, and the
 * difference between them and the measured value is exactly the error the constant now carries.
 *
 * <p>An external check that this matters: TruthfulAC ships a dedicated <b>jerk detection</b> aim
 * check, and Gaia's {@code AimF} flags rotations with no GCD residue as {@code gcdBypass}. A
 * velocity profile that is not jerk-shaped is a detection signal, not a cosmetic difference.
 */
public final class TheLookProfileIsShapedToTheMeasuredVelocityTest {

    private static final double MEASURED = 1.805D;

    private static double progress(double u) {
        return LookController.shapedProgress(u, LookController.VELOCITY_PEAK_MEAN);
    }

    @Test
    public void itStartsAtRestAndFinishesAtRest() {
        assertEquals("a hand starts slow", 0.0, progress(0.0), 1e-9);
        assertEquals("and arrives slow, which is what makes the mouse lattice snap cleanly instead "
                + "of overshooting", 1.0, progress(1.0), 1e-9);
    }

    @Test
    public void theVelocityIsBellShapedRatherThanFlatOrSpiked() {
        // A constant-velocity ramp is the single most recognisable machine tell; a spike is not
        // human either. Sampled derivative, with the ends near zero and the middle largest.
        int n = 200;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = progress((i + 1.0) / n) - progress(i / (double) n);
        }
        int maxAt = 0;
        for (int i = 1; i < n; i++) {
            if (v[i] > v[maxAt]) {
                maxAt = i;
            }
        }
        assertTrue("the peak must be in the middle, not at either end: max at index " + maxAt
                        + " of " + n, Math.abs(maxAt - n / 2) <= n / 20);
        assertTrue("and the first tick must be much slower than the peak, or it reads as a step: "
                        + v[0] + " vs " + v[maxAt], v[0] < v[maxAt] * 0.35);
        assertTrue("and the last, or it stops abruptly: " + v[n - 1] + " vs " + v[maxAt],
                v[n - 1] < v[maxAt] * 0.35);
    }

    @Test
    public void thePeakToMeanVelocityIsTheMeasuredRatio() {
        // Numerically, by the definition: the ratio of the peak derivative to the mean derivative
        // over the whole move. The mean is progress(1) - progress(0) = 1, so the mean velocity per
        // unit u is 1.0 and the ratio IS the peak slope.
        int n = 2000;
        double peak = 0;
        for (int i = 0; i < n; i++) {
            double a = i / (double) n;
            double b = (i + 1.0) / n;
            peak = Math.max(peak, (progress(b) - progress(a)) * n);
        }
        assertEquals("peak-to-mean velocity must be the measured 1.805, not the raw curve's pi/2",
                MEASURED, peak, 0.02);
    }

    @Test
    public void theRatioIsAChoiceNotAHardcodedCuriosity() {
        // The property that makes this calibratable: the same shape, a different ratio, and the
        // curve still starts and ends at rest and completes. Otherwise changing the constant would
        // mean rewriting the curve.
        for (double r : new double[] {1.571, 1.7, 1.805, 1.875, 2.0}) {
            assertEquals("starts at rest at ratio " + r, 0.0,
                    LookController.shapedProgress(0.0, r), 1e-9);
            assertEquals("and completes at ratio " + r, 1.0,
                    LookController.shapedProgress(1.0, r), 1e-9);
        }
        // A ratio at or below 1 has no warp, and falls back to the raw curve rather than dividing
        // by something that would make it non-monotonic.
        assertTrue("a degenerate ratio must not break the curve",
                LookController.shapedProgress(0.5, 1.0) > 0 && LookController.shapedProgress(0.5, 1.0) < 1);
        assertTrue("nor may a nonsensical one",
                LookController.shapedProgress(0.5, Double.NaN) > 0);
    }

    @Test
    public void theShippedConstantIsTheMeasuredValueNotTheOldOne() {
        assertEquals("the controller must actually be running the measured profile, not declaring it",
                1.805D, LookController.VELOCITY_PEAK_MEAN, 0.0);
        assertTrue("and it must differ from the old pi/2, or this is a comment change and not a "
                        + "behaviour change", Math.abs(LookController.VELOCITY_PEAK_MEAN - Math.PI / 2) > 0.1);
    }
}
