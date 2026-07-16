package net.marcloud.mcp.dwm.gl;

import java.util.function.LongConsumer;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultBackendRegistry;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.KernelStatePanel;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.UiComposer;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Reflective entry point for the DrawContext-axis (MD3) overlay on the handwritten-GL
 * backend. Distinct from {@code GlOverlayEntry} (which drives the static ContentBackend
 * panel): this drives the DWM MD3 component tree through {@link UiComposer} +
 * {@link GlRenderBackend}, so a real {@link MaterialButton} renders in-game on the
 * pure-Java GL path. Core reaches it by reflection under a UI-backend id; it returns a
 * {@link LongConsumer} that drives one UI frame per game render frame.
 *
 * <p>This is the axis the imgui and Skiko backends also target — same {@code UiComposer},
 * same MD3 root, only the {@code RenderBackend} differs — so it is the end-to-end
 * de-risking of the "M3 UI renders in-game" path before higher-fidelity backends land.
 */
public final class GlUiEntry {

    private GlUiEntry() {
    }

    /**
     * Build and arm the MD3 UI overlay on the GL backend; return the per-frame driver.
     * On any failure returns a no-op consumer (never null) so the seam still installs.
     *
     * @param windowHandleHint the GLFW window handle core knows, or 0 to self-resolve
     */
    public static LongConsumer frameSink(long windowHandleHint) {
        try {
            BackendHost host = new GameBackendHost(windowHandleHint);
            DefaultBackendRegistry registry = new DefaultBackendRegistry();
            registry.register(new GlRenderBackend());
            registry.activate("gl");

            Compositor compositor = new Compositor();
            FrameComponentContext ctx = new FrameComponentContext(
                    MaterialMdcTheme.darkTheme(), compositor.store(), WidgetId.root("overlay"));
            Component root = new KernelStatePanel();
            UiComposer composer = new UiComposer(host, registry, compositor, ctx, root);
            GameInput input = new GameInput();
            FrameClock clock = new FrameClock();

            // Read the live pointer/buttons each frame (overlay pixel space) so the MD3
            // tree hit-tests the real cursor. fb height for the y-flip comes from the host.
            // dtSeconds is the REAL wall-clock delta (clamped) so ripple/fade run at the
            // true render rate, not a hardcoded 60 Hz. scale stays 1f: BackendHost exposes
            // no DIP/pixel factor (overlay coords are already pixels), so there is nothing
            // truer to pass yet.
            return frame -> composer.driveFrame(
                    input.read(host.framebufferHeightPx()), 1f, clock.tick());
        } catch (Throwable t) {
            System.err.println("[GlUiEntry] arm failed (no-op overlay): " + t);
            return frame -> {
                /* inert */
            };
        }
    }
}
