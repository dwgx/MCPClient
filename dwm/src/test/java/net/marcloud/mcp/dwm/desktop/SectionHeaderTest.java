package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Teeth for {@link SectionHeader}'s clickable action label (the "View: List/Grid" toggle).
 * A press-then-release over the right-aligned action label must fire the action callback
 * exactly once; a plain (no-action) header must still render its title and never fire.
 */
public class SectionHeaderTest {

    private static final float W = 300f;

    private static FrameComponentContext ctx(RecordingDraw draw, FrameInput input) {
        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        c.bind(draw, m, input, new FrameMetrics(854, 480, 1f, 1f / 60f, 1L));
        return c;
    }

    @Test
    public void drawsTitleAndAction() {
        RecordingDraw draw = new RecordingDraw();
        new SectionHeader("All", "View: List").render(ctx(draw, FrameInput.none()),
                0, 0, W, SectionHeader.HEIGHT_DP);
        assertTrue(draw.drew("All"));
        assertTrue(draw.drew("View: List"));
    }

    @Test
    public void plainHeaderNeverFiresAndRenders() {
        int[] count = {0};
        // A header with an EMPTY action label must never fire, even if given a callback:
        // there is no action hit box to click. Wire a real callback so the assertion has
        // teeth (would fail if an empty-action header ever invoked onAction).
        SectionHeader h = new SectionHeader("Pinned", "", () -> count[0]++);
        RecordingDraw draw = new RecordingDraw();
        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        FrameMetrics fm = new FrameMetrics(854, 480, 1f, 1f / 60f, 1L);
        // press + release at the right edge (where an action label would be, if any).
        c.bind(draw, m, new FrameInput(W - 10f, SectionHeader.HEIGHT_DP / 2f, 1, 0f, 0f,
                java.util.List.of(), java.util.List.of()), fm);
        h.render(c, 0, 0, W, SectionHeader.HEIGHT_DP);
        c.bind(new RecordingDraw(), m, new FrameInput(W - 10f, SectionHeader.HEIGHT_DP / 2f, 0, 0f, 0f,
                java.util.List.of(), java.util.List.of()), fm);
        h.render(c, 0, 0, W, SectionHeader.HEIGHT_DP);
        assertTrue(draw.drew("Pinned"));
        assertEquals("empty-action header never fires its callback", 0, count[0]);
    }

    @Test
    public void clickingActionLabelFiresOnceOnRelease() {
        int[] count = {0};
        SectionHeader h = new SectionHeader("All", "View: List", () -> count[0]++);

        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        FrameMetrics fm = new FrameMetrics(854, 480, 1f, 1f / 60f, 1L);

        // Action label is right-aligned; measured width ~ len*size*0.6. Click near the right
        // edge where the label sits. Press then release over the same spot -> one click.
        float px = W - 20f;
        float py = SectionHeader.HEIGHT_DP / 2f;
        c.bind(new RecordingDraw(), m,
                new FrameInput(px, py, 1, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        h.render(c, 0, 0, W, SectionHeader.HEIGHT_DP); // press
        assertEquals("no click on press", 0, count[0]);
        c.bind(new RecordingDraw(), m,
                new FrameInput(px, py, 0, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        h.render(c, 0, 0, W, SectionHeader.HEIGHT_DP); // release over label -> click
        assertEquals("exactly one action click", 1, count[0]);
    }

    @Test
    public void clickingTitleAreaDoesNotFireAction() {
        int[] count = {0};
        SectionHeader h = new SectionHeader("All", "View: List", () -> count[0]++);
        // Click far LEFT (title area), not the right-aligned action hit box.
        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        FrameMetrics fm = new FrameMetrics(854, 480, 1f, 1f / 60f, 1L);
        c.bind(new RecordingDraw(), m,
                new FrameInput(4f, SectionHeader.HEIGHT_DP / 2f, 1, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        h.render(c, 0, 0, W, SectionHeader.HEIGHT_DP);
        c.bind(new RecordingDraw(), m,
                new FrameInput(4f, SectionHeader.HEIGHT_DP / 2f, 0, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        h.render(c, 0, 0, W, SectionHeader.HEIGHT_DP);
        assertEquals("clicking the title area must not fire the action", 0, count[0]);
    }
}
