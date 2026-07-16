package net.marcloud.mcp.dwm.gl;

import java.lang.reflect.Field;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The project-level "killer", now fully implemented in Java: after the overlay's raw
 * OpenGL calls disturb GL state (blend, program, texture binding, active unit,
 * viewport, FBO), Minecraft 1.8.9 keeps a Java-side SHADOW of that state in
 * {@code net.minecraft.client.renderer.GlStateManager}. A later {@code enableX()} /
 * {@code bindTexture()} / {@code color()} NO-OPs if the shadow already believes it is in
 * the target state (see {@code BooleanState.setState}, {@code bindTexture},
 * {@code setActiveTexture} — all early-return on "already there"). So on LEAVE we must
 * BOTH restore real GL to MC's expectation AND write through the private shadow fields,
 * or MC renders the world/hand/HUD wrong on subsequent frames — the classic symptom
 * being the whole frame going black because the shadow disagrees with real GL and MC's
 * own framebuffer blit draws against a poisoned state.
 *
 * <p><b>Why the previous (blend-only) guard black-screened.</b> The Kotlin
 * {@code dwm-compose} guard wrote back ONLY the blend shadow, leaving the texture-unit,
 * texture-binding, and depth shadows corrupted and never issuing {@code glUseProgram(0)}
 * (Skia leaves a program bound; MC 1.8.9 is fixed-function and expects program 0). This
 * guard closes that gap: it snapshots on {@link #enter()} and, on {@link #leave()}, both
 * restores raw GL and force-writes every shadow field MC could no-op against.
 *
 * <p><b>Mechanism.</b> Reflection into {@code GlStateManager}'s private static nested
 * state is the only way to write the shadow without editing the frozen vanilla class
 * (we read/write its private fields, never touch its source). All reflection handles are
 * resolved once in the constructor and cached; every reflective touch is fault-isolated
 * so a mapping miss degrades to a raw-GL-only restore rather than throwing on the render
 * thread. The field names below are verified verbatim against the client's
 * {@code GlStateManager} source (BlendState.{blend,srcFactor,dstFactor},
 * DepthState.{depthTest,maskEnabled,depthFunc}, TextureState.{texture2DState,textureName},
 * BooleanState.currentState, static {@code activeTextureUnit} + {@code textureState[]},
 * and {@code activeTextureUnit = rawGlEnum - OpenGlHelper.defaultTexUnit}).
 */
public final class GlStateGuard {

    // ---- cached reflection handles into GlStateManager's private static shadow ----
    // Null if the class/field layout does not match (then we fall back to raw-GL-only).
    private Field blendStateField;   // static BlendState blendState
    private Field depthStateField;   // static DepthState depthState
    private Field textureStateArr;   // static TextureState[] textureState
    private Field activeUnitField;   // static int activeTextureUnit

    private Field blend_blend;       // BlendState.blend (BooleanState)
    private Field blend_src;         // BlendState.srcFactor
    private Field blend_dst;         // BlendState.dstFactor
    private Field depth_test;        // DepthState.depthTest (BooleanState)
    private Field depth_mask;        // DepthState.maskEnabled
    private Field depth_func;        // DepthState.depthFunc
    private Field tex_2dState;       // TextureState.texture2DState (BooleanState)
    private Field tex_name;          // TextureState.textureName
    private Field bool_current;      // BooleanState.currentState
    private Field colorStateField;   // static Color colorState
    private Field color_r;           // Color.red
    private Field color_g;           // Color.green
    private Field color_b;           // Color.blue
    private Field color_a;           // Color.alpha
    private Field cullStateField;    // static CullState cullState
    private Field cull_cullFace;     // CullState.cullFace (BooleanState)
    private Field alphaStateField;   // static AlphaState alphaState
    private Field alpha_alphaTest;   // AlphaState.alphaTest (BooleanState)
    private Field colorMaskField;    // static ColorMask colorMaskState
    private Field cmask_r;           // ColorMask.red
    private Field cmask_g;           // ColorMask.green
    private Field cmask_b;           // ColorMask.blue
    private Field cmask_a;           // ColorMask.alpha

    /** GL_TEXTURE0 (33984): OpenGlHelper.defaultTexUnit is this on the fixed pipeline. */
    private static final int GL_TEXTURE0 = GL13.GL_TEXTURE0;

    // ---- snapshot taken on enter() ----
    private int savedFbo;
    private int savedProgram;
    private final int[] savedViewport = new int[4];
    private int savedActiveTex = GL_TEXTURE0;
    private int savedTex2d;
    private boolean blendWasEnabled;
    private int savedBlendSrc = GL11.GL_ONE;
    private int savedBlendDst = GL11.GL_ZERO;
    private boolean depthWasEnabled;
    private boolean depthMask = true;
    private int savedDepthFunc = GL11.GL_LEQUAL;
    private boolean tex2dWasEnabled = true;
    private boolean cullWasEnabled = true;
    private boolean alphaTestWasEnabled;
    private boolean stencilWasEnabled;
    private final boolean[] savedColorMask = {true, true, true, true};
    private float savedLineWidth = 1f;
    // Scissor is raw GL only (MC does NOT shadow GL_SCISSOR_TEST / GL_SCISSOR_BOX). A
    // native backend (Skia) can leave scissor ENABLED with a small box; MC's next
    // full-screen framebuffer blit is then clipped to that box → the whole frame goes
    // black. The gl backend self-disables scissor in GlDrawContext.reset() and imgui's
    // renderDrawData restores it, but skiko touches scissor nowhere and relies entirely
    // on this guard — so the guard MUST snapshot + raw-restore it.
    private boolean scissorWasEnabled;
    private final int[] savedScissorBox = new int[4];
    // Vertex/element buffer bindings (raw GL; MC does NOT shadow these). THE black-screen
    // cause for a native GL backend: Skia binds its own VBO (GL_ARRAY_BUFFER) + IBO
    // (GL_ELEMENT_ARRAY_BUFFER) and leaves them bound. MC 1.8.9's Framebuffer.framebufferRender
    // draws its offscreen texture to the screen with Tessellator.draw() using CLIENT-MEMORY
    // vertex arrays (glVertexPointer into RAM). If a non-zero GL_ARRAY_BUFFER is bound at that
    // moment, glVertexPointer is reinterpreted as a byte offset INTO that VBO instead of a RAM
    // pointer → the full-screen quad reads garbage → the framebuffer blit-quad is invisible →
    // the whole screen is black even though MC rendered the world into its FBO correctly.
    private int savedArrayBuffer;
    private int savedElementBuffer;
    // GL_MAX_VERTEX_ATTRIBS, resolved lazily on the first enter() (needs a live context).
    // Skia leaves generic vertex-attrib arrays enabled; leave() disables all of them so MC's
    // fixed-function glVertexPointer draw is not aliased by a stray enabled attrib array.
    private int maxVertexAttribs = 16;
    private boolean maxAttribsResolved;
    // Fixed-function matrix stacks (raw GL; GlStateManager operates GL matrices directly,
    // NOT via a shadow). Skia's makeGL/flush leaves the MODELVIEW + PROJECTION matrices in
    // its own state; MC then draws e.g. the main-menu splash with GlStateManager.translate/
    // rotate/scale (RELATIVE to the current matrix), so a Skia-poisoned base matrix throws
    // the splash to the screen edge in a rotated/stacked mess. Snapshot both matrices +
    // the active matrix mode at enter(), load them back at leave().
    private final java.nio.FloatBuffer savedModelview = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private final java.nio.FloatBuffer savedProjection = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private int savedMatrixMode = GL11.GL_MODELVIEW;
    // Track which push()es succeeded in enter() so leave() only pops what was pushed (a
    // push that faulted must not be popped — that would underflow the stack).
    private boolean pushedAttrib;
    private boolean pushedClientAttrib;
    private boolean pushedMatrices;

    public GlStateGuard() {
        try {
            Class<?> gsm = Class.forName("net.minecraft.client.renderer.GlStateManager");
            blendStateField = declared(gsm, "blendState");
            depthStateField = declared(gsm, "depthState");
            textureStateArr = declared(gsm, "textureState");
            activeUnitField = declared(gsm, "activeTextureUnit");

            Class<?> blendState = Class.forName("net.minecraft.client.renderer.GlStateManager$BlendState");
            blend_blend = declared(blendState, "blend");
            blend_src = declared(blendState, "srcFactor");
            blend_dst = declared(blendState, "dstFactor");

            Class<?> depthState = Class.forName("net.minecraft.client.renderer.GlStateManager$DepthState");
            depth_test = declared(depthState, "depthTest");
            depth_mask = declared(depthState, "maskEnabled");
            depth_func = declared(depthState, "depthFunc");

            Class<?> texState = Class.forName("net.minecraft.client.renderer.GlStateManager$TextureState");
            tex_2dState = declared(texState, "texture2DState");
            tex_name = declared(texState, "textureName");

            Class<?> boolState = Class.forName("net.minecraft.client.renderer.GlStateManager$BooleanState");
            bool_current = declared(boolState, "currentState");

            Class<?> colorClass = Class.forName("net.minecraft.client.renderer.GlStateManager$Color");
            colorStateField = declared(gsm, "colorState");
            color_r = declared(colorClass, "red");
            color_g = declared(colorClass, "green");
            color_b = declared(colorClass, "blue");
            color_a = declared(colorClass, "alpha");

            Class<?> cullClass = Class.forName("net.minecraft.client.renderer.GlStateManager$CullState");
            cullStateField = declared(gsm, "cullState");
            cull_cullFace = declared(cullClass, "cullFace");

            Class<?> alphaClass = Class.forName("net.minecraft.client.renderer.GlStateManager$AlphaState");
            alphaStateField = declared(gsm, "alphaState");
            alpha_alphaTest = declared(alphaClass, "alphaTest");

            Class<?> colorMaskClass = Class.forName("net.minecraft.client.renderer.GlStateManager$ColorMask");
            colorMaskField = declared(gsm, "colorMaskState");
            cmask_r = declared(colorMaskClass, "red");
            cmask_g = declared(colorMaskClass, "green");
            cmask_b = declared(colorMaskClass, "blue");
            cmask_a = declared(colorMaskClass, "alpha");
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] GlStateManager reflection unavailable, raw-GL-only restore: " + t);
        }
    }

    private static Field declared(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * ENTER: snapshot the GL state we are about to disturb, so LEAVE can restore MC's
     * exact expectation. Called on the game render thread with GL current, right before
     * the overlay draws. Never throws.
     */
    public void enter() {
        try {
            // ROBUST BRACKET (primary defense): save the ENTIRE fixed-function server +
            // client state as one atomic push, plus both matrix stacks. This is the correct
            // industry-standard way to isolate a native GL library (Skia/imgui) from MC's
            // fixed-function pipeline — one mechanism instead of hand-enumerating every field
            // and playing whack-a-mole. glPopAttrib/glPopClientAttrib/glPopMatrix in leave()
            // restore blend/depth/scissor/cull/alpha/color/texture-enables/line-width/…,
            // buffer + vertex-array client state, and the MODELVIEW/PROJECTION matrices in
            // one shot. The hand-enumerated snapshots below are KEPT because they feed the
            // GlStateManager SHADOW write-through (glPushAttrib restores real GL, but MC's
            // Java-side shadow cache must be re-synced separately or MC's next enableX()
            // no-ops against a stale shadow). Verified available (err=0) on the compat context.
            pushedAttrib = false;
            pushedClientAttrib = false;
            pushedMatrices = false;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            pushedAttrib = true;
            GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS);
            pushedClientAttrib = true;
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            pushedMatrices = true;

            savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
            savedActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            savedTex2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
            savedBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
            savedBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
            depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            savedDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            // GL_TEXTURE_2D enable is per active texture unit; MC shadows it as
            // textureState[unit].texture2DState. An overlay that toggles it (a solid quad
            // disables texturing) must restore BOTH real GL and that shadow, or MC's next
            // enableTexture2D() no-ops against a stale "on" shadow and the world renders
            // untextured (white). Snapshotted for the active unit observed here.
            tex2dWasEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            // Cull + alpha-test enables are MC shadow fields too. An overlay that toggles
            // them (a 2D panel disables cull so its winding is not discarded, and disables
            // alpha-test so translucent pixels are not clipped) must restore BOTH real GL
            // and the shadow, or MC's next enableCull()/enableAlpha() no-ops.
            cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            alphaTestWasEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            // Skia clips via the stencil buffer (stencil-8 FBO) and toggles the color
            // mask for AA/coverage. MC shadows colorMask (colorMask() early-returns on
            // match), so a stale colorMask shadow is the same no-op-against-stale-shadow
            // bug class as blend/depth. Stencil enable MC does NOT shadow → raw restore.
            stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            readColorMask(savedColorMask);
            savedLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
            // Scissor enable + box (raw GL; the skiko black-screen fix). If Skia leaves a
            // small scissor box enabled, MC's next full-screen blit is clipped → black.
            scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
            // Vertex + element array buffer bindings (raw GL). MC's Tessellator uses
            // client-memory arrays, so it expects binding 0; Skia leaves its own VBO/IBO
            // bound. Snapshot to restore MC's expectation on leave().
            savedArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            savedElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            if (!maxAttribsResolved) {
                int m = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
                if (m > 0) {
                    maxVertexAttribs = m;
                }
                maxAttribsResolved = true;
            }
            // Fixed-function matrix stacks: Skia leaves MODELVIEW/PROJECTION in its own
            // state; snapshot both matrices' top + the active mode so leave() can load them
            // back (the main-menu splash draws relative to the current matrix).
            savedMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            savedModelview.clear();
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, savedModelview);
            savedProjection.clear();
            GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, savedProjection);
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] enter snapshot faulted: " + t);
        }
    }

    /** Read the 4-bool GL color write-mask into {@code out} (RGBA). */
    private static void readColorMask(boolean[] out) {
        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, buf);
        out[0] = buf.get(0) != 0;
        out[1] = buf.get(1) != 0;
        out[2] = buf.get(2) != 0;
        out[3] = buf.get(3) != 0;
    }

    /**
     * LEAVE: force real GL back to the snapshot AND write through MC's private shadow so
     * a later GlStateManager call does not no-op against a stale shadow. Called after the
     * overlay's draw + composite. Never throws.
     */
    public void leave() {
        // 0) ROBUST BRACKET pop (primary restore): pop the matrix stacks first, then client
        //    + server attrib state — the mirror of enter()'s push, restoring the ENTIRE
        //    fixed-function state (matrices, blend, depth, scissor, cull, alpha, color,
        //    texture enables, line width, vertex-array client state) in one atomic step.
        //    Only pop what was actually pushed (a faulted push must not underflow the stack).
        //    NOTE: glPopAttrib does NOT cover FBO binding, current program, or buffer object
        //    bindings — those are restored explicitly below (still essential), and the
        //    GlStateManager shadow write-through at the end re-syncs MC's Java cache to the
        //    real GL this pop just restored.
        try {
            if (pushedMatrices) {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
            }
            if (pushedClientAttrib) {
                GL11.glPopClientAttrib();
            }
            if (pushedAttrib) {
                GL11.glPopAttrib();
            }
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] attrib/matrix pop faulted: " + t);
        } finally {
            pushedMatrices = false;
            pushedClientAttrib = false;
            pushedAttrib = false;
        }
        // 1) Raw GL restore — the values MC's own code expects to read back. MC 1.8.9 is
        //    fixed-function, so program 0 is the correct binding; MC never uses a program,
        //    so it does not shadow one and would leave a stray program bound otherwise.
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
            GL20.glUseProgram(savedProgram);
            GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            GL13.glActiveTexture(savedActiveTex);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex2d);
            if (blendWasEnabled) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            GL11.glBlendFunc(savedBlendSrc, savedBlendDst);
            if (depthWasEnabled) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            GL11.glDepthMask(depthMask);
            GL11.glDepthFunc(savedDepthFunc);
            if (tex2dWasEnabled) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            if (cullWasEnabled) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (alphaTestWasEnabled) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
            }
            // Stencil: MC does NOT shadow GL_STENCIL_TEST, so raw restore only (Skia
            // clips via stencil and can leave it enabled — that would mask MC's draws).
            if (stencilWasEnabled) {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
            } else {
                GL11.glDisable(GL11.GL_STENCIL_TEST);
            }
            // Color write-mask: raw restore (shadow write-through below).
            GL11.glColorMask(savedColorMask[0], savedColorMask[1], savedColorMask[2], savedColorMask[3]);
            // Line width: raw-GL leak (MC doesn't shadow it) — restore what MC expects.
            GL11.glLineWidth(savedLineWidth);
            // Scissor: raw restore of enable + box (MC does NOT shadow it). THE skiko
            // black-screen fix — a scissor Skia left enabled with a small box would clip
            // MC's full-screen framebuffer blit to that box and black the whole frame.
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
            if (scissorWasEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            // Restore the vertex + element buffer bindings MC expects (normally 0). THE
            // black-screen fix for native backends: Skia leaves its VBO/IBO bound, and MC's
            // framebuffer-to-screen quad uses client-memory Tessellator arrays that read
            // garbage if a non-zero GL_ARRAY_BUFFER is still bound → invisible quad → black.
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedArrayBuffer);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, savedElementBuffer);
            // Disable every generic vertex-attrib array. THE "corner-triangle-fan" fix: Skia
            // (programmable pipeline) leaves attrib arrays 0/1/2 ENABLED; MC 1.8.9 is
            // fixed-function and draws with glVertexPointer, but an enabled generic attrib
            // array 0 aliases / overrides the fixed-function vertex array on most drivers, so
            // MC's next draw reads Skia's attrib data → all vertices collapse toward the
            // origin → a fan of degenerate triangles in the corner. MC never uses generic
            // attrib arrays, so disabling all of them is safe and is what MC expects. Live-
            // proven: disabling [0,1,2] made the menu render cleanly.
            for (int i = 0; i < maxVertexAttribs; i++) {
                GL20.glDisableVertexAttribArray(i);
            }
            // Restore MC's fixed-function matrices. THE "splash flung to the edge" fix: Skia
            // left MODELVIEW/PROJECTION in its own state; load MC's snapshot back so relative
            // GlStateManager.translate/rotate/scale (main-menu splash, tooltips) build on the
            // matrix MC expects. Load PROJECTION then MODELVIEW, and leave the matrix mode as
            // MC had it.
            savedProjection.clear();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadMatrixf(savedProjection);
            savedModelview.clear();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadMatrixf(savedModelview);
            GL11.glMatrixMode(savedMatrixMode);
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] leave raw-GL restore faulted: " + t);
        }

        // 2) Shadow write-through: force GlStateManager's private state to AGREE with the
        //    real GL we just restored, so a subsequent enableX()/bindTexture() is not a
        //    no-op against a stale "already there" shadow. Each write is independently
        //    fault-isolated so one field-name miss cannot skip the rest.
        writeBlendShadow();
        writeDepthShadow();
        writeTextureShadow();
        writeBooleanStateShadow(cullStateField, cull_cullFace, cullWasEnabled, "cull");
        writeBooleanStateShadow(alphaStateField, alpha_alphaTest, alphaTestWasEnabled, "alpha");
        writeColorMaskShadow();
        resetColorShadow();
    }

    /**
     * Write MC's colorMaskState booleans to match the restored real color write-mask, so
     * a later {@code GlStateManager.colorMask(...)} does not no-op against a stale shadow
     * (Skia toggles the color mask; MC shadows it and early-returns on match).
     */
    private void writeColorMaskShadow() {
        if (colorMaskField == null) {
            return;
        }
        try {
            Object cm = colorMaskField.get(null);
            if (cm != null) {
                cmask_r.setBoolean(cm, savedColorMask[0]);
                cmask_g.setBoolean(cm, savedColorMask[1]);
                cmask_b.setBoolean(cm, savedColorMask[2]);
                cmask_a.setBoolean(cm, savedColorMask[3]);
            }
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] colorMask shadow write-through miss: " + t);
        }
    }

    /**
     * Generic write-through for a GlStateManager state object that holds a single
     * {@code BooleanState} enable flag (cull, alpha-test, ...): fetch the static state,
     * read its {@code BooleanState} field, force its {@code currentState} to {@code v}.
     * Fault-isolated per call so one field-name miss cannot skip the rest.
     */
    private void writeBooleanStateShadow(Field stateField, Field boolField, boolean v, String label) {
        if (stateField == null || boolField == null) {
            return;
        }
        try {
            Object state = stateField.get(null);
            if (state != null) {
                setBool(boolField.get(state), v);
            }
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] " + label + " shadow write-through miss: " + t);
        }
    }

    private void writeBlendShadow() {
        if (blendStateField == null) {
            return;
        }
        try {
            Object blendState = blendStateField.get(null);
            if (blendState == null) {
                return;
            }
            setBool(blend_blend.get(blendState), blendWasEnabled);
            blend_src.setInt(blendState, savedBlendSrc);
            blend_dst.setInt(blendState, savedBlendDst);
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] blend shadow write-through miss: " + t);
        }
    }

    private void writeDepthShadow() {
        if (depthStateField == null) {
            return;
        }
        try {
            Object depthState = depthStateField.get(null);
            if (depthState == null) {
                return;
            }
            setBool(depth_test.get(depthState), depthWasEnabled);
            depth_mask.setBoolean(depthState, depthMask);
            depth_func.setInt(depthState, savedDepthFunc);
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] depth shadow write-through miss: " + t);
        }
    }

    /**
     * Re-assert the shadow's active texture unit AND the bound texture name for that
     * unit. {@code activeTextureUnit} is the ZERO-BASED index MC keeps
     * ({@code rawGlEnum - GL_TEXTURE0}); {@code textureState[unit].textureName} is the
     * per-unit bound texture. If either is stale, MC's next {@code bindTexture} no-ops
     * and the world/HUD samples the overlay's texture (purple-black or, with a poisoned
     * blend, a black frame).
     */
    private void writeTextureShadow() {
        if (activeUnitField == null || textureStateArr == null) {
            return;
        }
        try {
            int unit = savedActiveTex - GL_TEXTURE0; // MC's zero-based index
            if (unit < 0) {
                unit = 0;
            }
            activeUnitField.setInt(null, unit);
            Object arr = textureStateArr.get(null);
            if (arr == null) {
                return;
            }
            Object[] states = (Object[]) arr;
            if (unit < states.length && states[unit] != null) {
                tex_name.setInt(states[unit], savedTex2d);
                // GL_TEXTURE_2D enable shadow for this unit: if the overlay disabled
                // texturing (solid quad) and only raw GL is restored, MC's shadow still
                // reads "enabled" and enableTexture2D() no-ops → world renders untextured
                // (white). Write the shadow to match the real enable we just restored.
                if (tex_2dState != null) {
                    setBool(tex_2dState.get(states[unit]), tex2dWasEnabled);
                }
            }
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] texture shadow write-through miss: " + t);
        }
    }

    /**
     * Force GlStateManager.colorState to the sentinel MC uses to mean "unset", so MC's
     * next {@code GlStateManager.color(r,g,b,a)} always re-issues the real {@code glColor}
     * instead of no-opping against a stale cached color the overlay's {@code glColor4f}
     * left behind. MC's own {@code resetColor()} sets every component to -1.0f; we mirror
     * that (a value {@code color()} can never legitimately equal, so it never no-ops).
     */
    private void resetColorShadow() {
        if (colorStateField == null) {
            return;
        }
        try {
            Object colorState = colorStateField.get(null);
            if (colorState == null) {
                return;
            }
            color_r.setFloat(colorState, -1.0f);
            color_g.setFloat(colorState, -1.0f);
            color_b.setFloat(colorState, -1.0f);
            color_a.setFloat(colorState, -1.0f);
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] color shadow reset miss: " + t);
        }
    }

    /** Set a GlStateManager$BooleanState's private {@code currentState} to {@code v}. */
    private void setBool(Object booleanState, boolean v) throws IllegalAccessException {
        if (booleanState != null && bool_current != null) {
            bool_current.setBoolean(booleanState, v);
        }
    }

    // ---- test seam ------------------------------------------------------------------
    // Lets a headless test exercise the SHADOW write-through (the black-screen fix)
    // against the real GlStateManager private fields WITHOUT any GL context: it sets the
    // "saved" values a leave() would have snapshotted, then runs only the reflective
    // shadow writes (no raw-GL restore). Package-visible on purpose.

    /** True if the GlStateManager reflection handles all resolved (else raw-GL-only). */
    boolean reflectionResolved() {
        return blendStateField != null && depthStateField != null
                && textureStateArr != null && activeUnitField != null && bool_current != null;
    }

    /**
     * Test seam for the SCISSOR fix (the skiko black-screen cause). Scissor is raw GL, not
     * a GlStateManager shadow, so it can't be verified via the shadow path; this lets a
     * headless test set the "saved" scissor snapshot fields (as if {@link #enter()} had
     * observed them) and read them back, proving the enable+box round-trip the {@link
     * #leave()} raw restore depends on. A regression that drops the scissor snapshot/restore
     * fails this because the fields would not carry the observed values.
     */
    void setScissorSnapshotForTest(boolean enabled, int x, int y, int w, int h) {
        this.scissorWasEnabled = enabled;
        this.savedScissorBox[0] = x;
        this.savedScissorBox[1] = y;
        this.savedScissorBox[2] = w;
        this.savedScissorBox[3] = h;
    }

    /** @return the saved scissor enable observed at enter() (test seam). */
    boolean savedScissorEnabledForTest() {
        return scissorWasEnabled;
    }

    /** @return the saved scissor box {x,y,w,h} observed at enter() (test seam). */
    int[] savedScissorBoxForTest() {
        return savedScissorBox.clone();
    }

    /**
     * Test seam for the VERTEX/ELEMENT BUFFER fix (the confirmed skiko black-screen cause:
     * live probe read GL_ARRAY_BUFFER_BINDING=1 after a skiko frame, and MC's framebuffer
     * blit-quad uses client-memory Tessellator arrays that read garbage if a VBO is bound).
     * Buffer bindings are raw GL, not a GlStateManager shadow, so this sets the "saved"
     * snapshot (as if enter() observed Skia's bound VBO/IBO) and reads it back — a guard
     * that dropped the buffer snapshot/restore would not carry these, failing the test.
     */
    void setBufferSnapshotForTest(int arrayBuffer, int elementBuffer) {
        this.savedArrayBuffer = arrayBuffer;
        this.savedElementBuffer = elementBuffer;
    }

    /** @return the saved GL_ARRAY_BUFFER binding observed at enter() (test seam). */
    int savedArrayBufferForTest() {
        return savedArrayBuffer;
    }

    /** @return the saved GL_ELEMENT_ARRAY_BUFFER binding observed at enter() (test seam). */
    int savedElementBufferForTest() {
        return savedElementBuffer;
    }

    /**
     * Set the snapshot fields as if {@link #enter()} had observed this state, then write
     * ONLY the shadow (no GL). Used by the regression test to prove the guard writes
     * through blend/depth/texture shadows — the exact fields the old blend-only guard
     * left corrupted.
     */
    void applyShadowForTest(boolean blendEnabled, int blendSrc, int blendDst,
                            boolean depthEnabled, boolean depthWriteMask, int depthFunc,
                            int activeTexEnum, int boundTexName, boolean tex2dEnabled) {
        this.blendWasEnabled = blendEnabled;
        this.savedBlendSrc = blendSrc;
        this.savedBlendDst = blendDst;
        this.depthWasEnabled = depthEnabled;
        this.depthMask = depthWriteMask;
        this.savedDepthFunc = depthFunc;
        this.savedActiveTex = activeTexEnum;
        this.savedTex2d = boundTexName;
        this.tex2dWasEnabled = tex2dEnabled;
        // Cull + alpha-test default to "was enabled" for the test's poison scenario.
        this.cullWasEnabled = true;
        this.alphaTestWasEnabled = true;
        // Color mask expected all-true (normal rendering) — poison scenario sets it false.
        this.savedColorMask[0] = true;
        this.savedColorMask[1] = true;
        this.savedColorMask[2] = true;
        this.savedColorMask[3] = true;
        writeBlendShadow();
        writeDepthShadow();
        writeTextureShadow();
        writeBooleanStateShadow(cullStateField, cull_cullFace, cullWasEnabled, "cull");
        writeBooleanStateShadow(alphaStateField, alpha_alphaTest, alphaTestWasEnabled, "alpha");
        writeColorMaskShadow();
        resetColorShadow();
    }
}
