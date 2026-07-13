package net.marcloud.mcp.dwm.backend;

/**
 * The environment a backend binds to on attach. Exposes only neutral facts (the
 * native window handle, whether we are on the render thread) so the SPI stays free
 * of GLFW/GL types — the backend adapter knows what to do with the handle.
 *
 * <p>The framebuffer facts below are the extra neutral bits a tree-rendering
 * {@link ContentBackend} (Compose/Skia) needs to build its render target: which FBO
 * the game is drawing into and its pixel size. They are still GL-type-free — plain
 * ints an adapter feeds to {@code BackendRenderTarget.makeGL} — and are {@code
 * default} so {@link NullBackend} and existing hosts keep compiling untouched. A
 * host that does not know the FBO returns the "unknown" sentinels, and the adapter
 * treats {@code -1} as "use the default framebuffer (0)".
 */
public interface BackendHost {

    /** The native window handle (e.g. the GLFW window the game owns). */
    long windowHandle();

    /** True if the caller is on the render/game thread (backends draw only there). */
    boolean onRenderThread();

    /**
     * The GL framebuffer id the game is currently drawing into
     * ({@code glGetIntegerv(GL_FRAMEBUFFER_BINDING)}), or {@code -1} when unknown —
     * the adapter treats unknown as "use the default framebuffer (0)".
     */
    default int currentFramebufferId() {
        return -1;
    }

    /** Framebuffer width in pixels, or {@code 0} when unknown. */
    default int framebufferWidthPx() {
        return 0;
    }

    /** Framebuffer height in pixels, or {@code 0} when unknown. */
    default int framebufferHeightPx() {
        return 0;
    }
}
