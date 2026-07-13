package net.marcloud.mcp.dwm.backend;

/**
 * Request to load a font at a pixel size, with optional hinting flags. Neutral
 * value type (no backend dependency); the backend interprets it.
 */
public record FontSpec(String family, float sizePx, boolean bold, boolean italic) {

    public FontSpec {
        if (family == null || family.isBlank()) {
            throw new IllegalArgumentException("font family must not be blank");
        }
        if (sizePx <= 0f) {
            throw new IllegalArgumentException("font sizePx must be > 0");
        }
    }
}
