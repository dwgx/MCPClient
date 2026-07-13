package net.marcloud.mcp.dwm.component.material;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Non-vacuous: wrong expand/fade timing or missing animating flags fail these.
 */
public class RippleStateTest {

    @Test
    public void pressStartsAliveAndAnimating() {
        RippleState r = new RippleState();
        assertFalse(r.animating());
        assertFalse(r.visible());

        r.press(10f, 12f);
        r.setMaxRadius(100f);

        assertTrue(r.holding());
        assertTrue(r.visible());
        assertTrue(r.animating());
        assertEquals(10f, r.originX(), 0.001f);
        assertEquals(12f, r.originY(), 0.001f);
        assertEquals(RippleState.PEAK_ALPHA, r.alpha(), 0.001f);
        // Just pressed: radius still near 0 before tick.
        assertEquals(0f, r.radius(), 0.001f);
    }

    @Test
    public void tickExpandsRadiusWithEaseOut() {
        RippleState r = new RippleState();
        r.press(0f, 0f);
        r.setMaxRadius(100f);

        r.tick(RippleState.EXPAND_SECONDS * 0.5f);
        float mid = r.radius();
        // ease-out cubic at t=0.5 is 1 - 0.125 = 0.875 * max
        float expectedMid = RippleState.easeOutCubic(0.5f) * 100f;
        assertEquals(expectedMid, mid, 0.5f);
        assertTrue("mid expand should exceed linear 50", mid > 50f);
        assertTrue(r.animating());

        r.tick(RippleState.EXPAND_SECONDS);
        assertEquals(100f, r.radius(), 0.5f);
        // Still holding, fully expanded: not animating expand, not fading.
        assertFalse("fully expanded while held should not animate", r.animating());
    }

    @Test
    public void releaseFadesOutAndStops() {
        RippleState r = new RippleState();
        r.press(5f, 5f);
        r.setMaxRadius(50f);
        r.tick(RippleState.EXPAND_SECONDS);
        assertEquals(RippleState.PEAK_ALPHA, r.alpha(), 0.001f);

        r.release();
        assertFalse(r.holding());
        assertTrue(r.animating());

        r.tick(RippleState.FADE_SECONDS * 0.5f);
        assertTrue(r.alpha() < RippleState.PEAK_ALPHA);
        assertTrue(r.alpha() > 0f);

        r.tick(RippleState.FADE_SECONDS);
        assertEquals(0f, r.alpha(), 0.001f);
        assertFalse(r.visible());
        assertFalse(r.animating());
    }

    @Test
    public void reentrantPressWhileHoldingDoesNotRestart() {
        RippleState r = new RippleState();
        r.press(1f, 1f);
        r.setMaxRadius(80f);
        r.tick(RippleState.EXPAND_SECONDS * 0.4f);
        float radiusBefore = r.radius();

        r.press(99f, 99f); // must ignore
        assertEquals(1f, r.originX(), 0.001f);
        assertEquals(radiusBefore, r.radius(), 0.001f);
    }

    @Test
    public void coverageRadiusIsFarthestCorner() {
        // origin top-left of 100x50 box -> farthest is bottom-right
        float r = RippleState.coverageRadius(0f, 0f, 100f, 50f);
        assertEquals(Math.hypot(100, 50), r, 0.01f);
    }
}
