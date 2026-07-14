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
        // (four corner arcs joined by the straight edges), as a GL_TRIANGLE_FAN. Uniform
        // radius is the per-corner walk with all four radii equal.
        emitFan(x + w * 0.5f, y + h * 0.5f, roundedPerimeter(x, y, w, h, r, r, r, r));
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, Corners c, int argb) {
        // True per-corner rounding (was previously a max-radius approximation): each corner
        // arc uses ITS OWN radius, clamped to half the short side (matching Skiko). A zero
        // radius collapses that arc to the sharp rect corner, so mixed sharp/round corners
        // (e.g. an MD3 top-rounded sheet) render faithfully on fixed-function GL too.
        float half = Math.min(w, h) * 0.5f;
        float rTL = clampCorner(c.topLeft(), half);
        float rTR = clampCorner(c.topRight(), half);
        float rBR = clampCorner(c.bottomRight(), half);
        float rBL = clampCorner(c.bottomLeft(), half);
        if (rTL <= 0.5f && rTR <= 0.5f && rBR <= 0.5f && rBL <= 0.5f) {
            rect(x, y, w, h, argb);
            return;
        }
        color(argb);
        emitFan(x + w * 0.5f, y + h * 0.5f, roundedPerimeter(x, y, w, h, rTL, rTR, rBR, rBL));
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
        // Real glyphs from the embedded 8x8 bitmap font (no texture / no native dep — the
        // fixed-function way): walk each glyph's lit pixels and emit one filled quad per
        // pixel. The 8 cell columns are scaled to fill exactly one advance
        // (sizePx * ADVANCE_RATIO) and the 8 rows to fill sizePx, so glyphs abut cleanly
        // and the advance matches GlRenderBackend.measureText (no layout shift). y is the
        // baseline-ish value MaterialButton computes; lift the cell top by ASCENT_RATIO em
        // so GL text lands where imgui text does.
        if (s == null || s.length() == 0) {
            return;
        }
        float advance = GlBitmapFont.advance(sizePx);
        float pixelW = advance / GlBitmapFont.CELL;
        float pixelH = sizePx / GlBitmapFont.CELL;
        float topY = y - sizePx * GlBitmapFont.ASCENT_RATIO;
        // Set the color once (with layer opacity folded) and batch every lit pixel of the
        // whole string into a single GL_QUADS block.
        color(argb);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            long glyph = GlBitmapFont.glyph(ch);
            if (glyph == 0L) {
                continue; // blank cell (space / unmapped): only advance, no quads
            }
            float cellLeft = x + i * advance;
            for (int row = 0; row < GlBitmapFont.CELL; row++) {
                int rowBits = (int) ((glyph >>> (row * GlBitmapFont.CELL)) & 0xFF);
                if (rowBits == 0) {
                    continue;
                }
                float py = topY + row * pixelH;
                for (int col = 0; col < GlBitmapFont.CELL; col++) {
                    if ((rowBits & (1 << col)) == 0) {
                        continue;
                    }
                    float px = cellLeft + col * pixelW;
                    GL11.glVertex2f(px, py);
                    GL11.glVertex2f(px + pixelW, py);
                    GL11.glVertex2f(px + pixelW, py + pixelH);
                    GL11.glVertex2f(px, py + pixelH);
                }
            }
        }
        GL11.glEnd();
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

    /** Clamp a single corner radius to [0, half-short-side]; negatives collapse to sharp. */
    private static float clampCorner(float radius, float halfShort) {
        if (radius <= 0f) {
            return 0f;
        }
        return Math.min(radius, halfShort);
    }

    private static int[] intersect(int[] a, int[] b) {
        int x0 = Math.max(a[0], b[0]);
        int y0 = Math.max(a[1], b[1]);
        int x1 = Math.min(a[0] + a[2], b[0] + b[2]);
        int y1 = Math.min(a[1] + a[3], b[1] + b[3]);
        return new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
    }

    /** Emit a GL_TRIANGLE_FAN: center vertex, the perimeter, then repeat vertex 0 to close. */
    private static void emitFan(float cx, float cy, float[] perimeter) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i + 1 < perimeter.length; i += 2) {
            GL11.glVertex2f(perimeter[i], perimeter[i + 1]);
        }
        if (perimeter.length >= 2) {
            GL11.glVertex2f(perimeter[0], perimeter[1]); // close the loop
        }
        GL11.glEnd();
    }

    /**
     * Compute the rounded-rect perimeter as a flat {@code [x0,y0,x1,y1,...]} vertex walk —
     * four quarter-arcs at the corners connected by straight edges, each corner with its
     * OWN radius. A corner radius of 0 places that corner's center exactly at the sharp
     * rect corner, so all its arc vertices collapse onto it and the corner renders sharp
     * with no special-case branch (harmless degenerate triangles in the fan).
     *
     * <p>Pure geometry (no GL) so the per-corner vertex placement is unit-testable
     * headless; {@link #emitFan} does the GL_TRIANGLE_FAN emission. Walk order is clockwise
     * in y-down space: top-right, bottom-right, bottom-left, top-left.
     */
    static float[] roundedPerimeter(float x, float y, float w, float h,
            float rTL, float rTR, float rBR, float rBL) {
        float l = x;
        float t = y;
        float ri = x + w;
        float b = y + h;
        // Per-corner centers (inset by that corner's own radius) + radius, in walk order.
        float[][] centers = {
                {ri - rTR, t + rTR}, // top-right
                {ri - rBR, b - rBR}, // bottom-right
                {l + rBL, b - rBL},  // bottom-left
                {l + rTL, t + rTL},  // top-left
        };
        float[] radii = {rTR, rBR, rBL, rTL};
        // Each corner sweeps 90 degrees; start angles connect arcs edge-to-edge clockwise.
        float[] startDeg = {-90f, 0f, 90f, 180f};
        float[] out = new float[4 * (CORNER_SEGMENTS + 1) * 2];
        int o = 0;
        for (int cIdx = 0; cIdx < 4; cIdx++) {
            float cx = centers[cIdx][0];
            float cy = centers[cIdx][1];
            float r = radii[cIdx];
            float start = (float) Math.toRadians(startDeg[cIdx]);
            for (int s = 0; s <= CORNER_SEGMENTS; s++) {
                float ang = start + (float) (Math.PI / 2) * s / CORNER_SEGMENTS;
                out[o++] = cx + (float) Math.cos(ang) * r;
                out[o++] = cy + (float) Math.sin(ang) * r;
            }
        }
        return out;
    }
}

