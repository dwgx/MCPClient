package net.marcloud.mcp.dwm.gl;

import java.util.ArrayDeque;
import java.util.Deque;

import org.lwjgl.opengl.GL11;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.TextureHandle;

/**
 * Immediate-mode OpenGL {@link DrawContext} — the pure-Java, fixed-function mapping of
 * DWM's drawing vocabulary onto MC 1.8.9's own GL profile ({@code glBegin/glVertex}).
 * No shaders, no textures, no native deps. This is the lowest-fidelity backend of the
 * three (imgui / Skiko do rounded rects + real text in one native call); here rounded
 * corners are triangle-fan approximations and text is a placeholder underline bar, but a
 * full MD3 component tree (container, state layer, ripple, clip) renders faithfully.
 *
 * <p><b>Coordinate model.</b> The owning backend sets up a pixel-space y-down ortho
 * (0,0 top-left .. w,h bottom-right) before {@code draw()} is used, so DIP coordinates
 * here are pixels (scale 1). Colors are packed ARGB {@code 0xAARRGGBB}; a layer-opacity
 * stack multiplies alpha on the way to GL. Clipping uses {@code glScissor} (the ortho is
 * y-down but scissor is y-up in framebuffer space, so y is flipped against the viewport
 * height captured at frame start).
 *
 * <p><b>State discipline.</b> This context assumes blend is enabled and cull/tex/depth
 * are configured by the backend around the frame (and restored + shadow-written by
 * {@link GlStateGuard}). It only toggles {@code GL_SCISSOR_TEST} for clips, which it
 * always balances (pushClip/popClip) and disables in {@link #reset}.
 */
public final class GlDrawContext implements DrawContext {

    /** Segments per 90-degree corner arc for rounded-rect fans. */
    private static final int CORNER_SEGMENTS = 6;

    private int fbHeight = 1;

    /** Effective (pre-multiplied) opacity stack; empty == 1.0. */
    private final Deque<Float> opacity = new ArrayDeque<>();

    /** Active scissor rects, so nested clips intersect and popClip restores the parent. */
    private final Deque<int[]> clips = new ArrayDeque<>();

    /**
     * Rebind for a new frame: record the framebuffer height (for scissor y-flip) and
     * clear the opacity + clip stacks. Called by the backend inside its begin/endFrame.
     */
    public void begin(int fbHeightPx) {
        this.fbHeight = Math.max(1, fbHeightPx);
        opacity.clear();
        clips.clear();
    }

    /** End-of-frame cleanup: ensure scissor is off and stacks are empty. */
    public void reset() {
        while (!clips.isEmpty()) {
            clips.pop();
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        opacity.clear();
    }

    private float opacity() {
        return opacity.isEmpty() ? 1f : opacity.peek();
    }

    /** Apply ARGB (with current layer opacity folded into alpha) as the GL color. */
    private void color(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f * opacity();
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        GL11.glColor4f(r, g, b, a);
    }

    // ---- primitives -----------------------------------------------------------------

    @Override
    public void rect(float x, float y, float w, float h, int argb) {
        color(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, float radius, int argb) {
        float r = clampRadius(radius, w, h);
        if (r <= 0.5f) {
            rect(x, y, w, h, argb);
            return;
        }
        color(argb);
        // Center fan covering the whole rounded rect: center point + a perimeter walk
        // (four corner arcs joined by the straight edges), as a GL_TRIANGLE_FAN.
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        emitRoundedPerimeter(x, y, w, h, r, true);
        GL11.glEnd();
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, Corners c, int argb) {
        // Fixed-function approximation: use a single uniform radius (the max of the four
        // per-corner radii, clamped). True per-corner is a caps=false degradation here;
        // imgui/Skiko backends honor Corners natively. Good enough for MD3 pills/cards.
        float max = Math.max(Math.max(c.topLeft(), c.topRight()),
                Math.max(c.bottomRight(), c.bottomLeft()));
        roundedRect(x, y, w, h, max, argb);
    }

    @Override
    public void rectStroke(float x, float y, float w, float h, float thickness, int argb) {
        float t = Math.max(1f, thickness);
        // Four edge quads (top, bottom, left, right) — a hollow border.
        rect(x, y, w, t, argb);                        // top
        rect(x, y + h - t, w, t, argb);                // bottom
        rect(x, y + t, t, h - 2f * t, argb);           // left
        rect(x + w - t, y + t, t, h - 2f * t, argb);   // right
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
        color(argb);
        GL11.glLineWidth(Math.max(1f, thickness));
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glEnd();
    }

    @Override
    public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) {
        // Placeholder: no glyph atlas yet on the GL backend. Draw a thin underline bar
        // sized to the approximate text width so layout/position is visible and the
        // component reads as "has a label here". Real font rendering is a later increment
        // (imgui/Skiko render text natively). Alpha is dimmed so it reads as a stand-in.
        if (s == null || s.length() == 0) {
            return;
        }
        float w = s.length() * sizePx * 0.55f;
        float barH = Math.max(1f, sizePx * 0.12f);
        int dimmed = (argb & 0x00FFFFFF) | 0x80000000; // ~50% alpha stand-in
        rect(x, y - barH, w, barH, dimmed);
    }

    @Override
    public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) {
        // No texture path on this first-increment GL backend; draw the tint as a flat
        // rect so an image slot is at least visible. Textured blit is a later increment.
        rect(x, y, w, h, tintArgb);
    }

    @Override
    public void path(PathSpec path, PaintSpec paint) {
        var pts = path.points();
        if (pts.isEmpty()) {
            return;
        }
        color(paint.argb());
        if (paint.fill()) {
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            for (float[] p : pts) {
                GL11.glVertex2f(p[0], p[1]);
            }
            GL11.glEnd();
        } else {
            GL11.glLineWidth(Math.max(1f, paint.strokeThickness()));
            GL11.glBegin(path.closed() ? GL11.GL_LINE_LOOP : GL11.GL_LINE_STRIP);
            for (float[] p : pts) {
                GL11.glVertex2f(p[0], p[1]);
            }
            GL11.glEnd();
        }
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        // Convert y-down ortho pixel box to y-up framebuffer scissor box, intersecting
        // with the current clip so nested clips behave (MD3 ripple clipped to the pill).
        int sx = Math.round(x);
        int sw = Math.max(0, Math.round(w));
        int sh = Math.max(0, Math.round(h));
        int sy = fbHeight - Math.round(y) - sh; // flip
        int[] box = {sx, sy, sw, sh};
        if (!clips.isEmpty()) {
            box = intersect(clips.peek(), box);
        }
        clips.push(box);
        if (clips.size() == 1) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glScissor(box[0], box[1], box[2], box[3]);
    }

    @Override
    public void popClip() {
        if (clips.isEmpty()) {
            return;
        }
        clips.pop();
        if (clips.isEmpty()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } else {
            int[] box = clips.peek();
            GL11.glScissor(box[0], box[1], box[2], box[3]);
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

    // ---- helpers --------------------------------------------------------------------

    private static float clampRadius(float radius, float w, float h) {
        return Math.min(radius, Math.min(w, h) * 0.5f);
    }

    private static int[] intersect(int[] a, int[] b) {
        int x0 = Math.max(a[0], b[0]);
        int y0 = Math.max(a[1], b[1]);
        int x1 = Math.min(a[0] + a[2], b[0] + b[2]);
        int y1 = Math.min(a[1] + a[3], b[1] + b[3]);
        return new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
    }

    /**
     * Emit the rounded-rect perimeter as a vertex walk (for a GL_TRIANGLE_FAN started at
     * the center). Four quarter-arcs at the corners, connected by straight edges. When
     * {@code closeLoop} is true the first perimeter vertex is repeated at the end so the
     * fan closes cleanly.
     */
    private static void emitRoundedPerimeter(float x, float y, float w, float h, float r, boolean closeLoop) {
        float l = x;
        float t = y;
        float ri = x + w;
        float b = y + h;
        // Corner centers.
        float[][] centers = {
                {ri - r, t + r}, // top-right
                {ri - r, b - r}, // bottom-right
                {l + r, b - r},  // bottom-left
                {l + r, t + r},  // top-left
        };
        // Each corner sweeps 90 degrees; start angles chosen so arcs connect edge-to-edge
        // going clockwise in y-down space.
        float[] startDeg = {-90f, 0f, 90f, 180f};
        float firstX = 0f;
        float firstY = 0f;
        boolean first = true;
        for (int cIdx = 0; cIdx < 4; cIdx++) {
            float cx = centers[cIdx][0];
            float cy = centers[cIdx][1];
            float start = (float) Math.toRadians(startDeg[cIdx]);
            for (int s = 0; s <= CORNER_SEGMENTS; s++) {
                float ang = start + (float) (Math.PI / 2) * s / CORNER_SEGMENTS;
                float vx = cx + (float) Math.cos(ang) * r;
                float vy = cy + (float) Math.sin(ang) * r;
                GL11.glVertex2f(vx, vy);
                if (first) {
                    firstX = vx;
                    firstY = vy;
                    first = false;
                }
            }
        }
        if (closeLoop && !first) {
            GL11.glVertex2f(firstX, firstY);
        }
    }
}

