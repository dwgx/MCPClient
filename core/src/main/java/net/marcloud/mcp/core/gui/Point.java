package net.marcloud.mcp.core.gui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single point in scaled-GUI space (top-left origin). Used as the
 * click-point the server feeds into the real {@code mouseClicked} handler, so
 * the LLM never has to guess pixels.
 */
public record Point(int x, int y) {

    /** Ordered map view for JSON emission / the MCP tool layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }
}
