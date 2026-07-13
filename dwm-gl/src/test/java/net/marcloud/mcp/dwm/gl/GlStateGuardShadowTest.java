package net.marcloud.mcp.dwm.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for the black-screen root cause: {@link GlStateGuard} must write
 * through the FULL GlStateManager Java-side shadow (blend + depth + texture-binding +
 * active-unit), not just blend. The previous Kotlin guard wrote ONLY the blend shadow,
 * leaving depth/texture shadows corrupted — MC then no-op'd its own state changes and
 * the frame went black.
 *
 * <p>This runs HEADLESS: it loads the real vanilla {@code GlStateManager} (its static
 * init is pure Java — {@code new BooleanState(cap)} / {@code new TextureState()}, no GL),
 * pokes a deliberately-stale shadow into the private fields, then invokes only the
 * guard's reflective shadow write-through (no GL calls) and asserts every shadow field
 * was corrected. A guard that wrote back only blend (the old behavior) FAILS the depth
 * and texture assertions here.
 */
public class GlStateGuardShadowTest {

    private static final String GSM = "net.minecraft.client.renderer.GlStateManager";

    private Class<?> gsm;

    @Before
    public void loadGsm() {
        try {
            gsm = Class.forName(GSM);
        } catch (Throwable t) {
            // client jar not on the test classpath in this environment — skip rather
            // than false-fail. (In the reactor it is provided-scope and present.)
            Assume.assumeNoException("GlStateManager not available headless", t);
        }
    }

    @Test
    public void guardResolvesAllReflectionHandles() {
        GlStateGuard guard = new GlStateGuard();
        assertTrue("guard must resolve every GlStateManager shadow field (else the "
                + "black-screen fix silently degrades to raw-GL-only)", guard.reflectionResolved());
    }

    @Test
    public void writesThroughBlendDepthAndTextureShadows() throws Exception {
        // 1) Poison the shadow: set values OPPOSITE to what we will restore, so a guard
        //    that skips a field leaves the poison detectable.
        Object blendState = staticField("blendState");
        Object depthState = staticField("depthState");
        setBooleanState(blendState, "blend", true);      // stale: blend "on"
        setInt(blendState, "srcFactor", 999);            // stale factors
        setInt(blendState, "dstFactor", 999);
        setBooleanState(depthState, "depthTest", true);  // stale: depth "on"
        setBoolean(depthState, "maskEnabled", false);    // stale mask
        setInt(depthState, "depthFunc", 111);            // stale func

        // active unit + texture[0].textureName + texture[0].texture2DState poisoned
        setStaticInt("activeTextureUnit", 5);
        Object[] texState = (Object[]) staticField("textureState");
        assertNotNull(texState);
        setInt(texState[0], "textureName", 424242);
        setBooleanState(texState[0], "texture2DState", true); // stale: MC thinks tex2d "on"

        // cull + alpha-test shadows poisoned to false (the panel disables them; a guard
        // that only restores raw GL leaves MC's shadow reading "off").
        Object cullState = staticField("cullState");
        Object alphaState2 = staticField("alphaState");
        setBooleanState(cullState, "cullFace", false);
        setBooleanState(alphaState2, "alphaTest", false);

        // colorState poisoned to a concrete color (an overlay's glColor4f leaves this;
        // the guard must reset it to the -1 sentinel so MC's next color() re-issues).
        Object colorState = staticField("colorState");
        setFloat(colorState, "red", 0.5f);
        setFloat(colorState, "green", 0.5f);
        setFloat(colorState, "blue", 0.5f);
        setFloat(colorState, "alpha", 0.5f);

        // 2) Run the guard's shadow write-through with a KNOWN target state.
        GlStateGuard guard = new GlStateGuard();
        Assume.assumeTrue(guard.reflectionResolved());
        final int GL_TEXTURE0 = 33984;
        final int GL_SRC_ALPHA = 770;
        final int GL_ONE_MINUS_SRC_ALPHA = 771;
        final int GL_LEQUAL = 515;
        guard.applyShadowForTest(
                /* blendEnabled */ false,
                /* blendSrc     */ GL_SRC_ALPHA,
                /* blendDst     */ GL_ONE_MINUS_SRC_ALPHA,
                /* depthEnabled */ true,
                /* depthMask    */ true,
                /* depthFunc    */ GL_LEQUAL,
                /* activeTexEnum*/ GL_TEXTURE0,      // unit 0
                /* boundTexName */ 7,
                /* tex2dEnabled */ true);            // MC expects texturing ON

        // 3) BLEND shadow corrected (the old guard got this right).
        assertFalse("blend shadow currentState must be false", getBooleanState(blendState, "blend"));
        assertEquals("blend srcFactor shadow", GL_SRC_ALPHA, getInt(blendState, "srcFactor"));
        assertEquals("blend dstFactor shadow", GL_ONE_MINUS_SRC_ALPHA, getInt(blendState, "dstFactor"));

        // 4) DEPTH shadow corrected (the OLD blend-only guard would FAIL these).
        assertTrue("depth shadow currentState must be true", getBooleanState(depthState, "depthTest"));
        assertTrue("depth maskEnabled shadow", getBoolean(depthState, "maskEnabled"));
        assertEquals("depth func shadow", GL_LEQUAL, getInt(depthState, "depthFunc"));

        // 5) TEXTURE shadow corrected (the OLD guard would FAIL these — the purple-black
        //    / poisoned-frame path).
        assertEquals("active texture unit shadow must be 0", 0, getStaticInt("activeTextureUnit"));
        assertEquals("bound texture name shadow for unit 0", 7, getInt(texState[0], "textureName"));

        // 6) TEXTURE_2D ENABLE shadow corrected (the WHITE-SCREEN root cause: a guard that
        //    restores raw GL but not this shadow lets MC's enableTexture2D() no-op → world
        //    renders untextured). Must be written back to the expected "on".
        assertTrue("texture2DState enable shadow for unit 0 must be true",
                getBooleanState(texState[0], "texture2DState"));

        // 7) COLOR shadow reset to the -1 sentinel so MC's next color() never no-ops
        //    against the overlay's leftover glColor.
        assertEquals("color red reset to -1 sentinel", -1.0f, getFloat(colorState, "red"), 0f);
        assertEquals("color alpha reset to -1 sentinel", -1.0f, getFloat(colorState, "alpha"), 0f);

        // 8) CULL + ALPHA-TEST enable shadows corrected back to "on" (a panel that
        //    disables them without shadow write-through leaves MC's world cull/alpha off).
        assertTrue("cull enable shadow must be restored to true",
                getBooleanState(cullState, "cullFace"));
        assertTrue("alpha-test enable shadow must be restored to true",
                getBooleanState(alphaState2, "alphaTest"));
    }

    // ---- reflection helpers ---------------------------------------------------------

    private Object staticField(String name) throws Exception {
        Field f = gsm.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private int getStaticInt(String name) throws Exception {
        Field f = gsm.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private void setStaticInt(String name, int v) throws Exception {
        Field f = gsm.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(null, v);
    }

    private static float getFloat(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(owner);
    }

    private static void setFloat(Object owner, String name, float v) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setFloat(owner, v);
    }

    private static int getInt(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(owner);
    }

    private static void setInt(Object owner, String name, int v) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(owner, v);
    }

    private static boolean getBoolean(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(owner);
    }

    private static void setBoolean(Object owner, String name, boolean v) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(owner, v);
    }

    /** Read the private {@code currentState} of a BooleanState field on {@code owner}. */
    private static boolean getBooleanState(Object owner, String fieldName) throws Exception {
        Field bf = owner.getClass().getDeclaredField(fieldName);
        bf.setAccessible(true);
        Object boolState = bf.get(owner);
        Field cs = boolState.getClass().getDeclaredField("currentState");
        cs.setAccessible(true);
        return cs.getBoolean(boolState);
    }

    /** Set the private {@code currentState} of a BooleanState field on {@code owner}. */
    private static void setBooleanState(Object owner, String fieldName, boolean v) throws Exception {
        Field bf = owner.getClass().getDeclaredField(fieldName);
        bf.setAccessible(true);
        Object boolState = bf.get(owner);
        Field cs = boolState.getClass().getDeclaredField("currentState");
        cs.setAccessible(true);
        cs.setBoolean(boolState, v);
    }
}
