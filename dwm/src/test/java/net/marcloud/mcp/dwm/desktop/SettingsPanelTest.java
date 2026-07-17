package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.desktop.theme.ThemeState;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Teeth for {@link SettingsPanel}: a press-then-release over each control must mutate the
 * shared {@link ThemeState} live (the whole point of the theme system). Built on the
 * PRODUCTION context/store so click-edge state persists across the two frames a click needs,
 * plus a recording draw context so the assertions are non-vacuous (the panel really drew).
 *
 * <p>The panel lays controls out at fixed offsets from its (x,y); the helpers below click at
 * those known centers. The layout math here MUST track {@link SettingsPanel}'s constants.
 */
public class SettingsPanelTest {

    private static final float X = 0f;
    private static final float Y = 0f;
    private static final float W = 400f;
    private static final float H = 400f;

    // Row geometry mirrored from SettingsPanel (label row + gap, then the control row).
    private static final float LABEL_H = 20f;
    private static final float ROW_GAP = 14f;
    private static final float SWATCH = 34f;
    private static final float SWATCH_GAP = 8f;
    private static final float STEP_BTN = 30f;

    /** y of the preset swatch row. */
    private static float presetRowY() {
        return Y + LABEL_H + 4f;
    }

    /** y of the accent swatch row. */
    private static float accentRowY() {
        return presetRowY() + SWATCH + ROW_GAP + LABEL_H + 4f;
    }

    /** y of the font-scale stepper row. */
    private static float fontRowY() {
        return accentRowY() + SWATCH + ROW_GAP + LABEL_H + 4f;
    }

    /** y of the panel-opacity stepper row. */
    private static float opacityRowY() {
        return fontRowY() + STEP_BTN + ROW_GAP + LABEL_H + 4f;
    }

    private static float swatchCenterX(int index) {
        return X + index * (SWATCH + SWATCH_GAP) + SWATCH * 0.5f;
    }

    private static float minusCenterX() {
        return X + STEP_BTN * 0.5f;
    }

    private static float plusCenterX() {
        return X + STEP_BTN + SWATCH_GAP + STEP_BTN * 0.5f;
    }

    private static final class Harness {
        final ThemeState state = new ThemeState();
        final SettingsPanel panel = new SettingsPanel(state);
        final FrameComponentContext ctx = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), new Compositor().store(), WidgetId.root("desktop"));
        final FrameComponentContext.TextMeasurer m =
                (f, t, s) -> new TextMetrics((t == null ? 0 : t.length()) * s * 0.6f, s * 0.8f, s * 0.2f);

        /** Press at (px,py), then release there, in the SAME context so ClickState persists. */
        void click(float px, float py) {
            frame(px, py, 1);   // press edge
            frame(px, py, 0);   // release over control -> click
        }

        void frame(float px, float py, int buttonMask) {
            ctx.bind(new RecordingDraw(), m,
                    new FrameInput(px, py, buttonMask, 0f, 0f, java.util.List.of(), java.util.List.of()),
                    new FrameMetrics(854, 480, 1f, 1f / 60f, 1L));
            panel.render(ctx, X, Y, W, H);
        }
    }

    @Test
    public void clickingPresetSwatchChangesPreset() {
        Harness h = new Harness();
        assertEquals(ThemeState.Preset.MIDNIGHT, h.state.preset());
        // Index 3 is LIGHT (the 4th preset) — click it.
        h.click(swatchCenterX(3), presetRowY() + SWATCH * 0.5f);
        assertEquals("preset swatch click switched the preset",
                ThemeState.Preset.LIGHT, h.state.preset());
    }

    @Test
    public void clickingAccentSwatchChangesAccent() {
        Harness h = new Harness();
        int before = h.state.accent();
        // Index 2 is green in ThemeState.ACCENTS.
        h.click(swatchCenterX(2), accentRowY() + SWATCH * 0.5f);
        assertNotEquals("accent swatch click switched the accent", before, h.state.accent());
        assertEquals(0xFF000000 | (ThemeState.ACCENTS[2] & 0x00FFFFFF), h.state.accent());
    }

    @Test
    public void fontStepperDecrementsAndIncrements() {
        Harness h = new Harness();
        float base = h.state.fontScale();
        h.click(minusCenterX(), fontRowY() + STEP_BTN * 0.5f);
        assertTrue("minus lowers font scale", h.state.fontScale() < base);
        float lowered = h.state.fontScale();
        h.click(plusCenterX(), fontRowY() + STEP_BTN * 0.5f);
        assertTrue("plus raises font scale", h.state.fontScale() > lowered);
    }

    @Test
    public void opacityStepperDecrementsAndIncrements() {
        Harness h = new Harness();
        int base = h.state.panelOpacity();
        h.click(minusCenterX(), opacityRowY() + STEP_BTN * 0.5f);
        assertTrue("minus lowers panel opacity", h.state.panelOpacity() < base);
        int lowered = h.state.panelOpacity();
        h.click(plusCenterX(), opacityRowY() + STEP_BTN * 0.5f);
        assertTrue("plus raises panel opacity", h.state.panelOpacity() > lowered);
    }

    @Test
    public void panelDrawsControlsNonVacuously() {
        Harness h = new Harness();
        RecordingDraw draw = new RecordingDraw();
        h.ctx.bind(draw, h.m, FrameInput.none(), new FrameMetrics(854, 480, 1f, 1f / 60f, 1L));
        h.panel.render(h.ctx, X, Y, W, H);
        // 4 presets + 6 accents + 2 step buttons + step-button hover fills = many rounded rects.
        assertTrue("panel drew swatches/buttons", draw.roundedRects >= 10);
        assertTrue("panel drew a section label", draw.drew("Theme"));
        assertTrue("panel drew the +/- glyphs", draw.drew("+") && draw.drew("-"));
    }
}
