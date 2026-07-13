package net.marcloud.mcp.dwm.compose

import net.marcloud.mcp.dwm.backend.BackendHost
import net.marcloud.mcp.dwm.backend.DefaultContentBackendRegistry
import net.marcloud.mcp.dwm.backend.FrameInput
import net.marcloud.mcp.dwm.compositor.ComposeCompositor
import java.util.function.LongConsumer

/**
 * The single reflective entry point core's [RenderOverlayCoordinator] calls. Core owns
 * no DWM/Compose types, so it reaches this class by reflection and receives a JDK
 * [LongConsumer]: each call (once per game render frame, on the render thread with GL
 * current) drives one Compose overlay frame.
 *
 * <p>This is where the DWM-side pieces (which core cannot import) are assembled:
 * a [ComposeSkiaGlBackend] registered in a [DefaultContentBackendRegistry] and driven
 * by a [ComposeCompositor] against a [BackendHost] that reports the game's window +
 * framebuffer facts (resolved reflectively from the shim/MC so this module hard-links
 * neither at compile time).
 */
object ComposeOverlayEntry {

    /**
     * Build and arm the overlay; return the per-frame driver. Called once by core via
     * reflection. On any failure returns a no-op consumer (never null) so the caller's
     * seam still installs cleanly and the frame path is inert.
     *
     * @param windowHandleHint the GLFW window handle core knows, or 0 to self-resolve
     */
    @JvmStatic
    fun frameSink(windowHandleHint: Long): LongConsumer {
        return try {
            val host = GameBackendHost(windowHandleHint)
            val registry = DefaultContentBackendRegistry()
            registry.register(ComposeSkiaGlBackend())
            registry.activate("compose")
            val compositor = ComposeCompositor(host, registry)
            LongConsumer { frame ->
                // driveFrame reconciles attach/detach, resizes on change, renders, and
                // is itself swallow-all fault-isolated. dt is approximate (per-frame).
                compositor.driveFrame(FrameInput.none(), 1f, 0.016f, System.nanoTime())
            }
        } catch (t: Throwable) {
            System.err.println("[ComposeOverlayEntry] arm failed (no-op overlay): $t")
            LongConsumer { /* inert */ }
        }
    }
}
