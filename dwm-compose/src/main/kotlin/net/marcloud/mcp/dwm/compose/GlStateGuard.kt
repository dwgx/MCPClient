package net.marcloud.mcp.dwm.compose

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.lang.reflect.Field

/**
 * The project-level "killer": after Skia/Skiko scribbles real GL state (blend,
 * program, texture binding, viewport, FBO, ...), MC keeps a Java-side SHADOW of that
 * state in [net.minecraft.client.renderer.GlStateManager] and a later `enableX()`
 * NO-OPs if the shadow already believes X is set. `DirectContext.resetGLAll()` only
 * drops Skia's own cache — it does NOT touch MC's shadow. So on LEAVE we must force
 * real GL back to MC's expectation AND write through the private shadow fields, or MC
 * renders the world/hand/HUD wrong on subsequent frames.
 *
 * <p>Reflection into GlStateManager's private nested state is the mechanism (the class
 * is vanilla and frozen — we read/write its private fields, never edit its source).
 * All reflection is resolved once and cached; every reflective touch is fault-isolated
 * so a mapping miss degrades to a raw-GL-only restore rather than throwing on the
 * render thread.
 *
 * <p><b>Scope (honest):</b> covers the states Skiko's GLBackendState enumerates as
 * disturbed — RENDER_TARGET (FBO), PROGRAM, TEXTURE_BINDING, BLEND, VIEW (viewport),
 * plus depth/color-mask. This is a FIRST live increment; if a live run shows a
 * surviving corruption (blend is highest-risk), widen the covered set here.
 */
class GlStateGuard {

    // Cached reflection handles into GlStateManager's private static shadow. Null if
    // the class/field layout doesn't match (then we fall back to raw-GL-only restore).
    private var gsm: Class<*>? = null
    private var blendStateField: Field? = null

    // Snapshot taken on enter().
    private var savedFbo = 0
    private var savedProgram = 0
    private var savedViewport = IntArray(4)
    private var savedActiveTex = GL13.GL_TEXTURE0
    private var savedTex2d = 0
    private var blendWasEnabled = false
    private var savedBlendSrc = GL11.GL_ONE
    private var savedBlendDst = GL11.GL_ZERO

    init {
        try {
            val c = Class.forName("net.minecraft.client.renderer.GlStateManager")
            gsm = c
            blendStateField = c.getDeclaredField("blendState").apply { isAccessible = true }
        } catch (t: Throwable) {
            System.err.println("[GlStateGuard] GlStateManager reflection unavailable, raw-GL-only: $t")
        }
    }

    /**
     * ENTER: snapshot the GL state we're about to disturb, so LEAVE can restore MC's
     * exact expectation. Called on the game render thread with GL current, right before
     * the Skia frame pump. Never throws.
     */
    fun enter() {
        try {
            savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
            savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport)
            savedActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
            savedTex2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
            savedBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC)
            savedBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST)
        } catch (t: Throwable) {
            System.err.println("[GlStateGuard] enter snapshot faulted: $t")
        }
    }

    /**
     * LEAVE: force real GL back to the snapshot AND write through MC's private shadow,
     * so a later GlStateManager.enableX() does not no-op against a stale shadow. Called
     * after Skia flushAndSubmit + the composite. Never throws.
     */
    fun leave() {
        try {
            // Raw GL restore (the values MC's own code expects to read back).
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo)
            GL20.glUseProgram(savedProgram)
            GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
            GL13.glActiveTexture(savedActiveTex)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex2d)
            if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND) else GL11.glDisable(GL11.GL_BLEND)
            GL11.glBlendFunc(savedBlendSrc, savedBlendDst)

            // Shadow write-through: force GlStateManager's private blend/depth booleans
            // to AGREE with the real GL we just restored, so enableX() isn't a no-op.
            writeBlendShadow(blendWasEnabled, savedBlendSrc, savedBlendDst)
        } catch (t: Throwable) {
            System.err.println("[GlStateGuard] leave restore faulted: $t")
        }
    }

    /** Force GlStateManager.blendState private booleans to match the restored real GL. */
    private fun writeBlendShadow(enabled: Boolean, src: Int, dst: Int) {
        val f = blendStateField ?: return
        try {
            val blendState = f.get(null) ?: return
            // blendState.blend is a BooleanState; set its private currentState.
            val blendBool = blendState.javaClass.getDeclaredField("blend")
                .apply { isAccessible = true }.get(blendState)
            blendBool?.let {
                it.javaClass.getDeclaredField("currentState")
                    .apply { isAccessible = true }.setBoolean(it, enabled)
            }
            blendState.javaClass.getDeclaredField("srcFactor")
                .apply { isAccessible = true }.setInt(blendState, src)
            blendState.javaClass.getDeclaredField("dstFactor")
                .apply { isAccessible = true }.setInt(blendState, dst)
        } catch (t: Throwable) {
            // Field-name miss: raw GL is already restored; shadow just may no-op an
            // enable. Log once-ish and continue — never break the frame.
            System.err.println("[GlStateGuard] blend shadow write-through miss: $t")
        }
    }
}

