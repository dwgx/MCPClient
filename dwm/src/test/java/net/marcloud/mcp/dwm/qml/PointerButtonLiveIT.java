package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.input.TextField;
import net.marcloud.mcp.dwm.ui.UiKeys;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Proves a pointer press carries the button qml4j means, by clicking a text field into focus.
 *
 * <p>Two button vocabularies meet at this boundary and they disagree on every value. LWJGL2 and
 * GLFW number buttons from zero — {@code Mouse.getEventButton()} returns 0 for left, 1 for right,
 * 2 for middle. qml4j speaks Qt's, where the values are a bitmask and {@code LeftButton} is
 * <b>1</b>, {@code RightButton} 2, {@code MiddleButton} 4 ({@code MouseEvent.LEFT_BUTTON} and
 * {@code Qt.LeftButton} both confirm it). Passing the raw index through means left arrives as
 * {@code NoButton} and right arrives as {@code LeftButton}.
 *
 * <p>Most of the UI hid this. {@code EventDispatcher.hitTestMouseArea} treats button 0 as a
 * wildcard — it skips the {@code acceptedButtons} mask test entirely when the button is 0 — so
 * every Fluent control, all of which are a {@code MouseArea} under the paint, kept working and
 * the live probe's 26 checks stayed green. The paths that compare against the button
 * <em>exactly</em> are the ones that broke: text-field focus, {@code AbstractButton} press, and
 * {@code Flickable} drag-scroll all guard on {@code button == 1}.
 *
 * <p>So this asserts the two directions that pin the mapping, and it takes both to pin it:
 * a left press must focus, and a right press must not. Only the first would pass just as well
 * with the button hardcoded to 1 — which is exactly the shape of the wrong fix.
 *
 * <p>A live IT rather than a unit test because focus follows a hit test, a hit test needs
 * geometry, and geometry comes from a layout pass that measures text through Skija's native
 * font stack.
 */
public class PointerButtonLiveIT {

    /** The scene with a focusable field; no shipped scene has one, which is its own finding. */
    private static final String SCENE = "dwm/test/TextEntry.qml";

    /** TextEntry.qml puts its field at (10,10) 300x40, so this is well inside it. */
    private static final float INSIDE_FIELD_X = 60.0F;
    private static final float INSIDE_FIELD_Y = 30.0F;

    /** LWJGL2 / GLFW button indices, which is what the SPI receives. */
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    /**
     * The whole path a user takes: click the box, then type. No {@code setFocus}.
     *
     * <p>{@link KeyDispatchLiveIT} focuses the field directly, which is what left this gap —
     * it proves a keystroke reaches a focused field, not that a click is what focuses it.
     */
    @Test
    public void aLeftClickFocusesTheTextFieldSoTypingLandsInIt() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openSurface();
            TextField field = fieldOf(surface);
            QmlView view = viewOf(surface);
            assertNull("precondition: nothing may be focused before the click", view.focused());

            press(surface, INSIDE_FIELD_X, INSIDE_FIELD_Y, LEFT);
            release(surface, INSIDE_FIELD_X, INSIDE_FIELD_Y, LEFT);

            assertSame("a left click inside a text field must focus it; with LWJGL's index 0 "
                + "passed through, qml4j reads Qt.NoButton and never runs its focus branch",
                field, view.focused());

            for (String ch : new String[] {"h", "i"}) {
                surface.key(UiKeys.NONE, ch, false, false);
            }
            assertEquals("typing after a click must land in the clicked field", "hi", field.text());

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * A right press must not focus a text field.
     *
     * <p>This is the half that makes the pair meaningful: it fails both when the index is passed
     * through raw (right = 1 = Qt's LeftButton, so right-clicking a box focuses it and starts a
     * selection) and when the mapping is faked by always sending 1.
     */
    @Test
    public void aRightClickDoesNotFocusTheTextField() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openSurface();
            QmlView view = viewOf(surface);

            press(surface, INSIDE_FIELD_X, INSIDE_FIELD_Y, RIGHT);
            release(surface, INSIDE_FIELD_X, INSIDE_FIELD_Y, RIGHT);

            assertNull("a right click must not take focus; qml4j reserves its focus and caret "
                    + "handling for Qt.LeftButton, so a raw index 1 impersonates a left click",
                view.focused());

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * A press outside the field must clear focus rather than leave it stuck.
     *
     * <p>qml4j drops focus on a left press that hits no text field, and that too is inside the
     * {@code button == 1} branch — so with the raw index the box could be focused (by a right
     * click) and never let go.
     */
    @Test
    public void aLeftClickOutsideTheFieldClearsFocus() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openSurface();
            TextField field = fieldOf(surface);
            QmlView view = viewOf(surface);

            press(surface, INSIDE_FIELD_X, INSIDE_FIELD_Y, LEFT);
            release(surface, INSIDE_FIELD_X, INSIDE_FIELD_Y, LEFT);
            assertSame("precondition: the click must have focused the field", field,
                view.focused());

            // Below the field (it ends at y=50), still inside the scene root.
            press(surface, INSIDE_FIELD_X, 160.0F, LEFT);
            release(surface, INSIDE_FIELD_X, 160.0F, LEFT);

            assertNull("clicking away from a text field must release its focus",
                view.focused());

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    /**
     * Press at a point given in the scene's LOGICAL units.
     *
     * <p>The SPI takes framebuffer pixels, so the scale is applied here — the same conversion
     * the game does, in the same direction. Hardcoding it would make the test pass only at 1x.
     */
    private static void press(QmlUiSurface surface, float x, float y, int button) throws Exception {
        float s = scaleOf(surface);
        surface.pointerDown(x * s, y * s, button);
    }

    private static void release(QmlUiSurface surface, float x, float y, int button)
            throws Exception {
        float s = scaleOf(surface);
        surface.pointerUp(x * s, y * s, button);
    }

    private static QmlUiSurface openSurface() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("scene must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        // One frame lays the scene out; without geometry there is nothing to hit test against.
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
        return surface;
    }

    private static float scaleOf(QmlUiSurface surface) throws Exception {
        float s = surface.uiScale();
        return s > 0.0F ? s : 1.0F;
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        return surface.view();
    }

    private static TextField fieldOf(QmlUiSurface surface) throws Exception {
        Item field = findTextField(viewOf(surface).root());
        assertNotNull("the test scene must contain a TextField", field);
        return (TextField) field;
    }

    private static Item findTextField(Item node) {
        if (node == null) {
            return null;
        }
        if (node instanceof TextField) {
            return node;
        }
        for (Item child : node.children) {
            Item hit = findTextField(child);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static void closeQuietly(QmlUiSurface surface) {
        if (surface != null) {
            surface.close();
        }
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(854, 480));
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
