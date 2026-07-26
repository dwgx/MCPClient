package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

/**
 * Drives the whole qml4j pipeline over a real GL context: Skija context, scene parse, frames,
 * the resize path, and input. No game needed — a bare GLFW window stands in for MC's.
 *
 * <p><b>Needs a display and native Skia</b>, so it is an IT and self-skips when either is
 * missing (a headless CI runner, or a platform whose Skija natives are not on the classpath)
 * rather than failing. On macOS it additionally needs {@code -XstartOnFirstThread}, since GLFW
 * must own the main thread; without it {@code Display.create()} fails and the test skips.
 *
 * <p>What it protects: the four things that were actually hard. Skija initialising at all on
 * Apple's GL 2.1 compatibility context; {@code load()} being given QML <em>source</em> rather
 * than a path; surviving a size change, which is the historic "world goes black after resize"
 * bug; and coming back down without wedging the JVM.
 */
public class QmlPipelineLiveIT {

    private static final String SCENE = "dwm/Main.qml";

    @Test
    public void qmlSceneOpensRendersResizesAndClosesOnALiveContext() {
        Assume.assumeTrue("needs a display + -XstartOnFirstThread on macOS", createDisplay());
        try {
            System.out.println("[IT] GL = " + GL11.glGetString(GL11.GL_VERSION));
            int w = Display.getWidth();
            int h = Display.getHeight();

            QmlUiSurface surface = new QmlUiSurface(SCENE);
            boolean opened = surface.open(w, h);
            assertTrue("scene must open on a live GL context; last error: " + surface.lastError(),
                opened);
            assertNull("opening must not record an error", surface.lastError());

            // The probe targets the default framebuffer; in game this is MC's framebufferObject.
            surface.setFramebufferId(0);
            for (int i = 0; i < 5; i++) {
                surface.frame(w, h, System.nanoTime());
                Display.update();
            }
            assertTrue("surface must stay live across frames; last error: " + surface.lastError(),
                surface.isOpen());

            // A size change rebuilds the Skia surface and context. Caching anything across it is
            // what made the game render black afterwards, so this step is the regression guard.
            surface.frame(w / 2 + 1, h / 2 + 1, System.nanoTime());
            assertTrue("surface must survive a size change; last error: " + surface.lastError(),
                surface.isOpen());

            // Dispatch must not throw whether or not the scene consumes it.
            surface.pointerMove(10.0F, 10.0F);
            surface.pointerDown(60.0F, 60.0F, 0);
            surface.pointerUp(60.0F, 60.0F, 0);
            assertTrue("input must not break the surface; last error: " + surface.lastError(),
                surface.isOpen());

            surface.close();
            // close() must be idempotent: MC can dispose a screen at moments we do not pick.
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /** The default scene must be loadable as text, whatever the display situation. */
    @Test
    public void defaultSceneIsReadableFromTheClasspath() {
        String source = ClasspathResources.readText(SCENE);
        assertNotNull(SCENE + " must be on the classpath", source);
        assertTrue("scene must declare its import", source.contains("import QtQuick"));
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
