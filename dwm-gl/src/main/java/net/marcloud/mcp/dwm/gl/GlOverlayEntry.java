package net.marcloud.mcp.dwm.gl;

import java.util.function.LongConsumer;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultContentBackendRegistry;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.compositor.ComposeCompositor;

/**
 * The single reflective entry point core's {@code RenderOverlayCoordinator} calls for
 * the pure-Java handwritten-GL overlay. Core owns no DWM types, so it reaches this class
 * by reflection and receives a JDK {@link LongConsumer}: each call (once per game render
 * frame, on the render thread with GL current) drives one overlay frame.
 *
 * <p>Pure-Java twin of {@code net.marcloud.mcp.dwm.compose.ComposeOverlayEntry} — the
 * same {@code frameSink(long)} contract, so the two are interchangeable behind the
 * coordinator's candidate list. This is where the DWM-side pieces (which core cannot
 * import) are assembled: a {@link GlContentBackend} registered in a
 * {@link DefaultContentBackendRegistry} and driven by a {@link ComposeCompositor} against
 * a {@link BackendHost} that reports the game's window + framebuffer facts (resolved
 * reflectively so this module hard-links neither the shim nor MC at compile time).
 */
public final class GlOverlayEntry {

    private GlOverlayEntry() {
    }

    /**
     * Build and arm the overlay; return the per-frame driver. Called once by core via
     * reflection. On any failure returns a no-op consumer (never null) so the caller's
     * seam still installs cleanly and the frame path is inert.
     *
     * @param windowHandleHint the GLFW window handle core knows, or 0 to self-resolve
     * @return a per-frame driver (never null); a no-op consumer on any arm failure
     */
    public static LongConsumer frameSink(long windowHandleHint) {
        try {
            BackendHost host = new GameBackendHost(windowHandleHint);
            DefaultContentBackendRegistry registry = new DefaultContentBackendRegistry();
            registry.register(new GlContentBackend());
            registry.activate("gl");
            ComposeCompositor compositor = new ComposeCompositor(host, registry);
            return frame -> {
                // driveFrame reconciles attach/detach, resizes on change, renders, and is
                // itself swallow-all fault-isolated. dt is approximate (per-frame).
                compositor.driveFrame(FrameInput.none(), 1f, 0.016f, System.nanoTime());
            };
        } catch (Throwable t) {
            System.err.println("[GlOverlayEntry] arm failed (no-op overlay): " + t);
            return frame -> {
                /* inert */
            };
        }
    }
}
