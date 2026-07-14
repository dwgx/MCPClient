package net.marcloud.mcp.dwm.gl;

import java.lang.reflect.Method;
import java.util.List;

import net.marcloud.mcp.dwm.backend.FrameInput;

/**
 * Reads the live game's pointer + button state REFLECTIVELY (the shim's
 * {@code org.lwjgl.input.Mouse}) and marshals it into a DWM {@link FrameInput} in
 * OVERLAY pixel space, so the MD3 component tree can hit-test the real cursor. Pure-Java
 * twin of {@link GameBackendHost} — hard-links neither the shim nor MC at compile time
 * (both are on the runtime classpath via the game jars); every lookup is fault-isolated
 * so an unresolved handle degrades to an empty {@link FrameInput}, never a throw on the
 * render thread.
 *
 * <p><b>Coordinate flip.</b> {@code Mouse.getX()/getY()} is BOTTOM-LEFT origin (y-up,
 * "already GL-oriented" per the shim). The overlay ortho is TOP-LEFT (y-down), so
 * {@code overlayY = fbHeight - 1 - mouseY}. X passes through. The caller supplies the
 * framebuffer height for the frame (it already has it from the metrics).
 *
 * <p><b>Button mask.</b> Bit {@code 1<<n} is set when {@code Mouse.isButtonDown(n)} is
 * true; bit 0 is the primary button, matching {@code MaterialButton.PRIMARY_BUTTON == 1}.
 * Reflection handles are resolved once and cached.
 */
public final class GameInput {

    private Method getX;
    private Method getY;
    private Method isButtonDown;

    private boolean resolved;

    public GameInput() {
        try {
            Class<?> mouse = Class.forName("org.lwjgl.input.Mouse");
            getX = mouse.getMethod("getX");
            getY = mouse.getMethod("getY");
            isButtonDown = mouse.getMethod("isButtonDown", int.class);
            resolved = true;
        } catch (Throwable t) {
            System.err.println("[GameInput] Mouse reflection unavailable, input disabled: " + t);
        }
    }

    /**
     * Build a {@link FrameInput} for this frame with the pointer in overlay pixel space.
     *
     * @param fbHeightPx the framebuffer height in pixels (for the y-flip)
     * @return the frame's input, or {@link FrameInput#none()} if unresolved / any fault
     */
    public FrameInput read(int fbHeightPx) {
        if (!resolved) {
            return FrameInput.none();
        }
        try {
            int mx = (Integer) getX.invoke(null);
            int my = (Integer) getY.invoke(null);
            float px = mx;
            float py = Math.max(0, fbHeightPx - 1 - my); // y-up -> y-down overlay space
            int mask = 0;
            // Buttons 0..2 (left/right/middle) — bit n = 1<<n; bit 0 = primary.
            for (int b = 0; b < 3; b++) {
                if (Boolean.TRUE.equals(isButtonDown.invoke(null, b))) {
                    mask |= (1 << b);
                }
            }
            return new FrameInput(px, py, mask, 0f, 0f, List.of(), List.of());
        } catch (Throwable t) {
            return FrameInput.none();
        }
    }
}
