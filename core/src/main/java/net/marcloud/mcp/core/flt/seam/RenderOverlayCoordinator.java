package net.marcloud.mcp.core.flt.seam;

import java.lang.instrument.Instrumentation;
import java.util.function.LongConsumer;

/**
 * Boot-time coordinator that wires the render-frame overlay WITHOUT core depending on
 * the DWM/Compose modules. It reflectively discovers the optional Compose overlay
 * backend (packaged in the detachable {@code dwm-compose} fat jar, on the game
 * classpath only when built) and, if present, installs the {@link RenderFrameInjector}
 * seam and routes each frame to it.
 *
 * <p><b>Decoupling contract.</b> Core owns exactly two neutral types on this path:
 * {@link RenderBridge.RenderFrameSink} and the JDK {@link LongConsumer} the entry
 * point hands back. The overlay module (which CAN import DWM's {@code ComposeCompositor}
 * + the {@code ContentBackend} SPI, but NOT core) exposes a public static factory
 * {@code net.marcloud.mcp.dwm.compose.ComposeOverlayEntry.frameSink(long windowHandle)}
 * returning a {@link LongConsumer} that drives one overlay frame per call. Core reaches
 * it by reflection only — no compile-time link — so deleting {@code dwm-compose} leaves
 * core compiling and the game running with no overlay.
 *
 * <p><b>Degrade-to-absent.</b> If the entry class is not on the classpath
 * (ClassNotFound) or anything throws, this is a silent no-op: no seam installed, no
 * overlay, game unaffected. Mirrors the Board/Backplane "reflect, miss, degrade" idiom.
 */
public final class RenderOverlayCoordinator {

    private static final String ENTRY_CLASS = "net.marcloud.mcp.dwm.compose.ComposeOverlayEntry";
    private static final String ENV_FLAG = "mcp.core.overlay";

    private RenderOverlayCoordinator() {
    }

    /**
     * Discover + wire the overlay if its module is present and enabled. Off by default:
     * the overlay is an experimental live feature, so it arms only when {@code
     * -Dmcp.core.overlay=true} is set (a bad overlay must never be a surprise in a
     * normal run). Never throws.
     *
     * @param inst         the agent Instrumentation (the seam needs retransform; null → skip)
     * @param windowHandle the GLFW window handle the overlay renders against (0 if unknown)
     */
    public static void tryInstall(Instrumentation inst, long windowHandle) {
        if (!Boolean.parseBoolean(System.getProperty(ENV_FLAG, "false"))) {
            return; // opt-in only
        }
        if (inst == null || !inst.isRetransformClassesSupported()) {
            System.err.println("[MCP Overlay] Instrumentation/retransform unavailable — overlay disabled.");
            return;
        }
        try {
            Class<?> entry = Class.forName(ENTRY_CLASS);
            Object result = entry.getMethod("frameSink", long.class).invoke(null, windowHandle);
            if (!(result instanceof LongConsumer driver)) {
                System.err.println("[MCP Overlay] entry did not return a LongConsumer — overlay disabled.");
                return;
            }
            // install() wires the sink into RenderBridge AND registers the advice, so
            // the driver is live the moment the transformer is in place (the advice
            // calls RenderBridge.onRenderFrame -> our sink -> driver.accept(frame)).
            RenderFrameInjector injector = new RenderFrameInjector();
            injector.install(inst, driver::accept);
            System.err.println("[MCP Overlay] Compose overlay armed (render-frame seam installed).");
        } catch (ClassNotFoundException e) {
            // dwm-compose not on the classpath — expected when the overlay jar is absent.
            System.err.println("[MCP Overlay] backend not present (dwm-compose jar absent) — no overlay.");
        } catch (Throwable t) {
            System.err.println("[MCP Overlay] overlay wiring failed (disabled, game unaffected): " + t);
        }
    }
}
