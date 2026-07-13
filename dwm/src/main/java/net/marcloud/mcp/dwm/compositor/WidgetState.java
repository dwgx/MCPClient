package net.marcloud.mcp.dwm.compositor;

/**
 * Retained per-widget animation/interaction state (ripple progress, state-layer
 * alpha, elevation transition, drag offset). This is the ONE thing that must
 * survive frames in an immediate-mode world — TIME. Geometry is recomputed every
 * frame; only implementations of this interface are retained in {@link
 * UiStateStore}.
 *
 * <p>Backend-agnostic by contract: a {@code WidgetState} holds ZERO backend / GL /
 * imgui type, so it survives a backend hot-swap untouched.
 */
public interface WidgetState {

    /**
     * Advance this state's timeline by {@code dtSeconds}. Called once per frame,
     * before draw, for every live state.
     */
    void tick(float dtSeconds);

    /**
     * Whether this state is mid-animation. Drives the frame pump: if any live state
     * is animating, the compositor renders continuously; otherwise on demand.
     */
    boolean animating();
}
