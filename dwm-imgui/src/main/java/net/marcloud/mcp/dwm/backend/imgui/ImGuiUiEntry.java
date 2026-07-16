package net.marcloud.mcp.dwm.backend.imgui;

import java.util.function.LongConsumer;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultBackendRegistry;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.KernelStatePanel;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.UiComposer;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.gl.FrameClock;
import net.marcloud.mcp.dwm.gl.GameBackendHost;
import net.marcloud.mcp.dwm.gl.GameInput;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Reflective entry point for the DrawContext-axis (MD3) overlay on the imgui backend —
 * the imgui twin of {@code GlUiEntry}. Drives the DWM MD3 component tree through
 * {@link UiComposer} + {@link ImGuiRenderBackend}, so the SAME {@link MaterialButton}
 * renders in-game with native rounded rects + real font text. Returns a
 * {@link LongConsumer} driving one UI frame per game render frame; a no-op consumer
 * (never null) on any arm failure.
 */
public final class ImGuiUiEntry {

    private ImGuiUiEntry() {
    }

    public static LongConsumer frameSink(long windowHandleHint) {
        try {
            BackendHost host = new GameBackendHost(windowHandleHint);
            DefaultBackendRegistry registry = new DefaultBackendRegistry();
            registry.register(new ImGuiRenderBackend());
            registry.activate("imgui");

            Compositor compositor = new Compositor();
            FrameComponentContext ctx = new FrameComponentContext(
                    MaterialMdcTheme.darkTheme(), compositor.store(), WidgetId.root("overlay"));
            Component root = new KernelStatePanel();
            UiComposer composer = new UiComposer(host, registry, compositor, ctx, root);
            GameInput input = new GameInput();
            FrameClock clock = new FrameClock();

            // Real wall-clock frame delta (clamped) instead of a hardcoded 60 Hz, so
            // animations run at the true render rate. scale stays 1f (no DIP factor on
            // BackendHost; overlay coords are pixels).
            return frame -> composer.driveFrame(
                    input.read(host.framebufferHeightPx()), 1f, clock.tick());
        } catch (Throwable t) {
            System.err.println("[ImGuiUiEntry] arm failed (no-op overlay): " + t);
            return frame -> {
                /* inert */
            };
        }
    }

}
