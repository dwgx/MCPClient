package net.marcloud.mcp.dwm.component.material;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.theme.MdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.ShapeSize;

/**
 * MD3 Card — a rounded surface container that holds arbitrary child content. Three
 * variants: {@code FILLED} (surface-variant fill), {@code ELEVATED} (surface fill; a
 * shadow would be drawn by a shadow-capable backend), {@code OUTLINED} (surface fill +
 * outline stroke). Colors, corner radius come from {@link MdcTheme}; the child renders
 * inside the card's rounded box (clipped to the corner radius).
 *
 * <p>Layout: the card takes the box it is given and hands the SAME box to its child (use
 * a {@link net.marcloud.mcp.dwm.component.layout.Padding} child for inset content).
 * {@link #measure} returns the child's size (content-sized cards wrap a Padding+Column).
 */
public final class MaterialCard implements Component {

    /** MD3 card variants. */
    public enum Variant {
        FILLED,
        ELEVATED,
        OUTLINED
    }

    /** MD3 card corner (medium shape token, 12dp). */
    public static final float OUTLINE_THICKNESS_DP = 1f;

    private final Variant variant;
    private final Component child;

    public MaterialCard(Component child) {
        this(Variant.FILLED, child);
    }

    public MaterialCard(Variant variant, Component child) {
        this.variant = variant == null ? Variant.FILLED : variant;
        this.child = child;
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        DrawContext d = ctx.draw();
        MdcTheme theme = ctx.theme();
        float radius = theme.corner(ShapeSize.MEDIUM);

        int container = switch (variant) {
            case FILLED -> theme.color(ColorRole.SURFACE_VARIANT);
            case ELEVATED, OUTLINED -> theme.color(ColorRole.SURFACE);
        };
        d.roundedRect(x, y, w, h, radius, container);
        if (variant == Variant.OUTLINED) {
            d.rectStroke(x, y, w, h, OUTLINE_THICKNESS_DP, theme.color(ColorRole.OUTLINE_VARIANT));
        }

        // Child content clipped to the card's rounded box.
        Result r = Result.idle();
        if (child != null) {
            d.pushClip(x, y, w, h);
            try {
                r = child.render(ctx, x, y, w, h);
            } finally {
                d.popClip();
            }
        }
        return r;
    }

    @Override
    public Size measure(ComponentContext ctx) {
        return child == null ? new Size(0f, 0f) : child.measure(ctx);
    }
}
