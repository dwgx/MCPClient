package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Render + click teeth for {@link SoftwareRow}, built on the PRODUCTION context/theme/store
 * (no material-package fakes needed) plus a recording draw context. Asserts the row draws
 * its name, and that a press-then-release over the row fires the toggle callback exactly
 * once with the right software.
 */
public class SoftwareRowTest {

    private static FrameComponentContext ctx(RecordingDraw draw, FrameInput input) {
        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer measurer =
                (font, text, sizePx) -> new TextMetrics(
                        (text == null ? 0 : text.length()) * sizePx * 0.6f, sizePx * 0.8f, sizePx * 0.2f);
        c.bind(draw, measurer, input, new FrameMetrics(854, 480, 1f, 1f / 60f, 1L));
        return c;
    }

    @Test
    public void drawsSoftwareNameAndIconInitial() {
        RecordingDraw draw = new RecordingDraw();
        SoftwareView v = new SoftwareView("esp", "ESP", "Render", 0, false);
        new SoftwareRow(v, sv -> { }).render(ctx(draw, FrameInput.none()), 0, 0, 300, SoftwareRow.HEIGHT_DP);
        assertTrue("row drew its name", draw.drew("ESP"));
        assertTrue("row drew an icon/dot rounded rect", draw.roundedRects >= 1);
    }

    @Test
    public void clickFiresToggleOnceWithThisSoftware() {
        AtomicReference<SoftwareView> toggled = new AtomicReference<>();
        int[] count = {0};
        SoftwareView v = new SoftwareView("speed", "Speed", "Movement", 0, false);
        SoftwareRow row = new SoftwareRow(v, sv -> { toggled.set(sv); count[0]++; });

        // Frame 1: pointer over the row, button DOWN → press edge, no click yet.
        FrameInput down = new FrameInput(10f, SoftwareRow.HEIGHT_DP / 2f, 1, 0f, 0f,
                java.util.List.of(), java.util.List.of());
        row.render(ctx(new RecordingDraw(), down), 0, 0, 300, SoftwareRow.HEIGHT_DP);
        assertEquals("no click on press", 0, count[0]);

        // Frame 2 MUST reuse the SAME store so the ClickState persists across frames.
        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        // press then release in one persistent context:
        c.bind(new RecordingDraw(), m, down, new FrameMetrics(854, 480, 1f, 1f / 60f, 1L));
        row.render(c, 0, 0, 300, SoftwareRow.HEIGHT_DP);          // press
        FrameInput up = new FrameInput(10f, SoftwareRow.HEIGHT_DP / 2f, 0, 0f, 0f,
                java.util.List.of(), java.util.List.of());
        c.bind(new RecordingDraw(), m, up, new FrameMetrics(854, 480, 1f, 1f / 60f, 1L));
        row.render(c, 0, 0, 300, SoftwareRow.HEIGHT_DP);          // release over row → click
        assertEquals("exactly one click", 1, count[0]);
        assertEquals("callback got this software", "speed", toggled.get().chipId());
    }

    @Test
    public void rightClickFiresPinNotToggle() {
        int[] toggles = {0};
        int[] pins = {0};
        SoftwareView v = new SoftwareView("esp", "ESP", "Render", 0, false);
        SoftwareRow row = new SoftwareRow(v, sv -> toggles[0]++, sv -> pins[0]++, false);

        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        FrameMetrics fm = new FrameMetrics(854, 480, 1f, 1f / 60f, 1L);

        // Secondary button = bit 1 (mask 2). Press then release over the row.
        float rx = 10f;
        float ry = SoftwareRow.HEIGHT_DP / 2f;
        c.bind(new RecordingDraw(), m,
                new FrameInput(rx, ry, 2, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        row.render(c, 0, 0, 300, SoftwareRow.HEIGHT_DP); // right press
        c.bind(new RecordingDraw(), m,
                new FrameInput(rx, ry, 0, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        row.render(c, 0, 0, 300, SoftwareRow.HEIGHT_DP); // right release -> pin
        assertEquals("right-click fires pin exactly once", 1, pins[0]);
        assertEquals("right-click must NOT fire toggle", 0, toggles[0]);
    }

    @Test
    public void noClickWhenReleasedOffTheRow() {
        int[] count = {0};
        SoftwareRow row = new SoftwareRow(new SoftwareView("x", "X", "", 0, false),
                sv -> count[0]++);
        FrameComponentContext c = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);
        FrameMetrics fm = new FrameMetrics(854, 480, 1f, 1f / 60f, 1L);
        // press ON the row
        c.bind(new RecordingDraw(), m,
                new FrameInput(10f, SoftwareRow.HEIGHT_DP / 2f, 1, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        row.render(c, 0, 0, 300, SoftwareRow.HEIGHT_DP);
        // release far OFF the row
        c.bind(new RecordingDraw(), m,
                new FrameInput(9999f, 9999f, 0, 0f, 0f, java.util.List.of(), java.util.List.of()), fm);
        row.render(c, 0, 0, 300, SoftwareRow.HEIGHT_DP);
        assertEquals("release off the row must not click", 0, count[0]);
        assertFalse(false);
    }
}
