package net.marcloud.mcp.dwm.skiko;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.ClipMode;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.PaintMode;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.TextureHandle;

/**
 * Skia {@link DrawContext} — maps DWM's drawing vocabulary onto a Skia {@link Canvas}
 * for the highest-fidelity Material 3 rendering (true antialiased rounded rects,
 * per-corner radii, real font text). Pure Java against {@code org.jetbrains.skia.*}
 * (the JetBrains Skija fork); the only interop wrinkle is Companion factories elsewhere
 * (none needed here — Canvas/Paint/Font are all direct calls).
 *
 * <p><b>Coordinate + color model.</b> DIP coords pass through as pixels at scale 1.
 * DWM colors are packed ARGB {@code 0xAARRGGBB} — exactly what {@link Paint#setColor(int)}
 * takes, so no channel swap (unlike imgui's IM_COL32). Layer opacity is folded into the
 * paint alpha on the way in.
 *
 * <p><b>Clip + opacity via canvas save/restore.</b> pushClip does {@code canvas.save()} +
 * {@code clipRRect/clipRect}; popClip does {@code canvas.restore()}. Opacity multiplies
 * the alpha the color path applies. A reused {@link Paint} is configured per call
 * (cheap; avoids per-primitive allocation).
 */
public final class SkikoDrawContext implements DrawContext {

    private Canvas canvas;
    private final Paint paint = new Paint();
    private Font font; // default typeface font, set by the backend
    private final Deque<Float> opacity = new ArrayDeque<>();
    private int clipDepth; // canvas.save() balance; popClip only restores if > 0

    /** Rebind to this frame's canvas + default font; clear the per-frame stacks. */
    public void bind(Canvas canvas, Font font) {
        this.canvas = canvas;
        this.font = font;
        opacity.clear();
        clipDepth = 0;
    }

    /** Close the native Paint. Called by the backend on detach (Managed native memory). */
    public void close() {
        try {
            if (!paint.isClosed()) {
                paint.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private float opacity() {
        return opacity.isEmpty() ? 1f : opacity.peek();
    }

    /** Configure the shared paint: fill, ARGB color with layer opacity folded, AA on. */
    private Paint fill(int argb) {
        paint.setMode(PaintMode.FILL);
        paint.setColor(foldAlpha(argb));
        paint.setAntiAlias(true);
        return paint;
    }

    private Paint stroke(int argb, float thickness) {
        paint.setMode(PaintMode.STROKE);
        paint.setStrokeWidth(Math.max(1f, thickness));
        paint.setColor(foldAlpha(argb));
        paint.setAntiAlias(true);
        return paint;
    }

    /** Multiply the ARGB's alpha by the current layer opacity. */
    private int foldAlpha(int argb) {
        float o = opacity();
        if (o >= 0.999f) {
            return argb;
        }
        int a = Math.round(((argb >>> 24) & 0xFF) * o);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ---- primitives -----------------------------------------------------------------

    @Override
    public void rect(float x, float y, float w, float h, int argb) {
        if (canvas == null) {
            return;
        }
        canvas.drawRect(x, y, x + w, y + h, fill(argb));
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, float radius, int argb) {
        if (canvas == null) {
            return;
        }
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        // drawRRect(l, t, r, b, float[]{radius}, paint) — uniform corner radius.
        canvas.drawRRect(x, y, x + w, y + h, new float[] {r}, fill(argb));
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, Corners c, int argb) {
        if (canvas == null) {
            return;
        }
        // Skia's per-corner radii array is [tl, tr, br, bl] (matches Corners' order).
        float maxR = Math.min(w, h) * 0.5f;
        float[] radii = {
                Math.min(c.topLeft(), maxR),
                Math.min(c.topRight(), maxR),
                Math.min(c.bottomRight(), maxR),
                Math.min(c.bottomLeft(), maxR),
        };
        canvas.drawRRect(x, y, x + w, y + h, radii, fill(argb));
    }

    @Override
    public void rectStroke(float x, float y, float w, float h, float thickness, int argb) {
        if (canvas == null) {
            return;
        }
        canvas.drawRect(x, y, x + w, y + h, stroke(argb, thickness));
    }

    @Override
    public void roundedRectStroke(float x, float y, float w, float h, float radius,
                                  float thickness, int argb) {
        if (canvas == null) {
            return;
        }
        // True rounded outline: a STROKE-mode RRect with the same radius as the fill, so the
        // border hugs the rounded corners instead of poking sharp corners past them (the white
        // corner bug). Radius clamped to half the short side, matching roundedRect.
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        canvas.drawRRect(x, y, x + w, y + h, new float[] {r}, stroke(argb, thickness));
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
        if (canvas == null) {
            return;
        }
        canvas.drawLine(x0, y0, x1, y1, stroke(argb, thickness));
    }

    @Override
    public void text(FontHandle fontHandle, float sizePx, float x, float y, int argb, CharSequence s) {
        if (canvas == null || font == null || s == null || s.length() == 0) {
            return;
        }
        // DrawContext's y is a baseline-ish center; Skia drawString y IS the baseline, so
        // it maps directly (MaterialButton computed a baseline-oriented ty).
        canvas.drawString(s.toString(), x, y, font, fill(argb));
    }

    @Override
    public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) {
        // No texture upload path on this first increment; draw the tint as a filled rect.
        rect(x, y, w, h, tintArgb);
    }

    @Override
    public void path(PathSpec path, PaintSpec spec) {
        if (canvas == null) {
            return;
        }
        List<float[]> pts = path.points();
        if (pts.size() < 2) {
            return;
        }
        // Draw as connected line segments (fill degrades to an outline stroke here; the
        // MD3 ripple uses roundedRect, not path, so this is a rarely-hit fallback).
        Paint p = spec.fill()
                ? fill(spec.argb())
                : stroke(spec.argb(), spec.strokeThickness());
        for (int i = 0; i + 1 < pts.size(); i++) {
            float[] a = pts.get(i);
            float[] b = pts.get(i + 1);
            canvas.drawLine(a[0], a[1], b[0], b[1], p);
        }
        if (path.closed()) {
            float[] a = pts.get(pts.size() - 1);
            float[] b = pts.get(0);
            canvas.drawLine(a[0], a[1], b[0], b[1], p);
        }
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        if (canvas == null) {
            return;
        }
        canvas.save();
        clipDepth++;
        canvas.clipRect(x, y, x + w, y + h, ClipMode.INTERSECT, true);
    }

    @Override
    public void popClip() {
        // Only restore a save WE made — an unbalanced extra popClip must not restore()
        // past the frame baseline (that would corrupt the canvas transform/clip). The
        // backend's unwind() does restoreToCount(baseline) as the final safety net.
        if (canvas != null && clipDepth > 0) {
            canvas.restore();
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
