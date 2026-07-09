package org.lwjgl.impl.input;

import java.nio.ByteBuffer;

/**
 * SPI for a keyboard backend driven by the {@link org.lwjgl.input.Keyboard}
 * facade. A backend registers OS callbacks in {@link #createKeyboard()},
 * maintains an instantaneous key-down snapshot copied out by
 * {@link #pollKeyboard(ByteBuffer)}, and appends buffered events drained by
 * {@link #readKeyboard(ByteBuffer)}.
 */
public interface KeyboardImplementation {

    /** Register OS callbacks against the current Display window. */
    void createKeyboard();

    /** Free any native callbacks. */
    void destroyKeyboard();

    /**
     * Copy the current key-down state into {@code keyDownBuffer} without
     * advancing its position. Indexed by LWJGL2 scan code region; one byte per
     * key (1 = down, 0 = up).
     */
    void pollKeyboard(ByteBuffer keyDownBuffer);

    /** Drain buffered key events into {@code readBuffer}. */
    void readKeyboard(ByteBuffer readBuffer);
}