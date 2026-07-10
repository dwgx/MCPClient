package net.marcloud.mcp.core.gui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The interaction state of a {@link GuiElement}. Fields that don't apply to a
 * kind are reported with their neutral default (e.g. a button has no
 * {@code focused} concept, so it stays {@code false}).
 */
public record State(boolean enabled, boolean visible, boolean focused, boolean hovered) {

    /** Ordered map view for JSON emission / the MCP tool layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("visible", visible);
        m.put("focused", focused);
        m.put("hovered", hovered);
        return m;
    }
}
