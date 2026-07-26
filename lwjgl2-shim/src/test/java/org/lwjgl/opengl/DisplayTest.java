package org.lwjgl.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless regression tests for the pure-state surface of the {@link Display}
 * shim — the parts that run without ever opening a GLFW window. Anything that
 * touches GLFW (create/update/fullscreen transitions/monitor queries) is
 * RUNTIME_ONLY and deliberately not exercised here.
 *
 * <p>Each test sets its own precondition rather than assuming pristine statics,
 * because Display holds mutable static state shared across the JVM and JUnit does
 * not guarantee method order. The one invariant we can rely on: {@code create()}
 * is never called in this suite, so the window handle stays at the -1 sentinel
 * and {@code isCreated()} stays false throughout.</p>
 */
public class DisplayTest {

    @Test
    public void notCreatedWithoutWindow() {
        assertFalse("no window opened => not created", Display.isCreated());
        assertEquals("LWJGL2 ABI sentinel for 'no window' is -1", -1L, Display.getWindowHandle());
        assertEquals("getWindow alias must match handle", -1L, Display.getWindow());
    }

    @Test
    public void setDisplayModeUpdatesReportedDimensions() {
        Display.setDisplayMode(new DisplayMode(1280, 720));
        assertEquals(1280, Display.getWidth());
        assertEquals(720, Display.getHeight());
    }

    @Test
    public void setDisplayModeClampsToAtLeastOne() {
        Display.setDisplayMode(new DisplayMode(0, 0));
        assertTrue("width clamped to >=1", Display.getWidth() >= 1);
        assertTrue("height clamped to >=1", Display.getHeight() >= 1);
    }

    @Test
    public void setDisplayModeNullIsIgnored() {
        Display.setDisplayMode(new DisplayMode(640, 480));
        Display.setDisplayMode(null);
        assertEquals("null mode must not change width", 640, Display.getWidth());
        assertEquals("null mode must not change height", 480, Display.getHeight());
    }

    /** setDisplayMode sets the resized flag; wasResized latches then clears it. */
    @Test
    public void wasResizedLatchesThenClears() {
        Display.setDisplayMode(new DisplayMode(800, 600));
        assertTrue("dirty after setDisplayMode", Display.wasResized());
        assertFalse("cleared after consumption", Display.wasResized());
    }

    /** getDisplayMode with no window/GLFW init reflects the last setDisplayMode. */
    @Test
    public void getDisplayModeReflectsSetMode() {
        Display.setDisplayMode(new DisplayMode(1024, 768));
        DisplayMode m = Display.getDisplayMode();
        // May be the stored currentMode (headless) or a live-derived mode; either
        // way it must carry the dimensions we just set.
        assertEquals(1024, m.getWidth());
        assertEquals(768, m.getHeight());
    }

    /**
     * Pre-create setters that guard on {@code isCreated()} must not throw and must
     * not spawn a window. ({@code setVSyncEnabled} is intentionally excluded — it
     * calls GLFW unconditionally and so is RUNTIME_ONLY, needing native GLFW.)
     */
    @Test
    public void preCreateSettersAreSafe() {
        Display.setTitle("MCP test");
        Display.setTitle(null); // null coalesces to "" — no NPE
        Display.setResizable(true);
        Display.setResizable(false);
        assertFalse("setters must not create a window", Display.isCreated());
    }

    /** setFullscreen before create only defers; never throws, never creates. */
    @Test
    public void setFullscreenBeforeCreateDefers() {
        Display.setFullscreen(true);
        assertFalse(Display.isCreated());
        Display.setFullscreen(false); // reset the deferred flag
        assertFalse(Display.isCreated());
    }

    @Test
    public void setIconNullReturnsZero() {
        assertEquals(0, Display.setIcon(null));
    }

    /** Focus defaults to false until a window gains it (RUNTIME_ONLY to flip). */
    @Test
    public void notActiveWithoutWindow() {
        assertFalse(Display.isActive());
    }

    /** sync with non-positive fps is a documented no-op and must return promptly. */
    @Test
    public void syncNonPositiveIsNoOp() {
        Display.sync(0);
        Display.sync(-30);
        // Reaching here without hanging is the assertion.
        assertTrue(true);
    }

    /** update() on a non-created display is a safe no-op (guards on isCreated). */
    @Test
    public void updateWithoutWindowIsNoOp() {
        Display.update();
        assertFalse(Display.isCreated());
    }

    /** isCloseRequested is false when there is no window to close. */
    @Test
    public void closeNotRequestedWithoutWindow() {
        assertFalse(Display.isCloseRequested());
    }
}
