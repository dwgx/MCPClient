package net.marcloud.mcp.dwm;

import java.lang.reflect.Constructor;

/**
 * The one class Board looks for. Everything above dwm goes through here.
 *
 * <p>Two contract points shape this file. It imports <b>no</b> {@code core} class, so dwm has
 * no security-decision power and no dependency on the kernel. And it names the qml4j adapter
 * only as a <b>string</b>, resolved reflectively, so this class links without qml4j or Skija on
 * the classpath — which is what makes the backend genuinely swappable and what lets a build
 * without the UI dependency still compile and run.
 *
 * <p>Board finds this the same way it finds mcp-core: by class name through the reflective
 * Backplane, catching everything. So the method contract here is deliberately narrow and
 * expressed in JDK types only.
 */
public final class DwmEntry {

    /** The qml4j-backed screen. Named as a string so this class does not link against it. */
    private static final String QML_SCREEN = "net.marcloud.mcp.dwm.qml.QmlGuiScreen";

    /** Scene loaded when no other path is given. */
    private static final String DEFAULT_SCENE = "dwm/Main.qml";

    private DwmEntry() {
    }

    /**
     * True when a usable UI backend is on the classpath.
     *
     * <p>Checks for the adapter and for qml4j itself: the adapter class can resolve while its
     * own dependencies are missing, and a caller wants to know whether opening will actually
     * work, not merely whether dwm was built.
     */
    public static boolean isAvailable() {
        return resolve(QML_SCREEN) != null
            && resolve("io.github.timer_err.qml4j.render.QmlView") != null;
    }

    /**
     * Create the default UI screen.
     *
     * @return a {@code net.minecraft.client.gui.GuiScreen}, or null if the backend is absent or
     *         failed to construct. Returned as {@link Object} so callers reached through the
     *         Backplane need no compile-time link to either dwm or the game.
     */
    public static Object createScreen() {
        return createScreen(DEFAULT_SCENE);
    }

    /**
     * Create a UI screen for a specific scene.
     *
     * @param qmlPath resource path of the scene to load
     * @return a {@code GuiScreen}, or null when unavailable. Never throws: a missing backend is
     *         a normal condition for a detachable module, not an error the caller must handle.
     */
    public static Object createScreen(String qmlPath) {
        Class<?> screen = resolve(QML_SCREEN);
        if (screen == null) {
            return null;
        }
        try {
            Constructor<?> ctor = screen.getConstructor(String.class);
            return ctor.newInstance(qmlPath == null ? DEFAULT_SCENE : qmlPath);
        } catch (Throwable t) {
            // A changed constructor, or a backend whose natives will not load. dwm going
            // missing must never propagate.
            System.err.println("[dwm] could not create UI screen: " + t);
            return null;
        }
    }

    private static Class<?> resolve(String name) {
        try {
            // initialize=false: asking whether the UI exists must not start loading Skia natives.
            return Class.forName(name, false, DwmEntry.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }
}
