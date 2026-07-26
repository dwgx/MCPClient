package org.lwjgl;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

/**
 * LWJGL2 org.lwjgl.Sys re-implemented for the LWJGL3 runtime.
 * Independently written; timing is nanoTime-based and version defers to real LWJGL3.
 */
public final class Sys {

    /** Nanosecond origin captured at class load, mirroring LWJGL2's zeroed hires timer. */
    private static final long TIMER_OFFSET = System.nanoTime();

    private Sys() {
    }

    /** Core LWJGL version string; delegates to the real LWJGL3 Version class. */
    public static String getVersion() {
        return Version.getVersion();
    }

    /** Dummy initializer that forces the static timer setup, matching LWJGL2. */
    public static void initialize() {
    }

    /** Ticks the hires timer does per second. We use nanoseconds. */
    public static long getTimerResolution() {
        return 1000000000L;
    }

    /** Current hires time in ticks (always >= 0), monotonic from class load. */
    public static long getTime() {
        return (System.nanoTime() - TIMER_OFFSET) & 0x7FFFFFFFFFFFFFFFL;
    }

    /**
     * Clipboard text, or null when empty or unavailable. LWJGL2 exposed this and
     * LWJGL3 moved it onto GLFW.
     */
    public static String getClipboard() {
        if (!Display.isCreated()) {
            return null;
        }
        try {
            return GLFW.glfwGetClipboardString(Display.getWindowHandle());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Shim extension — LWJGL2 had no clipboard setter, so callers reached for
     * java.awt.Toolkit instead. On macOS that starts AppKit on the thread GLFW
     * owns under -XstartOnFirstThread, after which the JVM can no longer shut
     * down; routing through GLFW keeps AWT out of the process entirely.
     */
    public static void setClipboard(String text) {
        if (text == null || !Display.isCreated()) {
            return;
        }
        try {
            GLFW.glfwSetClipboardString(Display.getWindowHandle(), text);
        } catch (RuntimeException e) {
            // Clipboard access is best-effort, exactly as it was under AWT.
        }
    }

    public static boolean openURL(String url) {
        if (url == null) {
            return false;
        }
        // macOS: java.awt.Desktop dispatches to AppKit on the main thread, which
        // GLFW already owns under -XstartOnFirstThread — browsing from there can
        // wedge the game loop. Shell out to `open` instead; AWT is never touched.
        if (LWJGLUtil.getPlatform() == LWJGLUtil.PLATFORM_MACOSX) {
            try {
                new ProcessBuilder("open", url).start();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    return true;
                }
            }
        } catch (IOException e) {
            // fall through
        } catch (URISyntaxException e) {
            // fall through
        } catch (UnsupportedOperationException e) {
            // fall through
        }
        return false;
    }
}