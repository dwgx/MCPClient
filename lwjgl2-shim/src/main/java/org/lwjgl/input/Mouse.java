package org.lwjgl.input;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.Sys;
import org.lwjgl.impl.LWJGLImplementationUtils;
import org.lwjgl.impl.input.InputImplementation;
import org.lwjgl.opengl.Display;

/**
 * LWJGL2-compatible raw mouse facade.
 *
 * <p>Static entry point MC 1.8.9 calls directly. LWJGL3 dropped {@code org.lwjgl.input.Mouse},
 * so it is re-authored here over the group-4 {@link InputImplementation} backend. Buffered
 * events use the historical LWJGL2 wire layout (22 bytes):</p>
 *
 * <pre>
 *   byte button   (index, or -1 for a move/wheel-only event)
 *   byte state    (0 = up, non-zero = down)
 *   int  x        (absolute position, already GL-oriented / Y-flipped by the backend)
 *   int  y
 *   int  dz       (wheel delta)
 *   long nanos    (event time)
 *   ------------------------------------------------------- = 22 bytes
 * </pre>
 *
 * <p>Coordinates arriving from the backend are expected to already be in LWJGL2's
 * bottom-left origin space (the backend applies {@code y = Display.getHeight()-1-glfwY}).</p>
 */
public class Mouse {

    /** Wire size, in bytes, of a single buffered mouse event. */
    public static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;

    /** Maximum number of events buffered between reads. */
    private static final int BUFFER_SIZE = 50;

    // -- runtime state --

    private static boolean created;
    private static boolean initialized;
    private static boolean isGrabbed;
    private static boolean hasWheel;

    private static InputImplementation implementation;

    /** Per-button pressed snapshot from the last poll, indexed by button number. */
    private static ByteBuffer buttons;

    /** Scratch buffer the backend fills with {dx/x, dy/y, dwheel} on each poll. */
    private static IntBuffer coordBuffer;

    /** Rolling buffer of unread mouse events written by the backend. */
    private static ByteBuffer readBuffer;

    private static int buttonCount = -1;
    private static String[] buttonName;
    private static final Map<String, Integer> buttonMap = new HashMap<String, Integer>(16);

    // polled absolute position (clamped) and unclamped absolute position
    private static int x;
    private static int y;
    private static int absoluteX;
    private static int absoluteY;

    // accumulated deltas since the caller last read them
    private static int dx;
    private static int dy;
    private static int dwheel;

    // current event fields (populated by next())
    private static int eventButton = -1;
    private static boolean eventState;
    private static int eventDX;
    private static int eventDY;
    private static int eventX;
    private static int eventY;
    private static int eventDWheel;
    private static long eventNanos;

    // position captured when the cursor was grabbed, restored on ungrab
    private static int grabX;
    private static int grabY;

    // last raw (pre-clip) event position, used to derive deltas in ungrabbed mode
    private static int lastRawEventX;
    private static int lastRawEventY;

    private static boolean clipToWindow =
            !Boolean.getBoolean("org.lwjgl.input.Mouse.allowNegativeMouseCoords");

    private Mouse() {
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return clipToWindow;
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        clipToWindow = clip;
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        Sys.initialize();
        buttonName = new String[16];
        for (int i = 0; i < buttonName.length; i++) {
            buttonName[i] = "BUTTON" + i;
            buttonMap.put(buttonName[i], Integer.valueOf(i));
        }
        initialized = true;
    }

    /** Reflective entry point mirroring LWJGL2. */
    private static void create(InputImplementation impl) throws LWJGLException {
        if (created) {
            return;
        }
        if (!initialized) {
            initialize();
        }
        implementation = impl;
        implementation.createMouse();
        hasWheel = implementation.hasWheel();
        created = true;

        buttonCount = implementation.getButtonCount();
        buttons = BufferUtils.createByteBuffer(buttonCount);
        coordBuffer = BufferUtils.createIntBuffer(3);
        readBuffer = ByteBuffer.allocate(EVENT_SIZE * BUFFER_SIZE);
        readBuffer.limit(0);
        setGrabbed(isGrabbed);
    }

    public static void create() throws LWJGLException {
        if (!Display.isCreated()) {
            throw new IllegalStateException("Display must be created.");
        }
        create(LWJGLImplementationUtils.getOrCreateInputImplementation());
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        if (!created) {
            return;
        }
        created = false;
        buttons = null;
        coordBuffer = null;
        implementation.destroyMouse();
    }

    private static void resetMouse() {
        dx = dy = dwheel = 0;
        if (readBuffer != null) {
            readBuffer.position(readBuffer.limit());
        }
    }

    /**
     * Refreshes the polled position/button snapshot and drains pending events.
     * When grabbed, the backend reports relative motion; otherwise absolute positions.
     * The window layer must pump OS messages (via Display.update) first.
     */
    public static void poll() {
        if (!created) {
            throw new IllegalStateException("Mouse must be created before you can poll it");
        }
        implementation.pollMouse(coordBuffer, buttons);

        int c1 = coordBuffer.get(0);
        int c2 = coordBuffer.get(1);
        int wheel = coordBuffer.get(2);

        if (isGrabbed()) {
            dx += c1;
            dy += c2;
            x += c1;
            y += c2;
            absoluteX += c1;
            absoluteY += c2;
        } else {
            dx = c1 - absoluteX;
            dy = c2 - absoluteY;
            absoluteX = x = c1;
            absoluteY = y = c2;
        }

        if (clipToWindow) {
            x = Math.min(Display.getWidth() - 1, Math.max(0, x));
            y = Math.min(Display.getHeight() - 1, Math.max(0, y));
        }

        dwheel += wheel;
        read();
    }

    private static void read() {
        readBuffer.compact();
        implementation.readMouse(readBuffer);
        readBuffer.flip();
    }

    public static boolean isButtonDown(int button) {
        if (!created) {
            throw new IllegalStateException("Mouse must be created before you can poll the button state");
        }
        if (button < 0 || button >= buttonCount) {
            return false;
        }
        return buttons.get(button) == 1;
    }

    public static String getButtonName(int button) {
        if (button < 0 || button >= buttonName.length) {
            return null;
        }
        return buttonName[button];
    }

    public static int getButtonIndex(String name) {
        Integer index = buttonMap.get(name);
        return index == null ? -1 : index.intValue();
    }

    /**
     * Advances to the next buffered mouse event. Delta values are computed here so callers
     * see per-event movement whether or not the cursor is grabbed.
     *
     * @return true if an event was loaded into the current-event slot
     */
    public static boolean next() {
        if (!created) {
            throw new IllegalStateException("Mouse must be created before you can read events");
        }
        if (readBuffer.remaining() < EVENT_SIZE) {
            return false;
        }

        eventButton = readBuffer.get();
        eventState = readBuffer.get() != 0;

        if (isGrabbed()) {
            eventDX = readBuffer.getInt();
            eventDY = readBuffer.getInt();
            eventX += eventDX;
            eventY += eventDY;
            lastRawEventX = eventX;
            lastRawEventY = eventY;
        } else {
            int newX = readBuffer.getInt();
            int newY = readBuffer.getInt();
            eventDX = newX - lastRawEventX;
            eventDY = newY - lastRawEventY;
            eventX = newX;
            eventY = newY;
            lastRawEventX = newX;
            lastRawEventY = newY;
        }

        if (clipToWindow) {
            eventX = Math.min(Display.getWidth() - 1, Math.max(0, eventX));
            eventY = Math.min(Display.getHeight() - 1, Math.max(0, eventY));
        }

        eventDWheel = readBuffer.getInt();
        eventNanos = readBuffer.getLong();
        return true;
    }

    public static int getEventButton() {
        return eventButton;
    }

    public static boolean getEventButtonState() {
        return eventState;
    }

    public static int getEventDX() {
        return eventDX;
    }

    public static int getEventDY() {
        return eventDY;
    }

    public static int getEventX() {
        return eventX;
    }

    public static int getEventY() {
        return eventY;
    }

    public static int getEventDWheel() {
        return eventDWheel;
    }

    public static long getEventNanoseconds() {
        return eventNanos;
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
    }

    public static int getDX() {
        int result = dx;
        dx = 0;
        return result;
    }

    public static int getDY() {
        int result = dy;
        dy = 0;
        return result;
    }

    public static int getDWheel() {
        int result = dwheel;
        dwheel = 0;
        return result;
    }

    public static int getButtonCount() {
        return buttonCount;
    }

    public static boolean hasWheel() {
        return hasWheel;
    }

    public static boolean isGrabbed() {
        return isGrabbed;
    }

    /**
     * Grabs or releases the cursor. On grab the current position is stashed; on release the
     * cursor is warped back to that position so the UI pointer does not jump.
     */
    public static void setGrabbed(boolean grab) {
        boolean wasGrabbed = isGrabbed;
        isGrabbed = grab;
        if (isCreated()) {
            if (grab && !wasGrabbed) {
                grabX = x;
                grabY = y;
            } else if (!grab && wasGrabbed) {
                implementation.setCursorPosition(grabX, grabY);
            }

            implementation.grabMouse(grab);
            poll();
            eventX = x;
            eventY = y;
            lastRawEventX = x;
            lastRawEventY = y;
            resetMouse();
        }
    }

    /**
     * Moves the cursor to the given position (GL-oriented coordinates relative to the window).
     * When grabbed, only the internal grab anchor is updated.
     */
    public static void setCursorPosition(int newX, int newY) {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse is not created");
        }
        x = eventX = newX;
        y = eventY = newY;
        if (!isGrabbed()) {
            implementation.setCursorPosition(x, y);
        } else {
            grabX = newX;
            grabY = newY;
        }
    }

    public static boolean isInsideWindow() {
        return implementation.isInsideWindow();
    }
}