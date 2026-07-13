package net.marcloud.mcp.dwm.compose

import net.marcloud.mcp.dwm.backend.BackendCaps
import net.marcloud.mcp.dwm.backend.BackendHost
import net.marcloud.mcp.dwm.backend.ContentBackend
import net.marcloud.mcp.dwm.backend.FrameInput
import net.marcloud.mcp.dwm.backend.FrameMetrics
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density

/**
 * The live Compose Material 3 overlay backend — implements DWM's [ContentBackend] and
 * renders an M3 tree, per game render frame, onto a Skia surface backed by the game's
 * GL context, guarded by [GlStateGuard] so MC's own rendering survives.
 *
 * <p><b>First live increment (honest scope):</b> uses the proven single-frame
 * [ImageComposeScene] path (Phase 0/1) rather than the full
 * CanvasLayersComposeScene + FrameRecomposer + game-thread dispatcher. That defers the
 * Compose #4788 recomposer-deadlock risk until the two harder risks — Skiko native in
 * the live agent+DCEVM JVM, and GlStateGuard shadow write-through — are proven live.
 * A static M3 surface is enough to prove "an M3 overlay appears in-game and MC still
 * renders." Animated recomposition is a later increment.
 *
 * <p>Topology (Phase-1 verified): render into a self-owned offscreen FBO, then the
 * host composites (or Skia targets MC's FBO directly for this first cut). Y-flip is
 * SKIA_TOP_AT_HIGH_GLY per the harness, so BOTTOM_LEFT origin needs no extra flip when
 * the texture is sampled as GL. All GL touches are on the render thread with the
 * context current; everything is fault-isolated so a Skia/GL fault never breaks the
 * game frame.
 */
class ComposeSkiaGlBackend : ContentBackend {

    private val guard = GlStateGuard()
    private var ctx: DirectContext? = null
    private var rt: BackendRenderTarget? = null
    private var surface: Surface? = null

    @Volatile private var fbW = 0
    @Volatile private var fbH = 0
    @Volatile private var fbId = 0

    override fun id(): String = "compose"

    override fun caps(): BackendCaps =
        BackendCaps(true, true, true, true, true, 4096)

    override fun onAttach(host: BackendHost) {
        try {
            ctx = DirectContext.makeGL()
            fbId = host.currentFramebufferId().let { if (it < 0) 0 else it }
            fbW = host.framebufferWidthPx().coerceAtLeast(1)
            fbH = host.framebufferHeightPx().coerceAtLeast(1)
            buildSurface()
            System.err.println("[ComposeBackend] attached: ctx=${ctx != null} fb=${fbW}x${fbH}#$fbId")
        } catch (t: Throwable) {
            System.err.println("[ComposeBackend] onAttach faulted (overlay disabled): $t")
            onDetach()
        }
    }

    override fun onDetach() {
        runCatching { surface?.close() }; surface = null
        runCatching { rt?.close() }; rt = null
        runCatching { ctx?.close() }; ctx = null
    }

    override fun resize(fbWidth: Int, fbHeight: Int, fbId: Int, fbFormat: Int) {
        this.fbW = fbWidth.coerceAtLeast(1)
        this.fbH = fbHeight.coerceAtLeast(1)
        this.fbId = if (fbId < 0) 0 else fbId
        buildSurface()
    }

    /** (Re)build the Skia surface over the current framebuffer. Null-checks the factory. */
    private fun buildSurface() {
        val c = ctx ?: return
        runCatching { surface?.close() }; surface = null
        runCatching { rt?.close() }; rt = null
        // stencil 8 matches the harness; GL_RGBA8 = 0x8058.
        val target = BackendRenderTarget.makeGL(fbW, fbH, 0, 8, fbId, 0x8058)
        rt = target
        surface = Surface.makeFromBackendRenderTarget(
            c, target, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
        )
        if (surface == null) {
            System.err.println("[ComposeBackend] Surface.makeFromBackendRenderTarget returned null " +
                "(fbFormat/attachment mismatch) — overlay inert this frame.")
        }
    }

    // Input model: this first increment renders a static M3 tree, so pointer/keyboard
    // are not yet consumed. Wiring GlfwInputBridge -> sendPointerEvent is a later step.
    override fun submitInput(input: FrameInput) { /* no-op: static overlay */ }

    override fun renderFrame(m: FrameMetrics, nanoTime: Long) {
        val c = ctx ?: return
        try {
            guard.enter()
            c.resetGLAll()
            // Single-frame Compose render of the M3 tree into an image, drawn onto our
            // GL-backed surface. (First increment: ImageComposeScene, no recomposer.)
            val scene = ImageComposeScene(width = fbW, height = fbH, density = Density(1f)) {
                OverlayContent.Root()
            }
            try {
                val img = scene.render(nanoTime)
                surface?.canvas?.drawImage(img, 0f, 0f)
                surface?.flushAndSubmit()
            } finally {
                scene.close()
            }
        } catch (t: Throwable) {
            System.err.println("[ComposeBackend] renderFrame faulted (frame skipped): $t")
        } finally {
            guard.leave()
        }
    }

    override fun wantsContinuousFrames(): Boolean = false
    override fun consumedPointer(): Boolean = false
    override fun consumedKeyboard(): Boolean = false
}

