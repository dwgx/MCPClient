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
 * Holds the Settings-page idiom in place: cards with a visible plate, an icon column, a two-line
 * text block, and an expander whose rows are unreachable until it opens.
 *
 * <p>This suite exists because the previous round produced metrically correct CONTROLS on a page
 * that still did not look like Windows Settings — the page idiom is the CARD, and nothing was
 * asserting that a card existed at all. Every check here is about the card, not its contents.
 *
 * <p>Metrics come from CommunityToolkit/Windows,
 * {@code components/SettingsControls/src/SettingsCard/SettingsCard.xaml}, which is the control
 * Windows Settings itself is built from.
 */
public class SettingsCardLiveIT {

    private static final String SCENE = "dwm/CardGallery.qml";

    /** SettingsCardMinHeight. */
    private static final int MIN_HEIGHT = 68;
    /** SettingsCardPadding, uniform 16. */
    private static final int PADDING = 16;
    /** SettingsCardHeaderIconMaxSize. */
    private static final int ICON_SIZE = 20;
    /**
     * Where a card's text column starts: padding + icon lead + icon + icon gap = 16+2+20+20.
     * Also SettingsExpanderItemPadding's left value, which is why a nested row lines up with it.
     */
    private static final int TEXT_COLUMN = 58;

    @Test
    public void aCardIsSixtyEightTallWithItsTextColumnPastTheIcon() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            Item card = byName(root, "plainCard");
            assertEquals("SettingsCardMinHeight (Toolkit: 68). Measured, a two-line card's text "
                    + "sums to 30 -- qml4j's implicitHeight tracks font size, not the ramp's line "
                    + "height -- so 16+30+16 is 62 and this value comes from the FLOOR, not from "
                    + "the text. That makes the floor the thing under test here.",
                MIN_HEIGHT, Math.round(card.height.peekFloat()));
            assertTrue("the card's own minHeight property must be the Toolkit's 68, since that is "
                    + "what the height above actually resolves to",
                Math.round(numberProp(card, "minHeight")) == MIN_HEIGHT);

            // A header-only card must not shrink to its single line.
            Item headerOnly = byName(root, "headerOnlyCard");
            assertEquals("a card with no description must still meet the 68px minimum",
                MIN_HEIGHT, Math.round(headerOnly.height.peekFloat()));

            assertEquals("SettingsCardPadding (16) drives the icon's own offset, plus the 2px "
                    + "SettingsCardHeaderIconMargin lead", PADDING + 2,
                Math.round(iconOf(card).x.peekFloat()));
            assertEquals("SettingsCardHeaderIconMaxSize", ICON_SIZE,
                Math.round(iconOf(card).width.peekFloat()));

            // The text column is what makes a card read as a card rather than as a padded row.
            Item header = textAt(card, 0);
            assertEquals("a card's text must start past the icon column: 16 padding + 2 lead + "
                    + "20 icon + 20 gap", TEXT_COLUMN, Math.round(header.x.peekFloat()));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * The card's plate must be visibly lighter than the surface behind it.
     *
     * <p>The assertion that matters most in this file, and the one a screenshot alone could not
     * settle. {@code CardBackgroundFillColorDefault} is {@code #0DFFFFFF} — alpha 13 — which WinUI
     * composites over the OPAQUE {@code SolidBackgroundFillColorBase}. Measured on a live client
     * while dwm's panel was still translucent over gameplay, a card's interior read {@code #2B2D39}
     * against {@code #2C2E3A} outside it: a delta of one, i.e. no card at all, while every metric
     * above passed. Correct geometry with an invisible plate is exactly the failure this round
     * started from.
     */
    @Test
    public void aCardsPlateIsBrighterThanThePanelBehindIt() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        ScenePixels px = null;
        try {
            surface = open();
            px = layerPixels(surface);
            assertNotNull("the layer must be readable", px);

            float s = scale(surface);
            // The first card spans y=10..78 at x=10..410. Sampled well inside it, and in the 12px
            // gap above it, both clear of the icon, text and control.
            int insideX = Math.round(200 * s);
            int insideY = Math.round(45 * s);
            int outsideY = Math.round(4 * s);

            int inside = luminance(px.at(insideX, insideY));
            int outside = luminance(px.at(insideX, outsideY));

            assertTrue("a card's plate must be BRIGHTER than the panel behind it -- that contrast "
                    + "is the whole card. inside=" + inside + " outside=" + outside
                    + " (dump: " + px.dump("settings-card") + ")",
                inside > outside);
            // At least 4 per channel. The measured delta is +7, and it takes TWO layers to get
            // there: FluentElevation's baseColor is cardStroke #19000000 -- black at alpha 25 --
            // which darkens #2A2A2A to #26, and the #0DFFFFFF face then lifts that to #31. The
            // face alone over the panel would be #35, i.e. +11, so quoting the single-layer
            // arithmetic here would overstate what this assertion can expect. A delta of one or
            // two would mean the plate is technically drawn and practically invisible, which is
            // the state this test exists to reject.
            assertTrue("the card's plate must be visibly brighter, not brighter by rounding: "
                    + "inside=" + inside + " outside=" + outside + " delta=" + (inside - outside)
                    + " (dump: " + px.dump("settings-card") + ")",
                inside - outside >= 4);

            surface.close();
            surface = null;
        } finally {
            if (px != null) {
                px.close();
            }
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * A non-clickable card must not intercept its own control's presses.
     *
     * <p>The defect this catches broke every control on the page: qml4j returns the FIRST hit when
     * walking children in reverse z order, so the card's own trailing MouseArea won over the
     * toggle nested above it. On a live client the toggle reported {@code down=true} — the card had
     * consumed it — while its own area reported {@code containsMouse=false}, and nothing could be
     * operated. Asserted on {@code enabled} rather than on size because a zero-sized area is the
     * separate defect {@link NavigationShellLiveIT} scans for.
     */
    @Test
    public void aNonClickableCardDoesNotStealPressesFromItsControl() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            MouseArea plain = ownArea(byName(root, "plainCard"));
            assertNotNull("the card must have its own hit area", plain);
            assertTrue("a non-clickable card's own hit area must be DISABLED, or it consumes the "
                + "press meant for the control inside it -- hitTestMouseArea returns the first hit "
                + "walking children in reverse declaration order, and this area is declared last",
                Boolean.FALSE.equals(plain.enabled.peek()));

            MouseArea clickable = ownArea(byName(root, "clickableCard"));
            assertNotNull("a clickable card must have its own hit area", clickable);
            assertTrue("a CLICKABLE card's area must be enabled, or the card cannot be clicked "
                + "at all", Boolean.TRUE.equals(clickable.enabled.peek()));

            // And the toggle inside the plain card must be reachable, which is the property the
            // two assertions above exist to protect.
            QmlView view = viewOf(surface);
            Item toggle = byName(root, "cardToggle");
            boolean before = Boolean.TRUE.equals(checkedOf(toggle));
            float s = scale(surface);
            // The toggle sits inside the first card; press its centre in framebuffer pixels.
            float tx = absX(toggle) + (toggle.width.peekFloat() / 2);
            float ty = absY(toggle) + (toggle.height.peekFloat() / 2);
            surface.pointerDown(tx * s, ty * s, 0);
            surface.pointerUp(tx * s, ty * s, 0);
            assertTrue("clicking the toggle inside a card must flip it; unchanged means the card "
                    + "swallowed the press", before != Boolean.TRUE.equals(checkedOf(toggle)));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /** An expander's rows must be absent until it opens, and indented to the parent's column. */
    @Test
    public void anExpandersRowsAppearOnlyWhenOpenAndAlignToTheTextColumn() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();
            Item expander = byName(root, "expander");

            assertEquals("a closed expander must be exactly its header, i.e. one card tall",
                MIN_HEIGHT, Math.round(expander.height.peekFloat()));
            assertEquals("a closed expander must contribute no body height",
                0, Math.round(numberProp(expander, "bodyHeight")));

            setBool(expander, "expanded", true);
            // Pumped until it settles, because opening is now a 250ms animation rather than an
            // instant resize. One frame used to be enough and is not any more: this assertion
            // correctly failed the moment the animation went in, having grown only a pixel or two.
            // Waiting for the TARGET rather than for a fixed frame count keeps it from depending on
            // how fast this machine runs.
            settle(surface, expander);

            assertTrue("an open expander must be taller than its header alone",
                expander.height.peekFloat() > MIN_HEIGHT + 1);
            assertEquals("and must reach its full body height, not stop part-way -- an animation "
                    + "that stalls mid-flight is the failure mode worth catching here",
                Math.round(numberProp(expander, "targetBodyHeight")),
                Math.round(numberProp(expander, "bodyHeight")));
            Item row = byName(root, "expanderRow");
            assertEquals("SettingsExpanderItemPadding's left value is 58, which lines a nested "
                    + "row's text up with the PARENT card's text column -- an arbitrary indent is "
                    + "the usual tell of a hand-rolled expander",
                TEXT_COLUMN, Math.round(numberProp(row, "indent")));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /** The SVG icon path must put actual pixels on the surface. */
    @Test
    public void cardIconsRenderAsPixels() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        ScenePixels px = null;
        try {
            surface = open();
            // Before sampling: confirm the premise. The icon decodes on a worker thread, so the two
            // frames open() pumps may or may not have adopted it -- and a missing icon and an
            // undrawn one read identically from the pixels.
            awaitDecoded(surface, iconOf(byName(viewOf(surface).root(), "plainCard")));
            px = layerPixels(surface);
            assertNotNull("the layer must be readable", px);

            float s = scale(surface);
            // The first card's icon box is (28,26)-(48,46) in scene units: card at y=10, padding
            // 16, icon lead 2. Scanning the box rather than sampling one point, because a line
            // icon is mostly empty space and a single sample would land between strokes.
            int covered = 0;
            for (int y = 26; y < 46; y++) {
                for (int x = 28; x < 48; x++) {
                    if (px.isOpaqueEnoughAt(Math.round(x * s), Math.round(y * s))) {
                        covered++;
                    }
                }
            }
            // The whole card is opaque, so coverage alone proves nothing -- what matters is that
            // the icon's strokes are BRIGHTER than the plate they sit on.
            int litPixels = 0;
            for (int y = 26; y < 46; y++) {
                for (int x = 28; x < 48; x++) {
                    if (luminance(px.at(Math.round(x * s), Math.round(y * s))) > 0x80) {
                        litPixels++;
                    }
                }
            }
            assertTrue("the icon box must be drawn at all", covered > 0);
            assertTrue("an SVG icon must put visible strokes on the card -- zero lit pixels means "
                    + "the SVG rasterized to nothing, which Skia reports as SUCCESS when a colour "
                    + "is invalid. litPixels=" + litPixels
                    + " (dump: " + px.dump("card-icon") + ")",
                litPixels > 10);

            surface.close();
            surface = null;
        } finally {
            if (px != null) {
                px.close();
            }
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    private static int luminance(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 30 + g * 59 + b * 11) / 100;
    }

    private static QmlUiSurface open() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("scene must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        // Two frames: the first lays out, the second lets a measured text height settle into the
        // card heights that depend on it.
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
        return surface;
    }

    /**
     * Pump frames until an expander's animated body height reaches its target.
     *
     * <p>Bounded, so a stalled animation fails the assertion that follows rather than hanging the
     * suite. Real wall-clock gaps between frames because the animator interpolates against a clock:
     * a tight loop advances the value barely at all, which is a measurement artefact rather than a
     * defect and was worth learning once.
     */
    private static void settle(QmlUiSurface surface, Item expander) throws Exception {
        for (int i = 0; i < 60; i++) {
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
            float current = numberProp(expander, "bodyHeight");
            float target = numberProp(expander, "targetBodyHeight");
            if (Math.abs(current - target) < 0.5F) {
                return;
            }
            Thread.sleep(16);
        }
    }

    private static float absX(Item item) {
        float x = 0;
        for (Item cur = item; cur != null; cur = cur.parent.peek()) {
            x += cur.x.peekFloat();
        }
        return x;
    }

    private static float absY(Item item) {
        float y = 0;
        for (Item cur = item; cur != null; cur = cur.parent.peek()) {
            y += cur.y.peekFloat();
        }
        return y;
    }

    /** The card's OWN MouseArea: a direct child, not one belonging to a nested control. */
    private static MouseArea ownArea(Item card) {
        for (Item child : card.children) {
            if (child instanceof MouseArea) {
                return (MouseArea) child;
            }
        }
        return null;
    }

    /**
     * Pump frames until an {@code Image} has decoded, because a fixed frame count races the decoder.
     *
     * <p>qml4j loads and decodes every {@code Image.source} on a daemon worker
     * ({@code ImageLoader.decode} submits to a pool unconditionally -- the {@code asynchronous}
     * property does not gate it), and the render thread only adopts the result on a later frame,
     * once {@code decodeReadyGen} has caught up with {@code decodeGen}. An icon therefore needs the
     * worker to finish loading, rasterizing the SVG and decoding it, and then one more frame.
     *
     * <p>Two frames do not guarantee that, and the flake was real rather than theoretical: a full
     * {@code verify} run reported {@code litPixels=0} here while the same class passed alone, and a
     * re-run of the identical order passed 43/43. Waiting on {@code status} (Qt's
     * Null=0/Ready=1/Loading=2/Error=3) is the convergence signal, which is the same lesson
     * {@code live-verification.md} §8 records for animation: wait for the state, not for a
     * frame count.
     *
     * <p><b>What is proven about this wait, and what is not.</b> Starving the loop to zero
     * iterations makes it throw, so it does read the live {@code status} and would fire. But it was
     * never observed pumping a single extra frame -- not on a warm cache, and not with
     * {@code SvgRaster}'s cache cleared to force a real rasterize. On this machine the worker wins
     * the race every time it was measured, so the flake's window stays unreproduced and this guard
     * is currently doing nothing observable. It is kept because the alternative to an unfired guard
     * is the failure that was actually seen: a missing icon reported as a drawing defect.
     */
    private static void awaitDecoded(QmlUiSurface surface, Item image) throws Exception {
        for (int i = 0; i < 120; i++) {
            if (Math.round(numberProp(image, "status")) == IMAGE_READY) {
                // One further frame, so the adopted image is actually PAINTED before we sample.
                surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
                return;
            }
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
            Thread.sleep(4);
        }
        throw new AssertionError("an icon Image did not reach status Ready within 120 frames, so "
            + "the pixel assertion below would be measuring a NOT-YET-DECODED icon and reporting it "
            + "as a drawing defect. Last status=" + Math.round(numberProp(image, "status"))
            + " (Qt: 0 Null, 1 Ready, 2 Loading, 3 Error) -- 2 means the worker is still going and "
            + "the budget is too small; 3 means the source or the SVG itself is broken.");
    }

    /** qml4j mirrors Qt's Image.status: Null=0, Ready=1, Loading=2, Error=3. */
    private static final int IMAGE_READY = 1;

    private static Item iconOf(Item card) {
        for (Item child : card.children) {
            if (child.getClass().getSimpleName().equals("Image")
                && child.width.peekFloat() > 0) {
                return child;
            }
        }
        throw new AssertionError("the card must contain an icon Image");
    }

    /** The nth Text directly under {@code card}, in declaration order. */
    private static Item textAt(Item card, int index) {
        int seen = 0;
        for (Item child : card.children) {
            if (child.getClass().getSimpleName().equals("Text")) {
                if (seen == index) {
                    return child;
                }
                seen++;
            }
        }
        throw new AssertionError("the card must contain a Text at index " + index);
    }

    private static Object checkedOf(Item item) throws Exception {
        Field f = item.getClass().getField("checked");
        Object property = f.get(item);
        return property.getClass().getMethod("peek").invoke(property);
    }

    private static float numberProp(Item item, String name) throws Exception {
        Field f = item.getClass().getField(name);
        Object property = f.get(item);
        Object value = property.getClass().getMethod("peek").invoke(property);
        assertNotNull("property " + name + " must have a value", value);
        return ((Number) value).floatValue();
    }

    private static void setBool(Item item, String name, boolean value) throws Exception {
        Field f = item.getClass().getField(name);
        Object property = f.get(item);
        property.getClass().getMethod("set", Object.class).invoke(property, Boolean.valueOf(value));
    }

    private static Item byName(Item root, String objectName) {
        Item hit = search(root, objectName);
        assertNotNull("the scene must contain an item named " + objectName, hit);
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

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        return surface.view();
    }

    private static float scale(QmlUiSurface surface) throws Exception {
        float s = surface.uiScale();
        return s > 0.0F ? s : 1.0F;
    }

    private static ScenePixels layerPixels(QmlUiSurface surface) throws Exception {
        Object backend = surface.backend();
        assertNotNull("the backend must exist after a frame", backend);

        Field lf = backend.getClass().getDeclaredField("layer");
        lf.setAccessible(true);
        Object layer = lf.get(backend);

        Field sf = layer.getClass().getDeclaredField("snapshot");
        sf.setAccessible(true);
        Image snapshot = (Image) sf.get(layer);
        assertNotNull("the compositor must have cached a scene snapshot", snapshot);

        Field cf = backend.getClass().getDeclaredField("context");
        cf.setAccessible(true);
        return ScenePixels.of(snapshot, (DirectContext) cf.get(backend));
    }

    private static void closeQuietly(QmlUiSurface surface) {
        if (surface != null) {
            surface.close();
        }
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(440, 420));
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
