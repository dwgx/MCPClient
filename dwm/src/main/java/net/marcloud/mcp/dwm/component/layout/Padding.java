package net.marcloud.mcp.dwm.component.layout;

import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;

/**
 * Insets a single child by (left, top, right, bottom) DIP. The child renders in the
 * shrunk box; measure adds the insets back. The other minimal layout primitive — cards
 * and list items are Padding around content.
 */
public final class Padding implements Component {

    private final float left;
    private final float top;
    private final float right;
    private final float bottom;
    private final Component child;

    public Padding(float left, float top, float right, float bottom, Component child) {
        this.left = Math.max(0f, left);
        this.top = Math.max(0f, top);
        this.right = Math.max(0f, right);
        this.bottom = Math.max(0f, bottom);
        this.child = child;
    }

    /** Uniform padding on all four sides. */
    public static Padding all(float p, Component child) {
        return new Padding(p, p, p, p, child);
    }

    /** Symmetric horizontal / vertical padding. */
    public static Padding symmetric(float horizontal, float vertical, Component child) {
        return new Padding(horizontal, vertical, horizontal, vertical, child);
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        float cw = Math.max(0f, w - left - right);
        float ch = Math.max(0f, h - top - bottom);
        return child.render(ctx, x + left, y + top, cw, ch);
    }

    @Override
    public Size measure(ComponentContext ctx) {
        Size cs = child.measure(ctx);
        return new Size(cs.width() + left + right, cs.height() + top + bottom);
    }
}
