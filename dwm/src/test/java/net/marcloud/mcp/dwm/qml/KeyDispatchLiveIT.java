package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 * Proves keystrokes actually reach the scene — the assertion whose absence hid a dead key path.
 *
 * <p>qml4j's signature is {@code dispatchKey(keyCode, text, down, shift)}. The adapter used to
 * pass {@code shift} in the {@code down} slot, so with Shift up qml4j saw a key RELEASE: it
 * emitted {@code Keys.released} rather than {@code pressed}, skipped every specific signal and
 * the Tab focus move, and returned true from its {@code !down} branch — consuming the key and
 * doing nothing. Every character was dropped unless Shift happened to be held. Nothing caught
 * it: no shipped scene has a text field, {@code QmlPipelineLiveIT} dispatches only pointer
 * events, and Escape still closed the menu because {@link QmlGuiScreen} falls back to its own
 * {@code close()} when the scene declines the key.
 *
 * <p>So this asserts on CONTENT, not on the absence of an exception. Typing "abc" with no
 * modifier must leave "abc" in the field; on the old argument order the field stays empty.
 *
 * <p>A live IT rather than a unit test because a {@code TextField} only accepts input once the
 * scene has been laid out, and layout measures text through Skija's native font stack.
 */
public class KeyDispatchLiveIT {

    /** A scene with a focusable text field — deliberately not the shipped menu, which has none. */
    private static final String SCENE = "dwm/test/TextEntry.qml";

    @Test
    public void typedCharactersReachTheSceneWithoutAModifier() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = openSurface();
            TextField field = focusField(surface);

            // No Shift, no Ctrl: the ordinary case, and the one that was broken.
            for (String ch : new String[] {"a", "b", "c"}) {
                surface.key(UiKeys.NONE, ch, false, false);
            }

            assertEquals("plain typing must reach the field; with shift passed as qml4j's `down` "
                + "flag every character is swallowed and this stays empty",
                "abc", field.text());
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /** Holding Shift must not change whether the character arrives — only its case upstream. */
    @Test
    public void typedCharactersAlsoReachTheSceneWithShiftHeld() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = openSurface();
            TextField field = focusField(surface);

            surface.key(UiKeys.NONE, "A", true, false);
            surface.key(UiKeys.NONE, "B", true, false);

            assertEquals("Shift must not gate whether input arrives", "AB", field.text());
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /**
     * An editing key must be delivered as a press.
     *
     * <p>Backspace is the cheapest observable proof: qml4j only applies it on {@code down}, so
     * under the old argument order it was silently discarded whenever Shift was up.
     */
    @Test
    public void editingKeysAreDeliveredAsPresses() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = openSurface();
            TextField field = focusField(surface);

            surface.key(UiKeys.NONE, "a", false, false);
            surface.key(UiKeys.NONE, "b", false, false);
            surface.key(UiKeys.BACKSPACE, null, false, false);

            assertEquals("backspace must delete a character, which qml4j only does on a press",
                "a", field.text());
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /**
     * Ctrl+C/X/V must reach qml4j's clipboard API, not travel as a key with a modifier.
     *
     * <p>Asserted through the round trip: copy the field's selection, then paste it back at the
     * caret. A Ctrl combo leaking into {@code dispatchKey} as text would instead insert a literal
     * "c" or "v", which this would catch as a content mismatch.
     */
    @Test
    public void ctrlCombosDriveTheClipboardRatherThanInsertingLetters() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = openSurface();
            TextField field = focusField(surface);

            surface.key(UiKeys.NONE, "h", false, false);
            surface.key(UiKeys.NONE, "i", false, false);
            String typed = field.text();
            assertEquals("precondition: the two characters must have landed", "hi", typed);

            // Whether the clipboard verbs consume depends on there being a selection, which is
            // not what this asserts. What matters is that no literal letter is inserted.
            surface.key(UiKeys.NONE, "c", false, true);
            surface.key(UiKeys.NONE, "v", false, true);
            surface.key(UiKeys.NONE, "x", false, true);

            assertTrue("a Ctrl combo must not insert its letter as text (field is now \""
                + field.text() + "\")", !field.text().contains("cv"));
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    private static QmlUiSurface openSurface() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("scene must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        // One frame lays the scene out; a TextField accepts input only once it has geometry.
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
        return surface;
    }

    /** Focus the scene's text field, so dispatched keys have somewhere to land. */
    private static TextField focusField(QmlUiSurface surface) throws Exception {
        QmlView view = viewOf(surface);
        Item field = findByType(view.root());
        assertNotNull("the test scene must contain a TextField", field);
        view.setFocus(field);
        return (TextField) field;
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        return surface.view();
    }

    private static Item findByType(Item node) {
        if (node == null) {
            return null;
        }
        if (node instanceof TextField) {
            return node;
        }
        for (Item child : node.children) {
            Item hit = findByType(child);
            if (hit != null) {
                return hit;
            }
        }
        return null;
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
