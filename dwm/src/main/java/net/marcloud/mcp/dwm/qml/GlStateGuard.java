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

    /**
     * Which generic vertex attribute arrays were enabled on entry.
     *
     * <p>The same family of leak as the buffer bindings above, found by measuring rather than by
     * waiting for it to crash: a probe read the enable bits before and after one real UI frame and
     * saw {@code []} become {@code [0 1]}. Skia turns on the attribute arrays its shaders use and
     * leaves them on, and — like the buffer bindings — they are outside {@code glPushAttrib}'s
     * server-state groups, so the pop cannot undo them.
     *
     * <p>Why it matters even though MC never uses generic attributes: on a compatibility profile the
     * fixed-function pointers and the generic attribute arrays feed the SAME vertex puller. An
     * attribute array left enabled still has Skia's stale pointer and stride attached, so the driver
     * keeps fetching from it on the game's next {@code glDrawArrays} — reading memory the game never
     * offered. That is precisely the shape of the crash already fixed here, which is reason enough
     * not to leave it to luck.
     *
     * <p>Sized to the context's real {@code GL_MAX_VERTEX_ATTRIBS}, resolved once, because the
     * minimum guaranteed 16 is not necessarily what the driver reports.
     */
    private static boolean[] hadAttribArray;

    /**
     * The pixel-unpack alignment on entry. Third member of the same family, also measured: Skia
     * leaves it at 1 where the default is 4.
     *
     * <p><b>Benign today, and restored anyway.</b> The docs are explicit that pixel store state
     * cannot be saved on the attribute stack, so {@code glPopAttrib} will never cover it. It happens
     * not to matter for this client: every texture MC uploads is 4 bytes per pixel, and a probe
     * confirmed that uploading identical data at alignment 1 and at 4 reads back byte-for-byte the
     * same, because a row of 4-byte pixels needs no padding under either. The only place vanilla
     * touches pixel store at all is {@code ScreenShotHelper}, which sets what it needs explicitly.
     *
     * <p>It is restored because this is the third leak found in the class of state
     * {@code glPushAttrib} does not reach, and the first two of those crashed the client. "Harmless
     * given what the host happens to upload today" is a property of the host, not of this guard, and
     * two lines is a poor trade against re-deriving that argument the next time something changes.
     */
    private static int hadUnpackAlignment;
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
            hadUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            if (hadAttribArray == null) {
                // Once per run: the count cannot change for a live context, and querying it every
                // frame would be a driver round-trip for a constant.
                hadAttribArray = new boolean[Math.max(0,
                        GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS))];
            }
            for (int i = 0; i < hadAttribArray.length; i++) {
                hadAttribArray[i] = GL20.glGetVertexAttribi(
                        i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0;
            }
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

            // AFTER the push, so glPopAttrib in leave() undoes it: give Skia a clean alpha test.
            //
            // MC draws its GUI with GL_ALPHA_TEST enabled at GL_GREATER 0.1, and that is inherited
            // by whatever draws next -- so every Skia fragment with alpha <= 25 (0.1 * 255) was
            // being DISCARDED BY THE GPU. Measured on a live client with an alpha ladder: 13, 16,
            // 18, 20, 22 and 24 all left the destination untouched while 26 and 30 composited
            // normally. That is the whole of the "a settings card's plate is invisible" bug, and it
            // explains why the card's TEXT was fine -- text is alpha 255 and 197, far above the
            // cutoff -- while its #0DFFFFFF face (alpha 13) never appeared.
            //
            // Skia expresses translucency through blending, never through the fixed-function alpha
            // test, so disabling it removes a constraint Skia does not use and cannot see. It is
            // also why no headless test could catch this: a bare GLFW window never enables the
            // alpha test, and painting into a CPU raster surface does not go through GL at all.
            //
            // This is the fifth member of the family documented at the top of this file: state that
            // MC leaves set and that glPushAttrib alone does not make safe for a foreign renderer.
            GL11.glDisable(GL11.GL_ALPHA_TEST);
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

            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, hadUnpackAlignment);

            // The attribute array enables, restored after the buffer bindings so a re-enable cannot
            // pick up Skia's binding. Measured leak: [] in, [0 1] out.
            if (hadAttribArray != null) {
                for (int i = 0; i < hadAttribArray.length; i++) {
                    if (hadAttribArray[i]) {
                        GL20.glEnableVertexAttribArray(i);
                    } else {
                        GL20.glDisableVertexAttribArray(i);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[dwm] GlStateGuard.leave faulted: " + t);
        }
    }
}
