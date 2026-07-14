package net.marcloud.mcp.dwm.gl;

import net.marcloud.mcp.dwm.backend.DrawContext;

/**
 * An embedded 8x8 ASCII bitmap font for the fixed-function GL backend — real glyphs
 * without a texture atlas or a native font dependency (the GL backend's zero-dep ethos:
 * no shaders, no textures, no LWJGL font lib). Each of the 128 ASCII glyphs is 8 rows of
 * 8 bits packed into a {@code long} (row {@code r} is byte {@code r}, bit {@code c} is the
 * pixel at column {@code c}, LSB = leftmost). At draw time {@link GlDrawContext#text}
 * walks the set bits and emits one filled quad per lit pixel, scaled so 8 cell-rows fill
 * the requested pixel size — the classic immediate-mode-GL text approach.
 *
 * <p><b>Provenance.</b> Glyph bitmaps are the public-domain {@code font8x8_basic} set
 * (Daniel Hepper, from the IBM VGA / Marcel Sondaar ROM font) — no license obligation,
 * safe to embed verbatim.
 *
 * <p><b>Metrics contract.</b> A glyph's advance at {@code sizePx} is
 * {@code sizePx * ADVANCE_RATIO}, and the 8 bitmap columns are scaled to fill exactly that
 * advance (so glyphs abut with the font's own trailing-column gap). {@link #ADVANCE_RATIO}
 * is deliberately the SAME value the GL backend advertised before real glyphs existed
 * ({@code 0.55}) and is the single source of truth {@link GlRenderBackend#measureText}
 * routes through — so the GL pill width the layout computes is byte-for-byte unchanged by
 * adding glyphs (no layout shift, cross-backend pill parity preserved) AND the drawn text
 * fits exactly within the measured advance.
 *
 * <p><b>Vertical placement.</b> {@link DrawContext#text}'s {@code y} is a baseline-ish
 * value; the glyph cell top sits {@code sizePx * ASCENT_RATIO} above it (mirroring the
 * imgui backend's 0.8em lift) so GL text lands at the same vertical spot as imgui text.
 *
 * <p>Pure arithmetic + data: no GL calls here, so the glyph geometry (which pixels a glyph
 * lights, the advance / cell math) is fully unit-testable headless; only the quad emission
 * in {@link GlDrawContext} needs a live GL context.
 */
public final class GlBitmapFont {

    private GlBitmapFont() {
    }

    /** Glyph cell edge in font pixels (the bitmap is 8x8). */
    public static final int CELL = 8;

    /**
     * Per-glyph advance as a fraction of {@code sizePx}. Kept equal to the GL backend's
     * historical placeholder advance ({@code 0.55}) so introducing real glyphs shifts NO
     * layout — {@link GlRenderBackend#measureText} routes through this same constant, so
     * measure and draw can never disagree.
     */
    public static final float ADVANCE_RATIO = 0.55f;

    /** Glyph cell top offset above the {@code text} baseline, as a fraction of sizePx. */
    public static final float ASCENT_RATIO = 0.8f;

    /**
     * 128 ASCII glyphs, 8x8 packed little-endian by row then column (byte r = row r,
     * bit c = column c from the left). Public-domain font8x8_basic.
     */
    private static final long[] GLYPHS = {
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x0000000000000000L, 0x00180018183C3C18L, 0x0000000000003636L, 0x0036367F367F3636L,
            0x000C1F301E033E0CL, 0x0063660C18336300L, 0x006E333B6E1C361CL, 0x0000000000030606L,
            0x00180C0606060C18L, 0x00060C1818180C06L, 0x0000663CFF3C6600L, 0x00000C0C3F0C0C00L,
            0x060C0C0000000000L, 0x000000003F000000L, 0x000C0C0000000000L, 0x000103060C183060L,
            0x003E676F7B73633EL, 0x003F0C0C0C0C0E0CL, 0x003F33061C30331EL, 0x001E33301C30331EL,
            0x0078307F33363C38L, 0x001E3330301F033FL, 0x001E33331F03061CL, 0x000C0C0C1830333FL,
            0x001E33331E33331EL, 0x000E18303E33331EL, 0x000C0C00000C0C00L, 0x060C0C00000C0C00L,
            0x00180C0603060C18L, 0x00003F00003F0000L, 0x00060C1830180C06L, 0x000C000C1830331EL,
            0x001E037B7B7B633EL, 0x0033333F33331E0CL, 0x003F66663E66663FL, 0x003C66030303663CL,
            0x001F36666666361FL, 0x007F46161E16467FL, 0x000F06161E16467FL, 0x007C66730303663CL,
            0x003333333F333333L, 0x001E0C0C0C0C0C1EL, 0x001E333330303078L, 0x006766361E366667L,
            0x007F66460606060FL, 0x0063636B7F7F7763L, 0x006363737B6F6763L, 0x001C36636363361CL,
            0x000F06063E66663FL, 0x00381E3B3333331EL, 0x006766363E66663FL, 0x001E33380E07331EL,
            0x001E0C0C0C0C2D3FL, 0x003F333333333333L, 0x000C1E3333333333L, 0x0063777F6B636363L,
            0x0063361C1C366363L, 0x001E0C0C1E333333L, 0x007F664C1831637FL, 0x001E06060606061EL,
            0x00406030180C0603L, 0x001E18181818181EL, 0x0000000063361C08L, 0xFF00000000000000L,
            0x0000000000180C0CL, 0x006E333E301E0000L, 0x003B66663E060607L, 0x001E3303331E0000L,
            0x006E33333E303038L, 0x001E033F331E0000L, 0x000F06060F06361CL, 0x1F303E33336E0000L,
            0x006766666E360607L, 0x001E0C0C0C0E000CL, 0x1E33333030300030L, 0x0067361E36660607L,
            0x001E0C0C0C0C0C0EL, 0x00636B7F7F330000L, 0x00333333331F0000L, 0x001E3333331E0000L,
            0x0F063E66663B0000L, 0x78303E33336E0000L, 0x000F06666E3B0000L, 0x001F301E033E0000L,
            0x00182C0C0C3E0C08L, 0x006E333333330000L, 0x000C1E3333330000L, 0x00367F7F6B630000L,
            0x0063361C36630000L, 0x1F303E3333330000L, 0x003F260C193F0000L, 0x00380C0C070C0C38L,
            0x0018181800181818L, 0x00070C0C380C0C07L, 0x0000000000003B6EL, 0x0000000000000000L
    };

    /** The packed 8x8 bitmap for {@code ch} (unmapped / non-ASCII → space, all clear). */
    public static long glyph(char ch) {
        return ch < GLYPHS.length ? GLYPHS[ch] : 0L;
    }

    /** True if the pixel at ({@code col},{@code row}) of {@code ch}'s cell is lit. */
    public static boolean pixel(char ch, int col, int row) {
        if (col < 0 || col >= CELL || row < 0 || row >= CELL) {
            return false;
        }
        long g = glyph(ch);
        int bit = row * CELL + col;
        return ((g >>> bit) & 1L) != 0L;
    }

    /** Advance (in pixels) for one glyph rendered at {@code sizePx}. */
    public static float advance(float sizePx) {
        return sizePx * ADVANCE_RATIO;
    }

    /** Total width (in pixels) of {@code s} rendered at {@code sizePx}. */
    public static float measureWidth(CharSequence s, float sizePx) {
        int n = s == null ? 0 : s.length();
        return n * advance(sizePx);
    }
}
