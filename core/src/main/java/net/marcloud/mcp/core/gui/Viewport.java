package net.marcloud.mcp.core.gui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The screen geometry needed to map between scaled-GUI space (where elements
 * and click-points live) and the real framebuffer (where a PNG overlay is
 * drawn). {@code scaleFactor} is Minecraft's GUI scale: scaled = framebuffer /
 * scaleFactor. When no live {@code ScaledResolution} is available the framebuffer
 * dimensions are reported as {@code -1}.
 */
public record Viewport(int width, int height, int scaleFactor,
                       int framebufferWidth, int framebufferHeight) {

    /** Ordered map view for JSON emission / the MCP tool layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", width);
        m.put("height", height);
        m.put("scaleFactor", scaleFactor);
        m.put("framebufferWidth", framebufferWidth);
        m.put("framebufferHeight", framebufferHeight);
        return m;
    }
}
