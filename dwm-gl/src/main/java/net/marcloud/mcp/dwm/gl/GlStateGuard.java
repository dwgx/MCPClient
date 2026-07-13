package net.marcloud.mcp.dwm.gl;

import java.lang.reflect.Field;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
        } catch (Throwable t) {
            System.err.println("[GlStateGuard] enter snapshot faulted: " + t);
        }
    }

    /**
     * LEAVE: force real GL back to the snapshot AND write through MC's private shadow so
     * a later GlStateManager call does not no-op against a stale shadow. Called after the
     * overlay's draw + composite. Never throws.
     */
    public void leave() {
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
        resetColorShadow();
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
        writeBlendShadow();
        writeDepthShadow();
        writeTextureShadow();
        writeBooleanStateShadow(cullStateField, cull_cullFace, cullWasEnabled, "cull");
        writeBooleanStateShadow(alphaStateField, alpha_alphaTest, alphaTestWasEnabled, "alpha");
        resetColorShadow();
    }
}
