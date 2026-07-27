package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Guards the compositor and, more importantly, that the UI is actually hittable.
 *
 * <p>The hittability tests exist because the menu once rendered perfectly and was completely dead
 * to input, and nothing caught it: the pipeline IT asserts frames render, and a screenshot cannot
 * show that a click does nothing. Hit testing walks down from the scene root and rejects any node
 * the point falls outside of, so a single zero-sized ancestor silently kills every click and hover
 * beneath it. Two separate causes produced exactly that — a root the host never sized, and
 * {@code anchors.fill} resolving against a parent that had not been laid out yet.
 *
 * <p>Needs a display and native Skia, so it self-skips like the other live ITs.
 */
public class CompositorLiveIT {

    private static final String SCENE = "dwm/Main.qml";

    private static boolean display() {
        try {
            Display.setDisplayMode(new DisplayMode(854, 480));
            Display.create();
            Display.update();
            return true;
        } catch (Throwable t) {
            System.out.println("[IT] no display (" + t + ") — skipping");
            return false;
        }
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("view");
        f.setAccessible(true);
        return (QmlView) f.get(surface);
    }

    /**
     * An idle scene must be composited without being repainted.
     *
     * <p>qml4j's global change counter standing still across many frames is the observable proof:
     * a repaint runs bindings and layout, which moves it.
     */
    @Test
    public void idleFramesCompositeWithoutRepainting() {
        Assume.assumeTrue("needs a display", display());
        try {
            QmlUiSurface surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open; " + surface.lastError(), surface.open(
                Display.getWidth(), Display.getHeight()));
            surface.setFramebufferId(0);

            int w = Display.getWidth();
            int h = Display.getHeight();
            surface.frame(w, h, System.nanoTime());

            long before = Property.changeVersion();
            for (int i = 0; i < 60; i++) {
                surface.frame(w, h, System.nanoTime());
            }
            long after = Property.changeVersion();

            assertTrue("60 idle frames must not repaint the scene (change counter moved from "
                + before + " to " + after + ")", before == after);
            assertTrue("the surface must still be live; " + surface.lastError(), surface.isOpen());
            surface.close();
        } finally {
            try {
                Display.destroy();
            } catch (Throwable ignored) {
                // Nothing to do about a dead display during teardown.
            }
        }
    }

    /**
     * The scene root must be given a non-zero size by the host.
     *
     * <p>qml4j leaves the root at 0x0 and expects the embedder to set it. Miss that and hit testing
     * rejects every point at the very first node.
     */
    @Test
    public void sceneRootIsSizedByTheHost() throws Exception {
        Assume.assumeTrue("needs a display", display());
        try {
            QmlUiSurface surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open", surface.open(Display.getWidth(), Display.getHeight()));
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

            Item root = viewOf(surface).root();
            assertNotNull("scene must have a root", root);
            assertTrue("root width must be non-zero, or every click misses at the first node",
                root.width.peekFloat() > 0.0F);
            assertTrue("root height must be non-zero, or every click misses at the first node",
                root.height.peekFloat() > 0.0F);
            surface.close();
        } finally {
            try {
                Display.destroy();
            } catch (Throwable ignored) {
                // As above.
            }
        }
    }

    /**
     * Every MouseArea in the scene must have a non-zero size.
     *
     * <p>This is the assertion that would have caught the dead menu. A zero-sized MouseArea is
     * invisible to hit testing while everything still renders, so it fails silently in exactly the
     * way a screenshot cannot reveal.
     */
    @Test
    public void everyMouseAreaHasANonZeroHitBox() throws Exception {
        Assume.assumeTrue("needs a display", display());
        try {
            QmlUiSurface surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open", surface.open(Display.getWidth(), Display.getHeight()));
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

            StringBuilder dead = new StringBuilder();
            int total = countMouseAreas(viewOf(surface).root(), dead);

            assertTrue("the menu must contain MouseAreas to be interactive at all", total > 0);
            assertTrue("every MouseArea must have a non-zero hit box; zero-sized ones render fine "
                + "and cannot be clicked:" + dead, dead.length() == 0);
            surface.close();
        } finally {
            try {
                Display.destroy();
            } catch (Throwable ignored) {
                // As above.
            }
        }
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
}
