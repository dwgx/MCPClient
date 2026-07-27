package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Image;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Asserts the Fluent controls actually put pixels on the surface, and put them in the right places.
 *
 * <p>Pixels, not exceptions. This module has twice shipped a test that passed while nothing was
 * drawn — first a {@code GlStateGuard} probe that disturbed too little to notice a leak, then every
 * live IT running against a frame whose Skia surface was never created. Both were "it did not
 * throw" assertions. A colour read back at a coordinate cannot be satisfied by silence, which is
 * the only property that makes this suite worth having.
 *
 * <p>Assertions are deliberately coarse: that a filled control is opaque where its fill belongs,
 * that an unfilled one is not, that an accent fill differs from a subtle one, and that a slider's
 * filled extent matches its value. Exact colour equality would pin antialiasing and blend
 * behaviour, which differ by driver and are not what these controls promise.
 *
 * <p>On failure the frame is written to {@code target/scene-pixels} so it can be looked at rather
 * than reasoned about.
 */
public class ControlGalleryLiveIT {

    private static final String SCENE = "dwm/Gallery.qml";

    /** The gallery's own logical size; samples below are in these coordinates. */
    private static final int SCENE_WIDTH = 420;
    private static final int SCENE_HEIGHT = 380;

    @Test
    public void everyControlRendersAndTheFilledOnesDifferFromTheEmptyOnes() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        ScenePixels px = null;
        try {
            surface = openGallery();
            px = layerPixels(surface);
            assertNotNull("the layer must be readable; without it there is nothing to assert on",
                px);

            float s = scale(surface);

            // Sample points come from scanning the rendered rows, not from reasoning about layout.
            // That matters: the first version of this test sampled the centre of each button and
            // was reading the LABEL GLYPHS, so "accent differs from standard" was comparing two
            // antialiased letters and passed with the accent variant switched off. Every point
            // below sits in a control's fill, clear of its text.

            // An accent button is opaque accent across its plate; the standard variant is a barely
            // there subtle plate. Sampled left of both labels.
            assertAccent(px, s, 165, 30, "accent button plate");
            assertTranslucent(px, s, 25, 30, "standard button plate");

            // A checked box is accent-filled. Sampled 3px in from its left edge, inside the fill
            // but outside the checkmark that sits in the middle.
            assertAccent(px, s, 23, 140, "checked box fill");
            // An unchecked box is nothing but an outline, so its interior must be fully clear.
            assertClear(px, s, 170, 140, "unchecked box interior");

            // The on-track is accent-filled; the off-track's interior is empty between its borders.
            assertAccent(px, s, 30, 90, "on toggle track");
            // x=185, not the middle: an off toggle parks its knob at the LEFT, so the middle is
            // knob, not empty track. Scanning the row is what showed the knob spanning 163..176.
            assertClear(px, s, 185, 90, "off toggle track interior");

            surface.close();
            surface = null;
        } finally {
            if (px != null) {
                px.close();
            }
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    /**
     * A slider's filled portion must match its value.
     *
     * <p>The extent is what a single-point sample cannot check, and it is exactly where an
     * arithmetic slip hides: an off-by-a-thumb-radius error still renders a plausible slider. The
     * gallery's slider is at 0.5 over 200px with a 20px thumb, so the fill spans about half of the
     * 180px travel.
     */
    @Test
    public void theSlidersFilledExtentMatchesItsValue() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        ScenePixels px = null;
        try {
            surface = openGallery();
            px = layerPixels(surface);
            assertNotNull("the layer must be readable", px);

            float s = scale(surface);
            // The track's vertical centre: the slider row is 40px tall at y=170.
            int trackY = Math.round((170 + 20) * s);
            int left = Math.round(30 * s);
            int mid = Math.round(110 * s);
            int right = Math.round(210 * s);

            assertTrue("the track must be filled left of the thumb (sampled x=30)",
                px.isOpaqueEnoughAt(left, trackY));
            // Right of the thumb the track is still drawn, in the rest colour, so the meaningful
            // assertion is that the two sides differ rather than that one is empty.
            assertTrue("filled and unfilled halves of the track must differ; identical means the "
                    + "value is not driving the fill (dump: " + px.dump("slider-extent") + ")",
                px.at(left, trackY) != px.at(right, trackY));
            assertTrue("the thumb must sit near the middle at value 0.5",
                px.isOpaqueEnoughAt(mid, trackY));

            surface.close();
            surface = null;
        } finally {
            if (px != null) {
                px.close();
            }
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    /**
     * Every MouseArea in the gallery must be hittable.
     *
     * <p>The same walk {@link CompositorLiveIT} runs on the menu, extended to the controls: a
     * zero-sized MouseArea renders perfectly and silently swallows every click, and six new
     * controls is six new chances to introduce one.
     */
    @Test
    public void everyControlIsHittable() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openGallery();
            StringBuilder dead = new StringBuilder();
            int total = countMouseAreas(viewOf(surface).root(), dead);

            assertTrue("the gallery must contain MouseAreas to be interactive at all", total > 0);
            assertTrue("every MouseArea must have a non-zero hit box; a zero-sized one renders "
                + "fine and can never be clicked:" + dead, dead.length() == 0);

            surface.close();
            surface = null;
        } finally {
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    // ---- assertions ------------------------------------------------------------

    /** Fluent.accent, as an ARGB pixel: the token is #4cc2ff over an opaque fill. */
    private static final int ACCENT_RGB = 0x4cc2ff;

    /**
     * The point must be opaque and carry the accent hue.
     *
     * <p>Asserting the actual colour, not merely "something is here". Plain opacity is what let the
     * earlier version of this test pass with the accent variant disabled — a subtle white plate and
     * an accent fill are both "drawn". A small per-channel tolerance absorbs antialiasing near an
     * edge without admitting a different colour.
     */
    private static void assertAccent(ScenePixels px, float scale, int x, int y, String what) {
        int dx = Math.round(x * scale);
        int dy = Math.round(y * scale);
        int argb = px.at(dx, dy);
        assertTrue(what + " must be opaque at (" + x + "," + y + "); alpha was "
                + px.alphaAt(dx, dy) + " (dump: " + px.dump("gallery") + ")",
            ((argb >>> 24) & 0xFF) > 0xF0);
        assertTrue(what + " must be filled with the accent colour at (" + x + "," + y + "); found "
                + String.format("%08x", argb) + ", expected about "
                + String.format("%06x", ACCENT_RGB) + " (dump: " + px.dump("gallery") + ")",
            near(argb, ACCENT_RGB, 12));
    }

    /**
     * The point must carry some coverage but be far from opaque — a subtle fill.
     *
     * <p>Both bounds matter: a fully transparent point means the plate is missing, and an opaque one
     * means it is not the subtle variant.
     */
    private static void assertTranslucent(ScenePixels px, float scale, int x, int y, String what) {
        int dx = Math.round(x * scale);
        int dy = Math.round(y * scale);
        int alpha = px.alphaAt(dx, dy);
        assertTrue(what + " must be drawn at (" + x + "," + y + ") but was fully transparent"
                + " (dump: " + px.dump("gallery") + ")", alpha > 4);
        assertTrue(what + " must be a SUBTLE fill, not an opaque one; alpha was " + alpha
                + " (dump: " + px.dump("gallery") + ")", alpha < 0x80);
    }

    /** Per-channel closeness, so antialiasing near an edge does not fail an exact match. */
    private static boolean near(int argb, int rgb, int tolerance) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (argb >>> shift) & 0xFF;
            int b = (rgb >>> shift) & 0xFF;
            if (Math.abs(a - b) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static void assertClear(ScenePixels px, float scale, int x, int y, String what) {
        int dx = Math.round(x * scale);
        int dy = Math.round(y * scale);
        assertTrue(what + " must stay unfilled at (" + x + "," + y + "); alpha was "
                + px.alphaAt(dx, dy) + " (dump: " + px.dump("gallery") + ")",
            !px.isOpaqueEnoughAt(dx, dy));
    }


    // ---- harness ---------------------------------------------------------------

    private static QmlUiSurface openGallery() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("gallery must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
        return surface;
    }

    /** The DPI scale the canvas applied, so logical sample points map to device pixels. */
    private static float scale(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("uiScale");
        f.setAccessible(true);
        float s = (Float) f.get(surface);
        return s > 0.0F ? s : 1.0F;
    }

    /**
     * The compositor's cached layer image, read back.
     *
     * <p>Sampling the layer rather than the default framebuffer is deliberate: the layer is what the
     * scene was actually painted into, so a readback of it cannot be satisfied by whatever happened
     * to be on screen.
     */
    private static ScenePixels layerPixels(QmlUiSurface surface) throws Exception {
        Field bf = QmlUiSurface.class.getDeclaredField("backend");
        bf.setAccessible(true);
        Object backend = bf.get(surface);
        assertNotNull("the backend must exist after a frame", backend);

        Field lf = backend.getClass().getDeclaredField("layer");
        lf.setAccessible(true);
        Object layer = lf.get(backend);

        Field sf = layer.getClass().getDeclaredField("snapshot");
        sf.setAccessible(true);
        Image snapshot = (Image) sf.get(layer);
        assertNotNull("the compositor must have cached a scene snapshot; null means the scene was "
            + "never painted into the offscreen layer", snapshot);

        Field cf = backend.getClass().getDeclaredField("context");
        cf.setAccessible(true);
        DirectContext context = (DirectContext) cf.get(backend);
        return ScenePixels.of(snapshot, context);
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("view");
        f.setAccessible(true);
        return (QmlView) f.get(surface);
    }

    private static int countMouseAreas(Item item, StringBuilder dead) {
        if (item == null) {
            return 0;
        }
        int found = 0;
        if (item instanceof MouseArea) {
            found++;
            if (item.width.peekFloat() <= 0.0F || item.height.peekFloat() <= 0.0F) {
                dead.append("\n  ").append(item.getClass().getSimpleName())
                    .append(" ").append(item.width.peekFloat())
                    .append("x").append(item.height.peekFloat());
            }
        }
        for (Item child : item.children) {
            found += countMouseAreas(child, dead);
        }
        return found;
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(SCENE_WIDTH + 60, SCENE_HEIGHT + 60));
            Display.create();
            Display.update();
            return true;
        } catch (Throwable t) {
            System.out.println("[IT] no display (" + t + ") — skipping");
            return false;
        }
    }

    private static void destroyDisplay() {
        try {
            Display.destroy();
        } catch (Throwable ignored) {
            // Teardown of an already-dead display is not actionable.
        }
    }
}
