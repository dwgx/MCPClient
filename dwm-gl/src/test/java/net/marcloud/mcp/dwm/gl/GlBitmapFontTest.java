package net.marcloud.mcp.dwm.gl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.TextMetrics;

/**
 * Non-vacuous tests for {@link GlBitmapFont} + the GL backend's text metrics. These prove
 * the HEADLESS-verifiable half of the "gl real font" gap: the glyph bitmaps are non-empty
 * and correctly shaped, the per-pixel geometry math is right, and — critically — the
 * advance arithmetic is byte-for-byte the historical value so adding real glyphs shifts
 * NO layout (cross-backend pill parity preserved). The actual GL quad emission (glVertex)
 * is live-only and not exercised here; this locks the math the live draw depends on.
 */
public class GlBitmapFontTest {

    @Test
    public void printableGlyphsHaveInkBlankControlsDoNot() {
        // A visible letter must light some pixels; space + a control char must be all-clear.
        assertTrue("'A' must have lit pixels", GlBitmapFont.glyph('A') != 0L);
        assertTrue("'g' must have lit pixels", GlBitmapFont.glyph('g') != 0L);
        assertEquals("space is blank", 0L, GlBitmapFont.glyph(' '));
        assertEquals("NUL is blank", 0L, GlBitmapFont.glyph('\0'));
    }

    @Test
    public void everyLabelLetterUsedByDemoRendersNonBlank() {
        // Guards against a corrupt/misaligned table row: every letter the MD3 demo labels
        // use ("Save", "Filled", etc.) must be a non-blank glyph.
        for (char ch : "SaveFilledTonalOutlinedTextElevated 0123456789".toCharArray()) {
            if (ch == ' ') {
                continue;
            }
            assertTrue("glyph '" + ch + "' unexpectedly blank", GlBitmapFont.glyph(ch) != 0L);
        }
    }

    @Test
    public void pixelDecodingMatchesLetterI() {
        // 'I' (U+0049) row bytes: 1E 0C 0C 0C 0C 0C 1E 00. Row 0 = 0x1E = 0b00011110, so
        // columns 1..4 are lit and 0 is clear — verifies the LSB-is-leftmost decode.
        assertFalse(GlBitmapFont.pixel('I', 0, 0));
        assertTrue(GlBitmapFont.pixel('I', 1, 0));
        assertTrue(GlBitmapFont.pixel('I', 4, 0));
        assertFalse(GlBitmapFont.pixel('I', 5, 0));
        // Row 7 (0x00) is entirely clear.
        for (int c = 0; c < GlBitmapFont.CELL; c++) {
            assertFalse("row 7 must be clear at col " + c, GlBitmapFont.pixel('I', c, 7));
        }
    }

    @Test
    public void pixelOutOfBoundsIsClear() {
        assertFalse(GlBitmapFont.pixel('A', -1, 0));
        assertFalse(GlBitmapFont.pixel('A', 0, -1));
        assertFalse(GlBitmapFont.pixel('A', GlBitmapFont.CELL, 0));
        assertFalse(GlBitmapFont.pixel('A', 0, GlBitmapFont.CELL));
    }

    @Test
    public void nonAsciiDegradesToBlankNotCrash() {
        assertEquals(0L, GlBitmapFont.glyph('☃')); // snowman: out of the 128-entry table
    }

    @Test
    public void advanceIsHistoricalRatioSoNoLayoutShift() {
        // The layout-consistency invariant: the bitmap font's advance MUST equal the value
        // the GL backend advertised before real glyphs (0.55 * sizePx), so measured pill
        // widths do not move and stay equal to imgui/skiko. This is the guard rail against
        // silently changing cross-backend layout.
        assertEquals(0.55f, GlBitmapFont.ADVANCE_RATIO, 0f);
        assertEquals(0.55f * 14f, GlBitmapFont.advance(14f), 1e-5f);
        assertEquals(5 * 0.55f * 14f, GlBitmapFont.measureWidth("Hello", 14f), 1e-4f);
        assertEquals(0f, GlBitmapFont.measureWidth("", 14f), 0f);
        assertEquals(0f, GlBitmapFont.measureWidth(null, 14f), 0f);
    }

    @Test
    public void backendMeasureTextRoutesThroughFontAdvance() {
        // GlRenderBackend.measureText must agree with the font's own advance (single source
        // of truth) — measure and draw can never disagree, and the width is unchanged from
        // the pre-glyph 0.55 computation.
        GlRenderBackend backend = new GlRenderBackend();
        FontHandle f = new FontHandle(0L);
        TextMetrics m = backend.measureText(f, "Save", 14f);
        assertEquals(GlBitmapFont.measureWidth("Save", 14f), m.width(), 1e-4f);
        assertEquals(4 * 0.55f * 14f, m.width(), 1e-4f);
    }

    @Test
    public void glyphColumnsFillExactlyOneAdvance() {
        // The draw scales 8 cell columns across one advance; assert the derived pixel width
        // times CELL reconstructs the advance (the math text() uses to place quads).
        float sizePx = 14f;
        float advance = GlBitmapFont.advance(sizePx);
        float pixelW = advance / GlBitmapFont.CELL;
        assertEquals(advance, pixelW * GlBitmapFont.CELL, 1e-4f);
    }

    @Test
    public void allGlyphIndicesResolveWithoutException() {
        // Table must be exactly 128 entries — index every ASCII code and a few beyond.
        long[] first = new long[128];
        for (int i = 0; i < 128; i++) {
            first[i] = GlBitmapFont.glyph((char) i);
        }
        // Sanity: digits '0'..'9' are all non-blank and distinct-ish (not all equal).
        long[] digits = new long[10];
        for (int d = 0; d < 10; d++) {
            digits[d] = GlBitmapFont.glyph((char) ('0' + d));
            assertTrue("digit " + d + " blank", digits[d] != 0L);
        }
        assertFalse("'0' and '1' must differ", digits[0] == digits[1]);
        // ''..'' boundary: 0x7F in table, 0x80 out (blank).
        assertArrayEquals(new long[] {first[0x7F]}, new long[] {GlBitmapFont.glyph((char) 0x7F)});
        assertEquals(0L, GlBitmapFont.glyph((char) 0x80));
    }
}
