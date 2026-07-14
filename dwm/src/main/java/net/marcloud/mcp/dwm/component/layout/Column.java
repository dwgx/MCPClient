package net.marcloud.mcp.dwm.component.layout;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;

/**
 * A vertical stack: lays children top-to-bottom with a fixed gap, each child given the
 * column's full width and its own measured height. The minimal constraint-based layout
 * primitive — enough to build lists, cards' bodies, and dialogs without a full
 * Flutter/Compose layout engine.
 *
 * <p><b>Measure:</b> width = max child width (or the caller's width if wider); height =
 * sum of child heights + gaps. <b>Render:</b> place each child at the running y offset in
 * the given box, wrapping each in a {@code pushId(index)} / {@code popId()} scope so N
 * children get DISTINCT widget ids (ripple/state-layer state never collides — the
 * immediate-mode id pitfall). Each child's interaction {@link Component.Result} is OR-ed
 * into the column's result so a click on any child surfaces.
 */
public final class Column implements Component {

    private final List<Component> children;
    private final float gap;

    public Column(float gap, List<Component> children) {
        this.gap = Math.max(0f, gap);
        this.children = List.copyOf(children);
    }

    public Column(float gap, Component... children) {
        this(gap, List.of(children));
    }

    @Override
    public Result render(ComponentContext ctx, float x, float y, float w, float h) {
        boolean hovered = false;
        boolean pressed = false;
        boolean clicked = false;
        float cy = y;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Size cs = measureChild(ctx, i, child);
            ctx.pushId("col" + i);
            try {
                Result r = child.render(ctx, x, cy, w, cs.height());
                hovered |= r.hovered();
                pressed |= r.pressed();
                clicked |= r.clicked();
            } finally {
                ctx.popId();
            }
            cy += cs.height() + gap;
        }
        return new Result(hovered, pressed, clicked);
    }

    @Override
    public Size measure(ComponentContext ctx) {
        float maxW = 0f;
        float totalH = 0f;
        for (int i = 0; i < children.size(); i++) {
            Size cs = measureChild(ctx, i, children.get(i));
            maxW = Math.max(maxW, cs.width());
            totalH += cs.height();
        }
        if (children.size() > 1) {
            totalH += gap * (children.size() - 1);
        }
        return new Size(maxW, totalH);
    }

    /** Measure a child inside its own id scope (so measure keys match render keys). */
    private Size measureChild(ComponentContext ctx, int i, Component child) {
        ctx.pushId("col" + i);
        try {
            return child.measure(ctx);
        } finally {
            ctx.popId();
        }
    }

    /** Mutable builder for readability at call sites. */
    public static Column of(float gap, Component... children) {
        return new Column(gap, new ArrayList<>(List.of(children)));
    }
}
