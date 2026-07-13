package net.marcloud.mcp.dwm.compose

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Phase-1 offscreen GL harness (standalone, NO Minecraft): creates its own hidden
 * GLFW window + GL context, stands Skia up on a self-owned offscreen FBO, draws a
 * Y-asymmetric 2-color pattern through the Skia canvas, reads pixels back, and
 * asserts. The honest Phase-1 boundary marker.
 *
 * PROVES (in isolation): Skiko native loads and GL-binds under JBR25
 * (DirectContext.makeGL on a real current context); the offscreen FBO round-trip
 * (Skia renders into an RGBA8 FBO, pixels read back correctly); the Skia-origin ↔
 * FBO-readback Y-orientation (the UV convention a future composite quad needs); and
 * a minimal GlStateGuard FBO-binding save/restore.
 *
 * Does NOT prove (still live-only, Phase-2): MC's GlStateManager shadow write-through
 * (the actual killer — no MC here), orientation vs MC's presented framebuffer, the
 * render-frame hook, or Skiko-alongside-live-MC+DCEVM.
 */
class OffscreenGlHarnessTest {

    companion object {
        private const val W = 64
        private const val H = 48

        @BeforeClass
        @JvmStatic
        fun forceGlRenderApi() {
            // Ensure Skiko picks OpenGL, not D3D/ANGLE, before its native loads.
            System.setProperty("skiko.renderApi", "OPENGL")
        }
    }

    @Test
    fun offscreenFboRoundTripAndYOrientation() {
        GLFWErrorCallback.createPrint(System.err).set()
        assertTrue("glfwInit", GLFW.glfwInit())
        var win = 0L
        var ctx: DirectContext? = null
        var rt: BackendRenderTarget? = null
        var surface: Surface? = null
        var colorTex = 0
        var stencilRb = 0
        var fbo = 0
        var readback: ByteBuffer? = null
        try {
            // --- hidden GLFW window + GL context (parity with the client's context) ---
            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE)
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            win = GLFW.glfwCreateWindow(W, H, "phase1-harness", MemoryUtil.NULL, MemoryUtil.NULL)
            assertTrue("glfwCreateWindow", win != 0L)
            GLFW.glfwMakeContextCurrent(win)
            GL.createCapabilities()

            // --- self-owned offscreen FBO (RGBA8 color + stencil) ---
            colorTex = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            stencilRb = GL30.glGenRenderbuffers()
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, stencilRb)
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_STENCIL_INDEX8, W, H)
            fbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorTex, 0)
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                GL30.GL_RENDERBUFFER, stencilRb)
            assertEquals("FBO complete", GL30.GL_FRAMEBUFFER_COMPLETE,
                GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER))

            // --- GlStateGuard ENTER (raw-GL half): snapshot binding, reset Skia, bind FBO ---
            val savedBinding = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)

            // --- Skia on the FBO. makeGL alone proves Skiko native loaded + GL-bound. ---
            ctx = DirectContext.makeGL()
            assertNotNull("DirectContext.makeGL", ctx)
            ctx!!.resetGLAll()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            rt = BackendRenderTarget.makeGL(W, H, 0, 8, fbo, GL11.GL_RGBA8)
            surface = Surface.makeFromBackendRenderTarget(
                ctx, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)
            assertNotNull("Surface.makeFromBackendRenderTarget (null = fbFormat mismatch)", surface)

            // --- draw: blue fill, red TOP-LEFT quadrant in Skia coords (origin top-left) ---
            val canvas = surface!!.canvas
            canvas.clear(0xFF0000FF.toInt()) // blue (ARGB)
            val paint = Paint()
            paint.color = 0xFFFF0000.toInt() // red
            canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(0f, 0f, (W / 2).toFloat(), (H / 2).toFloat()), paint)
            surface!!.flushAndSubmit()
            GL11.glFinish()

            // --- read back (glReadPixels origin = bottom-left, row 0 = bottom) ---
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0)
            readback = MemoryUtil.memAlloc(W * H * 4)
            GL11.glReadPixels(0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback)

            // --- Y-flip probes: left column (X unambiguous), one bottom + one top row ---
            val xProbe = W / 4
            val bottomRed = isRed(readback!!, xProbe, 2)
            val topRed = isRed(readback!!, xProbe, H - 3)
            // Exactly one probe must be red — the asymmetry resolves orientation.
            assertTrue("exactly one of top/bottom is red (Y-asymmetry resolved)", bottomRed != topRed)
            val orientation = if (topRed) "SKIA_TOP_AT_HIGH_GLY (no extra V-flip when sampling as GL tex)"
                              else "SKIA_TOP_AT_LOW_GLY (composite quad must flip V)"
            println("[Phase1 GL harness] Y-orientation: $orientation")

            // --- GlStateGuard LEAVE: restore the FBO binding to what ENTER saw ---
            // NOTE: a GLFW window's default framebuffer binding is driver-dependent
            // (observed 1 here, not 0), which is exactly why the guard must RESTORE
            // the saved binding rather than assume 0 — the real MC leave has the same
            // obligation against MC's target FBO.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedBinding)
            assertEquals("guard LEAVE restored the pre-ENTER framebuffer binding",
                savedBinding, GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING))
        } finally {
            surface?.close()
            rt?.close()
            ctx?.close()
            readback?.let { MemoryUtil.memFree(it) }
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo)
            if (stencilRb != 0) GL30.glDeleteRenderbuffers(stencilRb)
            if (colorTex != 0) GL11.glDeleteTextures(colorTex)
            if (win != 0L) {
                GLFW.glfwMakeContextCurrent(MemoryUtil.NULL)
                GLFW.glfwDestroyWindow(win)
            }
            GLFW.glfwTerminate()
            GLFW.glfwSetErrorCallback(null)?.free()
        }
    }

    /** True if the RGBA pixel at (x, glY) in the bottom-left-origin readback is red. */
    private fun isRed(buf: ByteBuffer, x: Int, glY: Int): Boolean {
        val off = (glY * W + x) * 4
        val r = buf.get(off).toInt() and 0xFF
        val g = buf.get(off + 1).toInt() and 0xFF
        val b = buf.get(off + 2).toInt() and 0xFF
        return r > 200 && g < 60 && b < 60
    }
}

