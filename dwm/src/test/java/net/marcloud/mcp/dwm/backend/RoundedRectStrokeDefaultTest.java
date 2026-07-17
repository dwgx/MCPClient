package net.marcloud.mcp.dwm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract test for {@link DrawContext#roundedRectStroke}'s DEFAULT: a backend that does not
 * override it (no native rounded stroke) MUST degrade to {@link DrawContext#rectStroke} with
 * the same box + thickness + color — never throw, never no-op. This is what keeps the new
 * primitive safe to call from components (SettingsPanel's selection ring) regardless of which
 * backend is active: high-fidelity backends draw a true rounded outline, the rest fall back to
 * the old sharp stroke (visually identical to before, so adding the call is a pure no-regression
 * upgrade). A regression that dropped the default (or routed it wrong) fails here.
 */
public class RoundedRectStrokeDefaultTest {

    /** Minimal DrawContext that records only the rectStroke it receives (all else no-op). */
    private static final class StrokeRecorder implements DrawContext {
        boolean rectStrokeCalled;
        float sx, sy, sw, sh, st;
        int sArgb;

        @Override public void rect(float x, float y, float w, float h, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, float r, int a) { }
        @Override public void roundedRect(float x, float y, float w, float h, Corners c, int a) { }
        @Override public void rectStroke(float x, float y, float w, float h, float t, int argb) {
            rectStrokeCalled = true;
            sx = x; sy = y; sw = w; sh = h; st = t; sArgb = argb;
        }
        @Override public void line(float x0, float y0, float x1, float y1, float t, int a) { }
        @Override public void text(FontHandle f, float s, float x, float y, int a, CharSequence cs) { }
        @Override public void image(TextureHandle tex, float x, float y, float w, float h, int t) { }
        @Override public void path(PathSpec p, PaintSpec paint) { }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public void pushOpacity(float alpha) { }
        @Override public void popOpacity() { }
    }

    @Test
    public void defaultDegradesToRectStrokeWithSameGeometry() {
        StrokeRecorder r = new StrokeRecorder();
        // Call the DEFAULT (StrokeRecorder does not override roundedRectStroke).
        r.roundedRectStroke(10f, 20f, 30f, 40f, 8f /* radius, dropped by fallback */, 2f, 0xFFABCDEF);

        assertTrue("default must route to rectStroke, not no-op", r.rectStrokeCalled);
        assertEquals("x preserved", 10f, r.sx, 1e-6);
        assertEquals("y preserved", 20f, r.sy, 1e-6);
        assertEquals("w preserved", 30f, r.sw, 1e-6);
        assertEquals("h preserved", 40f, r.sh, 1e-6);
        assertEquals("thickness preserved", 2f, r.st, 1e-6);
        assertEquals("color preserved", 0xFFABCDEF, r.sArgb);
    }
}
