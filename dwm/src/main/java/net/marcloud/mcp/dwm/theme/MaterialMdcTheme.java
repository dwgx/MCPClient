package net.marcloud.mcp.dwm.theme;

/**
 * Production {@link MdcTheme}: the Material Design 3 BASELINE color scheme (the
 * canonical light + dark role values Google publishes as the default M3 palette),
 * plus the MD3 shape and type-scale tokens. Pure data + math, no drawing, no backend
 * type — a component pulls roles/shape/type from here so a theme swap never touches
 * component code (the locked theme-decoupling contract).
 *
 * <p><b>Scope (honest).</b> This is the fixed BASELINE scheme, not yet the single-seed
 * dynamic-color algorithm (material-color-utilities HCT tonal-palette derivation). The
 * baseline values ARE real M3 — the same roles the dynamic algorithm produces for the
 * default seed — so every component renders correct M3 today; swapping in a seed-derived
 * palette later is a drop-in replacement behind this same interface. {@link #light()} and
 * {@link #dark()} give the two standard variants.
 */
public final class MaterialMdcTheme implements MdcTheme {

    private final boolean dark;

    private MaterialMdcTheme(boolean dark) {
        this.dark = dark;
    }

    /** The MD3 baseline LIGHT scheme. */
    public static MaterialMdcTheme lightTheme() {
        return new MaterialMdcTheme(false);
    }

    /** The MD3 baseline DARK scheme. */
    public static MaterialMdcTheme darkTheme() {
        return new MaterialMdcTheme(true);
    }

    @Override
    public boolean dark() {
        return dark;
    }

    // ---- MD3 baseline color roles (0xAARRGGBB, full opacity) ------------------------
    // Values are the published Material 3 baseline scheme. Light and dark are the two
    // canonical variants; a role reads from whichever variant this theme is.

    @Override
    public int color(ColorRole role) {
        return dark ? darkRole(role) : lightRole(role);
    }

    private static int lightRole(ColorRole role) {
        return switch (role) {
            case PRIMARY -> 0xFF6750A4;
            case ON_PRIMARY -> 0xFFFFFFFF;
            case PRIMARY_CONTAINER -> 0xFFEADDFF;
            case ON_PRIMARY_CONTAINER -> 0xFF21005D;
            case SECONDARY -> 0xFF625B71;
            case ON_SECONDARY -> 0xFFFFFFFF;
            case SURFACE -> 0xFFFEF7FF;
            case ON_SURFACE -> 0xFF1D1B20;
            case SURFACE_VARIANT -> 0xFFE7E0EC;
            case ON_SURFACE_VARIANT -> 0xFF49454F;
            case OUTLINE -> 0xFF79747E;
            case OUTLINE_VARIANT -> 0xFFCAC4D0;
            case ERROR -> 0xFFB3261E;
            case ON_ERROR -> 0xFFFFFFFF;
        };
    }

    private static int darkRole(ColorRole role) {
        return switch (role) {
            case PRIMARY -> 0xFFD0BCFF;
            case ON_PRIMARY -> 0xFF381E72;
            case PRIMARY_CONTAINER -> 0xFF4F378B;
            case ON_PRIMARY_CONTAINER -> 0xFFEADDFF;
            case SECONDARY -> 0xFFCCC2DC;
            case ON_SECONDARY -> 0xFF332D41;
            case SURFACE -> 0xFF141218;
            case ON_SURFACE -> 0xFFE6E0E9;
            case SURFACE_VARIANT -> 0xFF49454F;
            case ON_SURFACE_VARIANT -> 0xFFCAC4D0;
            case OUTLINE -> 0xFF938F99;
            case OUTLINE_VARIANT -> 0xFF49454F;
            case ERROR -> 0xFFF2B8B5;
            case ON_ERROR -> 0xFF601410;
        };
    }

    // ---- MD3 shape scale (corner radius in DIP) -------------------------------------

    @Override
    public float corner(ShapeSize size) {
        return switch (size) {
            case NONE -> 0f;
            case EXTRA_SMALL -> 4f;
            case SMALL -> 8f;
            case MEDIUM -> 12f;
            case LARGE -> 16f;
            case EXTRA_LARGE -> 28f;
            case FULL -> 1000f; // pill: clamped to half-height by the component/backend
        };
    }

    // ---- MD3 type scale (size in DIP) -----------------------------------------------

    @Override
    public float typeSizePx(TypeRole role) {
        return switch (role) {
            case DISPLAY_LARGE -> 57f;
            case HEADLINE_MEDIUM -> 28f;
            case TITLE_MEDIUM -> 16f;
            case LABEL_LARGE -> 14f;
            case BODY_MEDIUM -> 14f;
        };
    }
}
