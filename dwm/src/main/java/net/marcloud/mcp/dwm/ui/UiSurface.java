package net.marcloud.mcp.dwm.ui;

/**
 * What a DWM render backend has to provide, in plain JVM types.
 *
 * <p>This is the SPI half of the module contract in {@code dwm/README.md}: the backend
 * (qml4j/Skija today, something else tomorrow) is swappable, and its native types live in
 * exactly one adapter package. Nothing that implements or calls this interface may expose a
 * Skija, qml4j or OpenGL type in its signatures — that is what keeps the rest of dwm, and
 * Board above it, compilable when a backend is absent or replaced.
 *
 * <p><b>Threading.</b> Every method is called on the game's render thread, inside MC's
 * frame, while MC's GL context is current. On macOS that thread is also the process main
 * thread (GLFW owns it under {@code -XstartOnFirstThread}), so an implementation must never
 * block on another thread or try to own a window of its own.
 *
 * <p><b>Lifecycle.</b> {@link #open} then any number of {@link #frame} calls then
 * {@link #close}, and possibly {@code open} again. Implementations must tolerate
 * {@code close} without {@code open}, and a second {@code close}, without throwing —
 * MC can dispose a screen at times we do not choose.
 */
public interface UiSurface {

    /**
     * Bring the backend up at this framebuffer size. Called once when the screen is shown.
     *
     * @param widthPx  framebuffer width in pixels, never less than 1
     * @param heightPx framebuffer height in pixels, never less than 1
     * @return true if the backend is usable; false means it failed to initialise and the
     *         caller should fall back to not drawing rather than treating it as fatal
     */
    boolean open(int widthPx, int heightPx);

    /**
     * Draw one frame at the current framebuffer size.
     *
     * <p>Size is passed every frame rather than only on resize: a window resize recreates
     * MC's framebuffer GL objects, sometimes under a recycled id, and a backend that cached
     * anything across that change renders black afterwards. Re-checking each frame is what
     * makes that class of bug impossible.
     *
     * @param widthPx     current framebuffer width in pixels
     * @param heightPx    current framebuffer height in pixels
     * @param nanoTime    monotonic clock for animation, in nanoseconds
     */
    void frame(int widthPx, int heightPx, long nanoTime);

    /** Release native resources. Must be idempotent and must not throw. */
    void close();

    /** True between a successful {@link #open} and a {@link #close}. */
    boolean isOpen();
}
