package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import java.util.Locale;
import org.junit.Test;

/**
 * A tinted icon must reach the raster at EVERY alpha the scene ships, not only at full opacity.
 *
 * <p>Skia's SVG parser paints no 8-digit hex in either byte order -- {@code #5DFFFFFF} and
 * {@code #FFFFFF5D} alike rasterize to a fully transparent image while parsing and rendering both
 * report success. The scene's disabled tints are QML {@code #AARRGGBB} 8-digit values, so before
 * {@link SvgRaster#tint} translated them a disabled settings card lost its icon OUTRIGHT rather than
 * dimming it: {@code FluentSettingsCard} uses {@code 5dffffff} when disabled and
 * {@code PageSettings} binds a card's {@code enabled} to {@code Motion.uiEffects}, which is a
 * ToggleSwitch a user can flip.
 *
 * <p>Asserts painted PIXELS rather than the substituted string, because the whole defect class here
 * is a colour that parses and then paints nothing -- a test on the text would have passed throughout.
 * The alpha values are the ones the scene actually ships, so this stays coupled to the real risk.
 */
public class SvgTintPaintsAtEveryAlphaTest {

    /** The scene's real tints: enabled card icon, secondary chevron, disabled anything. */
    private static final String[] SHIPPED_TINTS = {"ffffff", "c5ffffff", "5dffffff"};

    /** Peak alpha found in the rasterized icon, 0 when nothing was painted. */
    private static int peakAlpha(String tint) {
        String svg = ClasspathResources.readText("dwm/icons/person.svg");
        assertNotNull("the probe icon must exist", svg);
        byte[] png = SvgRaster.toPng(
            SvgRaster.tint(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8), tint),
            "tint-test-" + tint, SvgRaster.DEFAULT_EDGE);
        assertNotNull("rasterizing must produce PNG bytes for tint " + tint, png);
        try (Image img = Image.makeDeferredFromEncodedBytes(png)) {
            Bitmap bm = new Bitmap();
            try {
                bm.allocPixels(ImageInfo.makeN32(img.getWidth(), img.getHeight(),
                    ColorAlphaType.UNPREMUL));
                img.readPixels(bm, 0, 0);
                int peak = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        peak = Math.max(peak, (bm.getColor(x, y) >>> 24) & 0xff);
                    }
                }
                return peak;
            } finally {
                bm.close();
            }
        }
    }

    @Test
    public void everyTintTheSceneShipsPutsPixelsOnTheIcon() {
        for (String tint : SHIPPED_TINTS) {
            int peak = peakAlpha(tint);
            assertTrue("tint " + tint + " painted NOTHING (peak alpha 0). Skia's SVG parser rejects "
                    + "8-digit hex in either order, so an AARRGGBB tint has to be translated to "
                    + "rgba() -- otherwise a disabled card's icon vanishes instead of dimming.",
                peak > 0);
        }
    }

    /**
     * The translation must not depend on the machine's locale.
     *
     * <p>{@code String.format("%.3f", ...)} takes its decimal separator from the default locale, so
     * on a de_DE or fr_FR machine the alpha comes out as {@code 0,365} and the result is
     * {@code rgba(255,255,255,0,365)} -- five arguments, which Skia answers by painting nothing.
     * Measured: the comma form rasterizes to peak alpha 0 against 93 for the dot form. That is the
     * same defect this class exists to catch, re-entering through the formatter, and it would never
     * have failed on the machine it was written on.
     */
    @Test
    public void theTranslationSurvivesACommaDecimalLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String emitted = new String(
                SvgRaster.tint("stroke=\"currentColor\"".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), "5dffffff"),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("the alpha must use a '.' whatever the locale, or Skia sees a 5-argument "
                    + "rgba() and paints nothing. Emitted: " + emitted,
                emitted.contains("0.365"));
            assertEquals("and it must still reach the raster under that locale", 0x5D,
                peakAlpha("5dffffff"), 2.0);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    public void thePaintedAlphaTracksTheTintsAlphaRatherThanBeingFlattened() {
        // The point of an 8-digit tint is the alpha. Painting SOMETHING is not enough: returning a
        // fully opaque icon for a 36%-alpha tint would satisfy the test above while making disabled
        // look identical to enabled. 0x5D is 93; allow +-2 for the rgba() decimal round-trip.
        int disabled = peakAlpha("5dffffff");
        assertEquals("a 5dffffff tint must paint at ITS alpha, not at full opacity", 0x5D, disabled,
            2.0);

        int secondary = peakAlpha("c5ffffff");
        assertEquals("a c5ffffff tint must paint at ITS alpha", 0xC5, secondary, 2.0);

        assertEquals("a 6-digit tint stays fully opaque", 0xFF, peakAlpha("ffffff"), 0.0);

        assertTrue("and the ordering must survive: dimmer tint, dimmer pixels",
            disabled < secondary);
    }
}
