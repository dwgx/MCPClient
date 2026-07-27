package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.timer_err.qml4j.render.QmlView;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Asserts the very first frame actually renders — the hole every other live IT was sitting in.
 *
 * <p>{@code frameTarget} rebuilt the Skia surface only when the size or the FBO id <em>moved</em>.
 * {@code init} recorded the size without wrapping, and a host on the DEFAULT framebuffer passes id
 * 0, which the backend deliberately treats as "not mine, keep the last good one". So neither
 * condition ever fired: no surface, so the driver never entered {@code composite()}, so qml4j never
 * rendered and never even laid the scene out. It stayed that way until something resized.
 *
 * <p>Every live IT drives exactly that shape — {@code open}, {@code setFramebufferId(0)},
 * {@code frame} — so all of them were asserting against a frame that drew nothing. They passed
 * because they asserted that frames do not throw. That is the same weak-probe failure that once let
 * a broken {@code GlStateGuard} look correct, one level further down: the strongest available
 * assertion is worthless if the thing under test never ran.
 *
 * <p>So this asserts the mechanism directly: a surface exists, and qml4j did work, after ONE frame.
 */
public class FirstFrameRendersLiveIT {

    @Test
    public void aSurfaceExistsAfterTheFirstFrameOnTheDefaultFramebuffer() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = new QmlUiSurface("dwm/Main.qml");
            assertTrue("scene must open; " + surface.lastError(),
                surface.open(Display.getWidth(), Display.getHeight()));
            // Id 0 = the default framebuffer, which is what a bare GLFW host has and what every
            // live IT passes. In game this is MC's own framebufferObject instead.
            surface.setFramebufferId(0);

            assertTrue("precondition: no surface should exist before the first frame",
                !hasSurface(surface));

            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

            assertTrue("one frame must be enough to wrap a surface; without it the driver never "
                + "reaches composite() and nothing is ever drawn or even laid out",
                hasSurface(surface));
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /**
     * qml4j must have laid the scene out on that frame.
     *
     * <p>The settle-pass count is the observable proof that the layout pass ran at all: it stays 0
     * when the driver never reached {@code renderFrame}, which is precisely what the missing
     * surface caused.
     */
    @Test
    public void qml4jLaysTheSceneOutOnTheFirstFrame() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = new QmlUiSurface("dwm/Main.qml");
            assertTrue("scene must open", surface.open(Display.getWidth(), Display.getHeight()));
            surface.setFramebufferId(0);
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

            QmlView view = viewOf(surface);
            assertTrue("the layout pass must have run on the first frame (settle passes were "
                + view.renderer().settlePassCount() + ")",
                view.renderer().settlePassCount() > 0);
            assertTrue("and it must have measured the scene's nodes",
                view.renderer().measuredNodeCount() > 0);
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /**
     * A wrap that fails must be retried once, not every frame.
     *
     * <p>The fix has to build a surface on the first frame without reopening the thrash hazard the
     * unchanged-params early return exists for: rebuilding a Skia context every frame against a
     * target that will never be complete. Driving many frames at fixed parameters must therefore
     * produce at most one attempt beyond the first.
     */
    @Test
    public void repeatedFramesAtFixedParametersDoNotRebuild() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = new QmlUiSurface("dwm/Main.qml");
            assertTrue("scene must open", surface.open(Display.getWidth(), Display.getHeight()));
            surface.setFramebufferId(0);

            int w = Display.getWidth();
            int h = Display.getHeight();
            surface.frame(w, h, System.nanoTime());
            Object first = contextOf(surface);

            for (int i = 0; i < 30; i++) {
                surface.frame(w, h, System.nanoTime());
            }

            assertEquals("the Skia context must be the same object after 30 unchanged frames; a "
                + "new one means the backend is rebuilding per frame",
                System.identityHashCode(first), System.identityHashCode(contextOf(surface)));
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    private static boolean hasSurface(QmlUiSurface surface) throws Exception {
        Object backend = backendOf(surface);
        if (backend == null) {
            return false;
        }
        Method m = backend.getClass().getDeclaredMethod("hasSurface");
        m.setAccessible(true);
        return Boolean.TRUE.equals(m.invoke(backend));
    }

    private static Object contextOf(QmlUiSurface surface) throws Exception {
        Object backend = backendOf(surface);
        Field f = backend.getClass().getDeclaredField("context");
        f.setAccessible(true);
        return f.get(backend);
    }

    private static Object backendOf(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("backend");
        f.setAccessible(true);
        return f.get(surface);
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        Field f = QmlUiSurface.class.getDeclaredField("view");
        f.setAccessible(true);
        return (QmlView) f.get(surface);
    }

    private static boolean createDisplay() {
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

    private static void destroyDisplay() {
        try {
            Display.destroy();
        } catch (Throwable ignored) {
            // Teardown of an already-dead display is not actionable.
        }
    }
}
