package net.marcloud.mcp.dwm.ui;

/**
 * Input a DWM backend accepts, in plain JVM types.
 *
 * <p>Coordinates are <b>framebuffer pixels with a top-left origin</b>, which is the one
 * convention worth stating loudly: MC's GUI works in scaled GUI units, LWJGL2's mouse works
 * bottom-left, and on a Retina display the framebuffer is twice the window. The caller owns
 * every one of those conversions; an implementation of this interface receives coordinates
 * already in this single space and must not scale again.
 *
 * <p><b>Buttons are zero-based indices</b>: 0 left, 1 right, 2 middle, as LWJGL2 and GLFW report
 * them. Stated because the obvious alternative is a bitmask — Qt, and so any Qt-shaped backend,
 * numbers left as 1 — and the two vocabularies overlap on every value, so a mix-up is silent.
 * Translating to whatever a backend wants is the implementation's job, not the caller's.
 *
 * <p>Each method returns whether the UI consumed the event. An unconsumed event is the
 * caller's to pass on to the game.
 */
public interface UiInput {

    /**
     * @param button zero-based button index; 0 left, 1 right, 2 middle
     * @return true if the UI consumed this press
     */
    boolean pointerDown(float xPx, float yPx, int button);

    /**
     * @param button zero-based button index; 0 left, 1 right, 2 middle
     * @return true if the UI consumed this release
     */
    boolean pointerUp(float xPx, float yPx, int button);

    /** @return true if the UI consumed this motion */
    boolean pointerMove(float xPx, float yPx);

    /**
     * @param dxNotches horizontal wheel movement in notches, positive right
     * @param dyNotches vertical wheel movement in notches, positive up
     * @return true if the UI consumed this scroll
     */
    boolean wheel(float xPx, float yPx, float dxNotches, float dyNotches);

    /**
     * A key event.
     *
     * @param keyCode   a {@link UiKeys} constant, or 0 when this is text-only input
     * @param text      the typed character(s), or null for a non-text key
     * @param shift     shift held
     * @param control   control (or command on macOS) held
     * @return true if the UI consumed this key
     */
    boolean key(int keyCode, String text, boolean shift, boolean control);
}
