package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * World time has one reduction, and every reader of it shares that copy.
 *
 * <p>This repository has now produced THREE of them, each disagreeing with the others on
 * negative world times. Java's remainder keeps the sign of the dividend, so {@code t % 24000}
 * is negative for negative {@code t}, and any reader that forgets to normalise reports a tick
 * outside the day it is claiming to describe. The third copy — {@code Math.abs(wt) % 24000} in
 * the packet summariser — was worse than a sign slip: {@code Math.abs} folds the sign *before*
 * the modulus, so it is not a reduction at all. World time -1 reports {@code tod=1}, the same
 * value as +1, while being 23999 ticks away from it in the cycle.
 *
 * <p>Negative world time is not hypothetical: {@code /time set} and a restored save both put the
 * counter below zero, and {@link WorldViewCapture#timeBucket} already documents having been
 * caught by exactly this. These tests pin the one copy and the property that makes the other two
 * copies unnecessary — the reduction is a map into {@code [0, 24000)}, so it is idempotent and
 * wraps in both directions.
 */
public class TimeOfDayHasOneReductionTest {

    @Test
    public void aNegativeWorldTimeLandsInsideTheDayInsteadOfBeforeIt() {
        // The regression. `t % 24000` alone gives -6000 here, which buckets as "sunrise" while
        // the same instant in the cycle is 18000, "night".
        assertEquals(18000L, WorldViewCapture.timeOfDay(-6000L));
        assertEquals(6000L, WorldViewCapture.timeOfDay(-18000L));
        assertEquals(23999L, WorldViewCapture.timeOfDay(-1L));
    }

    @Test
    public void theReductionNeverLeavesTheDay() {
        // Property, not examples: every input lands in [0, 24000), which is what makes the two
        // observation tools agreeing a matter of construction rather than of care.
        long[] probes = {0L, 1L, -1L, 11999L, 12000L, 23999L, 24000L, 24001L, -23999L, -24000L,
            -24001L, -48001L, Long.MAX_VALUE, Long.MIN_VALUE + 1, -2_000_000_000L};
        for (long t : probes) {
            long d = WorldViewCapture.timeOfDay(t);
            assertTrue("timeOfDay(" + t + ") = " + d + ", which is outside one day",
                    d >= 0L && d < 24000L);
        }
    }

    @Test
    public void theReductionIsIdempotentAndWrapsInBothDirections() {
        // Idempotence is the property a caller can rely on: normalising an already-normalised
        // value must not move it, so a reader may normalise defensively without double-shifting.
        for (long t = -50000L; t <= 50000L; t += 997L) {
            long once = WorldViewCapture.timeOfDay(t);
            assertEquals("not idempotent at " + t, once, WorldViewCapture.timeOfDay(once));
        }
        assertEquals(WorldViewCapture.timeOfDay(6000L), WorldViewCapture.timeOfDay(6000L + 24000L));
        assertEquals(WorldViewCapture.timeOfDay(6000L), WorldViewCapture.timeOfDay(6000L - 24000L));
    }

    @Test
    public void minusOneAndPlusOneAreDifferentInstantsAndMustNotCollapse() {
        // The specific failure of Math.abs(wt) % 24000, named as its own case because it is the
        // one a summary line shows an operator: both are adjacent to midnight and neither is the
        // other, and a folded sign puts them in the same tick of the day.
        assertNotEquals(WorldViewCapture.timeOfDay(-1L), WorldViewCapture.timeOfDay(1L));
    }

    @Test
    public void theBucketAndTheTickAgreeBecauseTheyShareTheReduction() {
        // The two readers that a disagreement between them would make unrepresentable: a
        // summary line saying "night" while the grid says "sunrise" about the same instant.
        assertEquals("sunrise", WorldViewCapture.timeBucket(-23999L));
        assertEquals(1L, WorldViewCapture.timeOfDay(-23999L));

        assertEquals("night", WorldViewCapture.timeBucket(-6000L));
        assertEquals(18000L, WorldViewCapture.timeOfDay(-6000L));
    }
}
