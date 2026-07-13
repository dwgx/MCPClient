package net.marcloud.mcp.dwm.component.material;

/**
 * Packed ARGB helpers for MD3 state-layer / ripple compositing. Components never
 * invent theme colors; they only re-alpha an existing role color from
 * {@link net.marcloud.mcp.dwm.theme.MdcTheme}.
 */
final class Argb {

    private Argb() {}

    /**
     * Replace the alpha channel of {@code argb} with {@code alpha} in [0,1].
     * RGB channels are preserved.
     */
    static int withAlpha(int argb, float alpha) {
        float a = clamp01(alpha);
        int ai = Math.round(a * 255f) & 0xFF;
        return (ai << 24) | (argb & 0x00FFFFFF);
    }

    /** Multiply existing alpha by {@code factor} in [0,1]. */
    static int scaleAlpha(int argb, float factor) {
        float existing = ((argb >>> 24) & 0xFF) / 255f;
        return withAlpha(argb, existing * clamp01(factor));
    }

    static float clamp01(float v) {
        if (v <= 0f) {
            return 0f;
        }
        if (v >= 1f) {
            return 1f;
        }
        return v;
    }
}
