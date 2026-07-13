package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.theme.MdcTheme;

/**
 * Deterministic theme with unique per-role ARGB values so tests can assert
 * "color came from theme" (a hardcoded substitute would not match).
 */
final class FakeMdcTheme implements MdcTheme {

    // Distinct full-opaque ARGB tokens — never collide with accidental 0xFF000000 etc.
    static final int PRIMARY = 0xFF6750A4;
    static final int ON_PRIMARY = 0xFFFFFFFF;
    static final int PRIMARY_CONTAINER = 0xFFEADDFF;
    static final int ON_PRIMARY_CONTAINER = 0xFF21005D;
    static final int SECONDARY = 0xFF625B71;
    static final int ON_SECONDARY = 0xFFFFFFFF;
    static final int SURFACE = 0xFFFEF7FF;
    static final int ON_SURFACE = 0xFF1D1B20;
    static final int SURFACE_VARIANT = 0xFFE7E0EC;
    static final int ON_SURFACE_VARIANT = 0xFF49454F;
    static final int OUTLINE = 0xFF79747E;
    static final int OUTLINE_VARIANT = 0xFFCAC4D0;
    static final int ERROR = 0xFFB3261E;
    static final int ON_ERROR = 0xFFFFFFFF;

    static final float CORNER_FULL = 20f;
    static final float LABEL_LARGE_PX = 14f;

    private final boolean dark;

    FakeMdcTheme() {
        this(false);
    }

    FakeMdcTheme(boolean dark) {
        this.dark = dark;
    }

    @Override
    public int color(ColorRole role) {
        return switch (role) {
            case PRIMARY -> PRIMARY;
            case ON_PRIMARY -> ON_PRIMARY;
            case PRIMARY_CONTAINER -> PRIMARY_CONTAINER;
            case ON_PRIMARY_CONTAINER -> ON_PRIMARY_CONTAINER;
            case SECONDARY -> SECONDARY;
            case ON_SECONDARY -> ON_SECONDARY;
            case SURFACE -> SURFACE;
            case ON_SURFACE -> ON_SURFACE;
            case SURFACE_VARIANT -> SURFACE_VARIANT;
            case ON_SURFACE_VARIANT -> ON_SURFACE_VARIANT;
            case OUTLINE -> OUTLINE;
            case OUTLINE_VARIANT -> OUTLINE_VARIANT;
            case ERROR -> ERROR;
            case ON_ERROR -> ON_ERROR;
        };
    }

    @Override
    public float corner(ShapeSize size) {
        return switch (size) {
            case NONE -> 0f;
            case EXTRA_SMALL -> 4f;
            case SMALL -> 8f;
            case MEDIUM -> 12f;
            case LARGE -> 16f;
            case EXTRA_LARGE -> 28f;
            case FULL -> CORNER_FULL;
        };
    }

    @Override
    public float typeSizePx(TypeRole role) {
        return switch (role) {
            case DISPLAY_LARGE -> 57f;
            case HEADLINE_MEDIUM -> 28f;
            case TITLE_MEDIUM -> 16f;
            case LABEL_LARGE -> LABEL_LARGE_PX;
            case BODY_MEDIUM -> 14f;
        };
    }

    @Override
    public boolean dark() {
        return dark;
    }
}
