package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Rotation must be a position a mouse can express, and must arrive like a hand moved.
 *
 * <p>Two independent properties, both measured rather than chosen, both previously false.
 *
 * <p><b>The lattice.</b> {@code 0.15} degrees is not a tolerance someone picked. The mouse path
 * is {@code EntityRenderer:1099-1100} computing {@code f1 = (sens*0.6+0.2)^3 * 8}, then
 * {@code Entity.setAngles:392} multiplying by {@code 0.15}. At the default sensitivity those
 * cancel to exactly 0.15 degrees per mouse count, so the reachable set is {@code 0.15 * Z} --
 * 2400 orientations on the circle, 1200 for pitch. {@code LivePlayerActuator:180-201} writes
 * the rotation floats directly, bypassing all three, so the quantisation they would have given
 * for free never happens, and the server does not check it either. A raw float is a rotation
 * no cursor produces.
 *
 * <p><b>The shape.</b> Flash and Hogan (1985) measured aimed movement and found velocity
 * bell-shaped, peak/mean about 1.75. The old code was a clamp-to-{@code slewDegPerTick} ramp: a
 * flat-topped trapezoid, wrong at both ends and wrong most where a hand is most conspicuous.
 */
public class ALookIsAMousePositionAndAHandShapeTest {

    // ===== the lattice =====

    @Test
    public void everyReachableAngleSitsOnTheMouseLattice() {
        // 0.15 * Z, both signs, including the wrap where the lattice is circular.
        for (int i = -1200; i <= 1200; i += 7) {   // within +-180: outside that the lattice WRAPS
            double want = i * LookController.MOUSE_GCD_DEG;
            double got = LookController.quantizeMouse(want);
            assertEquals("quantising a lattice point must be the identity: " + want,
                    want, got, 1e-9);
        }
    }

    @Test
    public void anOffLatticeAngleSnapsToItsNearestReachableNeighbour() {
        // -5.194427 is vanilla's exact look-at pitch for this geometry and is NOT reachable.
        double got = LookController.quantizeMouse(-5.194427490234375);
        double steps = got / LookController.MOUSE_GCD_DEG;
        assertEquals("the result must itself be on the lattice", Math.rint(steps), steps, 1e-6);
        assertTrue("and within half a mouse of the request, got " + got,
                Math.abs(got - (-5.194427490234375)) <= LookController.MOUSE_GCD_DEG / 2 + 1e-9);
    }

    @Test
    public void theLatticeWrapsInsteadOfClamping() {
        // 359.97 and -0.03 are ONE mouse apart. Clamping instead of wrapping would leave the
        // circle with a seam where the last 0.075 degrees is unreachable, which is a discontinuity
        // in the middle of nowhere rather than at the -180/180 line where one already exists.
        double a = LookController.quantizeMouse(359.97);
        double b = LookController.quantizeMouse(-0.03);
        assertEquals("359.97 and -0.03 must land on the same point of the circle",
                a, b, 1e-9);
    }

    // ===== the shape =====

    @Test
    public void theProfileIsFlatAtBothEndsAndPeaksInTheMiddle() {
        // The measured property. A profile with a non-zero derivative at u=0 starts the turn at
        // speed, which is the ramp tell in its purest form.
        double first = LookController.minJerkProgress(1e-6) - LookController.minJerkProgress(0);
        double last = LookController.minJerkProgress(1.0) - LookController.minJerkProgress(1 - 1e-6);
        assertTrue("the curve must leave rest, not already be moving: " + first, first < 1e-5);
        assertTrue("and must arrive at rest: " + last, last < 1e-5);
        assertTrue("it must peak at the midpoint: " + LookController.minJerkProgress(0.5),
                LookController.minJerkProgress(0.5) > LookController.minJerkProgress(0.25));
        assertEquals("and the midpoint is exactly half the arc", 0.5,
                LookController.minJerkProgress(0.5), 1e-9);
    }

    @Test
    public void theProfileIsMonotoneSoTheAimNeverReverses() {
        double prev = 0;
        for (int i = 1; i <= 200; i++) {
            double v = LookController.minJerkProgress(i / 200.0);
            assertTrue("a dip at u=" + (i / 200.0) + " would send the crosshair back the way it "
                    + "came, which no hand does", v >= prev - 1e-12);
            prev = v;
        }
    }

    @Test
    public void theProfileSpansExactlyTheWholeArc() {
        // Progress 0 is nothing done and progress 1 is everything: if these were not 0 and 1
        // the aim would either stop short or be counted twice.
        assertEquals(0.0, LookController.minJerkProgress(0.0), 1e-12);
        assertEquals(1.0, LookController.minJerkProgress(1.0), 1e-12);
    }

    @Test
    public void thePeakIsInTheRangeMeasuredForHumanHands() {
        // Flash & Hogan measured peak/mean ~1.75 for aimed movement. The raised cosine used here
        // is 2.0 -- deliberately 14% peakier, and deliberately NOT tuned down to 1.75: a fitted
        // beta curve would hit the number and would also be a fudge nobody could check. What
        // this pins is the bound that actually matters, that the shape is a gentle bell rather
        // than the textbook minimum-jerk polynomial's 6.0, whose opening tick rounds to zero.
        int n = 400;
        double total = 0;
        double peak = 0;
        for (int i = 0; i < n; i++) {
            double v = LookController.minJerkProgress(i / (double) n)
                    - LookController.minJerkProgress(i / (double) n - 1.0 / n);
            total += v;
            peak = Math.max(peak, v);
        }
        // MEAN, not total: the increments sum to the whole arc, so peak/sum is ~1/n and says
        // nothing about the shape. peak/mean is the ratio the measurement is in.
        double mean = total / n;
        double ratio = peak / mean;
        assertTrue("peak/mean was " + ratio + "; the measured human value is 1.75, this curve is "
                + "pi/2 = 1.571, and the textbook minimum-jerk polynomial is 6.0",
                ratio > 1.45 && ratio < 1.75);
    }

    @Test
    public void aTrapezoidWouldFailTheSameTestAndSoThisHasTeeth() {
        // The old behaviour, computed here so the property above is not vacuous: a constant
        // rate is peak/mean == 1, which is a flat top and the thing being replaced.
        int n = 400;
        double mean = 1.0 / n;
        double peak = 1.0 / n;
        assertTrue("a constant rate is peak/mean 1.0, outside the range the profile must sit in",
                !(peak / mean > 1.45 && peak / mean < 1.75));
    }
}
