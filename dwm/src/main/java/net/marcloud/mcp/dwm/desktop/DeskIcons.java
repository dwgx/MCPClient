package net.marcloud.mcp.dwm.desktop;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;

/**
 * Lucide-style line icons drawn through {@link DrawContext} — clean 1.5dp strokes matching
 * the Anthropic/Lucide visual language (the reference set the owner uses in AgentScope).
 * Rather than embed an icon TTF, each glyph is generated parametrically from circles/arcs
 * (sampled to short polylines, since {@link PathSpec} is a polyline) and straight strokes,
 * so there is no font file dependency and every icon scales crisply to any box.
 *
 * <p>Each method draws the icon centered in the given {@code (x,y,size,size)} box in the
 * given color. Stroke width scales with the box so icons read at both 16dp and 24dp.
 */
public final class DeskIcons {

    private DeskIcons() {
    }

    private static float stroke(float size) {
        return Math.max(1.2f, size * 0.08f);
    }

    /** Magnifier: a ring + a handle stroke (Lucide "search"). */
    public static void search(DrawContext d, float x, float y, float size, int argb) {
        float sw = stroke(size);
        float r = size * 0.30f;
        float cx = x + size * 0.42f;
        float cy = y + size * 0.42f;
        circle(d, cx, cy, r, sw, argb);
        // Handle from the ring's lower-right toward the corner.
        float hx = cx + (float) Math.cos(Math.PI / 4) * r;
        float hy = cy + (float) Math.sin(Math.PI / 4) * r;
        d.line(hx, hy, x + size * 0.82f, y + size * 0.82f, sw, argb);
    }

    /** Gear: a ring hub + eight radial teeth (Lucide "settings", simplified). */
    public static void settings(DrawContext d, float x, float y, float size, int argb) {
        float sw = stroke(size);
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;
        float rHub = size * 0.16f;
        float rIn = size * 0.26f;
        float rOut = size * 0.40f;
        circle(d, cx, cy, rHub, sw, argb);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0;
            float ix = cx + (float) Math.cos(a) * rIn;
            float iy = cy + (float) Math.sin(a) * rIn;
            float ox = cx + (float) Math.cos(a) * rOut;
            float oy = cy + (float) Math.sin(a) * rOut;
            d.line(ix, iy, ox, oy, sw, argb);
        }
    }

    /** Pin: a filled teardrop-ish dot with a stem (Lucide "pin", simplified to a marker). */
    public static void pin(DrawContext d, float x, float y, float size, int argb) {
        float sw = stroke(size);
        float cx = x + size * 0.5f;
        float headY = y + size * 0.38f;
        float r = size * 0.20f;
        circle(d, cx, headY, r, sw, argb);
        d.line(cx, headY + r, cx, y + size * 0.82f, sw, argb); // stem
    }

    /** A user glyph: head circle + shoulders arc (Lucide "user"), for the avatar fallback. */
    public static void user(DrawContext d, float x, float y, float size, int argb) {
        float sw = stroke(size);
        float cx = x + size * 0.5f;
        float headR = size * 0.16f;
        float headY = y + size * 0.34f;
        circle(d, cx, headY, headR, sw, argb);
        // Shoulders: a shallow upward arc across the lower half.
        arc(d, cx, y + size * 0.92f, size * 0.30f, Math.PI * 1.15, Math.PI * 1.85, sw, argb);
    }

    // ---- primitives ---------------------------------------------------------------------

    /** Stroke a full circle by sampling it into a closed polyline. */
    private static void circle(DrawContext d, float cx, float cy, float r, float sw, int argb) {
        arc(d, cx, cy, r, 0, Math.PI * 2, sw, argb);
    }

    /** Stroke an arc [a0,a1] (radians) of radius r as a polyline via {@link DrawContext#path}. */
    private static void arc(DrawContext d, float cx, float cy, float r,
                            double a0, double a1, float sw, int argb) {
        int seg = Math.max(8, (int) (Math.abs(a1 - a0) / (Math.PI * 2) * 32));
        List<float[]> pts = new ArrayList<>(seg + 1);
        for (int i = 0; i <= seg; i++) {
            double a = a0 + (a1 - a0) * i / seg;
            pts.add(new float[] {cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r});
        }
        boolean closed = Math.abs((a1 - a0) - Math.PI * 2) < 1e-3;
        d.path(new PathSpec(pts, closed), PaintSpec.stroke(argb, sw));
    }
}
