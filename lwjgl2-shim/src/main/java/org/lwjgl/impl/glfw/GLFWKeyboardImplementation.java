package org.lwjgl.impl.glfw;

import java.nio.ByteBuffer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.impl.input.KeyboardImplementation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EventQueue;

/**
 * GLFW-backed keyboard producing classic LWJGL2 key events.
 *
 * Event record (Keyboard.EVENT_SIZE = 18 bytes):
 *   int  keyCode  (offset 0)  LWJGL2 DirectInput scan code; 0 for a char-only event
 *   byte state    (offset 4)  1 = pressed, 0 = released
 *   int  character(offset 5)  translated Unicode code point, 0 if none
 *   long nanos    (offset 9)  System.nanoTime() at generation
 *   byte repeat   (offset 17) 1 if this is an OS auto-repeat
 *
 * GLFW delivers a key callback (code, state, char=0) immediately followed by a
 * char callback (code point) when a key produces text. We merge the code point
 * into the preceding key-down event so a single LWJGL2 event carries both key
 * and character. A char callback with no matching prior key-down (dead keys,
 * IME, composed input) is posted as a standalone event with keyCode = 0 so that
 * Minecraft's getEventKey()==0 text path fires.
 */
public class GLFWKeyboardImplementation implements KeyboardImplementation {

    // Byte offsets inside an 18-byte key event.
    private static final int OFF_KEYCODE = 0;
    private static final int OFF_STATE = 4;
    private static final int OFF_CHAR = 5;

    private GLFWKeyCallback keyCallback;
    private GLFWCharCallback charCallback;
    private long windowHandle;

    /** Instantaneous key-down snapshot, indexed by LWJGL2 scan code. */
    private final byte[] keyDownState = new byte[Keyboard.KEYBOARD_SIZE];

    private final EventQueue eventQueue = new EventQueue(Keyboard.EVENT_SIZE);
    private final ByteBuffer scratch = ByteBuffer.allocate(Keyboard.EVENT_SIZE);

    public void createKeyboard() {
        this.windowHandle = Display.getWindowHandle();

        this.keyCallback = GLFWKeyCallback.create(new GLFWKeyCallback() {
            public void invoke(long window, int glfwKey, int scancode, int action, int mods) {
                int key = translateKeyFromGLFW(glfwKey);
                // Drop unmapped keys (media keys, F20-F25, etc). They translate to
                // KEY_NONE (0); posting them would mark KEY_NONE down and fire a
                // spurious keyTyped(0,0). Real bound keys never resolve to 0.
                if (key <= 0 || key >= keyDownState.length) {
                    return;
                }
                boolean repeat = action == GLFW.GLFW_REPEAT;
                if (action == GLFW.GLFW_PRESS) {
                    keyDownState[key] = 1;
                } else if (action == GLFW.GLFW_RELEASE) {
                    keyDownState[key] = 0;
                }
                // Down for press/repeat, up for release.
                byte state = action == GLFW.GLFW_RELEASE ? (byte) 0 : (byte) 1;
                postEvent(key, state, 0, System.nanoTime(), repeat);
            }
        });

        this.charCallback = GLFWCharCallback.create(new GLFWCharCallback() {
            public void invoke(long window, int codepoint) {
                postCharacter(codepoint);
            }
        });

        GLFW.glfwSetKeyCallback(this.windowHandle, this.keyCallback);
        GLFW.glfwSetCharCallback(this.windowHandle, this.charCallback);
    }

    /**
     * Merge a code point into the preceding key-down event when possible,
     * otherwise post a standalone char-only event (keyCode = 0).
     */
    private void postCharacter(int codepoint) {
        ByteBuffer last = eventQueue.getLastEvent();
        // Attach only when there IS a previous event and it was a real key-down
        // (keyCode > 0), still down (state == 1), with no character captured yet.
        if (last != null
                && last.getInt(OFF_KEYCODE) > 0
                && last.get(OFF_STATE) == 1
                && last.getInt(OFF_CHAR) == 0) {
            last.putInt(OFF_CHAR, codepoint);
            return;
        }
        // Standalone character (dead key / IME / composed). keyCode 0 drives the
        // Minecraft getEventKey()==0 -> getEventCharacter() text-input branch.
        postEvent(0, (byte) 1, codepoint, System.nanoTime(), false);
    }

    private void postEvent(int keyCode, byte state, int character, long nanos, boolean repeat) {
        scratch.clear();
        scratch.putInt(keyCode);        // 0
        scratch.put(state);             // 4
        scratch.putInt(character);      // 5
        scratch.putLong(nanos);         // 9
        scratch.put(repeat ? (byte) 1 : (byte) 0); // 17
        scratch.flip();
        eventQueue.putEvent(scratch);
    }

    public void destroyKeyboard() {
        if (keyCallback != null) {
            keyCallback.free();
            keyCallback = null;
        }
        if (charCallback != null) {
            charCallback.free();
            charCallback = null;
        }
    }

    public void pollKeyboard(ByteBuffer keyDownBuffer) {
        int oldPosition = keyDownBuffer.position();
        keyDownBuffer.put(keyDownState);
        keyDownBuffer.position(oldPosition);
    }

    public void readKeyboard(ByteBuffer readBuffer) {
        eventQueue.copyEvents(readBuffer);
    }

    /**
     * Map a GLFW key code to the LWJGL2 DirectInput scan code KeyBindings are
     * persisted with. Unmapped keys fall through unchanged; -1 (GLFW unknown)
     * maps to KEY_NONE.
     */
    public static int translateKeyFromGLFW(int glfwKey) {
        return Keyboard.getKeyIndexFromGLFW(glfwKey);
    }

}