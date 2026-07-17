package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.ShapeSize;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * The launcher's top search field: a rounded surface-variant bar with a magnifier glyph
 * and either the live query or a placeholder. Phase 1 is display-only — it shows whatever
 * query string it is given (typed input is wired to the keyboard seam in a later phase);
 * the box, icon, and text/placeholder are the fixed template.
 *
 * <p>Draws only through {@link DrawContext}, so it renders identically on gl / imgui /
 * skiko. The magnifier is drawn as a small ring + handle line (no glyph atlas dependency).
 */
public final class SearchBar implements Component {

    /** Search-bar height (DIP). */
    public static final float HEIGHT_DP = 40f;
    private static final float PAD_H_DP = 14f;
    private static final float ICON_DP = 16f;
    private static final FontHandle FONT = new FontHandle(0L);

    private final String query;
    private final String placeholder;

    public SearchBar(String query, String placeholder) {
        this.query = query == null ? "" : query;
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        // Full-width by design; intrinsic height is fixed.
        return new Size(0f, HEIGHT_DP);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        DrawContext d = ctx.draw();

        // Rounded field background.
        float radius = Math.min(theme.corner(ShapeSize.LARGE), h * 0.5f);
        d.roundedRect(x, y, w, h, radius, theme.color(ColorRole.SURFACE_VARIANT));

        // Magnifier (Lucide-style ring + handle), vertically centered at the left pad.
        int iconColor = theme.color(ColorRole.ON_SURFACE_VARIANT);
        DeskIcons.search(d, x + PAD_H_DP, y + (h - ICON_DP) * 0.5f, ICON_DP, iconColor);

        // Text: the query, or placeholder in a dimmer tone.
        float textSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        float textX = x + PAD_H_DP + ICON_DP + 8f;
        float baseline = y + h * 0.5f + textSize * 0.35f;
        if (query.isEmpty()) {
            d.text(FONT, textSize, textX, baseline,
                    DesktopArgb.scaleAlpha(theme.color(ColorRole.ON_SURFACE_VARIANT), 0.7f),
                    placeholder);
        } else {
            d.text(FONT, textSize, textX, baseline, theme.color(ColorRole.ON_SURFACE), query);
        }
        return Result.idle();
    }
}
