package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
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
            // An unchecked box is FAINTLY filled, not empty. WinUI's
            // CheckBoxCheckBackgroundFillUnchecked is ControlAltFillColorSecondary (#19000000) --
            // a black-based recess. This assertion used to demand a fully clear interior, which
            // was asserting the absence of a fill the real control has.
            assertFaintFill(px, s, 170, 140, "unchecked box interior");

            // The on-track is accent-filled; the off-track carries the same faint recess as an
            // unchecked box -- ToggleSwitchFillOff is ControlAltFillColorSecondary too.
            assertAccent(px, s, 30, 90, "on toggle track");
            // x=185, not the middle: an off toggle parks its knob at the LEFT, so the middle is
            // knob, not empty track. Scanning the row is what showed the knob spanning 163..176.
            assertFaintFill(px, s, 185, 90, "off toggle track interior");

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
     * gallery's slider is at 0.5 over 200px with an 18px thumb, so the fill spans about half of
     * the 182px travel.
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
     * A button's top edge must be brighter than its bottom edge.
     *
     * <p>This is the elevation border — WinUI's {@code ControlElevationBorderBrush}, a 3px absolute
     * ramp from {@code ControlStrokeColorSecondary} (#18FFFFFF) down to
     * {@code ControlStrokeColorDefault} (#12FFFFFF). It is most of what gives a Windows 11 control
     * its sense of thickness, and it is invisible to every other kind of assertion: a control with
     * a flat single-colour outline renders perfectly and looks only slightly flatter.
     *
     * <p>Asserted as a RELATION rather than against the two hex values, because both stops are the
     * SAME white and differ only in alpha (#18FFFFFF vs #12FFFFFF). Measured down the middle of
     * the standard button: alpha runs 0x28, 0x35, 0x32 across the top three rows, holds at 0x20
     * through the interior, and finishes at 0x12 on the bottom row. So the comparison must be on
     * ALPHA -- a luminance comparison is identically 255 on both edges and can never fail, which
     * is what the first version of this assertion did.
     *
     * <p>Sampled 20px in from the left edge, clear of the corner arcs where antialiasing blends
     * both stops together.
     */
    @Test
    public void aButtonsTopEdgeIsLitAndItsBottomEdgeIsNot() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        ScenePixels px = null;
        try {
            surface = openGallery();
            px = layerPixels(surface);
            assertNotNull("the layer must be readable", px);

            float s = scale(surface);
            // The standard button sits at (20,20) and is 32 tall, so its edges are y=20 and y=51.
            //
            // Sampled in DEVICE rows, not by converting two logical y values: the ramp is 3
            // LOGICAL px tall, which at 2x covers six device rows, and the first version of this
            // test compared logical y=20 against y=51 -- the second of which is the FACE, not the
            // outline. It therefore passed with the ramp inverted, flattened, and with both stops
            // set equal. Anchoring to the box's own first and last device row is what makes the
            // comparison be about the border at all.
            int x = Math.round(40 * s);
            int topY = Math.round(20 * s);
            int bottomY = Math.round(52 * s) - 1;

            int top = px.alphaAt(x, topY);
            int bottom = px.alphaAt(x, bottomY);

            assertTrue("the button's top edge must be DRAWN; nothing there means the elevation "
                    + "border is missing entirely (dump: " + px.dump("elevation") + ")",
                px.isOpaqueEnoughAt(x, topY));

            // The MAGNITUDE, not merely the ordering. Measured: the lit stop (#18FFFFFF) over the
            // base (#12FFFFFF) composites to alpha 0x28 against 0x12 at the bottom, a gap of 22.
            // Flattening both stops to the base colour still leaves 0x23 over 0x12 -- because the
            // strip is a second layer over the base rectangle, "top brighter than bottom" holds by
            // construction and is true even with no ramp at all. An ordering-only assertion
            // therefore passes with the border flattened, inverted, or both stops set equal; all
            // three were tried against it. The floor is what makes the ramp itself observable.
            assertEquals("the button's bottom edge must be exactly ControlStrokeColorDefault "
                    + "(#12FFFFFF, alpha 18) -- a different value means the base stop is not "
                    + "reaching the bottom row (dump: " + px.dump("elevation") + ")",
                0x12, bottom);
            // Threshold arithmetic, since it is what makes this assertion able to fail. Source
            // alpha over the same base composites to a + a(1-a):
            //   lit  0x18 over base 0x12 -> 0x28, gap 22   (correct)
            //   base 0x12 over base 0x12 -> 0x23, gap 17   (litColor flattened to the base)
            // So the bar sits at 20: above the flattened case, below the correct one. A threshold
            // of 16 -- tried first -- admits the flattened border, because two stacked layers of
            // the base colour are genuinely brighter than one.
            assertTrue("the button's top edge must be MORE opaque than its bottom edge BY THE "
                    + "RAMP'S WORTH: the lit stop over the base composites to 0x28 against 0x12, "
                    + "a gap of 22, while a border whose stops are equal gives only 17. top alpha="
                    + top + " bottom alpha=" + bottom + " gap=" + (top - bottom)
                    + " (dump: " + px.dump("elevation") + ")",
                top - bottom >= 20);

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
     * The geometry every control was rebuilt against must be the WinUI value, not a plausible one.
     *
     * <p>Read off the live scene graph rather than sampled, because these are the numbers that
     * were WRONG while the UI looked entirely correct: the toggle track's radius was 10 when WinUI
     * ships 7, its knob a fixed 14 when WinUI has three sizes, the slider thumb 20 when it is 18,
     * its track radius 4 when it is 2, and the progress bar 4px tall with a 4px radius when it is
     * 3 and 1.5. Every one of those renders as a believable Fluent control, which is exactly why
     * an assertion is the only thing that keeps them honest.
     *
     * <p>Asking the scene rather than the QML source is deliberate — the same rule the navigation
     * probe learned the hard way. A value read from the file proves what was written; a value read
     * from the laid-out scene proves what the control is actually using.
     */
    @Test
    public void controlGeometryMatchesTheShippedWinUiValues() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openGallery();
            Item root = viewOf(surface).root();

            // ToggleSwitch: 40x20 track at CornerRadius 7, knob 12 at rest.
            Item toggle = byName(root, "toggleOff");
            assertEquals("ToggleSwitch track width (WinUI: 40)", 40.0F, prop(toggle, "trackWidth"),
                0.01F);
            assertEquals("ToggleSwitch track height (WinUI: 20)", 20.0F,
                prop(toggle, "trackHeight"), 0.01F);
            assertEquals("ToggleSwitch track radius must be WinUI's CornerRadius=\"7\", not "
                + "trackHeight/2 -- at 10 the pill reads as a capsule", 7.0F,
                prop(toggle, "trackRadius"), 0.01F);
            assertEquals("ToggleSwitch rest knob (SwitchKnob Normal keyframe: 12)", 12.0F,
                prop(toggle, "knobSize"), 0.01F);
            assertEquals("ToggleSwitch hover knob (PointerOver keyframe: 14)", 14.0F,
                prop(toggle, "knobSizeHover"), 0.01F);
            // The pressed knob is the one non-square state: 17 wide by 14 tall.
            assertEquals("ToggleSwitch pressed knob width (Pressed keyframe: 17)", 17.0F,
                prop(toggle, "knobPressedWidth"), 0.01F);
            assertEquals("ToggleSwitch pressed knob height (Pressed keyframe: 14)", 14.0F,
                prop(toggle, "knobPressedHeight"), 0.01F);
            assertTrue("the pressed knob must be WIDER than tall -- WinUI squashes it along the "
                + "travel axis, and a square pressed knob loses that affordance entirely",
                prop(toggle, "knobPressedWidth") > prop(toggle, "knobPressedHeight"));

            // CheckBox: CheckBoxSize 20, CheckBoxGlyphSize 12.
            Item check = byName(root, "checkOff");
            assertEquals("CheckBoxSize", 20.0F, prop(check, "boxSize"), 0.01F);
            assertEquals("CheckBoxGlyphSize", 12.0F, prop(check, "glyphSize"), 0.01F);

            // Slider: thumb 18, track 4 tall at radius 2.
            Item slider = byName(root, "slider");
            assertEquals("SliderHorizontalThumbWidth (WinUI: 18)", 18.0F,
                prop(slider, "thumbSize"), 0.01F);
            assertEquals("SliderTrackThemeHeight", 4.0F, prop(slider, "trackHeight"), 0.01F);
            assertEquals("SliderTrackCornerRadius is 2, NOT the 4px control radius -- the "
                + "published \"bar-shaped elements\" rule is about the control's corners, not "
                + "the inner track's", 2.0F, prop(slider, "trackRadius"), 0.01F);

            // ProgressBar: 3px fill over a 1px track, radii 1.5 / 0.5.
            Item progress = byName(root, "progress");
            assertEquals("ProgressBarMinHeight (WinUI: 3)", 3.0F, prop(progress, "fillHeight"),
                0.01F);
            assertEquals("ProgressBarTrackHeight (WinUI: 1)", 1.0F, prop(progress, "trackHeight"),
                0.01F);
            assertEquals("ProgressBarCornerRadius (WinUI: 1.5)", 1.5F,
                prop(progress, "fillRadius"), 0.01F);
            assertTrue("the unfilled track must be THINNER than the fill -- that difference is "
                + "what makes the real control read as a hairline that thickens with progress",
                prop(progress, "trackHeight") < prop(progress, "fillHeight"));

            surface.close();
            surface = null;
        } finally {
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

    /**
     * The point must carry a faint fill: present, but nowhere near a filled control.
     *
     * <p>For the recessed rest state of an unfilled control (an unchecked box, an off toggle
     * track), which WinUI gives {@code ControlAltFillColorSecondary} — {@code #19000000}, alpha
     * 0x19. Both bounds carry weight: no coverage means the recess is missing entirely, and
     * anything approaching opaque means the control is reading as filled when it is not.
     */
    private static void assertFaintFill(ScenePixels px, float scale, int x, int y, String what) {
        int dx = Math.round(x * scale);
        int dy = Math.round(y * scale);
        int alpha = px.alphaAt(dx, dy);
        assertTrue(what + " must carry the faint recess fill at (" + x + "," + y + ") but was "
                + "fully transparent (dump: " + px.dump("gallery") + ")", alpha > 8);
        assertTrue(what + " must stay a FAINT fill, not a filled control; alpha was " + alpha
                + " (dump: " + px.dump("gallery") + ")", alpha < 0x50);
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
        float s = surface.uiScale();
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
        Object backend = surface.backend();
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
        return surface.view();
    }

    /** A named item from the live scene, asserted present so a typo cannot pass as an absence. */
    private static Item byName(Item root, String objectName) {
        Item hit = search(root, objectName);
        assertNotNull("the gallery must contain an item named " + objectName, hit);
        return hit;
    }

    private static Item search(Item node, String objectName) {
        if (node == null) {
            return null;
        }
        if (objectName.equals(node.objectName.peek())) {
            return node;
        }
        for (Item child : node.children) {
            Item hit = search(child, objectName);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * A custom QML property's numeric value, off the live item.
     *
     * <p>Properties declared in a .qml document (trackRadius, knobSize, …) are not fields on
     * qml4j's {@code Item} — the compiler generates a subclass per document and puts them there as
     * public {@code Property} fields. So this reflects over the RUNTIME class, which is also how
     * the live probe reads control state.
     *
     * <p>A missing property fails rather than defaulting: a renamed property would otherwise turn
     * every assertion below it into a silent no-op.
     */
    private static float prop(Item item, String name) throws Exception {
        Field f = item.getClass().getField(name);
        Object property = f.get(item);
        assertNotNull("property " + name + " must be initialised", property);
        Object value = property.getClass().getMethod("peek").invoke(property);
        assertNotNull("property " + name + " must have a value", value);
        return ((Number) value).floatValue();
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
