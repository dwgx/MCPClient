package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-logic teeth for {@link ScrollState}: wheel input moves the offset, the offset clamps
 * to {@code [0, content-viewport]}, and content that fits never scrolls. No GL/context.
 */
public class ScrollStateTest {

    @Test
    public void contentThatFitsNeverScrolls() {
        ScrollState s = new ScrollState();
        // viewport 200 >= content 100 -> nothing to scroll, any wheel is pinned to 0.
        assertEquals(0f, s.apply(-5f, 100f, 200f), 0.001f);
        assertEquals(0f, s.apply(5f, 100f, 200f), 0.001f);
        assertEquals(0f, s.offset(), 0.001f);
    }

    @Test
    public void wheelDownIncreasesOffsetUpToMax() {
        ScrollState s = new ScrollState();
        // content 1000, viewport 300 -> max offset 700. Wheel DOWN = negative notches.
        float after = s.apply(-1f, 1000f, 300f);
        assertTrue("wheel down scrolls content up (offset > 0)", after > 0f);
        // Many down notches clamp at the max, never beyond.
        for (int i = 0; i < 50; i++) {
            s.apply(-1f, 1000f, 300f);
        }
        assertEquals("offset clamps to content-viewport", 700f, s.offset(), 0.001f);
    }

    @Test
    public void wheelUpNeverGoesNegative() {
        ScrollState s = new ScrollState();
        s.apply(-3f, 1000f, 300f);         // scroll down a bit
        assertTrue(s.offset() > 0f);
        for (int i = 0; i < 50; i++) {
            s.apply(1f, 1000f, 300f);      // scroll up past the top
        }
        assertEquals("offset never goes below 0", 0f, s.offset(), 0.001f);
    }

    @Test
    public void clampToShrinksOffsetWhenContentShrinks() {
        ScrollState s = new ScrollState();
        for (int i = 0; i < 50; i++) {
            s.apply(-1f, 1000f, 300f);     // pin to max 700
        }
        assertEquals(700f, s.offset(), 0.001f);
        // Content shrinks (e.g. a filter hides rows) -> re-clamp pulls the offset in.
        float reclamped = s.clampTo(400f, 300f); // new max 100
        assertEquals(100f, reclamped, 0.001f);
        assertEquals(100f, s.offset(), 0.001f);
    }

    @Test
    public void notAnimatingAndTickIsNoOp() {
        ScrollState s = new ScrollState();
        s.tick(0.016f);
        assertTrue("scroll state carries no timeline", !s.animating());
    }
}
