package net.marcloud.mcp.dwm.backend;

import java.util.List;

/**
 * A polyline + arc path for {@link DrawContext#path}: MD3 ripple arcs, checkmarks,
 * custom shape outlines. Neutral geometry (points in DIP); the backend tessellates
 * it, or a capability-limited backend approximates arcs with polygons.
 */
public record PathSpec(List<float[]> points, boolean closed) {

    public PathSpec {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
