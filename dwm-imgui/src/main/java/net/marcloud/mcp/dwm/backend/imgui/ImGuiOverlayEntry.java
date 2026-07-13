package net.marcloud.mcp.dwm.backend.imgui;

import java.util.function.LongConsumer;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultContentBackendRegistry;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.compositor.ComposeCompositor;
import net.marcloud.mcp.dwm.gl.GameBackendHost;

/**
 * The single reflective entry point core's {@code RenderOverlayCoordinator} calls for the
 * imgui overlay backend. Core owns no DWM types, so it reaches this class by reflection
 * (registered under the {@code imgui} backend id at the FQN
 * {@code net.marcloud.mcp.dwm.backend.imgui.ImGuiOverlayEntry}) and receives a JDK
 * {@link LongConsumer}: each call (once per game render frame, on the render thread with
 * GL current) drives one overlay frame.
 *
 * <p>Mirror of {@code net.marcloud.mcp.dwm.gl.GlOverlayEntry} — the same
 * {@code frameSink(long)} contract, reusing dwm-gl's {@link GameBackendHost} and the
 * {@link ComposeCompositor} render-frame driver so the imgui backend differs from the
 * handwritten-GL one ONLY in which {@code ContentBackend} it registers.
 */
public final class ImGuiOverlayEntry {

    private ImGuiOverlayEntry() {
    }

    /**
     * Build and arm the imgui overlay; return the per-frame driver. Called once by core
     * via reflection. On any failure returns a no-op consumer (never null) so the caller's
     * seam still installs cleanly and the frame path is inert.
     *
     * @param windowHandleHint the GLFW window handle core knows, or 0 to self-resolve
     * @return a per-frame driver (never null); a no-op consumer on any arm failure
     */
    public static LongConsumer frameSink(long windowHandleHint) {
        try {
            BackendHost host = new GameBackendHost(windowHandleHint);
            DefaultContentBackendRegistry registry = new DefaultContentBackendRegistry();
            registry.register(new ImGuiContentBackend());
            registry.activate("imgui");
            ComposeCompositor compositor = new ComposeCompositor(host, registry);
            return frame -> compositor.driveFrame(FrameInput.none(), 1f, 0.016f, System.nanoTime());
        } catch (Throwable t) {
            System.err.println("[ImGuiOverlayEntry] arm failed (no-op overlay): " + t);
            return frame -> {
                /* inert */
            };
        }
    }
}
