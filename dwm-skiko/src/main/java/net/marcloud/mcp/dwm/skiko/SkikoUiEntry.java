package net.marcloud.mcp.dwm.skiko;

import java.util.function.LongConsumer;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultBackendRegistry;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.DemoPanel;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.UiComposer;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.gl.GameBackendHost;
import net.marcloud.mcp.dwm.gl.GameInput;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Reflective entry point for the DrawContext-axis (MD3) overlay on the Skiko backend —
 * the highest-fidelity twin of {@code GlUiEntry} / {@code ImGuiUiEntry}. Drives the DWM
 * MD3 component tree through {@link UiComposer} + {@link SkikoRenderBackend}, so the SAME
 * {@link MaterialButton} renders in-game with true antialiased vector shapes + real font
 * text via Skia. Returns a {@link LongConsumer} driving one UI frame per game render
 * frame; a no-op consumer (never null) on any arm failure.
 */
public final class SkikoUiEntry {

    private SkikoUiEntry() {
    }

    public static LongConsumer frameSink(long windowHandleHint) {
        try {
            BackendHost host = new GameBackendHost(windowHandleHint);
            DefaultBackendRegistry registry = new DefaultBackendRegistry();
            registry.register(new SkikoRenderBackend());
            registry.activate("skiko");

            Compositor compositor = new Compositor();
            FrameComponentContext ctx = new FrameComponentContext(
                    MaterialMdcTheme.darkTheme(), compositor.store(), WidgetId.root("overlay"));
            Component root = new DemoPanel();
            UiComposer composer = new UiComposer(host, registry, compositor, ctx, root);
            GameInput input = new GameInput();

            return frame -> composer.driveFrame(
                    input.read(host.framebufferHeightPx()), 1f, 1f / 60f);
        } catch (Throwable t) {
            System.err.println("[SkikoUiEntry] arm failed (no-op overlay): " + t);
            return frame -> {
                /* inert */
            };
        }
    }

}
