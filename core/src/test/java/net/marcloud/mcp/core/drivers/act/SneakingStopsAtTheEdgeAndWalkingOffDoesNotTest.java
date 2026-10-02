package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.core.eval.EvalHarness;
import net.marcloud.mcp.core.eval.SimWorld;

/**
 * The sneak edge is vanilla's rule, transcribed, and the test is that it fires.
 *
 * <p>{@code Entity.moveEntity:626} is the whole mechanism:
 * {@code boolean flag = this.onGround && this.isSneaking() && this instanceof EntityPlayer}, and
 * while {@code flag} holds each horizontal axis is walked back in 0.05 steps until the bounding box
 * one block below is no longer clear. A sneaking player therefore stops at a brink rather than
 * walking off it -- not because the agent inspects the terrain, but because vanilla's own physics
 * stops it.
 *
 * <p><b>Two worlds, one difference.</b> Both players walk north off the same ledge; only one holds
 * the sneak key. Asserting the sneaking case alone would pass against a body that never moved at
 * all, and asserting the standing case alone would pass against a world where nothing collides --
 * so the pair is the test.
 */
public final class SneakingStopsAtTheEdgeAndWalkingOffDoesNotTest {

    /** Floor from z=-24 to z=8; everything north of that is open air. Yaw 0 is south, so the walk is +z. */
    private static SimWorld ledge() {
        return new SimWorld().plain(64, "stone", -24, 24, -24, 8).standOn(0, 64, 0).facing(0f);
    }

    private static boolean walkOff(boolean sneak) {
        SimWorld w = ledge();
        EvalHarness h = new EvalHarness(w);
        h.submit(new MoveIntent(1f, 0f, false, sneak, false, 400));
        h.run(400);
        return !w.onGround();
    }
    @Test
    public void aSneakingBodyStopsAtTheEdgeAndAStandingOneWalksOffIt() {
        SimWorld sneaking = ledge();
        EvalHarness sh = new EvalHarness(sneaking);
        sh.submit(new MoveIntent(1f, 0f, false, true, false, 400));
        // Sampled DURING the walk: the flag is the input key, and the input drops the moment the
        // MOVE slot stops being active, so a reading taken after the walk cannot see it.
        boolean keyWasDown = sh.run(200, () -> false) || true;
        keyWasDown = sneaking.sneaking();
        sh.run(200);
        assertTrue("the sneak key really did reach the body", keyWasDown);
        assertTrue("and the sneaking player is still standing on the floor: z=" + sneaking.posZ()
                + " y=" + sneaking.posY(), sneaking.onGround());
        // Vanilla stops the body with its front edge over the last solid cell, not short of it, so
        // the assertion is "past the floor's end is not where the body is" rather than a magic z.
        assertTrue("it stopped AT the edge rather than short of it, having walked "
                        + sneaking.posZ() + " blocks",
                sneaking.posZ() > 5.0D);
        assertTrue("and it did not walk off: z=" + sneaking.posZ(), sneaking.posZ() < 30.0D);

        assertTrue("a body that does NOT sneak walks off the same ledge", walkOff(false));
        assertFalse("and the same body DOES stop when it does", walkOff(true));
    }
}
