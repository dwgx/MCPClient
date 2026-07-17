package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.compositor.WidgetState;

/**
 * Retained vertical-scroll state for a scrollable launcher region (the "All" software list).
 * Immediate-mode drawing recomputes geometry each frame, so the scroll offset is the one bit
 * that must persist between frames; it lives here in the {@link net.marcloud.mcp.dwm.compositor.UiStateStore}
 * keyed by the region's {@link net.marcloud.mcp.dwm.compositor.WidgetId}.
 *
 * <p>The offset is a positive number of DIPs the content is scrolled UP by (so row {@code i}
 * draws at {@code baseY + i*rowH - offset}). Each frame the caller feeds the wheel delta and
 * the current content/viewport heights via {@link #apply}; the offset is clamped to
 * {@code [0, max(0, contentHeight - viewportHeight)]} so it can never scroll past the ends or
 * scroll at all when everything fits. Backend-agnostic: holds no GL/imgui type.
 */
public final class ScrollState implements WidgetState {

    /** DIPs scrolled per wheel notch — one notch moves roughly two list rows. */
    private static final float DIP_PER_NOTCH = 88f;

    private float offset;

    @Override
    public void tick(float dtSeconds) {
        // No timeline: the offset only changes on wheel input, applied in apply().
    }

    @Override
    public boolean animating() {
        return false;
    }

    /**
     * Advance the offset by {@code wheelNotches} (up = positive notches scroll the content up,
     * revealing lower items) and clamp it to the scrollable range for the current sizes.
     *
     * @param wheelNotches   wheel delta in notches this frame (FrameInput.scrollY)
     * @param contentHeight  total height of all rows (DIP)
     * @param viewportHeight visible region height (DIP)
     * @return the clamped offset to apply this frame
     */
    public float apply(float wheelNotches, float contentHeight, float viewportHeight) {
        // Wheel up (positive) should reveal content further down -> increase offset.
        offset -= wheelNotches * DIP_PER_NOTCH;
        return clampTo(contentHeight, viewportHeight);
    }

    /** Clamp the current offset to {@code [0, max(0, content - viewport)]} and return it. */
    public float clampTo(float contentHeight, float viewportHeight) {
        float max = Math.max(0f, contentHeight - viewportHeight);
        if (offset < 0f) {
            offset = 0f;
        } else if (offset > max) {
            offset = max;
        }
        return offset;
    }

    /** The current scroll offset in DIPs (content is shifted up by this much). */
    public float offset() {
        return offset;
    }
}
