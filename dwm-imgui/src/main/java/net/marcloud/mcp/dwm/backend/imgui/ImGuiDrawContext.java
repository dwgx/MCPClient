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
        // Uniform-radius degradation (ImDrawList rounding is uniform): use the max corner.
        float max = Math.max(Math.max(c.topLeft(), c.topRight()),
                Math.max(c.bottomRight(), c.bottomLeft()));
        roundedRect(x, y, w, h, max, argb);
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
