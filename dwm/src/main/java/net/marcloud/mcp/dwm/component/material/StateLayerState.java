package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.compositor.WidgetState;

/**
 * Retained MD3 state-layer opacity (and optional elevation) transition.
 * Hover / focus / pressed targets follow the MD3 state-layer table; the
 * component re-paints a full-shape rect of the on-color at {@link #layerAlpha()}
 * each frame.
 *
 * <p>Opacity reference (Material Design 3): hover 0.08, focus 0.12, pressed 0.12.
 */
public final class StateLayerState implements WidgetState {

    /** MD3 state-layer opacities. */
    public static final float HOVER_ALPHA = 0.08f;
    public static final float FOCUS_ALPHA = 0.12f;
    public static final float PRESSED_ALPHA = 0.12f;
    public static final float DRAGGED_ALPHA = 0.16f;

    /** Cross-fade time toward the target alpha (seconds). */
    public static final float TRANSITION_SECONDS = 0.08f;

    /**
     * Interaction tier driving the state layer. Higher tiers win when several
     * apply (pressed &gt; focus &gt; hover &gt; none).
     */
    public enum Interaction {
        NONE(0f, 0f),
        HOVERED(HOVER_ALPHA, 1f),
        FOCUSED(FOCUS_ALPHA, 1f),
        PRESSED(PRESSED_ALPHA, 0f),
        DRAGGED(DRAGGED_ALPHA, 0f);

        private final float layerAlpha;
        private final float elevBoostDp;

        Interaction(float layerAlpha, float elevBoostDp) {
            this.layerAlpha = layerAlpha;
            this.elevBoostDp = elevBoostDp;
        }

        public float targetLayerAlpha() {
            return layerAlpha;
        }

        public float elevationBoostDp() {
            return elevBoostDp;
        }
    }

    private Interaction interaction = Interaction.NONE;
    private float layerAlpha;
    private float elevationDp;
    private float baseElevationDp;
    private float targetElevationDp;

    /** Set rest elevation (e.g. Elevated button = 1dp). */
    public void setBaseElevationDp(float baseElevationDp) {
        this.baseElevationDp = Math.max(0f, baseElevationDp);
        // Re-derive target from current interaction.
        this.targetElevationDp = this.baseElevationDp + interaction.elevationBoostDp();
    }

    /**
     * Update the desired interaction. Does not snap alpha; {@link #tick} eases
     * toward the new target (one-frame lag if the compositor ticks before draw).
     */
    public void setInteraction(Interaction interaction) {
        this.interaction = interaction == null ? Interaction.NONE : interaction;
        this.targetElevationDp = baseElevationDp + this.interaction.elevationBoostDp();
    }

    public Interaction interaction() {
        return interaction;
    }

    /** Current eased state-layer opacity in [0,1]. */
    public float layerAlpha() {
        return layerAlpha;
    }

    /** Current eased elevation in dp (for Elevated / FAB-style surfaces). */
    public float elevationDp() {
        return elevationDp;
    }

    @Override
    public void tick(float dtSeconds) {
        if (dtSeconds <= 0f) {
            return;
        }
        float targetA = interaction.targetLayerAlpha();
        layerAlpha = approach(layerAlpha, targetA, dtSeconds, TRANSITION_SECONDS);
        elevationDp = approach(elevationDp, targetElevationDp, dtSeconds, TRANSITION_SECONDS);
    }

    @Override
    public boolean animating() {
        float targetA = interaction.targetLayerAlpha();
        return Math.abs(layerAlpha - targetA) > 0.0005f
                || Math.abs(elevationDp - targetElevationDp) > 0.0005f;
    }

    /**
     * Frame-rate independent move toward {@code target}. A single tick of
     * {@code duration} seconds reaches the target; smaller steps ease in.
     */
    private static float approach(float current, float target, float dt, float duration) {
        if (duration <= 0f) {
            return target;
        }
        float t = Math.min(1f, dt / duration);
        return current + (target - current) * t;
    }
}
