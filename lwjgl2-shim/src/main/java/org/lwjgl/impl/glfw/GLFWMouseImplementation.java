package org.lwjgl.impl.glfw;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorEnterCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.impl.input.MouseImplementation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EventQueue;

/**
 * GLFW-backed mouse producing classic LWJGL2 mouse events.
 *
 * Event record (Mouse.EVENT_SIZE = 22 bytes):
 *   byte button (offset 0)  button index, or -1 for a move / scroll event
 *   byte state  (offset 1)  1 = pressed, 0 = released
 *   int  x      (offset 2)  grabbed: dx; ungrabbed: absolute x (GL coords)
 *   int  y      (offset 6)  grabbed: dy; ungrabbed: absolute y (GL coords)
 *   int  dz     (offset 10) wheel delta
 *   long nanos  (offset 14) System.nanoTime() at generation
 *
 * Coordinates are flipped to LWJGL2's bottom-left origin
 * (y = Display.getHeight() - 1 - glfwY). The scroll delta is scaled to LWJGL2's
 * Windows WHEEL_DELTA of 120 per detent. When grabbed the coordinate fields
 * carry accumulated deltas; when ungrabbed they carry the absolute position.
 */
public class GLFWMouseImplementation implements MouseImplementation {

    /** LWJGL2 reported one wheel detent as +/- WHEEL_DELTA (120). */
    private static final int WHEEL_DELTA = 120;

    private GLFWMouseButtonCallback buttonCallback;
    private GLFWCursorPosCallback posCallback;
    private GLFWScrollCallback scrollCallback;
    private GLFWCursorEnterCallback enterCallback;

    private long windowHandle;
    private boolean grabbed;
    private boolean insideWindow;

    private final EventQueue eventQueue = new EventQueue(Mouse.EVENT_SIZE);
    private final ByteBuffer scratch = ByteBuffer.allocate(Mouse.EVENT_SIZE);

    /** Last known absolute position, GL coordinates (bottom-left origin). */
    private int lastX;
    private int lastY;

    /** Accumulated deltas drained by pollMouse. */
    private int accumDX;
    private int accumDY;
    private int accumDZ;

    private final byte[] buttonStates = new byte[getButtonCount()];

    public void createMouse() {
        this.windowHandle = Display.getWindowHandle();

        // Prefer raw (unaccelerated) motion for grabbed look control when the
        // platform supports it and the user has not disabled it.
        if (GLFW.glfwRawMouseMotionSupported()
                && !Boolean.getBoolean("org.lwjgl.input.Mouse.disableRawInput")) {
            GLFW.glfwSetInputMode(this.windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
        }

        this.buttonCallback = GLFWMouseButtonCallback.create(new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                byte state = action == GLFW.GLFW_PRESS ? (byte) 1 : (byte) 0;
                postButtonEvent((byte) button, state, System.nanoTime());
                if (button >= 0 && button < buttonStates.length) {
                    buttonStates[button] = state;
                }
            }
        });

        this.posCallback = GLFWCursorPosCallback.create(new GLFWCursorPosCallback() {
            public void invoke(long window, double xpos, double ypos) {
                int x = (int) xpos;
                int y = Display.getHeight() - 1 - (int) ypos; // flip to GL origin
                int dx = x - lastX;
                int dy = y - lastY;
                if (dx != 0 || dy != 0) {
                    accumDX += dx;
                    accumDY += dy;
                    lastX = x;
                    lastY = y;
                    long nanos = System.nanoTime();
                    if (grabbed) {
                        postMoveEvent(dx, dy, 0, nanos);
                    } else {
                        postMoveEvent(x, y, 0, nanos);
                    }
                }
            }
        });

        this.scrollCallback = GLFWScrollCallback.create(new GLFWScrollCallback() {
            public void invoke(long window, double xoffset, double yoffset) {
                int dz = (int) Math.round(yoffset) * WHEEL_DELTA;
                if (dz != 0) {
                    accumDZ += dz;
                    long nanos = System.nanoTime();
                    if (grabbed) {
                        postMoveEvent(0, 0, dz, nanos);
                    } else {
                        postMoveEvent(lastX, lastY, dz, nanos);
                    }
                }
            }
        });

        this.enterCallback = GLFWCursorEnterCallback.create(new GLFWCursorEnterCallback() {
            public void invoke(long window, boolean entered) {
                insideWindow = entered;
            }
        });

        GLFW.glfwSetMouseButtonCallback(this.windowHandle, this.buttonCallback);
        GLFW.glfwSetCursorPosCallback(this.windowHandle, this.posCallback);
        GLFW.glfwSetScrollCallback(this.windowHandle, this.scrollCallback);
        GLFW.glfwSetCursorEnterCallback(this.windowHandle, this.enterCallback);
    }

    /** Button press/release at the current cursor position. */
    private void postButtonEvent(byte button, byte state, long nanos) {
        if (grabbed) {
            postEvent(button, state, 0, 0, 0, nanos);
        } else {
            postEvent(button, state, lastX, lastY, 0, nanos);
        }
    }

    /** Move or scroll event (button = -1). */
    private void postMoveEvent(int coord1, int coord2, int dz, long nanos) {
        postEvent((byte) -1, (byte) 0, coord1, coord2, dz, nanos);
    }

    private void postEvent(byte button, byte state, int coord1, int coord2, int dz, long nanos) {
        scratch.clear();
        scratch.put(button);        // 0
        scratch.put(state);         // 1
        scratch.putInt(coord1);     // 2
        scratch.putInt(coord2);     // 6
        scratch.putInt(dz);         // 10
        scratch.putLong(nanos);     // 14
        scratch.flip();
        eventQueue.putEvent(scratch);
    }

    public void destroyMouse() {
        if (buttonCallback != null) { buttonCallback.free(); buttonCallback = null; }
        if (posCallback != null) { posCallback.free(); posCallback = null; }
        if (scrollCallback != null) { scrollCallback.free(); scrollCallback = null; }
        if (enterCallback != null) { enterCallback.free(); enterCallback = null; }
    }

    public void pollMouse(IntBuffer coordBuffer, ByteBuffer buttonsBuffer) {
        if (grabbed) {
            coordBuffer.put(0, accumDX);
            coordBuffer.put(1, accumDY);
        } else {
            coordBuffer.put(0, lastX);
            coordBuffer.put(1, lastY);
        }
        coordBuffer.put(2, accumDZ);
        accumDX = 0;
        accumDY = 0;
        accumDZ = 0;
        for (int i = 0; i < buttonStates.length; i++) {
            buttonsBuffer.put(i, buttonStates[i]);
        }
    }

    public void readMouse(ByteBuffer readBuffer) {
        eventQueue.copyEvents(readBuffer);
    }

    public void setCursorPosition(int x, int y) {
        // Facade supplies GL (bottom-left) coordinates; GLFW expects top-left.
        // Keep our tracking in sync so the next motion does not report a jump.
        this.lastX = x;
        this.lastY = y;
        GLFW.glfwSetCursorPos(this.windowHandle, x, Display.getHeight() - 1 - y);
    }

    public void grabMouse(boolean grab) {
        GLFW.glfwSetInputMode(this.windowHandle, GLFW.GLFW_CURSOR,
            grab ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        this.grabbed = grab;
        // Grab transitions invalidate accumulated deltas and buffered events.
        this.eventQueue.clearEvents();
        this.accumDX = 0;
        this.accumDY = 0;
    }

    public boolean hasWheel() {
        return true;
    }

    public int getButtonCount() {
        return GLFW.GLFW_MOUSE_BUTTON_LAST + 1;
    }

    public boolean isInsideWindow() {
        return this.insideWindow;
    }
}