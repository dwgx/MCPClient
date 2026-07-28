package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import net.marcloud.mcp.dwm.ui.UiWindowHost;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * The three caption buttons must do the three different things they claim to.
 *
 * <p>All three were previously inert but for hover feedback, on the stated grounds that dwm has no
 * OS window to act on. That is true of minimise and false of maximise — the shell owns its own
 * geometry — and it was never true of close, whose {@code closeRequested} signal was declared and
 * then wired to nothing at all: clicking the X did nothing.
 *
 * <p>The division under test is the one Windows draws. A caption button sends a
 * {@code WM_SYSCOMMAND} verb; the window manager carries it out. So maximize/restore is asserted as
 * a state change inside the scene (the geometry is the shell's own), while minimise and close are
 * asserted as requests that REACH THE HOST — and the host is what decides they differ.
 */
public class CaptionButtonsLiveIT {

    private static final String SCENE = "dwm/Shell.qml";

    /** Records which verbs arrived, so a signal wired to nothing cannot pass. */
    private static final class RecordingHost implements UiWindowHost {
        int minimizeCalls;
        int closeCalls;

        @Override
        public void minimize() {
            minimizeCalls++;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    /**
     * Maximize fills the available area; restore returns to the exact prior extent.
     *
     * <p>"Exact" is the assertion that matters: restore has to put back the extent that was in
     * force, which means the shell has to have REMEMBERED it — Windows keeps it in
     * {@code WINDOWPLACEMENT.rcNormalPosition} for the same reason. A restore that recomputed a
     * default would look right on the default page and be wrong after any resize.
     */
    @Test
    public void maximizeFillsTheScreenAndRestoreReturnsToTheExactPriorExtent() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open(null);
            QmlView view = viewOf(surface);
            Item window = byName(view, "window");
            Item nav = byName(view, "nav");

            // Resized away from the scene's declared default FIRST. Without this the test cannot
            // tell "restore remembered the extent" from "restore reset to the default", because
            // they coincide: measured, replacing the remembered values with a hardcoded 560x380 --
            // exactly the shape of that bug -- left this test passing. A restore that recomputes a
            // default is only wrong once the window has been some OTHER size.
            setNumber(window, "contentWidth", 480);
            setNumber(window, "contentHeight", 300);
            frame(surface);

            float normalWidth = window.width.peekFloat();
            float normalHeight = window.height.peekFloat();
            float navNormalWidth = nav.width.peekFloat();
            assertEquals("precondition: the window starts in the normal state",
                "normal", peek(window, "windowState"));
            assertTrue("precondition: the window must be smaller than the screen",
                normalWidth < view.root().width.peekFloat());
            assertEquals("precondition: the resize must have taken effect, or the assertion below "
                    + "compares the default against itself", 480.0F, normalWidth, 0.01F);

            invoke(window, "toggleMaximize");
            frame(surface);

            assertEquals("maximizing must record the state", "maximized",
                peek(window, "windowState"));
            assertTrue("a maximized window must be wider than it was: " + normalWidth + " -> "
                    + window.width.peekFloat(), window.width.peekFloat() > normalWidth);
            assertTrue("and the content must grow with it, not just the frame: nav stayed at "
                    + nav.width.peekFloat(), nav.width.peekFloat() > navNormalWidth);

            invoke(window, "toggleMaximize");
            frame(surface);

            assertEquals("restoring must record the state", "normal",
                peek(window, "windowState"));
            assertEquals("restore must return the EXACT prior width, not a recomputed default",
                normalWidth, window.width.peekFloat(), 0.01F);
            assertEquals("and the exact prior height",
                normalHeight, window.height.peekFloat(), 0.01F);

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * A maximized window must square its corners.
     *
     * <p>Not decoration: the published Windows 11 geometry guidance puts the radius at 0 "where the
     * window is snapped or maximized", because a rounded corner flush with the screen edge shows
     * what is behind it through the arc.
     */
    @Test
    public void aMaximizedWindowSquaresItsCorners() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open(null);
            QmlView view = viewOf(surface);
            Item window = byName(view, "window");

            Item shell = firstRectangle(window);
            assertNotNull("the window must have a surface rectangle", shell);
            assertTrue("a normal window must be rounded", radiusOf(shell) > 0);

            invoke(window, "toggleMaximize");
            frame(surface);
            assertEquals("a maximized window must have square corners", 0.0F, radiusOf(shell),
                0.01F);

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * Minimise and close must both reach the host, and must be DIFFERENT verbs.
     *
     * <p>The second half is the point. Two buttons that both call close are indistinguishable from
     * the state this replaced, where one of them did nothing — so the host records each separately
     * and the test insists exactly one of its counters moved per click.
     *
     * <p>Driven by CLICKING the buttons at their real coordinates rather than by emitting a signal.
     * That is not merely more realistic, it is the only version that tests anything: the first
     * attempt emitted {@code minimizeRequested} and passed nothing to the host, because on qml4j
     * 0.2.24 a component's generated class does not implement {@code SignalRelay} and an
     * {@code on<Signal>} handler written in the ENCLOSING document is dropped silently. The buttons
     * now address the host directly, and a click is what proves it.
     */
    @Test
    public void minimizeAndCloseReachTheHostAsDistinctVerbs() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            RecordingHost host = new RecordingHost();
            surface = open(host);
            QmlView view = viewOf(surface);
            Item window = byName(view, "window");

            clickCaptionButton(surface, window, 3);
            assertEquals("clicking minimise must reach the host", 1, host.minimizeCalls);
            assertEquals("and must NOT be routed to close -- two buttons that both close are "
                + "indistinguishable from the state this replaced, where one did nothing",
                0, host.closeCalls);

            clickCaptionButton(surface, window, 1);
            assertEquals("clicking close must reach the host", 1, host.closeCalls);
            assertEquals("and must not also minimise", 1, host.minimizeCalls);

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * Click the nth caption button counting from the right edge (1 = close, 2 = maximize, 3 = min).
     *
     * <p>Coordinates come from the window's live geometry and its own {@code captionWidth}, so the
     * test cannot drift from the layout — the rule the navigation probe established after hardcoded
     * row positions produced two false failures.
     */
    private static void clickCaptionButton(QmlUiSurface surface, Item window, int fromRight)
            throws Exception {
        float captionWidth = numberProp(window, "captionWidth");
        float barHeight = numberProp(window, "titleBarHeight");
        float centreX = absX(window) + window.width.peekFloat()
            - (captionWidth * (fromRight - 0.5F));
        float centreY = absY(window) + (barHeight / 2);

        float scale = scaleOf(surface);
        surface.pointerDown(centreX * scale, centreY * scale, 0);
        surface.pointerUp(centreX * scale, centreY * scale, 0);
    }

    private static float absX(Item item) {
        float x = 0;
        for (Item cur = item; cur != null; cur = cur.parent.peek()) {
            x += cur.x.peekFloat();
        }
        return x;
    }

    private static float absY(Item item) {
        float y = 0;
        for (Item cur = item; cur != null; cur = cur.parent.peek()) {
            y += cur.y.peekFloat();
        }
        return y;
    }

    private static float scaleOf(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("uiScale");
        f.setAccessible(true);
        float s = (Float) f.get(surface);
        return s > 0.0F ? s : 1.0F;
    }

    /**
     * The maximize button's glyph must change with the state.
     *
     * <p>A maximized window offers RESTORE, not maximize. Keeping one glyph is the usual tell of a
     * hand-built caption bar: the button goes on advertising an action it no longer performs.
     */
    @Test
    public void theMaximizeGlyphBecomesARestoreGlyphWhenMaximized() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open(null);
            QmlView view = viewOf(surface);
            Item window = byName(view, "window");

            String normalGlyph = maximizeGlyph(window);
            assertNotNull("the maximize button must have a glyph", normalGlyph);

            invoke(window, "toggleMaximize");
            frame(surface);
            String maximizedGlyph = maximizeGlyph(window);

            assertTrue("the glyph must change when maximized; it stayed \"" + normalGlyph + "\"",
                !normalGlyph.equals(maximizedGlyph));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    /**
     * The maximize button's glyph text.
     *
     * <p>Found by position rather than by name: the caption buttons are unnamed, and the maximize
     * one is the middle of the three, i.e. the Text whose parent sits two button-widths from the
     * right edge. Reading it off the live scene rather than the QML source is the same rule the
     * navigation probe learned — a value from the file proves what was written, not what is used.
     */
    private static String maximizeGlyph(Item window) {
        float captionWidth = numberProp(window, "captionWidth");
        float expectedX = window.width.peekFloat() - (captionWidth * 2);
        for (Item child : window.children) {
            if (Math.abs(child.x.peekFloat() - expectedX) < 0.5F
                && Math.abs(child.width.peekFloat() - captionWidth) < 0.5F) {
                for (Item grand : child.children) {
                    if (grand.getClass().getSimpleName().equals("Text")) {
                        Object text = peekField(grand, "text");
                        return text == null ? null : text.toString();
                    }
                }
            }
        }
        return null;
    }

    /** The window's own surface rectangle: the first full-size Rectangle child. */
    private static Item firstRectangle(Item window) {
        for (Item child : window.children) {
            if (child.getClass().getSimpleName().equals("Rectangle")
                && Math.abs(child.width.peekFloat() - window.width.peekFloat()) < 0.5F) {
                return child;
            }
        }
        return null;
    }

    private static float radiusOf(Item rectangle) {
        Object value = peekField(rectangle, "radius");
        return value == null ? -1 : ((Number) value).floatValue();
    }

    private static float numberProp(Item item, String name) {
        Object value = peekField(item, name);
        assertNotNull("item must declare " + name, value);
        return ((Number) value).floatValue();
    }

    /** Write a numeric QML property, so the test can move the window off its declared default. */
    private static void setNumber(Item item, String name, int value) throws Exception {
        Object property = item.getClass().getField(name).get(item);
        property.getClass().getMethod("set", Object.class).invoke(property, Integer.valueOf(value));
    }

    private static Object peekField(Item item, String name) {
        try {
            Object property = item.getClass().getField(name).get(item);
            return property.getClass().getMethod("peek").invoke(property);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String peek(Item item, String name) {
        Object value = peekField(item, name);
        return value == null ? null : value.toString();
    }

    /** Call a QML function declared on an item. */
    private static void invoke(Item item, String function) throws Exception {
        Object callable = item.getClass()
            .getMethod("__getFunction", String.class).invoke(item, function);
        assertNotNull("the item must declare the function " + function, callable);
        callable.getClass().getMethod("call", Object[].class)
            .invoke(callable, (Object) new Object[0]);
    }

    private static QmlUiSurface open(UiWindowHost host) {
        QmlUiSurface surface = new QmlUiSurface(SCENE, host);
        assertTrue("scene must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        frame(surface);
        return surface;
    }

    private static void frame(QmlUiSurface surface) {
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("view");
        f.setAccessible(true);
        return (QmlView) f.get(surface);
    }

    private static Item byName(QmlView view, String objectName) {
        Item hit = view.findByObjectName(objectName);
        assertNotNull("the scene must contain an item named " + objectName, hit);
        return hit;
    }

    private static void closeQuietly(QmlUiSurface surface) {
        if (surface != null) {
            surface.close();
        }
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(760, 560));
            Display.create();
            Display.update();
            return true;
        } catch (Throwable t) {
            System.out.println("[IT] no display (" + t + ") — skipping");
            return false;
        }
    }

    private static void destroyDisplay() {
        try {
            Display.destroy();
        } catch (Throwable ignored) {
            // Teardown of an already-dead display is not actionable.
        }
    }
}
