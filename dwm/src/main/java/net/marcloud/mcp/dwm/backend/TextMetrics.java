package net.marcloud.mcp.dwm.backend;

/**
 * Measured text extent. MUST be computable even by a headless NullBackend (via a
 * fixed-advance approximation) so layout can run without a live GL context.
 */
public record TextMetrics(float width, float ascent, float descent) {

    public float height() {
        return ascent + descent;
    }
}
