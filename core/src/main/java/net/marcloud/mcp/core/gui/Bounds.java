package net.marcloud.mcp.core.gui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Axis-aligned rectangle in scaled-GUI space (top-left origin), the same
 * coordinate space Minecraft uses for {@code mouseClicked(x,y,button)}. All
 * units are scaled-GUI pixels, not framebuffer pixels.
 */
public record Bounds(int x, int y, int w, int h) {

    /** Ordered map view for JSON emission / the MCP tool layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        return m;
    }
}
