package net.marcloud.mcp.dwm.component.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.backend.TextureHandle;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.compositor.UiStateStore;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;
import net.marcloud.mcp.dwm.theme.MdcTheme;

/**
 * Teeth for the constraint-layout primitives. Verifies Column stacks children with the
 * gap at the right offsets and distinct id scopes (no ripple-state collision), and that
 * Padding insets the child box + adds insets back on measure. Uses a fixed-size spy
 * component that records the box it was rendered in.
 */
public class LayoutTest {

    /** A leaf that reports a fixed size and records where it was placed + its id path. */
    private static final class Box implements Component {
        final float wDip;
        final float hDip;
        float lastX = Float.NaN, lastY, lastW, lastH;
        String lastIdPath;

        Box(float w, float h) {
            this.wDip = w;
            this.hDip = h;
        }

        @Override
        public Result render(ComponentContext ctx, float x, float y, float w, float h) {
            lastX = x; lastY = y; lastW = w; lastH = h;
            lastIdPath = ctx.id().path();
            return Result.idle();
        }

        @Override
        public Size measure(ComponentContext ctx) {
            return new Size(wDip, hDip);
        }
    }

    @Test
    public void columnStacksChildrenWithGapAtCorrectOffsets() {
        Box a = new Box(50, 20);
        Box b = new Box(80, 30);
        Box c = new Box(40, 10);
        Column col = new Column(8f, a, b, c);
        TestCtx ctx = new TestCtx();

        // Measure: width = max(50,80,40)=80; height = 20+30+10 + 2*8 = 76.
        Component.Size s = col.measure(ctx);
        assertEquals(80f, s.width(), 0.001f);
        assertEquals(76f, s.height(), 0.001f);

        // Render in a 200-wide box at (10,10): children full-width, stacked with gap.
        col.render(ctx, 10f, 10f, 200f, 76f);
        assertEquals("a at top", 10f, a.lastY, 0.001f);
        assertEquals("b below a + gap", 10f + 20f + 8f, b.lastY, 0.001f);
        assertEquals("c below b + gap", 10f + 20f + 8f + 30f + 8f, c.lastY, 0.001f);
        assertEquals("children get full column width", 200f, a.lastW, 0.001f);
    }

    @Test
    public void columnGivesEachChildADistinctIdScope() {
        Box a = new Box(10, 10);
        Box b = new Box(10, 10);
        Column col = new Column(0f, a, b);
        TestCtx ctx = new TestCtx();
        col.render(ctx, 0f, 0f, 100f, 20f);
        // Distinct id paths => distinct UiStateStore keys => no ripple collision.
        assertTrue("child a has a scoped id", a.lastIdPath.contains("col0"));
        assertTrue("child b has a scoped id", b.lastIdPath.contains("col1"));
        assertTrue("ids differ", !a.lastIdPath.equals(b.lastIdPath));
    }

    @Test
    public void paddingInsetsChildAndAddsBackOnMeasure() {
        Box inner = new Box(100, 40);
        Padding pad = Padding.all(12f, inner);
        TestCtx ctx = new TestCtx();

        Component.Size s = pad.measure(ctx);
        assertEquals("measure adds 2*12 to width", 124f, s.width(), 0.001f);
        assertEquals("measure adds 2*12 to height", 64f, s.height(), 0.001f);

        pad.render(ctx, 0f, 0f, 124f, 64f);
        assertEquals("child inset by left", 12f, inner.lastX, 0.001f);
        assertEquals("child inset by top", 12f, inner.lastY, 0.001f);
        assertEquals("child width shrunk by l+r", 100f, inner.lastW, 0.001f);
        assertEquals("child height shrunk by t+b", 40f, inner.lastH, 0.001f);
    }

    // ---- minimal test context (id stack + no-op draw) -------------------------------

    private static final class TestCtx implements ComponentContext {
        private final MdcTheme theme = MaterialMdcTheme.darkTheme();
        private final UiStateStore store = new net.marcloud.mcp.dwm.compositor.DefaultUiStateStore();
        private final WidgetId root = WidgetId.root("test");
        private final java.util.ArrayDeque<WidgetId> ids = new java.util.ArrayDeque<>();
        private final DrawContext draw = new NoopDraw();

        private WidgetId top() { return ids.isEmpty() ? root : ids.peek(); }

        @Override public DrawContext draw() { return draw; }
        @Override public MdcTheme theme() { return theme; }
        @Override public UiStateStore store() { return store; }
        @Override public FrameInput input() { return FrameInput.none(); }
        @Override public FrameMetrics metrics() { return new FrameMetrics(200, 200, 1f, 1f / 60f, 0L); }
        @Override public WidgetId id() { return top(); }
        @Override public WidgetId childId(String key) { return WidgetId.of(top(), key); }
        @Override public void pushId(String key) { ids.push(WidgetId.of(top(), key)); }
        @Override public void popId() { if (!ids.isEmpty()) ids.pop(); }
        @Override public TextMetrics measureText(FontHandle f, CharSequence s, float px) {
            int n = s == null ? 0 : s.length();
            return new TextMetrics(n * px * 0.5f, px * 0.8f, px * 0.2f);
        }
    }

    private static final class NoopDraw implements DrawContext {
        @Override public void rect(float x, float y, float w, float h, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, float radius, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, Corners c, int argb) { }
        @Override public void rectStroke(float x, float y, float w, float h, float thickness, int argb) { }
        @Override public void line(float x0, float y0, float x1, float y1, float thickness, int argb) { }
        @Override public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) { }
        @Override public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) { }
        @Override public void path(PathSpec path, PaintSpec paint) { }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public void pushOpacity(float alpha) { }
        @Override public void popOpacity() { }
    }
}
