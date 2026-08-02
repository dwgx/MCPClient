package net.marcloud.mcp.dwm.qml;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.svg.SVGDOM;

/**
 * Rasterizes an SVG to PNG bytes, so qml4j's {@code Image} can display it.
 *
 * <p><b>Why this exists.</b> qml4j 0.2.24 decodes an {@code Image.source} through
 * {@code Image.makeDeferredFromEncodedBytes}, which is Skia's BITMAP decoder — it does not
 * understand SVG. Skija does ship an SVG module ({@code io.github.humbleui.skija.svg.SVGDOM},
 * already on the classpath via skija-shared), but nothing wires the two together.
 *
 * <p><b>Where it hooks in.</b> {@link ClasspathResources#load} is qml4j's own asset channel and is
 * ours to implement, so an {@code .svg} request is intercepted there and answered with PNG bytes.
 * qml4j then decodes those the way it decodes any image and never learns the difference. The
 * alternative shapes were both worse: a custom {@code Item} registered through
 * {@code StockTypes.registry()} cannot reach the canvas, because {@code Painter.canvas()} is
 * package-private; and writing straight into {@code Image.skiaImage} would have to stay in step
 * with qml4j's {@code loadedSource}/{@code decodeGen}/{@code adoptedGen} state machine, which is
 * internal and would silently overwrite us on any version bump.
 *
 * <p><b>Cost.</b> Rasterizing needs no GL context — measured, it runs on a plain
 * {@code Surface.makeRaster} — so it happens once at load time rather than in the frame. Results
 * are cached by (path, size) because qml4j asks the loader again whenever a scene is reloaded.
 *
 * <p>A malformed SVG returns null rather than throwing: this runs inside qml4j's asset resolution,
 * on the render thread, and a broken icon must cost an icon rather than the frame.
 */
final class SvgRaster {

    /** Suffix that routes a resource request through this class. */
    static final String SUFFIX = ".svg";

    /**
     * Device pixels per logical unit to rasterize at.
     *
     * <p>2 rather than 1 because the scene is composited at the display's DPI scale and a 20px
     * icon rasterized at 20px would be resampled up on any Retina panel — visibly soft, which is
     * exactly what vector icons are supposed to avoid. Fixed rather than read from the live scale
     * because the loader runs before the surface knows it, and over-sampling costs memory while
     * under-sampling costs sharpness that cannot be recovered.
     */
    private static final int OVERSAMPLE = 2;

    /**
     * Cap on a rasterized icon's edge, in device pixels.
     *
     * <p>Icons here are 20px logical. The cap exists so a hand-edited {@code width} in an SVG
     * cannot ask for a surface measured in thousands of pixels; it is a sanity bound, not a
     * design metric.
     */
    private static final int MAX_EDGE = 512;

    /** Rasterized PNGs by cache key. Bounded by the number of distinct icons in the scenes. */
    private static final Map<String, byte[]> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<String, byte[]>());

    private SvgRaster() {
    }

    /** True when {@code path} should be answered by this class rather than read verbatim. */
    static boolean handles(String path) {
        return path != null && path.toLowerCase().endsWith(SUFFIX);
    }

    /**
     * PNG bytes for an SVG document, at {@code edge} logical pixels square.
     *
     * @param svg  the SVG source
     * @param key  cache key, normally the resource path
     * @param edge the logical edge length to render at; clamped to a sane range
     * @return encoded PNG, or null if the SVG could not be parsed or rendered
     */
    static byte[] toPng(byte[] svg, String key, int edge) {
        if (svg == null || svg.length == 0) {
            return null;
        }
        int px = Math.max(1, Math.min(MAX_EDGE, edge * OVERSAMPLE));
        String cacheKey = key + "@" + px;
        byte[] cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        byte[] png = rasterize(svg, px);
        if (png != null) {
            CACHE.put(cacheKey, png);
        }
        return png;
    }

    /**
     * The default logical edge, matching WinUI's {@code SettingsCardHeaderIconMaxSize}.
     *
     * <p>One size for every icon because the loader has no way to know which {@code Image} asked:
     * qml4j requests a path, not a path plus a box. Everything drawn from these SVGs is a
     * card or rail icon at this size, and an {@code Image} scaling a 2x raster down is cheap and
     * sharp, unlike scaling up.
     */
    static final int DEFAULT_EDGE = 20;

    private static byte[] rasterize(byte[] svg, int px) {
        try (Data data = Data.makeFromBytes(svg);
             SVGDOM dom = new SVGDOM(data)) {
            if (dom.getRoot() == null) {
                System.err.println("[dwm] SVG has no root element");
                return null;
            }
            // setContainerSize is what makes a viewBox-only document (no width/height attributes)
            // render at all: without it the container is 0x0 and render() draws nothing while
            // reporting success. Every icon here is authored viewBox-only, so this is load-bearing.
            dom.setContainerSize(px, px);
            try (Surface surface = Surface.makeRaster(
                    ImageInfo.makeN32(px, px, ColorAlphaType.PREMUL))) {
                Canvas canvas = surface.getCanvas();
                dom.render(canvas);
                try (Image image = surface.makeImageSnapshot();
                     Data png = EncoderPNG.encode(image)) {
                    return png == null ? null : png.getBytes();
                }
            }
        } catch (Throwable t) {
            // Malformed SVG, or a Skija native that would not load. An icon is not worth a frame.
            System.err.println("[dwm] could not rasterize SVG: " + t);
            return null;
        }
    }

    /**
     * Recolour an SVG source by substituting its colour placeholder.
     *
     * <p>The icons are authored with the literal token {@code currentColor}, which is valid SVG
     * but which Skia's SVG module does not resolve — it has no CSS cascade to inherit from, so a
     * {@code currentColor} stroke renders black, i.e. invisible on a dark theme. Substituting the
     * text before parsing is what makes one icon file serve a primary, secondary and disabled
     * variant.
     *
     * @param tint a CSS-style colour, e.g. {@code #ffffff}
     */
    static byte[] tint(byte[] svg, String tint) {
        if (svg == null || tint == null || tint.isEmpty()) {
            return svg;
        }
        String source = new String(svg, StandardCharsets.UTF_8);
        return source.replace("currentColor", cssColour(tint)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A tint as a colour Skia's SVG parser will actually paint.
     *
     * <p><b>Skia paints no 8-digit hex at all</b>, in either byte order -- measured, because the
     * obvious diagnosis was that QML's {@code #AARRGGBB} was being read as CSS's {@code #RRGGBBAA}.
     * It is not an ordering problem: {@code #5DFFFFFF} and {@code #FFFFFF5D} both rasterize to a
     * fully transparent image while parsing and rendering report success. {@code rgba()} does paint
     * and does carry alpha ({@code rgba(255,255,255,0.36)} measured back as {@code 5CFFFFFF}), so an
     * 8-digit tint is translated rather than passed through.
     *
     * <p>This is the same failure mode the {@code '#'} prefix below guards, and it is worth naming
     * once: an invalid paint costs the whole shape, silently. A disabled settings card was therefore
     * losing its icon outright rather than dimming it -- see
     * {@code SvgTintPaintsAtEveryAlphaTest}.
     *
     * <p>Alpha is folded into the colour rather than left to the {@code Image}'s own opacity because
     * the tint is all a {@link ResourceLoader} receives: qml4j hands this class a path and takes
     * bytes, so there is no channel to reach the node with.
     */
    private static String cssColour(String tint) {
        String hex = tint.startsWith("#") ? tint.substring(1) : tint;
        if (hex.length() == 8) {
            try {
                int a = Integer.parseInt(hex.substring(0, 2), 16);
                int r = Integer.parseInt(hex.substring(2, 4), 16);
                int g = Integer.parseInt(hex.substring(4, 6), 16);
                int b = Integer.parseInt(hex.substring(6, 8), 16);
                // Three decimals: 1/255 is 0.0039, so two would collapse adjacent alphas.
                return String.format("rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0F);
            } catch (NumberFormatException e) {
                // Not hex after all. Fall through to the literal form, which at worst reproduces
                // the old behaviour for this one value rather than dropping every icon.
                System.err.println("[dwm] tint is not 8-digit hex, passing through: " + tint);
            }
        }
        // The '#' is added when absent, and this is not cosmetic: a bare "4cc2ff" is not a valid SVG
        // colour and paints nothing. The tint arrives without it because it travels inside a
        // resource path, where '#' would read as a fragment.
        return tint.startsWith("#") ? tint : "#" + tint;
    }
}
