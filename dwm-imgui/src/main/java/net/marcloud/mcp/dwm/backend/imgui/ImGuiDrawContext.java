package net.marcloud.mcp.dwm.backend.imgui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import imgui.ImDrawList;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.TextureHandle;

/**
 * Dear ImGui {@link DrawContext} — maps DWM's drawing vocabulary onto an ImGui
 * background {@link ImDrawList}. Higher fidelity than the fixed-function GL backend:
 * native rounded rects, native clip-rect, and real font text (ImGui's default atlas)
 * all in one call. Reused across frames; {@link #bind} points it at the frame's draw
 * list before the component tree draws.
 *
 * <p><b>Coordinate + color model.</b> DWM coords are DIP; this first increment runs at
 * scale 1, so they pass through as pixels. DWM colors are packed ARGB
 * {@code 0xAARRGGBB}; ImGui wants IM_COL32, which is little-endian {@code 0xAABBGGRR}
 * (R in the low byte), so {@link #u32} swaps R and B and folds the current layer opacity
 * into alpha. The DrawContext gives edge-agnostic (x,y,w,h); ImGui's add* take
 * (x0,y0,x1,y1) corners.
 *
 * <p><b>Degradation.</b> per-corner radius folds to a uniform radius (ImDrawList's
 * rounding is uniform); {@code path} fill uses {@code addConvexPolyFilled} (convex only,
 * which the MD3 ripple/checkmark paths satisfy). No native shadow (elevation is a later
 * caps concern). Layer opacity is folded into every emitted color's alpha.
 */
public final class ImGuiDrawContext implements DrawContext {

    private ImDrawList dl;
    private final Deque<Float> opacity = new ArrayDeque<>();
    private int clipDepth;

    /** Rebind to this frame's draw list and clear the per-frame stacks. */
    public void bind(ImDrawList drawList) {
        this.dl = drawList;
        opacity.clear();
        clipDepth = 0;
    }

    /** Balance any clips the component tree left open (defensive end-of-frame). */
    public void endFrameCleanup() {
        while (clipDepth > 0 && dl != null) {
            dl.popClipRect();
            clipDepth--;
        }
        opacity.clear();
    }

    private float opacity() {
        return opacity.isEmpty() ? 1f : opacity.peek();
    }

    /** ARGB 0xAARRGGBB -> IM_COL32 0xAABBGGRR, with layer opacity folded into alpha. */
    private int u32(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        a = Math.round(a * opacity());
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // ---- primitives -----------------------------------------------------------------

    @Override
    public void rect(float x, float y, float w, float h, int argb) {
        if (dl == null) {
            return;
        }
        dl.addRectFilled(x, y, x + w, y + h, u32(argb));
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, float radius, int argb) {
        if (dl == null) {
            return;
        }
        dl.addRectFilled(x, y, x + w, y + h, u32(argb), radius);
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, Corners c, int argb) {
        if (dl == null) {
            return;
        }
        // True per-corner rounding (was a max-radius approximation). addRectFilled's
        // rounding is uniform, so build the shape from the PATH API instead — exactly how
        // ImGui's own PathRect constructs a rounded rect: one quarter-arc per corner via
        // pathArcToFast, each with ITS OWN radius, then a convex fill. pathArcToFast uses a
        // 12-slot circle (index 0 = +x/right, 3 = down, 6 = left, 9 = up in y-down space);
        // a radius < 0.5 makes it emit just the center point, so a zero corner degrades to
        // the sharp rect corner with no branch.
        float[] r = clampedCorners(w, h, c); // [tl, tr, br, bl]
        float x1 = x + w;
        float y1 = y + h;
        int col = u32(argb);
        dl.pathClear();
        dl.pathArcToFast(x + r[0], y + r[0], r[0], 6, 9);    // top-left
        dl.pathArcToFast(x1 - r[1], y + r[1], r[1], 9, 12);  // top-right
        dl.pathArcToFast(x1 - r[2], y1 - r[2], r[2], 0, 3);  // bottom-right
        dl.pathArcToFast(x + r[3], y1 - r[3], r[3], 3, 6);   // bottom-left
        dl.pathFillConvex(col);
    }

    /**
     * Clamp the four per-corner radii to {@code [0, half-short-side]} (negatives → sharp),
     * returned as {@code [topLeft, topRight, bottomRight, bottomLeft]}. Pure math extracted
     * from {@link #roundedRect(float, float, float, float, Corners, int)} so the per-corner
     * clamping is unit-testable without a native ImGui draw list.
     */
    static float[] clampedCorners(float w, float h, Corners c) {
        float half = Math.min(w, h) * 0.5f;
        return new float[] {
                clampCorner(c.topLeft(), half),
                clampCorner(c.topRight(), half),
                clampCorner(c.bottomRight(), half),
                clampCorner(c.bottomLeft(), half),
        };
    }

    private static float clampCorner(float radius, float halfShort) {
        if (radius <= 0f) {
            return 0f;
        }
        return Math.min(radius, halfShort);
    }

    @Override
    public void rectStroke(float x, float y, float w, float h, float thickness, int argb) {
        if (dl == null) {
            return;
        }
        // addRect(x0,y0,x1,y1,col,rounding,flags,thickness); rounding 0, flags 0.
        dl.addRect(x, y, x + w, y + h, u32(argb), 0f, 0, Math.max(1f, thickness));
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
        if (dl == null) {
            return;
        }
        dl.addLine(x0, y0, x1, y1, u32(argb), Math.max(1f, thickness));
    }

    @Override
    public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) {
        if (dl == null || s == null || s.length() == 0) {
            return;
        }
        // DrawContext's y is a baseline-ish center value (see MaterialButton's 0.8/0.2
        // split); ImGui addText y is top-left, so lift by ~0.8em to approximate. Real
        // per-glyph metrics are a later refinement; this centers the default-atlas label.
        float topY = y - sizePx * 0.8f;
        dl.addText(x, topY, u32(argb), s.toString());
    }

    @Override
    public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) {
        // No texture-upload path on this first increment; draw the tint as a filled rect.
        rect(x, y, w, h, tintArgb);
    }

    @Override
    public void path(PathSpec path, PaintSpec paint) {
        if (dl == null) {
            return;
        }
        List<float[]> pts = path.points();
        if (pts.isEmpty()) {
            return;
        }
        dl.pathClear(); // drop any leftover path points from a prior/aborted path call
        for (float[] p : pts) {
            dl.pathLineTo(p[0], p[1]);
        }
        if (paint.fill()) {
            dl.pathFillConvex(u32(paint.argb()));
        } else {
            dl.pathStroke(u32(paint.argb()), path.closed() ? 1 : 0, Math.max(1f, paint.strokeThickness()));
        }
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        if (dl == null) {
            return;
        }
        dl.pushClipRect(x, y, x + w, y + h, true); // intersect with current
        clipDepth++;
    }

    @Override
    public void popClip() {
        if (dl != null && clipDepth > 0) {
            dl.popClipRect();
            clipDepth--;
        }
    }

    @Override
    public void pushOpacity(float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        opacity.push(opacity() * a);
    }

    @Override
    public void popOpacity() {
        if (!opacity.isEmpty()) {
            opacity.pop();
        }
    }
}
