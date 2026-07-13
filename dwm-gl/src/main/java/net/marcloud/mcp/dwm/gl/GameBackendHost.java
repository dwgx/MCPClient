package net.marcloud.mcp.dwm.gl;

import net.marcloud.mcp.dwm.backend.BackendHost;

/**
 * {@link BackendHost} backed by the live game, resolving the GLFW window handle and MC's
 * framebuffer facts REFLECTIVELY so this module hard-links neither the shim nor MC at
 * compile time (both are on the runtime classpath via the game jars). All lookups are
 * fault-isolated: an unresolved handle degrades to the sentinel the adapter treats as
 * "use the default framebuffer / unknown", never a throw on the render thread.
 *
 * <p>Pure-Java twin of dwm-compose's {@code GameBackendHost} — identical reflective
 * contract, no Kotlin.
 */
public final class GameBackendHost implements BackendHost {

    private final long windowHint;

    public GameBackendHost(long windowHint) {
        this.windowHint = windowHint;
    }

    @Override
    public long windowHandle() {
        if (windowHint != 0L) {
            return windowHint;
        }
        // org.lwjgl.opengl.Display.getWindow() (the shim) returns the GLFW window handle.
        try {
            Class<?> display = Class.forName("org.lwjgl.opengl.Display");
            Object h = display.getMethod("getWindow").invoke(null);
            return (h instanceof Long) ? (Long) h : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // The overlay runs from the render-frame advice, which fires on the game render
    // thread with GL current — so by construction the caller IS on the render thread.
    @Override
    public boolean onRenderThread() {
        return true;
    }

    /**
     * The FBO MC is drawing into. At the {@code updateCameraAndRender} exit seam, MC's
     * own {@code framebufferMc} is still bound (unbind/blit is downstream), so
     * glGetInteger of the live binding is the correct target; -1 lets the adapter fall
     * back to 0.
     */
    @Override
    public int currentFramebufferId() {
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            // GL_FRAMEBUFFER_BINDING = 0x8CA6
            Object v = gl11.getMethod("glGetInteger", int.class).invoke(null, 0x8CA6);
            return (v instanceof Integer) ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public int framebufferWidthPx() {
        return mcDisplayField("displayWidth");
    }

    @Override
    public int framebufferHeightPx() {
        return mcDisplayField("displayHeight");
    }

    /** Read a private int field off the live Minecraft singleton (e.g. displayWidth). */
    private int mcDisplayField(String name) {
        try {
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
            Object instance = mc.getMethod("getMinecraft").invoke(null);
            java.lang.reflect.Field f = mc.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(instance);
        } catch (Throwable t) {
            return 0;
        }
    }
}
