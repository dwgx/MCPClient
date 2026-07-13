package net.marcloud.mcp.dwm.compositor;

import java.util.Objects;

/**
 * Stable identity for a widget across frames, independent of draw order (the imgui
 * PushID / React key idiom). The retained {@link UiStateStore} keys animation
 * state by {@code WidgetId}, so a widget's ripple/state-layer timeline survives
 * from frame to frame even though geometry is recomputed every frame.
 *
 * <p>LISTS must pass a stable per-item key (not the loop index) or animation state
 * flickers/swaps when the list reorders — the classic immediate-mode id pitfall.
 */
public final class WidgetId {

    private final String path;

    private WidgetId(String path) {
        this.path = path;
    }

    /** A root id from a stable key. */
    public static WidgetId root(String key) {
        return new WidgetId(require(key));
    }

    /** A child id: {@code parent/key}. */
    public static WidgetId of(WidgetId parent, String key) {
        Objects.requireNonNull(parent, "parent");
        return new WidgetId(parent.path + "/" + require(key));
    }

    private static String require(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("widget key must not be empty");
        }
        return key;
    }

    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WidgetId w && w.path.equals(path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return "WidgetId[" + path + "]";
    }
}
