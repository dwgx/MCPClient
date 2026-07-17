package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.compositor.WidgetState;

/**
 * Minimal retained click-edge state for a Desktop clickable (row / tile / power button).
 * Immediate-mode input is a per-frame snapshot, so "was clicked" needs one bit of memory:
 * a click fires on the press-then-release-while-still-hovered edge, exactly the
 * MaterialButton idiom, without pulling in the full ripple/state-layer machinery.
 *
 * <p>Also carries a small hover-highlight alpha that eases toward the hover target each
 * frame, so rows/tiles get a subtle hover feel that survives frames (the one thing that
 * must be retained). Backend-agnostic: holds no GL/imgui type.
 */
public final class ClickState implements WidgetState {

    private boolean holding;      // primary went down while hovering; awaiting release
    private boolean holdingSecondary; // secondary (right) went down while hovering
    private float hoverAlpha;     // eased hover-highlight [0,1]
    private float hoverTarget;    // 1 when hovered this frame, else 0

    /** Ease the hover highlight toward its target (~120ms). */
    @Override
    public void tick(float dtSeconds) {
        float speed = dtSeconds / 0.12f;
        if (speed < 0f) {
            speed = 0f;
        } else if (speed > 1f) {
            speed = 1f;
        }
        hoverAlpha += (hoverTarget - hoverAlpha) * speed;
        if (Math.abs(hoverAlpha - hoverTarget) < 0.001f) {
            hoverAlpha = hoverTarget;
        }
    }

    @Override
    public boolean animating() {
        return hoverAlpha != hoverTarget;
    }

    /**
     * Feed this frame's interaction and get whether a click completed this frame.
     *
     * @param hovered     pointer is inside the clickable's bounds
     * @param pointerDown primary button is held this frame
     * @return true exactly on the frame the click completes (release while hovered)
     */
    public boolean update(boolean hovered, boolean pointerDown) {
        hoverTarget = hovered ? 1f : 0f;
        boolean clicked = false;
        if (hovered && pointerDown && !holding) {
            holding = true;               // press edge
        } else if (holding && !pointerDown) {
            clicked = hovered;            // release edge → click only if still hovered
            holding = false;
        }
        return clicked;
    }

    /** Current eased hover-highlight alpha in [0,1]. */
    public float hoverAlpha() {
        return hoverAlpha;
    }

    /** True while the pointer is pressed after going down on this clickable. */
    public boolean holding() {
        return holding;
    }

    /**
     * Secondary-button (right-click) edge, mirroring {@link #update} for the primary button.
     * Feed whether the secondary button is held this frame; returns true exactly on the frame
     * a secondary click completes (release while still hovered). Kept separate from the
     * primary edge so a row can distinguish left-click (toggle) from right-click (pin).
     *
     * @param hovered        pointer is inside the clickable's bounds
     * @param secondaryDown  secondary (right) button is held this frame
     * @return true on the frame the secondary click completes
     */
    public boolean updateSecondary(boolean hovered, boolean secondaryDown) {
        boolean clicked = false;
        if (hovered && secondaryDown && !holdingSecondary) {
            holdingSecondary = true;
        } else if (holdingSecondary && !secondaryDown) {
            clicked = hovered;
            holdingSecondary = false;
        }
        return clicked;
    }
}
