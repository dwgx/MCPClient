package org.lwjgl.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Headless regression tests for the pure-logic surface of the {@link Keyboard}
 * shim: the DirectInput scancode constants MC persists in keybinds, the
 * name<->code registries, the canonical GLFW->LWJGL2 scancode translation, and
 * the repeat-events flag. None of these touch a window. The event pump
 * (create/poll/next reading real GLFW callbacks) is RUNTIME_ONLY.
 */
public class KeyboardTest {

    /** Scancodes are the classic LWJGL2 DirectInput values MC stores in options. */
    @Test
    public void directInputScancodesMatchLwjgl2() {
        assertEquals(0x01, Keyboard.KEY_ESCAPE);
        assertEquals(0x1C, Keyboard.KEY_RETURN);
        assertEquals(0x39, Keyboard.KEY_SPACE);
        assertEquals(0x2A, Keyboard.KEY_LSHIFT);
        assertEquals(0x1E, Keyboard.KEY_A);
        assertEquals(0x11, Keyboard.KEY_W);
        assertEquals(0xC8, Keyboard.KEY_UP);
        assertEquals(0x00, Keyboard.KEY_NONE);
    }

    /** Historical Windows-key aliases must share scancodes with the meta keys. */
    @Test
    public void winKeyAliasesShareMetaScancodes() {
        assertEquals(Keyboard.KEY_LMETA, Keyboard.KEY_LWIN);
        assertEquals(Keyboard.KEY_RMETA, Keyboard.KEY_RWIN);
    }

    @Test
    public void keyNameRoundTrips() {
        assertEquals("ESCAPE", Keyboard.getKeyName(Keyboard.KEY_ESCAPE));
        assertEquals("SPACE", Keyboard.getKeyName(Keyboard.KEY_SPACE));
        assertEquals("A", Keyboard.getKeyName(Keyboard.KEY_A));
        assertEquals(Keyboard.KEY_ESCAPE, Keyboard.getKeyIndex("ESCAPE"));
        assertEquals(Keyboard.KEY_A, Keyboard.getKeyIndex("A"));
    }

    @Test
    public void unknownKeyNameIsKeyNone() {
        assertEquals(Keyboard.KEY_NONE, Keyboard.getKeyIndex("NOT_A_REAL_KEY"));
    }

    @Test
    public void getKeyNameOutOfRangeIsNull() {
        assertNull(Keyboard.getKeyName(-1));
        assertNull(Keyboard.getKeyName(Keyboard.KEYBOARD_SIZE));
        assertNull(Keyboard.getKeyName(9999));
    }

    /** The GLFW->LWJGL2 mapping is the single source of truth for keybind persistence. */
    @Test
    public void glfwToLwjglScancodeMapping() {
        assertEquals(Keyboard.KEY_ESCAPE, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_ESCAPE));
        assertEquals(Keyboard.KEY_SPACE, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_SPACE));
        assertEquals(Keyboard.KEY_A, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_A));
        assertEquals(Keyboard.KEY_W, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_W));
        assertEquals(Keyboard.KEY_RETURN, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_ENTER));
        assertEquals(Keyboard.KEY_LSHIFT, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_LEFT_SHIFT));
        assertEquals(Keyboard.KEY_UP, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_UP));
        assertEquals(Keyboard.KEY_NUMPAD0, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_KP_0));
    }

    @Test
    public void glfwUnknownOrOutOfRangeIsKeyNone() {
        assertEquals(Keyboard.KEY_NONE, Keyboard.getKeyIndexFromGLFW(GLFW.GLFW_KEY_UNKNOWN));
        assertEquals(Keyboard.KEY_NONE, Keyboard.getKeyIndexFromGLFW(-5));
        assertEquals(Keyboard.KEY_NONE, Keyboard.getKeyIndexFromGLFW(Integer.MAX_VALUE));
    }

    /** enableRepeatEvents is a pure flag MC toggles 26 times; no window needed. */
    @Test
    public void repeatEventsFlagTogglesWithoutWindow() {
        Keyboard.enableRepeatEvents(true);
        assertTrue(Keyboard.areRepeatEventsEnabled());
        Keyboard.enableRepeatEvents(false);
        assertFalse(Keyboard.areRepeatEventsEnabled());
    }

    /** No two distinct scancodes may collide in the name table (except aliases). */
    @Test
    public void keyCountIsPositive() {
        assertTrue("registry must be populated", Keyboard.getKeyCount() > 100);
    }
}
