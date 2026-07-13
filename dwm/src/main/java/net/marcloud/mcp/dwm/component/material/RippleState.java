package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.compositor.WidgetState;

/**
 * Retained MD3-style ripple timeline: press expands a circle from the contact
 * point (decelerate / ease-out), release fades alpha out. Geometry is re-issued
 * every frame by the component; only this progress lives in
 * {@link net.marcloud.mcp.dwm.compositor.UiStateStore}.
 *
 * <p>Timings approximate material-components-android / material-web ripple
 * (enter ~450ms expand, exit ~375ms fade) — "looks like MD3", not a pixel-perfect
 * port of the Android View animator.
 */
public final class RippleState implements WidgetState {

    /** Expand duration (seconds), MD3-ish enter. */
    public static final float EXPAND_SECONDS = 0.45f;

    /** Fade-out duration (seconds) after release. */
    public static final float FADE_SECONDS = 0.375f;

    /** Peak ripple opacity on the on-color (MD3 pressed-layer ballpark). */
    public static final float PEAK_ALPHA = 0.12f;

    private float originX;
    private float originY;
    private float maxRadius;

    /** 0..1 expansion progress (eased when read). */
    private float expandT;
    /** Current draw alpha (linear in time during fade). */
    private float alpha;

    private boolean holding;
    private boolean fading;
    private boolean alive;

    /**
     * Begin a ripple at local (widget-space) coordinates. Idempotent while already
     * holding: ignores re-entrant presses so multi-frame downs do not restart.
     */
    public void press(float localX, float localY) {
        if (holding) {
            return;
        }
        this.originX = localX;
        this.originY = localY;
        this.expandT = 0f;
        this.alpha = PEAK_ALPHA;
        this.holding = true;
        this.fading = false;
        this.alive = true;
    }

    /**
     * Pointer released: keep expanding if incomplete, start fade-out.
     */
    public void release() {
        if (!holding && !alive) {
            return;
        }
        holding = false;
        if (alive) {
            fading = true;
        }
    }

    /**
     * Corner-to-corner coverage radius for the current widget box. Call each frame
     * before reading {@link #radius()} so expansion targets the true bounds.
     */
    public void setMaxRadius(float maxRadius) {
        this.maxRadius = Math.max(0f, maxRadius);
    }

    public boolean holding() {
        return holding;
    }

    public boolean visible() {
        return alive && alpha > 0.001f;
    }

    public float originX() {
        return originX;
    }

    public float originY() {
        return originY;
    }

    /** Current circle radius in DIP (ease-out of expand progress * maxRadius). */
    public float radius() {
        return easeOutCubic(expandT) * maxRadius;
    }

    public float alpha() {
        return alpha;
    }

    @Override
    public void tick(float dtSeconds) {
        if (!alive || dtSeconds <= 0f) {
            return;
        }
        if (expandT < 1f) {
            expandT = Math.min(1f, expandT + dtSeconds / EXPAND_SECONDS);
        }
        if (fading) {
            alpha -= PEAK_ALPHA * (dtSeconds / FADE_SECONDS);
            if (alpha <= 0f) {
                alpha = 0f;
                alive = false;
                fading = false;
                expandT = 0f;
            }
        }
    }

    @Override
    public boolean animating() {
        if (!alive) {
            return false;
        }
        if (expandT < 1f) {
            return true;
        }
        return fading;
    }

    /** Cubic ease-out: fast start, soft landing (MD3 decelerate family). */
    static float easeOutCubic(float t) {
        float u = Argb.clamp01(t);
        float inv = 1f - u;
        return 1f - inv * inv * inv;
    }

    /**
     * Max radius that covers the widget from {@code (ox,oy)} to the farthest corner.
     */
    public static float coverageRadius(float ox, float oy, float w, float h) {
        float dx = Math.max(ox, w - ox);
        float dy = Math.max(oy, h - oy);
        return (float) Math.hypot(dx, dy);
    }
}
