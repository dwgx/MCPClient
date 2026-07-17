package net.marcloud.mcp.dwm.desktop;

/**
 * The UI-side description of one piece of "software" — the Desktop (this module's core
 * display, the "integrated graphics" in the CPU/iGPU/monitor split) knows how to draw a
 * software entry from this, WITHOUT the software's functional body (a board {@code Chip},
 * the "CPU") needing to know any UI exists. A Chip runs and toggles with no UI; when the
 * Desktop wants to show it, it pairs the chip with a {@code SoftwareView} (or falls back
 * to a default view built from the chip's own {@code name()}/{@code category()}).
 *
 * <p>Pure data, no backend type — like an entry in a launcher's app catalog. {@code
 * enabled} is a per-frame snapshot of the chip's toggle state (the row highlights when on);
 * {@code iconId} names an icon in the Desktop's built-in set (0 = default letter/color
 * tile until a real icon set lands).
 *
 * @param chipId      stable id of the backing chip ({@code Chip.id()}); the toggle target
 * @param displayName human-readable label shown in the row/tile
 * @param category    group label ({@code Chip.category()}), or "" for uncategorised
 * @param iconId      built-in icon id, or 0 for the default placeholder tile
 * @param enabled     the chip's current toggle state, snapshotted this frame
 */
public record SoftwareView(String chipId, String displayName, String category,
                           int iconId, boolean enabled) {

    public SoftwareView {
        chipId = chipId == null ? "" : chipId;
        displayName = displayName == null || displayName.isBlank() ? chipId : displayName;
        category = category == null ? "" : category;
    }

    /** The category label to group under, or "Other" when uncategorised. */
    public String groupLabel() {
        return category.isBlank() ? "Other" : category;
    }

    /** True if this software's name/category contains {@code query} (case-insensitive). */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return displayName.toLowerCase(java.util.Locale.ROOT).contains(q)
                || category.toLowerCase(java.util.Locale.ROOT).contains(q);
    }
}
