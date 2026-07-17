package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * A launcher section header: a left title ("Pinned" / "Recommended" / "All") and an
 * optional right-aligned action label ("Show all" / "View: List"). When an {@code onAction}
 * callback is supplied, the action label becomes a clickable hit box (press-then-release
 * over the label fires the callback once, with a hover highlight); with no callback it is a
 * plain static label. Draws only through {@link DrawContext} — identical on gl / imgui / skiko.
 */
public final class SectionHeader implements Component {

    /** Header row height (DIP). */
    public static final float HEIGHT_DP = 28f;
    private static final int PRIMARY_BUTTON = 1;
    private static final FontHandle FONT = new FontHandle(0L);

    private final String title;
    private final String action; // right-side label, or "" for none
    private final Runnable onAction; // null = static label (non-interactive)

    public SectionHeader(String title, String action) {
        this(title, action, null);
    }

    /**
     * @param title    left-aligned section title
     * @param action   right-aligned action label, or "" for none
     * @param onAction invoked when the action label is clicked (null = static, non-clickable)
     */
    public SectionHeader(String title, String action, Runnable onAction) {
        this.title = title == null ? "" : title;
        this.action = action == null ? "" : action;
        this.onAction = onAction;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return new Size(0f, HEIGHT_DP);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        DrawContext d = ctx.draw();

        float titleSize = theme.typeSizePx(TypeRole.TITLE_MEDIUM);
        float baseline = y + h * 0.5f + titleSize * 0.35f;
        d.text(FONT, titleSize, x, baseline, theme.color(ColorRole.ON_SURFACE), title);

        if (!action.isEmpty()) {
            float actSize = theme.typeSizePx(TypeRole.LABEL_LARGE);
            float actW = ctx.measureText(FONT, action, actSize).width();
            float actX = x + w - actW;
            float actBaseline = y + h * 0.5f + actSize * 0.35f;

            int actColor = theme.color(ColorRole.ON_SURFACE_VARIANT);
            if (onAction != null) {
                // Clickable: a padded hit box around the label, hover highlight, click on
                // release-while-hovered (the ClickState idiom shared with SoftwareRow).
                float pad = 6f;
                float hx = actX - pad;
                float hy = y + (h - actSize - 8f) * 0.5f;
                float hw = actW + pad * 2f;
                float hh = actSize + 8f;
                ClickState st = ctx.store().state(ctx.childId("action"), ClickState::new);
                FrameInput in = ctx.input();
                boolean hovered = in.pointerX() >= hx && in.pointerX() < hx + hw
                        && in.pointerY() >= hy && in.pointerY() < hy + hh;
                boolean down = (in.buttonMask() & PRIMARY_BUTTON) != 0;
                if (st.update(hovered, down)) {
                    onAction.run();
                }
                float hover = st.hoverAlpha();
                if (hover > 0.001f) {
                    d.roundedRect(hx, hy, hw, hh, 6f,
                            DesktopArgb.withAlpha(theme.color(ColorRole.ON_SURFACE), 0.08f * hover));
                }
                // Clickable action reads in the accent color to signal interactivity.
                actColor = theme.color(ColorRole.PRIMARY);
            }
            d.text(FONT, actSize, actX, actBaseline, actColor, action);
        }
        return Result.idle();
    }
}
