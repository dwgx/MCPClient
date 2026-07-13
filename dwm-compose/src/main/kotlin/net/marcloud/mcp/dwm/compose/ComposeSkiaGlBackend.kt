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
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

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
    private val compositor = OverlayQuadCompositor()
    private var ctx: DirectContext? = null
    private var rt: BackendRenderTarget? = null
    private var surface: Surface? = null

    // Self-owned offscreen FBO + color texture Compose renders into. Compositing that
    // texture onto MC's FBO (SrcOver quad) is what keeps the blast radius to one quad
    // and stops transparent Compose pixels from clobbering MC's frame to black.
    private var offscreenFbo = 0
    private var offscreenTex = 0

    @Volatile private var fbW = 0
    @Volatile private var fbH = 0
    @Volatile private var mcFbId = 0

    override fun id(): String = "compose"

    override fun caps(): BackendCaps =
        BackendCaps(true, true, true, true, true, 4096)

    override fun onAttach(host: BackendHost) {
        try {
            ctx = DirectContext.makeGL()
            mcFbId = host.currentFramebufferId().let { if (it < 0) 0 else it }
            fbW = host.framebufferWidthPx().coerceAtLeast(1)
            fbH = host.framebufferHeightPx().coerceAtLeast(1)
            buildSurface()
            compositor.init()
            System.err.println("[ComposeBackend] attached: ctx=${ctx != null} fb=${fbW}x${fbH} mcFbo=$mcFbId offFbo=$offscreenFbo")
        } catch (t: Throwable) {
            System.err.println("[ComposeBackend] onAttach faulted (overlay disabled): $t")
            onDetach()
        }
    }

    override fun onDetach() {
        runCatching { surface?.close() }; surface = null
        runCatching { rt?.close() }; rt = null
        runCatching { ctx?.close() }; ctx = null
        runCatching { if (offscreenFbo != 0) GL30.glDeleteFramebuffers(offscreenFbo) }; offscreenFbo = 0
        runCatching { if (offscreenTex != 0) GL11.glDeleteTextures(offscreenTex) }; offscreenTex = 0
        runCatching { compositor.dispose() }
    }

    override fun resize(fbWidth: Int, fbHeight: Int, fbId: Int, fbFormat: Int) {
        this.fbW = fbWidth.coerceAtLeast(1)
        this.fbH = fbHeight.coerceAtLeast(1)
        this.mcFbId = if (fbId < 0) 0 else fbId
        buildSurface()
    }

    /**
     * (Re)build the self-owned offscreen FBO + color texture, and a Skia surface
     * targeting THAT (never MC's FBO). RGBA8, stencil 8. Compose renders here; the
     * composite step then blends this texture onto MC's FBO. Null-checks the factory.
     */
    private fun buildSurface() {
        val c = ctx ?: return
        runCatching { surface?.close() }; surface = null
        runCatching { rt?.close() }; rt = null
        runCatching { if (offscreenFbo != 0) GL30.glDeleteFramebuffers(offscreenFbo) }
        runCatching { if (offscreenTex != 0) GL11.glDeleteTextures(offscreenTex) }

        // Own offscreen color texture + FBO (GL_RGBA8 = 0x8058, GL_RGBA = 0x1908).
        offscreenTex = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, offscreenTex)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, 0x8058, fbW, fbH, 0,
            0x1908, GL11.GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        offscreenFbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, offscreenFbo)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, offscreenTex, 0)

        val target = BackendRenderTarget.makeGL(fbW, fbH, 0, 8, offscreenFbo, 0x8058)
        rt = target
        surface = Surface.makeFromBackendRenderTarget(
            c, target, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
        )
        if (surface == null) {
            System.err.println("[ComposeBackend] Surface.makeFromBackendRenderTarget returned null " +
                "(offscreen FBO mismatch) — overlay inert this frame.")
        }
    }

    // Input model: this first increment renders a static M3 tree, so pointer/keyboard
    // are not yet consumed. Wiring GlfwInputBridge -> sendPointerEvent is a later step.
    override fun submitInput(input: FrameInput) { /* no-op: static overlay */ }

    override fun renderFrame(m: FrameMetrics, nanoTime: Long) {
        val c = ctx ?: return
        val surf = surface ?: return
        try {
            guard.enter()
            c.resetGLAll()

            // 1) Render Compose into the OFFSCREEN surface. Clear it fully TRANSPARENT
            //    first so only the M3 content has alpha — the rest stays 0,0,0,0 and the
            //    SrcOver composite below leaves MC's frame untouched there.
            val scene = ImageComposeScene(width = fbW, height = fbH, density = Density(1f)) {
                OverlayContent.Root()
            }
            try {
                surf.canvas.clear(0x00000000) // transparent
                val img = scene.render(nanoTime)
                surf.canvas.drawImage(img, 0f, 0f)
                surf.flushAndSubmit()
            } finally {
                scene.close()
            }
            // Skia touched GL; tell it the outside world (our composite next) will too.
            c.resetGLAll()

            // 2) Composite the offscreen texture onto MC's FBO as ONE SrcOver quad.
            //    Only non-transparent overlay pixels blend over MC's frame. Y-flip is
            //    handled in the quad UVs (SKIA_TOP_AT_HIGH_GLY, per the Phase-1 harness).
            compositor.composite(offscreenTex, mcFbId, fbW, fbH)
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

