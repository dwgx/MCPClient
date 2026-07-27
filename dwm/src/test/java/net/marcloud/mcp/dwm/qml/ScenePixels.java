package net.marcloud.mcp.dwm.qml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;

/**
 * Reads back what a scene actually drew, so a control test can assert colour at a point.
 *
 * <p>Exists because of a specific failure this project has now hit twice: a test that asserts "the
 * frame did not throw" passes while nothing is drawn at all. The second time, every live IT had
 * been running against a frame with no surface for weeks. The only assertion that cannot be
 * satisfied by silence is one about pixels.
 *
 * <p><b>Test-only, and it does exactly what production must never do.</b> The compositor's
 * {@code RedirectionSurface} carries a standing prohibition on pixel readback — reading video
 * memory forces a sync with the GPU and stalls the pipeline, which is why Windows treats reading
 * the screen as a practice to avoid outright. That prohibition is about the per-frame render path.
 * Here the cost is irrelevant: it runs once per assertion in a test, off any hot path. Nothing in
 * this class may be called from {@code src/main}.
 */
final class ScenePixels {

    /** Where PNG dumps land, so a failure can be looked at rather than guessed about. */
    private static final String DUMP_DIR = "target/scene-pixels";

    private final Bitmap bitmap;
    private final int width;
    private final int height;

    private ScenePixels(Bitmap bitmap, int width, int height) {
        this.bitmap = bitmap;
        this.width = width;
        this.height = height;
    }

    /**
     * Read an offscreen surface's contents into CPU memory.
     *
     * @param context the surface's own DirectContext — required for a GPU-backed surface, which
     *                cannot be read without one
     * @return the pixels, or null if the readback failed (a caller should assert on null rather
     *         than silently pass)
     */
    static ScenePixels of(Surface surface, DirectContext context) {
        if (surface == null) {
            return null;
        }
        Image snapshot = null;
        try {
            snapshot = surface.makeImageSnapshot();
            return of(snapshot, context);
        } catch (Throwable t) {
            System.err.println("[test] pixel readback failed: " + t);
            return null;
        } finally {
            if (snapshot != null) {
                snapshot.close();
            }
        }
    }

    /**
     * Read an already-taken snapshot, which is how the compositor's cached layer image is sampled.
     *
     * <p>The image is NOT closed here: it belongs to the compositor, which reuses it across frames.
     */
    static ScenePixels of(Image snapshot, DirectContext context) {
        if (snapshot == null) {
            return null;
        }
        try {
            int w = snapshot.getWidth();
            int h = snapshot.getHeight();
            Bitmap bitmap = new Bitmap();
            // N32 premul matches the layer's own format, so the readback needs no conversion.
            if (!bitmap.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL))) {
                bitmap.close();
                return null;
            }
            if (!snapshot.readPixels(context, bitmap, 0, 0, false)) {
                bitmap.close();
                return null;
            }
            return new ScenePixels(bitmap, w, h);
        } catch (Throwable t) {
            System.err.println("[test] pixel readback failed: " + t);
            return null;
        }
    }

    /** ARGB at a point, or 0 when the point is outside the surface. */
    int at(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return bitmap.getColor(x, y);
    }

    int alphaAt(int x, int y) {
        return (at(x, y) >>> 24) & 0xFF;
    }

    /** True when the point has any coverage at all — the cheapest "something was drawn" check. */
    boolean isOpaqueEnoughAt(int x, int y) {
        return alphaAt(x, y) > 8;
    }

    /**
     * How many of the sampled row's pixels carry coverage.
     *
     * <p>Useful for asserting the EXTENT of something — a slider's filled portion, a progress bar's
     * width — which a single-point sample cannot distinguish from a stray pixel.
     */
    int coveredInRow(int y, int fromX, int toX) {
        int covered = 0;
        for (int x = fromX; x < toX; x++) {
            if (isOpaqueEnoughAt(x, y)) {
                covered++;
            }
        }
        return covered;
    }

    /** Whether two points differ visibly, for hover/press state assertions. */
    boolean differs(ScenePixels other, int x, int y) {
        return other == null || at(x, y) != other.at(x, y);
    }

    /**
     * Write a PNG beside the build output and return its path.
     *
     * <p>Called on failure so the artefact is inspectable. A dump that cannot be written is not
     * itself a failure — the assertion is about pixels, not about file IO.
     */
    String dump(String name) {
        try {
            Path dir = Paths.get(DUMP_DIR);
            Files.createDirectories(dir);
            Path out = dir.resolve(name.endsWith(".png") ? name : name + ".png");
            try (Image image = Image.makeRasterFromBitmap(bitmap);
                 Data png = EncoderPNG.encode(image)) {
                if (png == null) {
                    return "<encode failed>";
                }
                Files.write(out, png.getBytes());
            }
            return out.toAbsolutePath().toString();
        } catch (IOException | RuntimeException e) {
            return "<dump failed: " + e + ">";
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void close() {
        bitmap.close();
    }
}
