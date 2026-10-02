package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The fall-damage number is vanilla's, not this project's.
 *
 * <p>{@code EntityLivingBase.fall:1156} is
 * {@code int i = MathHelper.ceiling_float_int((distance - 3.0F - f) * damageMultiplier);} with the
 * damage applied only {@code if (i > 0)}. Everything below pins that arithmetic at its edges,
 * because the edges are where a plausible re-implementation differs: the three-block boundary, the
 * ceiling, and the Jump potion's {@code +1}.
 */
public final class TheFallDamageNumberIsVanillasTest {

    /** No Jump effect. {@code -1} is the honest "no effect" the seam passes. */
    private static final int NO_JUMP = -1;

    @Test
    public void threeBlocksIsTheBoundaryAndItIsFree() {
        // 3.0 - 3.0 = 0.0, ceiling(0.0) = 0, and the damage branch is `i > 0`.
        assertEquals(0, FallDamage.damageFor(0.0F, NO_JUMP, 1.0F));
        assertEquals(0, FallDamage.damageFor(3.0F, NO_JUMP, 1.0F));
        // One hundredth past the boundary is the first half heart: a ceiling, not a rounding.
        assertEquals(1, FallDamage.damageFor(3.01F, NO_JUMP, 1.0F));
        assertEquals(1, FallDamage.damageFor(4.0F, NO_JUMP, 1.0F));
        assertEquals(2, FallDamage.damageFor(5.0F, NO_JUMP, 1.0F));
        // The ceiling, not the truncation: 4.0 of fall is 1.0 over the boundary, and 5.0 is 2.0.
        assertEquals(1, FallDamage.damageFor(4.0F, NO_JUMP, 1.0F));
    }

    @Test
    public void aFallIsNeverReportedAsNegativeDamage() {
        assertEquals("a one-block step down costs nothing, not minus two hearts",
                0, FallDamage.damageFor(1.0F, NO_JUMP, 1.0F));
    }

    @Test
    public void jumpAbsorbsOneBlockPerAmplifierPlusVanillasOwnOne() {
        // EntityLivingBase.fall:1153-1154: f = amplifier + 1, or 0 with no effect.
        assertEquals(10, FallDamage.damageFor(13.0F, NO_JUMP, 1.0F));
        // Amplifier 0 already absorbs ONE block, not zero.
        assertEquals(9, FallDamage.damageFor(13.0F, 0, 1.0F));
        assertEquals(8, FallDamage.damageFor(13.0F, 1, 1.0F));
    }

    @Test
    public void lethalAgreesWithTheDamageItIsDerivedFrom() {
        // 20 HP of health, a 23-block fall: 20 half-hearts, exactly the health bar.
        assertEquals(20, FallDamage.damageFor(23.0F, NO_JUMP, 1.0F));
        assertTrue("a 23-block fall kills a full bar",
                FallDamage.lethal(23.0F, NO_JUMP, 1.0F, 20.0F));
        assertFalse("a 22-block fall does not", FallDamage.lethal(22.0F, NO_JUMP, 1.0F, 20.0F));
        // And the jump that makes 22 survivable is visible through the same call.
        assertTrue("but with Jump it is", FallDamage.lethal(22.0F, 0, 1.0F, 20.0F) == false);
    }
}
