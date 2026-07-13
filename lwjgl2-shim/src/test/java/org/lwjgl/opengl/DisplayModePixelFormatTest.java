package org.lwjgl.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless value-semantics tests for {@link DisplayMode} and {@link PixelFormat}.
 * MC constructs {@code new PixelFormat().withDepthBits(24)} at window creation and
 * compares/sorts DisplayModes from the enumerated mode list, so the LWJGL2
 * immutability + equality contract must hold exactly.
 */
public class DisplayModePixelFormatTest {

    // ---- DisplayMode ----

    @Test
    public void twoArgModeIsWindowedNotFullscreenCapable() {
        DisplayMode m = new DisplayMode(854, 480);
        assertEquals(854, m.getWidth());
        assertEquals(480, m.getHeight());
        assertEquals(0, m.getBitsPerPixel());
        assertEquals(0, m.getFrequency());
        assertFalse("2-arg mode is windowed => not fullscreen capable", m.isFullscreenCapable());
    }

    @Test
    public void fourArgModeIsFullscreenCapable() {
        DisplayMode m = new DisplayMode(1920, 1080, 32, 60);
        assertEquals(32, m.getBitsPerPixel());
        assertEquals(60, m.getFrequency());
        assertTrue("4-arg mode carries depth/freq => fullscreen capable", m.isFullscreenCapable());
    }

    @Test
    public void displayModeEqualityAndHashByAllFourFields() {
        DisplayMode a = new DisplayMode(800, 600, 32, 60);
        DisplayMode b = new DisplayMode(800, 600, 32, 60);
        DisplayMode c = new DisplayMode(800, 600, 32, 75);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals("differing frequency => not equal", a, c);
    }

    // ---- PixelFormat ----

    @Test
    public void defaultPixelFormatMatchesLwjgl2Defaults() {
        PixelFormat pf = new PixelFormat();
        assertEquals("default bpp = 0 (use desktop)", 0, pf.getBitsPerPixel());
        assertEquals("default alpha = 8", 8, pf.getAlphaBits());
        assertEquals("default depth = 0", 0, pf.getDepthBits());
        assertEquals("default stencil = 0", 0, pf.getStencilBits());
        assertEquals("default samples = 0", 0, pf.getSamples());
    }

    /** The exact call MC makes: new PixelFormat().withDepthBits(24). */
    @Test
    public void withDepthBitsProducesImmutableCopy() {
        PixelFormat base = new PixelFormat();
        PixelFormat withDepth = base.withDepthBits(24);
        assertNotSame("with* returns a new instance", base, withDepth);
        assertEquals("copy carries the new depth", 24, withDepth.getDepthBits());
        assertEquals("original is unchanged (immutable)", 0, base.getDepthBits());
        // Unrelated fields are preserved on the copy.
        assertEquals(8, withDepth.getAlphaBits());
    }

    @Test
    public void withBuildersChainAndPreserveOtherFields() {
        PixelFormat pf = new PixelFormat()
                .withDepthBits(24)
                .withStencilBits(8)
                .withSamples(4)
                .withAlphaBits(8);
        assertEquals(24, pf.getDepthBits());
        assertEquals(8, pf.getStencilBits());
        assertEquals(4, pf.getSamples());
        assertEquals(8, pf.getAlphaBits());
    }
}
