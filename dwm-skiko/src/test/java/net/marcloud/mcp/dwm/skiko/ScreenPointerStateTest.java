package net.marcloud.mcp.dwm.skiko;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.dwm.backend.FrameInput;
import org.junit.Test;

/**
 * Headless coverage for {@link ScreenPointerState} — the pointer/button state machine that
 * bridges MC's discrete GuiScreen callbacks to the DWM {@link FrameInput}. These are the
 * exact behaviours the launcher's hit-testing depends on: button bits latch across the
 * press/release event gap, and MC's ScaledResolution coords convert to the framebuffer pixel
 * space the P1 layout uses. A regression that drops the latch or mis-scales would break every
 * click, so each assertion fails loudly on the old/wrong behaviour.
 */
public class ScreenPointerStateTest {

    private static final int PRIMARY = 1;      // bit 0
    private static final int SECONDARY = 1 << 1; // bit 1

    @Test
    public void primaryButtonLatchesBetweenPressAndReleaseEvents() {
        ScreenPointerState s = new ScreenPointerState();
        assertEquals("no button down initially", 0, s.buttonMask());

        s.press(0);
        // The bit MUST stay set across frames until release — MaterialButton edge-detects
        // press-then-release, so a mask that cleared itself would never register a click.
        assertEquals("primary latched after press", PRIMARY, s.buttonMask() & PRIMARY);
        assertEquals("primary still latched on a later frame", PRIMARY, s.buttonMask() & PRIMARY);

        s.release(0);
        assertEquals("primary cleared after release", 0, s.buttonMask() & PRIMARY);
    }

    @Test
    public void secondaryButtonIsIndependentOfPrimary() {
        ScreenPointerState s = new ScreenPointerState();
        s.press(0);
        s.press(1);
        assertEquals("both buttons latched", PRIMARY | SECONDARY, s.buttonMask());
        s.release(0);
        assertEquals("releasing primary leaves secondary down", SECONDARY, s.buttonMask());
    }

    @Test
    public void negativeButtonIsIgnored() {
        ScreenPointerState s = new ScreenPointerState();
        s.press(-1); // MC uses -1 for "no button" in drag events — must not corrupt the mask
        assertEquals("negative button index ignored", 0, s.buttonMask());
    }

    @Test
    public void scaledCoordsConvertToPixelsByScaleFactor() {
        // MC computes scaled = pixel / scaleFactor; the inverse pixel = scaled * scaleFactor
        // must land the pointer where a pixel-space layout expects it.
        assertEquals("scale 2 doubles", 200f, ScreenPointerState.scaledToPixel(100, 2), 1e-6);
        assertEquals("scale 3 triples", 300f, ScreenPointerState.scaledToPixel(100, 3), 1e-6);
        assertEquals("scale 1 identity", 100f, ScreenPointerState.scaledToPixel(100, 1), 1e-6);
    }

    @Test
    public void nonPositiveScaleFactorCoercesToOneNeverZeroesCoords() {
        // A zero scale factor (shouldn't happen, but must be safe) must NOT collapse the
        // pointer to the origin — that would make everything hit-test at (0,0).
        assertEquals("scale 0 -> identity", 100f, ScreenPointerState.scaledToPixel(100, 0), 1e-6);
        assertEquals("negative -> identity", 100f, ScreenPointerState.scaledToPixel(100, -4), 1e-6);
    }

    @Test
    public void frameInputCarriesPointerButtonsAndScroll() {
        ScreenPointerState s = new ScreenPointerState();
        s.moveTo(42f, 84f);
        s.press(0);
        FrameInput in = s.toFrameInput(2.5f);
        assertEquals(42f, in.pointerX(), 1e-6);
        assertEquals(84f, in.pointerY(), 1e-6);
        assertTrue("primary present in FrameInput", (in.buttonMask() & PRIMARY) != 0);
        assertEquals("scroll notches carried through", 2.5f, in.scrollY(), 1e-6);
        // The pointer-state helper never injects key events (keyTyped feeds those separately),
        // so its FrameInput must carry an empty, non-null key list.
        assertTrue("pointer state contributes no key events", in.keyEvents().isEmpty());
    }
}
