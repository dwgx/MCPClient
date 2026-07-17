package net.marcloud.mcp.dwm.desktop.theme;

import net.marcloud.mcp.dwm.theme.MdcTheme;

/**
 * The live theme provider for the Desktop launcher — an {@link MdcTheme} whose roles are
 * derived from a mutable {@link ThemeState} (preset surfaces + accent + font scale). It is
 * the unified color/type source every component already reads via
 * {@code ComponentContext.theme()}, so editing the {@link ThemeState} from a settings panel
 * recolors and rescales the entire UI live, with no component changes.
 *
 * <p>Pure data + math, no drawing, no backend type. Reads the state each call (never
 * caches) so a mid-session change is reflected on the very next frame.
 */
public final class DesktopTheme implements MdcTheme {

    private final ThemeState state;

    public DesktopTheme(ThemeState state) {
        this.state = state == null ? new ThemeState() : state;
    }

    /** The mutable state a settings panel edits. */
    public ThemeState state() {
        return state;
    }

    @Override
    public boolean dark() {
        return !state.preset().light;
    }

    @Override
    public int color(ColorRole role) {
        ThemeState.Preset p = state.preset();
        int accent = state.accent();
        return switch (role) {
            case PRIMARY -> accent;
            case ON_PRIMARY -> onOf(accent);
            case PRIMARY_CONTAINER -> mix(p.surfaceVariant, accent, 0.35f);
            case ON_PRIMARY_CONTAINER -> p.onSurface;
            case SECONDARY -> mix(p.onSurfaceVariant, accent, 0.4f);
            case ON_SECONDARY -> onOf(p.onSurfaceVariant);
            case SURFACE -> p.surface;
            case ON_SURFACE -> p.onSurface;
            case SURFACE_VARIANT -> p.surfaceVariant;
            case ON_SURFACE_VARIANT -> p.onSurfaceVariant;
            case OUTLINE -> mix(p.surfaceVariant, p.onSurfaceVariant, 0.5f);
            case OUTLINE_VARIANT -> p.surfaceVariant;
            case ERROR -> 0xFFFF5449;
            case ON_ERROR -> 0xFFFFFFFF;
        };
    }

    /** Raised surface (search field, hovered row base) — one step lighter/darker than surface. */
    public int surfaceHi() {
        return state.preset().surfaceHi;
    }

    /** Panel background alpha for the acrylic base (0..255). */
    public int panelAlpha() {
        return state.panelOpacity();
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
            case FULL -> 1000f;
        };
    }

    @Override
    public float typeSizePx(TypeRole role) {
        float base = switch (role) {
            case DISPLAY_LARGE -> 40f;
            case HEADLINE_MEDIUM -> 24f;
            case TITLE_MEDIUM -> 15f;
            case LABEL_LARGE -> 13f;
            case BODY_MEDIUM -> 13f;
        };
        return base * state.fontScale();
    }

    // ---- color math -----------------------------------------------------------------

    /** Pick black/white for text ON the given background by luminance. */
    private static int onOf(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        double lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return lum > 0.55 ? 0xFF16161A : 0xFFFFFFFF;
    }

    /** Linear blend of two opaque colors: {@code t} in [0,1] toward {@code b}. */
    private static int mix(int a, int b, float t) {
        float tt = t < 0f ? 0f : (t > 1f ? 1f : t);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * tt);
        int g = Math.round(ag + (bg - ag) * tt);
        int bl = Math.round(ab + (bb - ab) * tt);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
