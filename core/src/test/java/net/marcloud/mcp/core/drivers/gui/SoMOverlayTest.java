package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.Test;

/**
 * Headless GEOMETRY tests for the Set-of-Marks GUI overlay. No live GL context is
 * available in tests, so we feed a SYNTHETIC solid-colour {@link BufferedImage}
 * plus synthetic {@link GuiElement}s and assert that:
 *
 * <ul>
 *   <li>a box outline lands on the element's bounds mapped by {@code scaleFactor}
 *       (GUI space × scaleFactor = framebuffer px), so the mark aligns with the
 *       element it labels;</li>
 *   <li>the interior of a marked element is left visible (outline-only, not
 *       filled/hidden);</li>
 *   <li>an unmarked region of the frame is untouched;</li>
 *   <li>the raw input frame is never mutated.</li>
 * </ul>
 *
 * <p>These fail against the absence of the overlay (a plain copy would leave the
 * element edges the background colour) and against a wrong scaleFactor transform
 * (the mark would land at the un-scaled coordinate instead).
 */
public class SoMOverlayTest {

    private static final int BG = 0x202020; // dark grey background frame

    private static GuiElement button(String id, int x, int y, int w, int h) {
        return new GuiElement(id, GuiElement.KIND_BUTTON, GuiElement.ROLE_PUSHBUTTON,
                "label-" + id, "", new Bounds(x, y, w, h),
                new Point(x + w / 2, y + h / 2), null, List.of("click"), null);
    }

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                img.setRGB(xx, yy, rgb);
            }
        }
        return img;
    }

    /** True if any pixel on the rectangle's perimeter differs from the background. */
    private static boolean perimeterHasMark(BufferedImage img, int x, int y, int w, int h) {
        boolean marked = false;
        for (int xx = x; xx <= x + w; xx++) {
            marked |= isNonBg(img, xx, y);
            marked |= isNonBg(img, xx, y + h);
        }
        for (int yy = y; yy <= y + h; yy++) {
            marked |= isNonBg(img, x, yy);
            marked |= isNonBg(img, x + w, yy);
        }
        return marked;
    }

    private static boolean isNonBg(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return false;
        }
        return (img.getRGB(x, y) & 0xFFFFFF) != BG;
    }

    /**
     * scaleFactor=2: a GUI-space bounds at (10,20,100,20) must be outlined at
     * framebuffer (20,40,200,40). The perimeter there is painted; the same
     * un-scaled coordinate box is NOT the drawn location.
     */
    @Test
    public void boxLandsAtBoundsTimesScaleFactor() {
        BufferedImage frame = solid(400, 300, BG);
        GuiElement b0 = button("b0", 10, 20, 100, 20);
        Viewport vp = GuiSnapshotService.viewport(200, 150, 2, 400, 300);

        BufferedImage marked = SoMOverlay.annotate(frame, List.of(b0), vp);

        // Mark present at the scaled rectangle.
        assertTrue("outline expected at bounds*scaleFactor (20,40,200,40)",
                perimeterHasMark(marked, 20, 40, 200, 40));
    }

    /**
     * Proves the transform actually uses scaleFactor: with sf=1 the very same
     * element outlines at its raw GUI coordinates, and the sf=2 location is where
     * a naive (unscaled) implementation would WRONGLY have drawn for sf=2.
     */
    @Test
    public void scaleFactorOneDrawsAtRawCoords() {
        BufferedImage frame = solid(400, 300, BG);
        GuiElement b0 = button("b0", 10, 20, 100, 20);
        Viewport vp = GuiSnapshotService.viewport(400, 300, 1, 400, 300);

        BufferedImage marked = SoMOverlay.annotate(frame, List.of(b0), vp);
        assertTrue("sf=1 outline at raw (10,20,100,20)",
                perimeterHasMark(marked, 10, 20, 100, 20));
    }

    /**
     * Outline-only: the CENTER of a marked element stays the background colour
     * (content not filled/hidden), while its perimeter is painted.
     */
    @Test
    public void outlineDoesNotFillInterior() {
        BufferedImage frame = solid(400, 300, BG);
        GuiElement b0 = button("b0", 50, 50, 80, 60); // sf=1
        Viewport vp = GuiSnapshotService.viewport(400, 300, 1, 400, 300);

        BufferedImage marked = SoMOverlay.annotate(frame, List.of(b0), vp);

        int cx = 50 + 80 / 2;
        int cy = 50 + 60 / 2;
        assertEquals("interior center must remain unfilled background",
                BG, marked.getRGB(cx, cy) & 0xFFFFFF);
        assertTrue("perimeter must be outlined",
                perimeterHasMark(marked, 50, 50, 80, 60));
    }

    /**
     * Two elements each get their own box at their own mapped bounds, and a region
     * with no element is left untouched — so marks are per-element, not global.
     */
    @Test
    public void eachElementMarkedIndependentlyAndEmptyRegionUntouched() {
        BufferedImage frame = solid(400, 300, BG);
        GuiElement b0 = button("b0", 5, 5, 40, 20);
        GuiElement b1 = button("b1", 200, 200, 40, 20);
        Viewport vp = GuiSnapshotService.viewport(400, 300, 1, 400, 300);

        BufferedImage marked = SoMOverlay.annotate(frame, List.of(b0, b1), vp);

        assertTrue(perimeterHasMark(marked, 5, 5, 40, 20));
        assertTrue(perimeterHasMark(marked, 200, 200, 40, 20));
        // A region between the two elements should be background.
        assertEquals(BG, marked.getRGB(120, 120) & 0xFFFFFF);
    }

    /** The raw input frame must not be mutated; a fresh image is returned. */
    @Test
    public void inputFrameIsNotMutated() {
        BufferedImage frame = solid(200, 150, BG);
        GuiElement b0 = button("b0", 10, 10, 50, 30);
        Viewport vp = GuiSnapshotService.viewport(200, 150, 1, 200, 150);

        BufferedImage marked = SoMOverlay.annotate(frame, List.of(b0), vp);

        assertNotSame(frame, marked);
        // Every pixel of the original is still the background colour.
        boolean anyChanged = false;
        for (int y = 0; y < frame.getHeight() && !anyChanged; y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                if ((frame.getRGB(x, y) & 0xFFFFFF) != BG) {
                    anyChanged = true;
                    break;
                }
            }
        }
        assertFalse("input frame must be untouched", anyChanged);
    }
}
