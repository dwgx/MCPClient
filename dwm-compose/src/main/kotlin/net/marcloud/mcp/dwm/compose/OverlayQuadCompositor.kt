package net.marcloud.mcp.dwm.compose

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30

/**
 * Composites the overlay's offscreen color texture onto MC's framebuffer as ONE
 * SrcOver-blended full-screen quad. This is the topology the design brief mandates:
 * Compose renders into its OWN FBO, then only this single textured quad touches MC's
 * FBO — so transparent overlay pixels blend cleanly over MC's frame instead of a full
 * drawImage clobbering it to black.
 *
 * <p>MC 1.8.9 runs a GL compat profile (fixed-function), so this uses immediate-mode
 * texturing (glBegin/glTexCoord/glVertex) in a pixel-space ortho projection — no
 * shaders. All GL is on the render thread with the context current. State it changes
 * (blend, texture enable, matrices) is restored by the surrounding [GlStateGuard]; this
 * class saves/restores its own matrix stack locally to be self-contained.
 *
 * <p>Y-flip: the Phase-1 harness measured SKIA_TOP_AT_HIGH_GLY — Skia's top-left
 * content lands at high glY in the texture — so sampling with V=1 at the top of the
 * screen (ortho with y-down) shows the overlay right-side-up. The quad UVs below encode
 * that (top edge samples v=1, bottom edge v=0).
 */
class OverlayQuadCompositor {

    fun init() { /* immediate-mode: nothing to allocate */ }
    fun dispose() { /* nothing owned */ }

    /**
     * Draw [tex] over MC's framebuffer [mcFbo], sized [w]x[h], with SrcOver blend.
     * Fault-isolated by the caller; keeps its own matrix push/pop so it does not leak
     * projection/modelview changes.
     */
    fun composite(tex: Int, mcFbo: Int, w: Int, h: Int) {
        if (tex == 0) return
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mcFbo)
        GL11.glViewport(0, 0, w, h)

        // Pixel-space ortho (0,0 top-left .. w,h bottom-right), own matrix scope.
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, w.toDouble(), h.toDouble(), 0.0, -1.0, 1.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()

        // SrcOver: overlay alpha over MC. Depth off so the 2D quad always shows.
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glColor4f(1f, 1f, 1f, 1f)

        val fw = w.toFloat()
        val fh = h.toFloat()
        GL11.glBegin(GL11.GL_QUADS)
        // screen (x,y ortho y-down) with UV; top edge v=1, bottom v=0 (SKIA_TOP_AT_HIGH_GLY).
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, 0f)    // top-left
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(fw, 0f)    // top-right
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(fw, fh)    // bottom-right
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, fh)    // bottom-left
        GL11.glEnd()

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPopMatrix()
    }
}
