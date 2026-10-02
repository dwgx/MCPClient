package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The mob line has to be a fact about the world, not a flag a controller sets about itself.
 *
 * <p><b>Why these four and not one.</b> A single test would go green for the wrong reason in every
 * way that matters: a stub that returns {@code true} from {@code damagePlayer} without touching the
 * health bar, or a {@code canSee} that always answers {@code true}, would each satisfy "a mob
 * exists and something happened". So each test pins one transcribed rule and the assertions are
 * chosen so that the cheap fake fails:
 *
 * <ul>
 *   <li>sight is checked against a world that has a wall in it, in both directions -- a
 *       constant-true and a constant-false {@code canSee} both die here;</li>
 *   <li>the resistant window is checked by reading the health bar across three calls, so
 *       "returned true" is not the assertion -- the number is;</li>
 *   <li>a mob that acts is measured by the player's health and the swing count together, which
 *       pins the damage per swing to the flat attribute rather than to anything self-reported;</li>
 *   <li>and the mob has to actually close the distance, because a mob that stands still and
 *       "attacks" would satisfy a damage assertion in a world where it never got in range.</li>
 * </ul>
 */
public class AMobThatActsIsMeasuredInTheWorldTest {

    /** A stone floor at y=63, so the player's feet are at y=64. */
    private static SimWorld flat() {
        return new SimWorld().plain(64, "stone", -12, 12, -12, 12).standOn(0, 64, 0).facing(0f);
    }

    /**
     * {@code EntitySenses.canSee} -> {@code EntityLivingBase.canEntityBeSeen:2155-2157}. A wall
     * between two eyes that are three blocks apart is the whole test: the answer has to flip when
     * one block is put down and come back when it is taken away.
     */
    @Test
    public void aWallStopsSightAndTakingItAwayRestoresIt() {
        SimWorld w = flat();
        double[] from = {0.5D, 65.62D, 0.5D};
        double[] to = {0.5D, 65.62D, 3.5D};

        assertTrue("open air over a stone floor is not a wall", w.canSee(from, to));

        w.put(0, 65, 2, "stone");
        assertFalse("a full block between the eyes stops the ray", w.canSee(from, to));

        w.remove(0, 65, 2);
        assertTrue("removing the block gives the ray back", w.canSee(from, to));
    }

    /**
     * {@code Entity.attackEntityFrom:893-912} with {@code maxHurtResistantTime = 20}. The
     * assertions are on the health bar, so a method that returned the right booleans while leaving
     * the bar alone would fail.
     */
    @Test
    public void theResistantWindowRefusesASecondHitAndALargerOneLandsOnlyTheDifference() {
        SimWorld w = flat();
        SimMob zombie = w.spawnMob("zombie", 0.5D, 64.0D, 1.5D);
        assertNotNull("zombie is one of the two transcribed types", zombie);

        assertTrue("the first hit lands", w.damagePlayer(3.0D, zombie));
        assertEquals(20.0D - 3.0D, w.health(), 1.0E-9D);

        // Inside the window: an equal hit is refused outright.
        assertFalse("an equal hit inside the window is refused", w.damagePlayer(3.0D, zombie));
        assertEquals(20.0D - 3.0D, w.health(), 1.0E-9D);

        // A SMALLER hit is refused too, not just an equal one.
        assertFalse("a smaller hit inside the window is refused", w.damagePlayer(1.0D, zombie));
        assertEquals(20.0D - 3.0D, w.health(), 1.0E-9D);

        // A bigger one lands the difference, and `lastDamage` remembers the size that ARRIVED,
        // so a third 5.0 is again refused rather than being compared against the 2.0 that landed.
        assertTrue("a bigger hit inside the window lands", w.damagePlayer(5.0D, zombie));
        assertEquals(20.0D - 3.0D - 2.0D, w.health(), 1.0E-9D);
        assertFalse("lastDamage is the size that arrived, not the part that landed",
                w.damagePlayer(5.0D, zombie));
        assertEquals(20.0D - 3.0D - 2.0D, w.health(), 1.0E-9D);
    }

    /**
     * The end-to-end claim: a mob spawned in range ends the run with less health, and the loss is
     * exactly its flat {@code attackDamage} per landed swing -- {@code EntityMob.attackEntityAsMob}
     * adds no enchantment modifier here because this world has none, which is a declared gap.
     */
    @Test
    public void aMobThatActsTakesTheHealthTheAttributeSaysItDoes() {
        SimWorld w = flat();
        EvalHarness h = new EvalHarness(w);
        SimMob zombie = w.spawnMob("zombie", 0.5D, 64.0D, 3.5D);
        assertNotNull(zombie);

        double before = w.health();
        assertEquals("nothing has happened yet", 20.0D, before, 1.0E-9D);

        h.ticks(120);

        assertTrue("the mob actually got in range and swung; hits=" + zombie.hits,
                zombie.hits >= 2);
        assertEquals("every landed swing costs the flat attribute, and no swing is free",
                before - 3.0D * zombie.hits, w.health(), 1.0E-9D);
        assertTrue("the player's health is a world fact, not a flag", w.health() < before);
    }

    /**
     * A mob that stands still and "attacks" would pass the health assertion in a world where it
     * never closed, so the closing is measured on its own.
     */
    @Test
    public void theMobWalksTowardThePlayerAndRepathsOnItsOwnDutyCycle() {
        SimWorld w = flat();
        EvalHarness h = new EvalHarness(w);
        SimMob zombie = w.spawnMob("zombie", 0.5D, 64.0D, 8.5D);
        assertNotNull(zombie);

        double before = Math.hypot(zombie.body.x - w.posX(), zombie.body.z - w.posZ());
        h.ticks(60);

        double after = Math.hypot(zombie.body.x - w.posX(), zombie.body.z - w.posZ());
        assertTrue("a zombie at 8 blocks closes to " + after + " from " + before,
                after < before - 3.0D);
        assertTrue("the path was re-issued on the duty cycle, refreshes=" + zombie.pathRefreshes,
                zombie.pathRefreshes >= 1);
    }

    /**
     * A type this substrate has no transcription for is refused rather than stood in for: a
     * "fight the creeper" task scored against something that walks at you and hits for 2 would be
     * measuring the absence of a transcription while looking like a combat test.
     */
    @Test
    public void aTypeWithNoTranscriptionIsRefusedRatherThanImprovised() {
        SimWorld w = flat();
        assertNull("no creeper AI is transcribed here", w.spawnMob("creeper", 0.5D, 64.0D, 3.5D));
        assertTrue("zombie and spider are the two that are", SimMob.kinds().contains("zombie")
                && SimMob.kinds().contains("spider"));
        assertTrue("and nothing claimed to act", w.mobs().isEmpty());
    }
}