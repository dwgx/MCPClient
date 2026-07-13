package net.marcloud.mcp.dwm.backend;

/**
 * Fill/stroke paint for {@link DrawContext#path}. Neutral value type.
 */
public record PaintSpec(int argb, boolean fill, float strokeThickness) {

    public static PaintSpec fill(int argb) {
        return new PaintSpec(argb, true, 0f);
    }

    public static PaintSpec stroke(int argb, float thickness) {
        return new PaintSpec(argb, false, thickness);
    }
}
