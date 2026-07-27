package net.marcloud.mcp.dwm.qml;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Brackets a Skija frame so MC keeps rendering afterwards.
 *
 * <p>The problem is not GL state as such — it is that MC does not read GL state, it
 * <i>shadows</i> it. {@code GlStateManager} keeps its own mirror of blend, alpha, depth, texture
 * and colour, and skips redundant GL calls based on that mirror. Skija issues raw GL directly, so
 * after a Skija frame the real GL state and MC's mirror can disagree, and MC will not correct the
 * difference because as far as its mirror is concerned nothing changed.
 *
 * <p><b>Restore what was there, never a hardcoded state.</b> An earlier version of this class
 * ended by asserting a fixed configuration through {@code GlStateManager} — texture on, alpha on,
 * blend on, white colour. That was measurably wrong: on a real qml4j frame it left
 * {@code GL_ALPHA_TEST} <em>enabled</em> when it had been disabled going in, because
 * {@code glPopAttrib} had already restored the correct state and the re-assert then overwrote it.
 * So {@link #enter()} reads the values it will need and {@link #leave()} drives
 * {@code GlStateManager} to exactly those, which leaves both real GL and the shadow agreeing with
 * what the caller had.
 *
 * <p>{@code glPushAttrib}/{@code glPopAttrib} are verified to work on Apple's GL 2.1-on-Metal:
 * a probe on macOS 26.5 / M2 confirmed enable bits, blend function, current colour and viewport all
 * survive a push/pop pair with {@code glGetError} clean. The pair does <em>not</em> save matrix
 * stacks, which is why those are pushed separately.
 *
 * <p>Both methods are called on the render thread with MC's context current, and neither throws:
 * a GL fault here would otherwise take down the game loop.
 */
final class GlStateGuard {

    /** Captured at enter(), re-applied through GlStateManager at leave(). */
    private static boolean hadTexture2D;
    private static boolean hadBlend;
    private static boolean hadAlpha;
    private static boolean hadDepth;
    private static boolean hadCull;
    private static int hadBlendSrc;
    private static int hadBlendDst;
    private static int hadTexture;
    /**
     * The draw framebuffer and shader program in force on entry.
     *
     * <p>Saved by hand because {@code glPushAttrib} does not cover them. It saves only
     * <em>stackable</em> server state — the fixed-function attribute groups of GL 1.1 — and both
     * framebuffer objects and the programmable pipeline postdate that stack entirely, so neither
     * has an attribute group to belong to. Skija binds its own FBO and program and leaves both in
     * place, which is a leak the pop cannot undo: the game would then draw into Skia's target with
     * Skia's shader, i.e. render nothing visible at all.
     */
    private static int hadFramebuffer;
    private static int hadProgram;

    /**
     * The buffer bindings in force on entry — and the reason the game survives a UI frame at all.
     *
     * <p>MC 1.8.9 draws the world through CLIENT-SIDE vertex arrays:
     * {@code WorldVertexBufferUploader.draw} calls {@code glVertexPointer(..., byteBuffer)} with a
     * pointer into Java memory, then {@code glDrawArrays}. In OpenGL those two calls mean completely
     * different things depending on whether an {@code ARRAY_BUFFER} is bound: with no binding the
     * pointer is an address, and with one bound it is reinterpreted as a byte OFFSET INTO THAT
     * BUFFER. Skija binds its own buffers and leaves them bound, so the game's next chunk upload
     * hands a heap address to the driver as an offset and dereferences somewhere arbitrary.
     *
     * <p>That is not a theory. Leaving these unrestored crashed the client in
     * {@code gleRunVertexSubmitImmediate} — Apple's GL vertex submit path — with SIGSEGV, reached
     * through {@code glDrawArrays} from {@code WorldVertexBufferUploader.draw}, the moment the UI
     * was opened over a live world. It survived eight minutes of ordinary play beforehand.
     *
     * <p>{@code glPushAttrib} cannot help here for the same reason it cannot save the framebuffer:
     * buffer bindings are not part of the fixed-function attribute stack. Nor does
     * {@code glPushClientAttrib} cover them — it covers client array POINTERS and enables, not the
     * binding that changes their meaning.
     *
     * <p><b>No vertex array object is saved</b>, deliberately. A first attempt restored one too and
     * that aborted the JVM outright: {@code glBindVertexArray} is GL 3.0, and on Apple's GL 2.1
     * compatibility profile LWJGL raises a fatal "No context is current or a function that is not
     * available in the current context was called" — a {@code jni_FatalError}, past any Java
     * handler. VAOs do not exist in a 2.1 context, so there is no binding to restore.
     */
    private static int hadArrayBuffer;
    private static int hadElementBuffer;
    private static final float[] HAD_COLOUR = new float[4];
    private static final FloatBuffer COLOUR_BUF = BufferUtils.createFloatBuffer(16);

    private GlStateGuard() {
    }

    /**
     * Save the state Skija is about to disturb. Pairs with exactly one {@link #leave()}.
     *
     * <p>{@code glPushAttrib} covers the fixed-function state MC's 2.1-era pipeline uses, and the
     * matrix pushes cover the transform stacks Skia sets up for its own projection. The reads on
     * top of that exist so leave() can re-sync MC's shadow to real values rather than guesses.
     */
    static void enter() {
        try {
            hadTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            hadBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            hadAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            hadDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            hadCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            hadBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
            hadBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
            hadTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            // Outside the attribute stack — see the field docs. Read before the push so leave()
            // can restore them after the pop has done what it can.
            hadFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            hadProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            hadArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            hadElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            COLOUR_BUF.clear();
            GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, COLOUR_BUF);
            for (int i = 0; i < 4; i++) {
                HAD_COLOUR[i] = COLOUR_BUF.get(i);
            }

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
        } catch (Throwable t) {
            System.err.println("[dwm] GlStateGuard.enter faulted: " + t);
        }
    }

    /**
     * Restore the state and re-sync MC's shadow of it.
     *
     * <p>{@code glPopAttrib} fixes real GL; the {@code GlStateManager} calls after it exist only so
     * MC's mirror agrees, and they replay the values captured in {@link #enter()} rather than any
     * assumed configuration.
     */
    static void leave() {
        try {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopAttrib();

            // Drive the shadow to the captured reality. Each of these is a no-op in GL terms when
            // popAttrib already got it right; the point is that GlStateManager's mirror updates.
            if (hadTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (hadBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (hadAlpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            if (hadDepth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            if (hadCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            GlStateManager.blendFunc(hadBlendSrc, hadBlendDst);
            GlStateManager.bindTexture(hadTexture);
            GlStateManager.color(HAD_COLOUR[0], HAD_COLOUR[1], HAD_COLOUR[2], HAD_COLOUR[3]);

            // Restored by hand, straight through GL: popAttrib cannot touch either, and
            // GlStateManager shadows neither. Skija leaves its own FBO and program bound, so
            // without these the game's next draw goes into Skia's render target through Skia's
            // shader — the whole frame silently disappears.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, hadFramebuffer);
            GL20.glUseProgram(hadProgram);

            // The buffer bindings, and this is the one that decides whether the game survives:
            // MC's world draw passes a Java heap pointer to glVertexPointer, which the driver
            // reinterprets as an offset into whatever ARRAY_BUFFER happens to be bound. Skija
            // leaves its own bound, so not clearing it makes the next chunk upload dereference a
            // heap address as a buffer offset — a hard SIGSEGV inside Apple's vertex submit path,
            // not a Java exception anything could catch.
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, hadArrayBuffer);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, hadElementBuffer);
        } catch (Throwable t) {
            System.err.println("[dwm] GlStateGuard.leave faulted: " + t);
        }
    }
}
