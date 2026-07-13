package net.marcloud.mcp.dwm.component.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.TextureHandle;

/**
 * Fake {@link DrawContext} that records every call for assertions. Tests fail if
 * the component draws the wrong primitive, color, or geometry.
 */
final class RecordingDrawContext implements DrawContext {

    enum Op {
        RECT, ROUNDED_RECT, ROUNDED_RECT_CORNERS, RECT_STROKE, LINE, TEXT, IMAGE,
        PATH, PUSH_CLIP, POP_CLIP, PUSH_OPACITY, POP_OPACITY
    }

    record Call(Op op, float[] f, int argb, Object extra) {
        static Call of(Op op, int argb, float... f) {
            return new Call(op, f, argb, null);
        }

        static Call ofExtra(Op op, int argb, Object extra, float... f) {
            return new Call(op, f, argb, extra);
        }
    }

    private final List<Call> calls = new ArrayList<>();

    List<Call> calls() {
        return Collections.unmodifiableList(calls);
    }

    void clear() {
        calls.clear();
    }

    int count(Op op) {
        int n = 0;
        for (Call c : calls) {
            if (c.op == op) {
                n++;
            }
        }
        return n;
    }

    List<Call> of(Op op) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) {
            if (c.op == op) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public void rect(float x, float y, float w, float h, int argb) {
        calls.add(Call.of(Op.RECT, argb, x, y, w, h));
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, float radius, int argb) {
        calls.add(Call.of(Op.ROUNDED_RECT, argb, x, y, w, h, radius));
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, Corners perCorner, int argb) {
        calls.add(Call.ofExtra(Op.ROUNDED_RECT_CORNERS, argb, perCorner, x, y, w, h));
    }

    @Override
    public void rectStroke(float x, float y, float w, float h, float thickness, int argb) {
        calls.add(Call.of(Op.RECT_STROKE, argb, x, y, w, h, thickness));
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
        calls.add(Call.of(Op.LINE, argb, x0, y0, x1, y1, thickness));
    }

    @Override
    public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) {
        calls.add(Call.ofExtra(Op.TEXT, argb, s == null ? "" : s.toString(), sizePx, x, y));
    }

    @Override
    public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) {
        calls.add(Call.of(Op.IMAGE, tintArgb, x, y, w, h));
    }

    @Override
    public void path(PathSpec path, PaintSpec paint) {
        calls.add(Call.ofExtra(Op.PATH, paint == null ? 0 : paint.argb(), path));
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        calls.add(Call.of(Op.PUSH_CLIP, 0, x, y, w, h));
    }

    @Override
    public void popClip() {
        calls.add(Call.of(Op.POP_CLIP, 0));
    }

    @Override
    public void pushOpacity(float alpha) {
        calls.add(Call.of(Op.PUSH_OPACITY, 0, alpha));
    }

    @Override
    public void popOpacity() {
        calls.add(Call.of(Op.POP_OPACITY, 0));
    }
}
