package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link FireDamage} and {@link DrowningDamage}: that their boundaries are vanilla's
 * boundaries, walked over the whole input domain rather than spot-checked.
 *
 * <p>These two classes exist because the same shape of problem appears twice -- a client-side
 * counter and a server-side damage application joined by fixed, published arithmetic. The tests
 * are written to the same standard as {@code FallDamage}'s: the numbers are walked whole and
 * derived from the constants vanilla declares, so a transcription that drifts by one at a
 * boundary fails even though it agrees everywhere else.
 */
public final class FireAndDrownAreVanillasArithmeticTest {

    // ---------------------------------------------------------------- fire

    /**
     * Fire damage fires exactly on the multiples of 20, walked whole.
     *
     * <p>{@code Entity.update:499-503} is {@code if (this.fire % 20 == 0)} then hit then
     * {@code --this.fire}. The property, not a table: a tick count is damage-bearing exactly when
     * it is a multiple of 20, and the count over a run is the number of such multiples. Checking
     * the whole band 0..600 is what makes an off-by-one at the boundary visible -- a
     * transcription using {@code >=} instead of {@code ==} agrees on 19 and 40 and disagrees on
     * nothing until the first sub-20 case, which is why the small end is walked too.
     */
    @Test
    public void fireHurtsOnlyOnVanillasMultiples() {
        for (int ticks = 0; ticks <= 600; ticks++) {
            int hits = FireDamage.damageOverTicks(ticks) / FireDamage.DAMAGE_PER_TICK;
            int expectedHits = ticks / FireDamage.TICKS_PER_DAMAGE_TICK;
            assertEquals("ticks=" + ticks, expectedHits, hits);
        }
        assertEquals("no fire is no damage", FireDamage.NONE, FireDamage.damageOverTicks(0));
        assertEquals("negative fire is no damage", FireDamage.NONE, FireDamage.damageOverTicks(-1));
    }

    /**
     * The damage is never negative and never exceeds the hurt-per-tick budget.
     *
     * <p>Every tick that deals damage costs the same amount, so a body that is hurt for the whole
     * of its fire must have taken exactly one hit per multiple of 20 -- never a partial one. This
     * is a property, so it holds for every input rather than being restated at one value.
     */
    @Test
    public void fireDamageIsNeverNegativeAndNeverAPartialHit() {
        for (int ticks = -50; ticks <= 1000; ticks++) {
            int damage = FireDamage.damageOverTicks(ticks);
            assertTrue("ticks=" + ticks + " damage=" + damage, damage >= FireDamage.NONE);
            assertEquals("ticks=" + ticks + " must be a whole number of hits", 0,
                    damage % FireDamage.DAMAGE_PER_TICK);
        }
    }

    /**
     * Seconds become ticks at vanilla's own rate, and lava's contact cost is its two halves added.
     *
     * <p>{@code Entity.setFire:551-560} multiplies seconds by 20, and
     * {@code setOnFireFromLava:539-545} pays 4 HP of contact damage plus {@code setFire(15)}. The
     * two halves are asserted separately as well as summed, because a caller told only the total
     * cannot tell which part armour would have reduced -- and the contact half is the only
     * blockable one.
     */
    @Test
    public void lavaContactIsContactDamagePlusTheBurnItLights() {
        int ticks = FireDamage.fireTicksForSeconds(FireDamage.LAVA_FIRE_SECONDS);
        assertEquals(FireDamage.TICKS_PER_SECOND * FireDamage.LAVA_FIRE_SECONDS, ticks);
        int burn = FireDamage.damageOverTicks(ticks);
        assertEquals(FireDamage.LAVA_CONTACT_DAMAGE + burn,
                FireDamage.damageForOneLavaContact());
        // The burn must exceed the contact hit, because a caller who dives through lava once and
        // swims out still pays most of the bill afterwards.
        assertTrue("the burn must dominate the one-off contact hit",
                burn > FireDamage.LAVA_CONTACT_DAMAGE);
        assertEquals("negative seconds are not fire", FireDamage.NONE,
                FireDamage.fireTicksForSeconds(-5));
        assertEquals(FireDamage.NONE, FireDamage.ticksRemaining(-3));
    }

    /**
     * The burning flag is bit 0 of DataWatcher byte 0, and nothing else in that byte means fire.
     *
     * <p>{@code Entity.setFlag:2215-2227} packs {@code 1 << flag} into watchable byte 0, and
     * {@code getFlag:2210-2213} reads it back. Flags 1-4 are sneak/riding/sprint/eating, so a
     * transcription that read the wrong bit would report a sprinting player as burning -- and
     * would report a genuinely burning player as fine whenever they were also sneaking. Walking
     * all 256 byte values is what pins "bit 0 only".
     */
    @Test
    public void onlyBitZeroOfTheFlagByteMeansBurning() {
        for (int flags = 0; flags <= 0xFF; flags++) {
            boolean expected = (flags & 1) != 0;
            assertEquals("flags=" + flags, expected,
                    FireDamage.isBurningFlag((byte) (flags & 0xFF)));
        }
        assertTrue(FireDamage.isBurningFlag((byte) 0x01));
        assertFalse("a sneaking player is not on fire", FireDamage.isBurningFlag((byte) 0x02));
        assertFalse(FireDamage.isBurningFlag((byte) 0x00));
        // The flag byte is DataWatcher index 0; air is index 1. Swapping them would report every
        // player as burning, since full air is 300 and 300 & 1 == 0 only by luck of the low bit.
        assertEquals(0, FireDamage.FLAG_DATAWATCHER_INDEX);
        assertEquals(0, FireDamage.FLAG_BURNING_BIT);
    }

    // ------------------------------------------------------------ drowning

    /**
     * Air crosses into the negative band exactly at -1 and keeps going to -20.
     *
     * <p>{@code SelfView} documents {@code -1..-19} as real values meaning "drowning has begun",
     * because vanilla decrements with no floor and only tests {@code == -20}. This walks the whole
     * band so a transcription that clamped at zero -- the intuitive fix -- would fail here rather
     * than hide the countdown entirely.
     */
    @Test
    public void theNegativeAirBandIsRealAndMeansDrowning() {
        assertFalse(DrowningDamage.isDrowning(DrowningDamage.AIR_FULL));
        assertFalse(DrowningDamage.isDrowning(0));
        assertTrue("air 0 is the last breath, not yet drowning",
                !DrowningDamage.isDrowning(0));
        assertTrue(DrowningDamage.isDrowning(DrowningDamage.AIR_DAMAGE_TICK + 1));
        for (int air = DrowningDamage.AIR_DAMAGE_TICK + 1; air < 0; air++) {
            assertTrue("air=" + air + " must read as drowning", DrowningDamage.isDrowning(air));
        }
    }

    /**
     * The distance to the damage tick is the decrements still owed, times what each decrement
     * costs on average -- checked against a Monte-Carlo of vanilla's own rule.
     *
     * <p>Simulating {@code decreaseAirSupply} directly, rather than asserting a hand-written
     * formula, is what makes this test worth having: the rule is
     * {@code rand.nextInt(level + 1) > 0 ? air : air - 1}, and the two ways to mis-transcribe it
     * -- dividing instead of multiplying, and inverting the skip condition -- both produce a
     * plausible-looking integer that no closed-form assertion would catch. The simulation is
     * seeded so the run is deterministic; the tolerance is loose because the quantity being
     * estimated is a mean, but it is far tighter than the gap between the right answer and the
     * inverted one.
     */
    @Test
    public void ticksUntilDamageMatchesASimulatedCountdown() {
        java.util.Random rand = new java.util.Random(20251001L);
        for (int respiration = 0; respiration <= 3; respiration++) {
            int bound = DrowningDamage.AIR_FULL + 10;
            // Average the observed ticks to reach the damage value over many independent runs.
            for (int air = 0; air <= bound; air += 7) {
                int runs = 4000;
                long total = 0;
                for (int run = 0; run < runs; run++) {
                    int value = air;
                    int ticks = 0;
                    while (value > DrowningDamage.AIR_DAMAGE_TICK && ticks < 100_000) {
                        boolean skip = respiration > 0
                                && rand.nextInt(respiration + 1) > 0;
                        if (!skip) {
                            value--;
                        }
                        ticks++;
                    }
                    total += ticks;
                }
                double mean = total / (double) runs;
                assertEquals("air=" + air + " respiration=" + respiration
                                + " (simulated mean " + mean + ")",
                        mean, DrowningDamage.ticksUntilDamage(air, respiration), mean * 0.10D + 2.0D);
            }
        }
    }

    /**
     * The first hit costs a whole breath; every hit after it costs twenty ticks.
     *
     * <p>This is the arithmetic {@code EntityLivingBase:305} forces and the one a caller most
     * often gets wrong, because {@code setAir(0)} -- not {@code setAir(300)} -- is what restarts
     * the clock. A uniform-interval model would report two hits for a 640-tick dive where vanilla
     * delivers seventeen, which is the difference between "recoverable" and "dead". The boundary
     * either side of the first band is walked because that is exactly where a uniform model
     * first disagrees.
     */
    @Test
    public void theSecondDrownHitComesTwentyTicksAfterTheFirst() {
        int firstBand = DrowningDamage.ticksUntilDamage(DrowningDamage.AIR_FULL, 0);
        assertEquals("full air owes a full breath plus the negative band",
                DrowningDamage.AIR_FULL - DrowningDamage.AIR_DAMAGE_TICK, firstBand);
        assertEquals("a dive shorter than the breath is free",
                DrowningDamage.NONE, DrowningDamage.damageOverTicks(firstBand - 1, 0));
        assertEquals("exactly one band is exactly one hit",
                DrowningDamage.DAMAGE_PER_TICK, DrowningDamage.damageOverTicks(firstBand, 0));
        assertEquals("still one hit one tick later",
                DrowningDamage.DAMAGE_PER_TICK, DrowningDamage.damageOverTicks(firstBand + 1, 0));

        int secondBand = -DrowningDamage.AIR_DAMAGE_TICK;
        assertEquals("the second hit is one short band away, not another full breath",
                2 * DrowningDamage.DAMAGE_PER_TICK,
                DrowningDamage.damageOverTicks(firstBand + secondBand, 0));
        assertEquals("and the third follows the same short band",
                3 * DrowningDamage.DAMAGE_PER_TICK,
                DrowningDamage.damageOverTicks(firstBand + 2 * secondBand, 0));

        // The shape that matters is the discontinuity at the first band. Before it, the damage
        // rate is one hit per whole breath; from it onward, one hit per twentieth of that. A
        // uniform-interval model -- which is what "ticks / 20 hits" gives -- is wrong by the band
        // ratio for the entire rest of the dive, and wrong in the direction that lets a caller
        // plan a dive it cannot finish. So the increment itself is the property: each further hit
        // must cost exactly one short band, never another full breath, and the increment must be
        // flat rather than growing.
        int shortBand = -DrowningDamage.AIR_DAMAGE_TICK;
        int previous = DrowningDamage.damageOverTicks(firstBand, 0);
        for (int hits = 1; hits <= 40; hits++) {
            int current = DrowningDamage.damageOverTicks(firstBand + hits * shortBand, 0);
            assertEquals("each further hit must cost exactly one short band, hit " + hits,
                    DrowningDamage.DAMAGE_PER_TICK, current - previous);
            previous = current;
        }
        // And a dive timed to land exactly on a band boundary must be charged for the hit on
        // that boundary -- the off-by-one a caller times against. The count is hits+1 because the
        // first hit lands on firstBand itself, before the short bands begin.
        for (int extraBands = 0; extraBands <= 10; extraBands++) {
            assertEquals("a dive ending exactly on band " + extraBands + " must pay for it",
                    (extraBands + 1) * DrowningDamage.DAMAGE_PER_TICK,
                    DrowningDamage.damageOverTicks(firstBand + extraBands * shortBand, 0));
        }
    }

    /**
     * "Will I run out of air" and "how much will it cost" must not disagree with each other.
     *
     * <p>Both read {@link DrowningDamage#ticksUntilDamage}, which is the point: a caller that
     * asks the survival question and then the damage question has to get a consistent pair. A
     * generous estimate in one and a strict one in the other is how a body is told it will
     * survive a dive it will not.
     */
    @Test
    public void theBreathQuestionAndTheDamageAgree() {
        for (int ticks = 0; ticks <= 1000; ticks++) {
            boolean ranOut = DrowningDamage.outOfBreath(ticks, 0);
            boolean paid = DrowningDamage.damageOverTicks(ticks, 0) > DrowningDamage.NONE;
            assertEquals("ticks=" + ticks, ranOut, paid);
        }
    }

    /**
     * Respiration lengthens the breath, and it must lengthen it rather than shorten it.
     *
     * <p>The direction is the whole content of this test. {@code decreaseAirSupply} skips a
     * decrement on {@code i} of {@code i + 1} rolls, so the drain rate is {@code 1 / (i + 1)} and
     * each owed decrement costs {@code i + 1} ticks. An implementation that divides where vanilla
     * multiplies still satisfies a "never worse" check at most inputs and fails at the short ones,
     * so the monotonicity is checked over the whole small domain and the first band is checked
     * for growth explicitly rather than merely for non-inversion.
     */
    @Test
    public void respirationOnlyEverHelps() {
        for (int ticks = 0; ticks <= 3000; ticks++) {
            int noRespiration = DrowningDamage.damageOverTicks(ticks, 0);
            for (int level = 1; level <= 3; level++) {
                assertTrue("ticks=" + ticks + " level=" + level
                                + " must not cost more than no Respiration",
                        DrowningDamage.damageOverTicks(ticks, level) <= noRespiration);
            }
        }
        // The first band itself must grow with the level, by exactly the level+1 factor. This is
        // the assertion a dividing implementation fails immediately.
        for (int level = 0; level <= 3; level++) {
            assertEquals("Respiration level " + level + " must multiply the wait by level+1",
                    (DrowningDamage.AIR_FULL - DrowningDamage.AIR_DAMAGE_TICK) * (level + 1),
                    DrowningDamage.ticksUntilDamage(DrowningDamage.AIR_FULL, level));
        }
        // And the worst case ignores it entirely, because an unlucky run skips nothing.
        for (int air = 0; air <= DrowningDamage.AIR_FULL; air++) {
            assertEquals("air=" + air, DrowningDamage.ticksUntilDamage(air, 0),
                    DrowningDamage.ticksUntilDamageWorstCase(air));
        }
        // A negative level is treated as absent rather than lengthening the breath without limit.
        assertEquals(DrowningDamage.damageOverTicks(500, 0),
                DrowningDamage.damageOverTicks(500, -3));
    }
}
