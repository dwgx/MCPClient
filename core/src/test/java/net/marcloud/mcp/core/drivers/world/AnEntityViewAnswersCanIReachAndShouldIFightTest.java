package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * An entity listing must answer "can I reach it" and "should I fight it", not just "where is it".
 *
 * <p>The existing {@link EntityView} carried id, type, position, distance, hp and a name. An agent
 * handed that cannot answer either of the two questions it needs before swinging: is the target
 * close enough for the SERVER to accept the hit, and is it a thing that may be hit at all. Both
 * gates exist in the vendored 1.8.9 source and neither was exposed:
 *
 * <ul>
 *   <li><b>Distance is squared, and the threshold depends on sight.</b>
 *       {@code NetHandlerPlayServer.processUseEntity} compares
 *       {@code getDistanceSqToEntity(entity) < d0}, where {@code d0} is 36.0 when
 *       {@code canEntityBeSeen} and <b>9.0 when not</b>. So the reachable radius is 6.0 blocks
 *       blindfolded and 6.0 seen -- wait: sqrt(36)=6.0 and sqrt(9)=3.0. The number that matters
 *       is the SQUARE, and 36.0 is 6 blocks while 9.0 is 3. Two different targets, and an agent
 *       that assumes one of them will be refused without an error.</li>
 *   <li><b>Clicking is clamped to 3.0 blocks.</b> {@code EntityRenderer.getMouseOver} nulls a
 *       pointed entity whose eye-to-hit distance exceeds 3.0 in survival, so even a target inside
 *       the server's 6-block radius is not selectable by aiming at it. Reach for an ATTACK and
 *       hover for a PICK are different questions.</li>
 *   <li><b>Some entities may not be attacked at all.</b> {@code processUseEntity} kicks the player
 *       for attacking an {@code EntityItem}, an {@code EntityXPOrb}, an {@code EntityArrow}, or
 *       themselves. That is a disconnect, not a failed click, and nothing in the current view
 *       hints at it.</li>
 * </ul>
 *
 * <p>The production rules are transcribed independently here and compared over a grid, so the
 * implementation cannot quietly invert one.
 */
public final class AnEntityViewAnswersCanIReachAndShouldIFightTest {

    /** {@code NetHandlerPlayServer.processUseEntity:899-935}, in its own order. */
    static boolean serverAccepts(double distSq, boolean canSee) {
        double d0 = canSee ? 36.0D : 9.0D;
        return distSq < d0;
    }

    /** {@code EntityRenderer.getMouseOver:498-502}, survival only. */
    static boolean pickable(double eyeToHit) {
        return eyeToHit <= 3.0D;
    }

    @Test
    public void theReachThresholdIsSixBlocksSeenAndThreeBlind() {
        assertTrue("a zombie 5 blocks away, seen: accepted",
                serverAccepts(5 * 5, true));
        assertTrue("the same zombie NOT seen: refused, because the threshold drops to 9.0",
                !serverAccepts(5 * 5, false));
        assertTrue("just under 3 blocks, not seen: accepted (9.0 is 3 squared)",
                serverAccepts(8.9, false));
        assertTrue("just under 6 blocks, seen: accepted",
                serverAccepts(35.9, true));
        assertTrue("exactly 36 is NOT accepted -- the comparison is strict, so a caller at the "
                        + "boundary must not be told it is in range",
                !serverAccepts(36.0, true));
        assertTrue("exactly 9 is not accepted either", !serverAccepts(9.0, false));
    }

    @Test
    public void aimingAndHittingHaveDifferentReach() {
        assertTrue("5 blocks is within the server's reach but past the 3.0 pick clamp",
                serverAccepts(25.0, true) && !pickable(5.0));
        assertTrue("so an entity can be attackable by id and un-clickable by crosshair",
                serverAccepts(25.0, true) && !pickable(5.0));
        assertTrue("2 blocks is both", serverAccepts(4.0, true) && pickable(2.0));
    }

    @Test
    public void theProductionVerdictsAgreeWithTheVanillaRulesAbove() {
        int mismatches = 0;
        for (int tenths = 0; tenths <= 90; tenths++) {
            double d = tenths / 10.0;
            for (boolean seen : new boolean[] {true, false}) {
                if (EntityCombat.serverAccepts(d * d, seen) != serverAccepts(d * d, seen)) {
                    mismatches++;
                }
            }
            if (EntityCombat.pickable(d) != pickable(d)) {
                mismatches++;
            }
        }
        assertEquals("the implementation and this file's independent transcription must agree at "
                + "every tenth of a block, including the strict 3.6/3.0 and 3.0/3.0 boundaries",
                0, mismatches);
    }

    @Test
    public void anUnattackableEntityIsNamedRatherThanLeftToDiscovery() {
        // Attacking these kicks the player; that is a disconnect, not a refused click.
        for (String t : new String[] {"Item", "XPOrb", "Arrow", "EntityPlayer", "EntityPlayerMP"}) {
            assertFalse("a dropped " + t + " is on the kick list, and attacking it disconnects the "
                            + "player, so it must be marked unattackable: " + t,
                    EntityCombat.attackable(t));
        }
        assertTrue("a Minecart is NOT on that list, so refusing it would be a guess: vanilla's "
                        + "list is EntityItem, EntityXPOrb, EntityArrow and the player, and "
                        + "inventing a fourth is the failure this class exists to prevent",
                EntityCombat.attackable("Minecart"));
        assertTrue("a Zombie is fair game", EntityCombat.attackable("Zombie"));
        assertTrue("and so is a Cow", EntityCombat.attackable("Cow"));
        assertTrue("an unrecognised name is PERMITTED, because vanilla's list is a deny list and "
                        + "an allow list here would refuse every horse, wolf and modded mob: this "
                        + "mirrors the server rather than second-guessing it",
                EntityCombat.attackable("??"));
        assertFalse("and a blank name is still refused, because there is nothing to judge",
                EntityCombat.attackable(""));
        assertFalse("as is a null one", EntityCombat.attackable(null));
    }

    @Test
    public void hostileIsADistinctionNotAHealthThreshold() {
        // "hostile" here means the game classes it as a mob that attacks the player, which is what
        // decides whether a fight is needed -- not "has low health" and not "is close".
        assertTrue(EntityCombat.hostile("Zombie"));
        assertTrue(EntityCombat.hostile("Creeper"));
        assertTrue(EntityCombat.hostile("Skeleton"));
        assertTrue(EntityCombat.hostile("Spider"));
        assertTrue("a pig is livestock", !EntityCombat.hostile("Pig"));
        assertTrue("a cow is livestock", !EntityCombat.hostile("Cow"));
        assertTrue("a villager is not hostile", !EntityCombat.hostile("Villager"));
        assertTrue("an item entity is not hostile", !EntityCombat.hostile("Item"));
    }
}
