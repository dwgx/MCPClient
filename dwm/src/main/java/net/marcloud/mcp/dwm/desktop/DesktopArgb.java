package net.marcloud.mcp.dwm.desktop;

/**
 * Packed-ARGB helpers for the Desktop launcher components. The material package has an
 * equivalent, but it is package-private there; Desktop keeps its own tiny copy rather than
 * widening that API. Components never invent colors — they only re-alpha a role color from
 * the theme.
 */
final class DesktopArgb {

    private DesktopArgb() {
    }

    /** Replace the alpha channel of {@code argb} with {@code alpha} in [0,1]; RGB kept. */
    static int withAlpha(int argb, float alpha) {
        int ai = Math.round(clamp01(alpha) * 255f) & 0xFF;
        return (ai << 24) | (argb & 0x00FFFFFF);
    }

    /** Multiply the existing alpha by {@code factor} in [0,1]. */
    static int scaleAlpha(int argb, float factor) {
        float existing = ((argb >>> 24) & 0xFF) / 255f;
        return withAlpha(argb, existing * clamp01(factor));
    }

    static float clamp01(float v) {
        return v <= 0f ? 0f : (v >= 1f ? 1f : v);
    }
}
