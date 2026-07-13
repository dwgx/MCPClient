package net.marcloud.mcp.dwm.backend;

/**
 * The environment a backend binds to on attach. Exposes only neutral facts (the
 * native window handle, whether we are on the render thread) so the SPI stays free
 * of GLFW/GL types — the backend adapter knows what to do with the handle.
 */
public interface BackendHost {

    /** The native window handle (e.g. the GLFW window the game owns). */
    long windowHandle();

    /** True if the caller is on the render/game thread (backends draw only there). */
    boolean onRenderThread();
}
