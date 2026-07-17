package net.marcloud.mcp.dwm.skiko;

import net.marcloud.mcp.dwm.backend.FrameInput;

/**
 * Pure, headless-testable pointer/button state for {@link DesktopGuiScreen} — the
 * immediate-mode bridge between MC's discrete {@code GuiScreen} callbacks and the DWM
 * {@link FrameInput} snapshot the component tree hit-tests each frame.
 *
 * <p><b>Why a state machine.</b> MC delivers the pointer POSITION every frame (as the
 * {@code mouseX,mouseY} args to {@code drawScreen}) but delivers button presses as
 * discrete {@code mouseClicked}/{@code mouseReleased} events on SEPARATE calls. The DWM
 * component tree, being immediate-mode, wants a per-frame snapshot: pointer + a button
 * MASK it edge-detects across frames (MaterialButton latches a press when the primary bit
 * goes 1 then acts when it goes 0). So this holds the button mask between events and the
 * screen feeds the current pointer in at draw time. This mirrors how the reference clients
 * (Novoline, Southside) read the pointer from the draw call and buttons from callbacks —
 * one coordinate space, one lifecycle, no parallel poller.
 *
 * <p><b>Coordinate space.</b> Coordinates are stored in FRAMEBUFFER PIXELS. MC hands
 * {@code GuiScreen} coordinates in ScaledResolution (GUI) space; the screen converts them
 * with {@link #scaledToPixel} before feeding this, because the P1 Skiko backend still lays
 * out and draws in raw framebuffer pixels. (P2 moves both to scaled space and this
 * conversion collapses to identity.)
 *
 * <p>Bit {@code 1<<n} of the mask is button {@code n}; bit 0 is the primary button
 * (matching {@code MaterialButton.PRIMARY_BUTTON == 1}).
 */
public final class ScreenPointerState {

    private float pointerX;
    private float pointerY;
    private int buttonMask;

    /** Update the pointer position (framebuffer pixels), typically from {@code drawScreen}. */
    public void moveTo(float pixelX, float pixelY) {
        this.pointerX = pixelX;
        this.pointerY = pixelY;
    }

    /** Latch button {@code n} down (from {@code mouseClicked}). Ignores negatives. */
    public void press(int button) {
        if (button >= 0 && button < 31) {
            buttonMask |= (1 << button);
        }
    }

    /** Clear button {@code n} (from {@code mouseReleased}). Ignores negatives. */
    public void release(int button) {
        if (button >= 0 && button < 31) {
            buttonMask &= ~(1 << button);
        }
    }

    public float pointerX() {
        return pointerX;
    }

    public float pointerY() {
        return pointerY;
    }

    public int buttonMask() {
        return buttonMask;
    }

    /**
     * Build this frame's {@link FrameInput}: current pointer + button mask, the given
     * vertical scroll (in notches, up positive), and no queued char/key events (those go
     * through the screen's {@code keyTyped} into the launcher's own input state, not here).
     */
    public FrameInput toFrameInput(float scrollY) {
        return new FrameInput(pointerX, pointerY, buttonMask, 0f, scrollY,
                java.util.List.of(), java.util.List.of());
    }

    /**
     * Convert a ScaledResolution (GUI-space) coordinate to a framebuffer pixel coordinate.
     * MC computes scaled = pixel / scaleFactor (integer), so pixel = scaled * scaleFactor is
     * the faithful inverse for hit-testing against a pixel-space layout. A non-positive
     * scaleFactor (shouldn't happen) is coerced to 1 so this never zeroes coordinates.
     */
    public static float scaledToPixel(int scaledCoord, int scaleFactor) {
        int sf = scaleFactor > 0 ? scaleFactor : 1;
        return (float) scaledCoord * sf;
    }
}
