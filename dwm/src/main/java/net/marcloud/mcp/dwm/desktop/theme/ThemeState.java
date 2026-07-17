package net.marcloud.mcp.dwm.desktop.theme;

/**
 * The live, mutable theme model the Desktop launcher reads through — the single source a
 * settings/theme panel edits to recolor the WHOLE UI at once (Codex/Claude-desktop style
 * theme system). Session-only: no persistence. Holds no backend/GL type.
 *
 * <p>A {@link net.marcloud.mcp.dwm.desktop.theme.DesktopTheme} wraps one of these and
 * implements {@link net.marcloud.mcp.dwm.theme.MdcTheme}, so every existing component
 * (which already pulls color/shape/type through {@code ComponentContext.theme()}) recolors
 * with zero component changes when this state mutates.
 *
 * <p>Model: a {@link Preset} base (dark/light + surface tone), an {@code accent} override
 * (the primary color the color-picker sets), and a {@code fontScale} multiplier applied to
 * every type size. Roles are derived from these in {@link DesktopTheme}.
 */
public final class ThemeState {

    /** Built-in presets: base surface family + light/dark, before the accent override. */
    public enum Preset {
        MIDNIGHT(false, 0xFF141218, 0xFF1E1B24, 0xFF2B2731, 0xFFE6E0E9, 0xFFB6AEC2),
        SLATE(false, 0xFF12161C, 0xFF1B222B, 0xFF28313D, 0xFFE3E8EF, 0xFFA6B0BD),
        CARBON(false, 0xFF0E0E10, 0xFF17171A, 0xFF232327, 0xFFEDEDED, 0xFF9A9AA2),
        LIGHT(true, 0xFFFAF7FF, 0xFFFFFFFF, 0xFFE9E2F0, 0xFF1D1B20, 0xFF5A5560);

        final boolean light;
        final int surface;         // panel background
        final int surfaceHi;       // raised surface (search bar, rows hover base)
        final int surfaceVariant;  // chips / icon tiles when off
        final int onSurface;       // primary text
        final int onSurfaceVariant;// secondary text

        Preset(boolean light, int surface, int surfaceHi, int surfaceVariant,
               int onSurface, int onSurfaceVariant) {
            this.light = light;
            this.surface = surface;
            this.surfaceHi = surfaceHi;
            this.surfaceVariant = surfaceVariant;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
        }

        /** Representative panel color for a settings-panel preview swatch. */
        public int surfaceColor() {
            return surface;
        }

        /** Representative text color for a settings-panel preview swatch. */
        public int onSurfaceColor() {
            return onSurface;
        }

        /** True if this preset is a light theme (vs dark) — for the settings label. */
        public boolean isLight() {
            return light;
        }
    }

    /** Built-in accent choices for the color picker (the primary color). */
    public static final int[] ACCENTS = {
            0xFF4C8DFF, // blue (Win11 default-ish)
            0xFF7C5CFF, // violet
            0xFF2ED47A, // green
            0xFFFF7A45, // orange
            0xFFFF4D6D, // pink/red
            0xFF33C4C4, // teal
    };

    private Preset preset = Preset.MIDNIGHT;
    private int accent = ACCENTS[0];
    private float fontScale = 1.0f;
    private int panelOpacity = 0xF5;   // panel background alpha (acrylic base), 0..255
    // 0xF5 (~96%): opaque enough that the game UI behind does not bleed through the panel,
    // while still reading as a translucent acrylic surface. Lower values (0xE6) let MC's
    // bright main-menu buttons/splash show through (seen in the live checkpoint capture).

    public Preset preset() {
        return preset;
    }

    public void setPreset(Preset preset) {
        if (preset != null) {
            this.preset = preset;
        }
    }

    public int accent() {
        return accent;
    }

    public void setAccent(int argb) {
        this.accent = 0xFF000000 | (argb & 0x00FFFFFF);
    }

    public float fontScale() {
        return fontScale;
    }

    /** Clamp font scale to a sane launcher range. */
    public void setFontScale(float scale) {
        this.fontScale = scale < 0.75f ? 0.75f : (scale > 1.5f ? 1.5f : scale);
    }

    public int panelOpacity() {
        return panelOpacity & 0xFF;
    }

    public void setPanelOpacity(int alpha0to255) {
        this.panelOpacity = alpha0to255 < 0 ? 0 : (alpha0to255 > 255 ? 255 : alpha0to255);
    }
}
