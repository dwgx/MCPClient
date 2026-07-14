package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * MD3 list item — a one- or two-line row: a headline (Title Medium, on-surface) and an
 * optional supporting line (Body Medium, on-surface-variant), left-aligned with the MD3
 * 16dp horizontal padding and a hover state layer. The workhorse for rendering kernel
 * state as a list (one item per privilege layer / capability / tool).
 *
 * <p>Interactive: reports hover/press/click via a state layer keyed by
 * {@link ComponentContext#id()} (so a Column of items each gets its own via the layout's
 * pushId). Fixed MD3 heights: 56dp one-line, 72dp two-line.
 */
public final class MaterialListItem implements Component {

    /** MD3 list-item horizontal padding (dp). */
    public static final float PAD_H_DP = 16f;
    /** MD3 one-line height (dp). */
    public static final float ONE_LINE_DP = 56f;
    /** MD3 two-line height (dp). */
    public static final float TWO_LINE_DP = 72f;

    private final String headline;
    private final String supporting; // null = one-line

    public MaterialListItem(String headline) {
        this(headline, null);
    }

    public MaterialListItem(String headline, String supporting) {
        this.headline = headline == null ? "" : headline;
        this.supporting = supporting;
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        DrawContext d = ctx.draw();
        MdcTheme theme = ctx.theme();

        // Hover state layer (on-surface) over the item bounds. The store's animation is
        // ticked by the Compositor; we just set the interaction target each frame.
        StateLayerState state = ctx.store().state(ctx.id(), StateLayerState::new);
        boolean hovered = pointInside(ctx, x, y, w, h);
        state.setInteraction(hovered
                ? StateLayerState.Interaction.HOVERED
                : StateLayerState.Interaction.NONE);
        float layer = state.layerAlpha();
        if (layer > 0.001f) {
            d.rect(x, y, w, h, Argb.withAlpha(theme.color(ColorRole.ON_SURFACE), layer));
        }

        boolean twoLine = supporting != null && !supporting.isEmpty();
        float headSize = theme.typeSizePx(TypeRole.TITLE_MEDIUM);
        float suppSize = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        float tx = x + PAD_H_DP;

        if (twoLine) {
            float headBaseline = y + h * 0.5f - suppSize * 0.5f + headSize * 0.35f - 2f;
            float suppBaseline = y + h * 0.5f + headSize * 0.5f + suppSize * 0.15f;
            d.text(FONT, headSize, tx, headBaseline, theme.color(ColorRole.ON_SURFACE), headline);
            d.text(FONT, suppSize, tx, suppBaseline, theme.color(ColorRole.ON_SURFACE_VARIANT), supporting);
        } else {
            float baseline = y + h * 0.5f + headSize * 0.35f;
            d.text(FONT, headSize, tx, baseline, theme.color(ColorRole.ON_SURFACE), headline);
        }
        return Result.idle();
    }

    @Override
    public Size measure(ComponentContext ctx) {
        boolean twoLine = supporting != null && !supporting.isEmpty();
        float headSize = ctx.theme().typeSizePx(TypeRole.TITLE_MEDIUM);
        float suppSize = ctx.theme().typeSizePx(TypeRole.BODY_MEDIUM);
        float headW = ctx.measureText(FONT, headline, headSize).width();
        float suppW = twoLine ? ctx.measureText(FONT, supporting, suppSize).width() : 0f;
        float w = PAD_H_DP * 2f + Math.max(headW, suppW);
        return new Size(w, twoLine ? TWO_LINE_DP : ONE_LINE_DP);
    }

    private static final net.marcloud.mcp.dwm.backend.FontHandle FONT =
            new net.marcloud.mcp.dwm.backend.FontHandle(0L);

    private static boolean pointInside(ComponentContext ctx, float x, float y, float w, float h) {
        var in = ctx.input();
        float px = in.pointerX();
        float py = in.pointerY();
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
