package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * A single line of MD3 text at a given type role + color role. The leaf that carries
 * labels/values for cards and list items. Measures through the context's
 * backend-consistent {@link ComponentContext#measureText}, so its size matches whatever
 * the active backend will actually draw (no per-backend width drift).
 *
 * <p>Draws the baseline at the vertically-centered position of its box (the
 * {@code DrawContext.text} y is treated as a baseline-ish center by the backends).
 */
public final class MaterialText implements Component {

    private static final FontHandle DEFAULT_FONT = new FontHandle(0L);

    private final String text;
    private final TypeRole type;
    private final ColorRole color;

    public MaterialText(String text) {
        this(text, TypeRole.BODY_MEDIUM, ColorRole.ON_SURFACE);
    }

    public MaterialText(String text, TypeRole type, ColorRole color) {
        this.text = text == null ? "" : text;
        this.type = type == null ? TypeRole.BODY_MEDIUM : type;
        this.color = color == null ? ColorRole.ON_SURFACE : color;
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        MdcTheme theme = ctx.theme();
        float sizePx = theme.typeSizePx(type);
        // Baseline near vertical center: center + a fraction of the ascent.
        float baseline = y + h * 0.5f + sizePx * 0.35f;
        ctx.draw().text(DEFAULT_FONT, sizePx, x, baseline, theme.color(color), text);
        return Result.idle();
    }

    @Override
    public Size measure(ComponentContext ctx) {
        float sizePx = ctx.theme().typeSizePx(type);
        TextMetrics tm = ctx.measureText(DEFAULT_FONT, text, sizePx);
        return new Size(tm.width(), Math.max(sizePx, tm.height()));
    }
}
