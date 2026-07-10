package net.marcloud.mcp.core.seam;

import java.lang.reflect.Method;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.seam.events.SeamKeyEvent;
import net.marcloud.mcp.core.seam.events.SeamMouseEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;

/**
 * GLFW key/mouse callback wrapper seam. Chains custom observer callbacks
 * (that publish to EventBus) in front of the game's original callbacks, so
 * AI can observe input without breaking game input handling.
 *
 * <p>Callback installation requires a live GLFW window. The mechanism is
 * guarded behind {@link #isAvailable()} for headless test safety.
 */
public final class InputHook {

    /** LWJGL2-compat Display façade that exposes the real GLFW window handle. */
    private static final String DISPLAY_CLASS = "org.lwjgl.opengl.Display";
    /** Display's "no window" sentinel (LWJGL2 ABI); GLFW NULL is 0L. Both mean
     *  "no live window" for our purposes. */
    private static final long NO_WINDOW_SENTINEL = -1L;

    private final GameAccess game;
    private final EventBus bus;
    private volatile long glfwWindow;
    private volatile GLFWKeyCallback originalKeyCallback;
    private volatile GLFWMouseButtonCallback originalMouseCallback;
    private volatile GLFWKeyCallback installedKeyCallback;
    private volatile GLFWMouseButtonCallback installedMouseCallback;

    public InputHook(GameAccess game, EventBus bus) {
        this.game = game;
        this.bus = bus;
    }

    /**
     * True if GLFW is available (not in a headless test environment). Guards
     * callback installation so tests don't need a live window.
     */
    public boolean isAvailable() {
        try {
            // If GLFW.glfwInit is callable and the window can be acquired,
            // we're in a live environment. In headless tests this will fail.
            return acquireWindow() != 0L;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Acquire the GLFW window handle. Tries to fetch from Minecraft's display
     * system via reflection. The exact path may vary by LWJGL3 version; this
     * scans for common field names.
     *
     * @return window handle (long), or 0 if unavailable
     */
    public long acquireWindow() {
        if (glfwWindow != 0L) {
            return glfwWindow;
        }
        long handle = resolveWindowHandle();
        // Treat both GLFW NULL (0L) and the LWJGL2 "not created" sentinel (-1L)
        // as "no window". Never cache a bogus value.
        if (handle == 0L || handle == NO_WINDOW_SENTINEL) {
            return 0L;
        }
        glfwWindow = handle;
        return handle;
    }

    /**
     * Resolve the real GLFW window handle via the explicit LWJGL3-compat display
     * accessor ({@code org.lwjgl.opengl.Display.getWindow()}), NOT by guessing a
     * "first nonzero long" field — a heuristic that can latch onto an unrelated
     * long (e.g. a systemTime) and report a bogus handle. If the accessor is
     * genuinely unavailable, we fail honestly and return 0L rather than guess.
     *
     * @return the window handle from the display API, or 0L if it cannot be
     *         resolved honestly.
     */
    private long resolveWindowHandle() {
        return resolveWindowHandle(DISPLAY_CLASS);
    }

    /**
     * Resolve the window handle from {@code className}'s static {@code getWindow()}
     * accessor. Package-private (not private) so the reset-heuristic regression
     * (HIGH#7) can be exercised headlessly against fixture display classes.
     *
     * <p>Crucially, this NEVER scans for "the first nonzero long" — if the class
     * has no usable {@code getWindow()} accessor, it returns 0L (honest "no
     * window") rather than latching onto some unrelated long field.
     */
    static long resolveWindowHandle(String className) {
        try {
            Class<?> display = Class.forName(className);
            Method getWindow = display.getMethod("getWindow");
            Object result = getWindow.invoke(null);
            if (result instanceof Long) {
                return (Long) result;
            }
            // Accessor exists but did not yield a long handle: do not guess.
            return 0L;
        } catch (ReflectiveOperationException | LinkageError e) {
            // Explicit accessor is genuinely unavailable — report unavailable
            // honestly instead of scanning for an arbitrary long field.
            System.err.println("[InputHook] window accessor unavailable, reporting no window: " + e);
            return 0L;
        }
    }

    /**
     * Install a key callback that chains the observer (publishing
     * SeamKeyEvent) before the original game callback. Safe to call multiple
     * times (idempotent).
     *
     * @return true if installed or already installed, false if unavailable
     */
    public boolean installKeyCallback() {
        if (installedKeyCallback != null) {
            return true; // Already installed.
        }
        if (!isAvailable()) {
            return false;
        }
        long window = acquireWindow();
        if (window == 0L) {
            return false;
        }
        try {
            // Capture the current callback by setting to null temporarily.
            originalKeyCallback = GLFW.glfwSetKeyCallback(window, null);
            // Now install our chaining callback.
            GLFWKeyCallback chain = new GLFWKeyCallback() {
                @Override
                public void invoke(long win, int key, int scancode, int action, int mods) {
                    try {
                        bus.publish(new SeamKeyEvent(key, scancode, action, mods));
                    } catch (Throwable ignored) {
                        // Observation fault must not break the game input.
                    }
                    // Chain to the original.
                    if (originalKeyCallback != null) {
                        originalKeyCallback.invoke(win, key, scancode, action, mods);
                    }
                }
            };
            GLFW.glfwSetKeyCallback(window, chain);
            installedKeyCallback = chain;
            return true;
        } catch (Exception e) {
            System.err.println("[InputHook] failed to install key callback: " + e);
            return false;
        }
    }

    /**
     * Install a mouse button callback that chains the observer (publishing
     * SeamMouseEvent) before the original game callback. Safe to call
     * multiple times (idempotent).
     *
     * @return true if installed or already installed, false if unavailable
     */
    public boolean installMouseCallback() {
        if (installedMouseCallback != null) {
            return true; // Already installed.
        }
        if (!isAvailable()) {
            return false;
        }
        long window = acquireWindow();
        if (window == 0L) {
            return false;
        }
        try {
            originalMouseCallback = GLFW.glfwSetMouseButtonCallback(window, null);
            GLFWMouseButtonCallback chain = new GLFWMouseButtonCallback() {
                @Override
                public void invoke(long win, int button, int action, int mods) {
                    try {
                        bus.publish(new SeamMouseEvent(button, action, mods));
                    } catch (Throwable ignored) {
                    }
                    if (originalMouseCallback != null) {
                        originalMouseCallback.invoke(win, button, action, mods);
                    }
                }
            };
            GLFW.glfwSetMouseButtonCallback(window, chain);
            installedMouseCallback = chain;
            return true;
        } catch (Exception e) {
            System.err.println("[InputHook] failed to install mouse callback: " + e);
            return false;
        }
    }

    /**
     * Uninstall the key callback and restore the original. Safe to call
     * multiple times.
     *
     * @return true if uninstalled, false if not installed or unavailable
     */
    public boolean uninstallKeyCallback() {
        if (installedKeyCallback == null) {
            return false;
        }
        long window = glfwWindow;
        if (window == 0L) {
            return false;
        }
        try {
            GLFW.glfwSetKeyCallback(window, originalKeyCallback);
            installedKeyCallback = null;
            return true;
        } catch (Exception e) {
            System.err.println("[InputHook] failed to uninstall key callback: " + e);
            return false;
        }
    }

    /**
     * Uninstall the mouse callback and restore the original. Safe to call
     * multiple times.
     *
     * @return true if uninstalled, false if not installed or unavailable
     */
    public boolean uninstallMouseCallback() {
        if (installedMouseCallback == null) {
            return false;
        }
        long window = glfwWindow;
        if (window == 0L) {
            return false;
        }
        try {
            GLFW.glfwSetMouseButtonCallback(window, originalMouseCallback);
            installedMouseCallback = null;
            return true;
        } catch (Exception e) {
            System.err.println("[InputHook] failed to uninstall mouse callback: " + e);
            return false;
        }
    }

    public boolean isKeyCallbackInstalled() {
        return installedKeyCallback != null;
    }

    public boolean isMouseCallbackInstalled() {
        return installedMouseCallback != null;
    }
}
