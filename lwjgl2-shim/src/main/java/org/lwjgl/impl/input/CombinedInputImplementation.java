package org.lwjgl.impl.input;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Concrete {@link InputImplementation} that forwards keyboard calls to one
 * backend and mouse calls to another, so the two devices can be implemented
 * independently while the facades see a single object.
 */
public class CombinedInputImplementation implements InputImplementation {

    private final KeyboardImplementation keyboard;
    private final MouseImplementation mouse;

    public CombinedInputImplementation(KeyboardImplementation keyboard, MouseImplementation mouse) {
        this.keyboard = keyboard;
        this.mouse = mouse;
    }

    // ----- keyboard -----

    public void createKeyboard() {
        keyboard.createKeyboard();
    }

    public void destroyKeyboard() {
        keyboard.destroyKeyboard();
    }

    public void pollKeyboard(ByteBuffer keyDownBuffer) {
        keyboard.pollKeyboard(keyDownBuffer);
    }

    public void readKeyboard(ByteBuffer readBuffer) {
        keyboard.readKeyboard(readBuffer);
    }

    // ----- mouse -----

    public void createMouse() {
        mouse.createMouse();
    }

    public void destroyMouse() {
        mouse.destroyMouse();
    }

    public void pollMouse(IntBuffer coordBuffer, ByteBuffer buttonsBuffer) {
        mouse.pollMouse(coordBuffer, buttonsBuffer);
    }

    public void readMouse(ByteBuffer readBuffer) {
        mouse.readMouse(readBuffer);
    }

    public void setCursorPosition(int x, int y) {
        mouse.setCursorPosition(x, y);
    }

    public void grabMouse(boolean grab) {
        mouse.grabMouse(grab);
    }

    public boolean hasWheel() {
        return mouse.hasWheel();
    }

    public int getButtonCount() {
        return mouse.getButtonCount();
    }

    public boolean isInsideWindow() {
        return mouse.isInsideWindow();
    }
}