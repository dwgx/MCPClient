package org.lwjgl.impl.input;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * SPI for a mouse backend driven by the {@link org.lwjgl.input.Mouse} facade.
 * A backend registers OS callbacks in {@link #createMouse()}, reports polled
 * coordinates/deltas and button state via {@link #pollMouse}, and appends
 * buffered events drained by {@link #readMouse(ByteBuffer)}.
 */
public interface MouseImplementation {

    /** Register OS callbacks against the current Display window. */
    void createMouse();

    /** Free any native callbacks. */
    void destroyMouse();

    /**
     * Write current mouse coordinates and wheel delta into {@code coordBuffer}
     * (index 0 = x-or-dx, 1 = y-or-dy, 2 = dwheel) and per-button state into
     * {@code buttonsBuffer}. When grabbed, indices 0/1 carry accumulated
     * deltas; when ungrabbed they carry the absolute position. Accumulators are
     * zeroed after each call.
     */
    void pollMouse(IntBuffer coordBuffer, ByteBuffer buttonsBuffer);

    /** Drain buffered mouse events into {@code readBuffer}. */
    void readMouse(ByteBuffer readBuffer);

    /** Move the cursor to the given position (LWJGL2 GL coordinates). */
    void setCursorPosition(int x, int y);

    /** Grab (hide + lock) or release the cursor. */
    void grabMouse(boolean grab);

    /** @return true if a scroll wheel is available. */
    boolean hasWheel();

    /** @return number of addressable mouse buttons. */
    int getButtonCount();

    /** @return true if the cursor is currently inside the window. */
    boolean isInsideWindow();
}