package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowContentScaleCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * LWJGL2 org.lwjgl.opengl.Display re-implemented on top of GLFW (real LWJGL3).
 * Independently written to reproduce LWJGL2 semantics; not copied from any reference.
 *
 * Holds a single GLFW window handle. Sentinel for "not created" is -1L (LWJGL2 ABI),
 * distinct from GLFW's NULL (0L) which is what glfwCreateWindow returns on failure.
 */
public final class Display {

    /** GLFW window handle; -1L means "no window" per LWJGL2 ABI. */
    private static long window = -1L;

    private static String title = "";
    private static boolean resizable = false;
    private static boolean vsyncEnabled = false;
    private static boolean fullscreen = false;
    private static boolean deferredFullscreen = false;

    /* Framebuffer dimensions (what getWidth/getHeight report, matching LWJGL2 pixel semantics). */
    private static int width = 854;
    private static int height = 480;

    /*
     * Framebuffer pixels per window unit. 1.0 everywhere except HiDPI displays,
     * where macOS hands back a Retina framebuffer twice the window size. GLFW
     * reports cursor positions in window units but we report the framebuffer size
     * as the display size, so the input backend has to bridge the two.
     *
     * This ratio is NOT a DPI scale: it is 1.0 on Windows and X11 at any DPI,
     * because there the framebuffer already matches the window. Its only job is
     * bringing a cursor coordinate into the same space as getWidth()/getHeight().
     * Anything that wants to know how large to draw must use getContentScale().
     */
    private static float pixelScaleX = 1.0F;
    private static float pixelScaleY = 1.0F;

    /*
     * The display's DPI scale, as the user configured it: 2.0 on a Retina Mac,
     * 1.5 at Windows' 150% setting, 1.0 by default. Unlike the ratio above this
     * is a real scale factor on every platform, so it is what a UI layer must
     * multiply by to come out physically the right size.
     */
    private static float contentScaleX = 1.0F;
    private static float contentScaleY = 1.0F;

    /* Saved windowed placement so we can restore after leaving fullscreen. */
    private static int windowedWidth = 854;
    private static int windowedHeight = 480;
    private static int windowedX = 0;
    private static int windowedY = 0;

    private static boolean resized = true;
    private static boolean focused = false;

    private static DisplayMode currentMode = new DisplayMode(854, 480);
    private static ByteBuffer[] cachedIcons = null;

    private static boolean glfwInitialized = false;
    private static GLFWErrorCallback errorCallback;
    private static GLFWFramebufferSizeCallback framebufferSizeCallback;
    private static GLFWWindowContentScaleCallback contentScaleCallback;
    private static GLFWWindowFocusCallback windowFocusCallback;

    /* Pure-Java frame limiter state (nanoTime based). */
    private static long syncNext = 0L;

    private Display() {
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /** Lazily initialize GLFW so monitor queries work even before create(). */
    private static void ensureInit() {
        if (glfwInitialized) {
            return;
        }
        if (errorCallback == null) {
            errorCallback = GLFWErrorCallback.createPrint(System.err);
            GLFW.glfwSetErrorCallback(errorCallback);
        }
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        glfwInitialized = true;
    }

    public static long getWindowHandle() {
        return window;
    }

    /** Alias for input backend attach. */
    public static long getWindow() {
        return window;
    }

    public static boolean isCreated() {
        return window != -1L;
    }

    public static void setTitle(String newTitle) {
        title = newTitle == null ? "" : newTitle;
        if (isCreated()) {
            GLFW.glfwSetWindowTitle(window, title);
        }
    }

    public static void setResizable(boolean isResizable) {
        resizable = isResizable;
        if (isCreated()) {
            GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_RESIZABLE, isResizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        }
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    /**
     * Recompute both scales; call whenever the framebuffer or window changes.
     *
     * They are separate quantities and conflating them is the classic HiDPI bug:
     * the framebuffer/window ratio happens to equal the DPI scale on a Retina Mac,
     * which is exactly why testing only there hides the difference. On Windows at
     * 150% the ratio is 1.0 while the DPI scale is 1.5.
     */
    private static void updateScales() {
        if (window == -1L) {
            pixelScaleX = 1.0F;
            pixelScaleY = 1.0F;
            contentScaleX = 1.0F;
            contentScaleY = 1.0F;
            return;
        }
        int[] ww = new int[1];
        int[] wh = new int[1];
        GLFW.glfwGetWindowSize(window, ww, wh);
        pixelScaleX = ww[0] > 0 ? (float) width / ww[0] : 1.0F;
        pixelScaleY = wh[0] > 0 ? (float) height / wh[0] : 1.0F;

        float[] sx = new float[1];
        float[] sy = new float[1];
        GLFW.glfwGetWindowContentScale(window, sx, sy);
        contentScaleX = sane(sx[0]);
        contentScaleY = sane(sy[0]);
    }

    /**
     * A usable scale factor, falling back to 1.0.
     *
     * {@code > 0} already excludes NaN, since every NaN comparison is false, so
     * only infinity needs testing separately.
     */
    private static float sane(float scale) {
        return (scale > 0.0F && !Float.isInfinite(scale)) ? scale : 1.0F;
    }

    /** Framebuffer pixels per horizontal window unit (2.0 on a Retina display). */
    public static float getPixelScaleX() {
        return pixelScaleX;
    }

    /** Framebuffer pixels per vertical window unit (2.0 on a Retina display). */
    public static float getPixelScaleY() {
        return pixelScaleY;
    }

    /**
     * The display's horizontal DPI scale: 2.0 on Retina, 1.5 at Windows' 150%.
     *
     * <p>This is what a UI layer scales its drawing by. Do not substitute
     * {@link #getPixelScaleX()} — that is 1.0 on Windows at every DPI.
     */
    public static float getContentScaleX() {
        return contentScaleX;
    }

    /** The display's vertical DPI scale. See {@link #getContentScaleX()}. */
    public static float getContentScaleY() {
        return contentScaleY;
    }

    public static boolean isActive() {
        return focused;
    }

    public static boolean isCloseRequested() {
        return isCreated() && GLFW.glfwWindowShouldClose(window);
    }

    public static boolean wasResized() {
        boolean r = resized;
        resized = false;
        return r;
    }

    public static void create() throws LWJGLException {
        create(new PixelFormat());
    }

    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        if (isCreated()) {
            return;
        }
        if (pixelFormat == null) {
            pixelFormat = new PixelFormat();
        }
        ensureInit();

        windowedWidth = width;
        windowedHeight = height;

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        // MC 1.8.9 uses immediate-mode GL; it needs a compatibility profile.
        // macOS only exposes core profiles, so request the version/profile hints elsewhere.
        if (!isMac()) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        }
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, pixelFormat.getDepthBits());
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, pixelFormat.getAlphaBits());
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, pixelFormat.getStencilBits());
        GLFW.glfwWindowHint(GLFW.GLFW_STEREO, pixelFormat.isStereo() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        // Keep framebuffer pixels 1:1 with window size; LWJGL2 had no HiDPI scaling.
        GLFW.glfwWindowHint(GLFW.GLFW_SCALE_TO_MONITOR, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);

        long created = GLFW.glfwCreateWindow(Math.max(1, width), Math.max(1, height), title,
                MemoryUtil.NULL, MemoryUtil.NULL);
        if (created == MemoryUtil.NULL) {
            window = -1L;
            throw new LWJGLException("Failed to create GLFW window");
        }
        window = created;

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor != MemoryUtil.NULL) {
            GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
            if (vm != null) {
                int[] mx = new int[1];
                int[] my = new int[1];
                GLFW.glfwGetMonitorPos(monitor, mx, my);
                windowedX = mx[0] + (vm.width() - width) / 2;
                windowedY = my[0] + (vm.height() - height) / 2;
                GLFW.glfwSetWindowPos(window, windowedX, windowedY);
            }
        }

        int[] fbw = new int[1];
        int[] fbh = new int[1];
        GLFW.glfwGetFramebufferSize(window, fbw, fbh);
        width = fbw[0] <= 0 ? 1 : fbw[0];
        height = fbh[0] <= 0 ? 1 : fbh[0];
        updateScales();

        framebufferSizeCallback = GLFWFramebufferSizeCallback.create(new GLFWFramebufferSizeCallback() {
            public void invoke(long win, int w, int h) {
                if (win == window && w > 0 && h > 0) {
                    width = w;
                    height = h;
                    updateScales();
                    resized = true;
                }
            }
        });
        GLFW.glfwSetFramebufferSizeCallback(window, framebufferSizeCallback);

        // Dragging the window between monitors of different DPI changes the content
        // scale without necessarily changing the framebuffer size, so the callback
        // above is not enough on its own.
        contentScaleCallback = GLFWWindowContentScaleCallback.create(new GLFWWindowContentScaleCallback() {
            public void invoke(long win, float xScale, float yScale) {
                if (win == window) {
                    contentScaleX = sane(xScale);
                    contentScaleY = sane(yScale);
                    resized = true;
                }
            }
        });
        GLFW.glfwSetWindowContentScaleCallback(window, contentScaleCallback);

        windowFocusCallback = GLFWWindowFocusCallback.create(new GLFWWindowFocusCallback() {
            public void invoke(long win, boolean isFocused) {
                if (win == window) {
                    focused = isFocused;
                }
            }
        });
        GLFW.glfwSetWindowFocusCallback(window, windowFocusCallback);
        focused = true;

        // Attach input backends (groups 4/5) while the context is current.
        Mouse.create();
        Keyboard.create();

        GLFW.glfwShowWindow(window);
        setVSyncEnabled(vsyncEnabled);

        if (deferredFullscreen) {
            setFullscreen(true);
        }
        if (cachedIcons != null) {
            setIcon(cachedIcons);
        }
        resized = true;
    }

    public static void update() {
        if (!isCreated()) {
            return;
        }
        GLFW.glfwSwapBuffers(window);
        GLFW.glfwPollEvents();
        Mouse.poll();
        Keyboard.poll();
    }

    /** Pure-Java frame limiter. Sleeps in ~1ms chunks then spins to hit the deadline. */
    public static void sync(int fps) {
        if (fps <= 0) {
            return;
        }
        long target = 1000000000L / fps;
        long now = System.nanoTime();
        if (syncNext == 0L) {
            syncNext = now;
        }
        long deadline = syncNext + target;
        try {
            // Coarse wait: sleep while more than ~1ms remains to avoid busy-burning the CPU.
            while (now + 1000000L < deadline) {
                Thread.sleep(1L);
                now = System.nanoTime();
            }
            // Fine wait: yield-spin for the last stretch for timing accuracy.
            while (now < deadline) {
                Thread.yield();
                now = System.nanoTime();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        syncNext = deadline;
        // If we fell more than a full frame behind, resync so we don't burst to catch up.
        if (now - syncNext > target) {
            syncNext = now;
        }
    }

    public static DisplayMode getDisplayMode() {
        if (isCreated() || glfwInitialized) {
            long monitor = GLFW.glfwGetPrimaryMonitor();
            int freq = 60;
            if (monitor != MemoryUtil.NULL) {
                GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
                if (vm != null) {
                    freq = vm.refreshRate();
                }
            }
            return new DisplayMode(width, height, 32, freq);
        }
        return currentMode;
    }

    public static void setDisplayMode(DisplayMode mode) {
        if (mode == null) {
            return;
        }
        currentMode = mode;
        width = Math.max(1, mode.getWidth());
        height = Math.max(1, mode.getHeight());
        windowedWidth = width;
        windowedHeight = height;
        if (isCreated() && GLFW.glfwGetWindowMonitor(window) == MemoryUtil.NULL) {
            GLFW.glfwSetWindowSize(window, width, height);
        }
        resized = true;
    }

    public static DisplayMode getDesktopDisplayMode() {
        ensureInit();
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) {
            return currentMode;
        }
        GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
        if (vm == null) {
            return currentMode;
        }
        return new DisplayMode(vm.width(), vm.height(),
                vm.redBits() + vm.greenBits() + vm.blueBits(), vm.refreshRate());
    }

    public static DisplayMode[] getAvailableDisplayModes() {
        ensureInit();
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) {
            return new DisplayMode[0];
        }
        GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(monitor);
        if (modes == null) {
            return new DisplayMode[0];
        }
        DisplayMode[] out = new DisplayMode[modes.limit()];
        for (int i = 0; i < modes.limit(); i++) {
            GLFWVidMode m = modes.get(i);
            out[i] = new DisplayMode(m.width(), m.height(),
                    m.redBits() + m.greenBits() + m.blueBits(), m.refreshRate());
        }
        return out;
    }

    public static void setFullscreen(boolean fs) {
        if (!isCreated()) {
            deferredFullscreen = fs;
            fullscreen = fs;
            return;
        }
        boolean isFs = GLFW.glfwGetWindowMonitor(window) != MemoryUtil.NULL;
        if (fs == isFs) {
            fullscreen = fs;
            return;
        }
        if (fs) {
            long monitor = GLFW.glfwGetPrimaryMonitor();
            if (monitor == MemoryUtil.NULL) {
                return;
            }
            GLFWVidMode vm = GLFW.glfwGetVideoMode(monitor);
            if (vm == null) {
                return;
            }
            int[] wx = new int[1];
            int[] wy = new int[1];
            GLFW.glfwGetWindowPos(window, wx, wy);
            windowedX = wx[0];
            windowedY = wy[0];
            windowedWidth = width;
            windowedHeight = height;
            GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, vm.width(), vm.height(), vm.refreshRate());
        } else {
            GLFW.glfwSetWindowMonitor(window, MemoryUtil.NULL, windowedX, windowedY,
                    windowedWidth, windowedHeight, GLFW.GLFW_DONT_CARE);
        }
        fullscreen = fs;
        // Swap interval is reset when the window's monitor changes; re-apply it.
        setVSyncEnabled(vsyncEnabled);
        resized = true;
    }

    public static void setVSyncEnabled(boolean enabled) {
        vsyncEnabled = enabled;
        if (GLFW.glfwGetCurrentContext() != MemoryUtil.NULL) {
            GLFW.glfwSwapInterval(enabled ? 1 : 0);
        }
    }

    /**
     * LWJGL2 allowed setIcon before window creation, so we deep-copy the (heap, RGBA)
     * buffers and re-apply them once the window exists. Returns 1 if applied, else 0.
     */
    public static int setIcon(ByteBuffer[] icons) {
        if (icons == null) {
            return 0;
        }
        if (!Arrays.equals(cachedIcons, icons)) {
            ByteBuffer[] copy = new ByteBuffer[icons.length];
            for (int i = 0; i < icons.length; i++) {
                ByteBuffer src = icons[i];
                ByteBuffer dst = ByteBuffer.allocate(src.capacity());
                int pos = src.position();
                dst.put(src);
                src.position(pos);
                dst.flip();
                copy[i] = dst;
            }
            cachedIcons = copy;
        }

        if (!isCreated() || isMac()) {
            return 0;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer buffer = GLFWImage.malloc(icons.length, stack);
            for (int j = 0; j < icons.length; j++) {
                ByteBuffer buf = icons[j];
                int pos = buf.position();
                int bytes = buf.remaining();
                int size = (int) Math.sqrt(bytes / 4.0);
                // GLFW needs an off-heap (direct) buffer; MC hands us a heap buffer.
                ByteBuffer direct = stack.malloc(bytes);
                direct.put(buf);
                buf.position(pos);
                direct.flip();
                buffer.position(j).width(size).height(size).pixels(direct);
            }
            buffer.position(0);
            GLFW.glfwSetWindowIcon(window, buffer);
        }
        return 1;
    }

    public static void destroy() {
        if (!isCreated()) {
            return;
        }
        // Tear down input first: Keyboard/Mouse.destroy() free their OWN GLFW callbacks
        // (GLFWKeyboardImplementation.destroyKeyboard frees keyCallback/charCallback;
        // the mouse impl frees its cursor-pos/button/scroll callbacks) and reset their
        // 'created' flags.
        Keyboard.destroy();
        Mouse.destroy();
        // Free ONLY the two callbacks Display itself registered (framebufferSize +
        // windowFocus). Do NOT call Callbacks.glfwFreeCallbacks(window): that frees EVERY
        // callback on the window — including the key/char/mouse ones Keyboard/Mouse.destroy
        // just freed — a double-free of already-deleted JNI global refs that crashes the
        // JVM on shutdown (EXCEPTION_ACCESS_VIOLATION in jni_DeleteGlobalRef). Freeing our
        // own two explicitly also fixes a real leak: they were previously only nulled.
        // Unbind before freeing so GLFW never holds a pointer to a released closure.
        if (framebufferSizeCallback != null) {
            GLFW.glfwSetFramebufferSizeCallback(window, null);
            framebufferSizeCallback.free();
            framebufferSizeCallback = null;
        }
        if (contentScaleCallback != null) {
            GLFW.glfwSetWindowContentScaleCallback(window, null);
            contentScaleCallback.free();
            contentScaleCallback = null;
        }
        if (windowFocusCallback != null) {
            GLFW.glfwSetWindowFocusCallback(window, null);
            windowFocusCallback.free();
            windowFocusCallback = null;
        }
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFWErrorCallback cb = GLFW.glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
        errorCallback = null;
        window = -1L;
        glfwInitialized = false;
        syncNext = 0L;
    }
}