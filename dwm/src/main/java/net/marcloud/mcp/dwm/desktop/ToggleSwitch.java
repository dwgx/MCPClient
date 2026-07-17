package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;

/**
 * A Win11 / MD3-style on/off switch drawn purely through {@link DrawContext}: a pill-shaped
 * track with a circular knob that sits left (off) or right (on), the track filled with the
 * accent color when on and an outline-ish surface when off. This is the visible on/off
 * control for a software row — far clearer than a small state dot.
 *
 * <p>Not a {@link net.marcloud.mcp.dwm.component.Component}: it is a stateless draw helper
 * invoked by the row (which owns hit-testing and the enabled snapshot), so it needs no id or
 * retained state. The knob position is derived from {@code on} (optionally eased by the
 * caller via {@code progress} for a slide animation; pass 1f/0f for a hard snap).
 */
public final class ToggleSwitch {

    /** Track width (DIP). */
    public static final float WIDTH_DP = 34f;
    /** Track height (DIP). */
    public static final float HEIGHT_DP = 18f;

    private ToggleSwitch() {
    }

    /**
     * Draw the switch at {@code (x,y)} with the standard {@link #WIDTH_DP}x{@link #HEIGHT_DP}.
     *
     * @param d        the draw context
     * @param theme    color source
     * @param x        left of the track
     * @param y        top of the track
     * @param progress knob travel in [0,1]: 0 = fully off (left), 1 = fully on (right).
     *                 Callers may pass an eased value for a slide; a hard 0/1 also looks fine.
     */
    public static void draw(DrawContext d, MdcTheme theme, float x, float y, float progress) {
        float p = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        float w = WIDTH_DP;
        float h = HEIGHT_DP;
        float radius = h * 0.5f;

        // Track: accent when (mostly) on, surface-variant when off; blend by progress so the
        // color crossfades with the knob slide.
        int off = theme.color(ColorRole.SURFACE_VARIANT);
        int on = theme.color(ColorRole.PRIMARY);
        int track = blend(off, on, p);
        d.roundedRect(x, y, w, h, radius, track);
        // No sharp rectStroke outline: a SQUARE stroke over the ROUNDED pill track left white
        // box corners poking past the rounded ends ("按钮边缘白色的框"). The filled pill defines
        // the switch edge cleanly on its own; DrawContext has no rounded-rect stroke.

        // Knob: a circle inset by 2dp, sliding from left to right as progress goes 0->1.
        float inset = 2f;
        float knob = h - inset * 2f;
        float travel = w - inset * 2f - knob;
        float kx = x + inset + travel * p;
        float ky = y + inset;
        int knobColor = p > 0.5f
                ? theme.color(ColorRole.ON_PRIMARY) : theme.color(ColorRole.ON_SURFACE_VARIANT);
        d.roundedRect(kx, ky, knob, knob, knob * 0.5f, knobColor);
    }

    /** Linear blend of two opaque ARGB colors toward {@code b} by {@code t} in [0,1]. */
    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
