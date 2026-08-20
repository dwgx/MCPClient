package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;

/**
 * Asserts that a real qml4j frame leaves the GL state exactly as it found it.
 *
 * <p>This is the test that matters most for living inside MC's frame, and it exists because a
 * weaker version of it passed while the code was wrong. A probe that drew a single rectangle through
 * Skija disturbed nothing measurable, so it "proved" the guard worked. Driving a real scene — text,
 * rounded rectangles, blending — immediately showed {@code GL_ALPHA_TEST} coming back <em>enabled</em>
 * when it had been disabled going in. The cause was not Skija: it was {@code GlStateGuard.leave()}
 * asserting a hardcoded configuration after {@code glPopAttrib} had already restored the right one.
 *
 * <p>So the assertion here is deliberately a whole-state comparison rather than a list of the states
 * someone thought to check. Anything that leaks shows up.
 *
 * <p>Needs a display and native Skia, so it self-skips like the other live ITs.
 */
public class GlStateGuardLiveIT {

    @Test
    public void aRealFrameLeavesTheGlStateUntouched() {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            int w = Display.getWidth();
            int h = Display.getHeight();

            QmlUiSurface surface = new QmlUiSurface("dwm/Main.qml");
            assertTrue("scene must open; " + surface.lastError(), surface.open(w, h));
            surface.setFramebufferId(0);

            // Set up the kind of state MC has in place when it calls drawScreen: a full-framebuffer
            // viewport, texturing and blending on, depth off (it clears only depth before the GUI).
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.25F, 0.5F, 0.75F, 1.0F);
            GL11.glViewport(0, 0, w, h);

            String before = snapshot();
            surface.frame(w, h, System.nanoTime());
            String after = snapshot();

            assertEquals("a qml4j frame must leave the GL state exactly as it found it, or MC stops "
                + "rendering correctly from the next frame on", before, after);
            assertEquals("the frame must not leave a GL error behind", 0, GL11.glGetError());

            // A second frame goes down the composite-only path; it must be just as clean.
            surface.frame(w, h, System.nanoTime());
            assertEquals("the composite-only path must also be state-neutral", before, snapshot());

            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /**
     * A low-alpha fill must survive MC's alpha test, and the state must still be restored.
     *
     * <p>The bug this pins cost four investigations. MC draws its GUI with {@code GL_ALPHA_TEST}
     * enabled at {@code GL_GREATER} 0.1, and a foreign renderer inherits it, so every Skia fragment
     * with alpha <= 25 was discarded by the GPU. A SettingsCard's {@code #0DFFFFFF} plate is alpha
     * 13, so it never appeared, while the card's text — alpha 255 and 197 — drew normally. That
     * "the text is there but the plate is not" split is the signature, and it looks exactly like a
     * compositing failure, which is where the earlier attempts went.
     *
     * <p>Why the whole suite was blind to it: {@link #aRealFrameLeavesTheGlStateUntouched} starts
     * from alpha test DISABLED (as a bare GLFW window does), so it never reproduced MC's posture,
     * and the layer-reading tests paint into a CPU raster surface, which does not go through GL at
     * all. Only a GL draw under MC's own state can see this, which is what this test sets up.
     *
     * <p>Asserted on the PIXEL rather than on {@code glIsEnabled}, deliberately: the guard could
     * disable the alpha test and still be defeated by something else in the pipeline, and what the
     * user cares about is whether the fill lands. The 0.1 reference is also restated here rather
     * than read back, so the test states MC's actual configuration instead of trusting the value it
     * happens to find.
     */
    @Test
    public void aLowAlphaFillIsNotDiscardedByMinecraftsAlphaTest() {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            int w = Display.getWidth();
            int h = Display.getHeight();

            QmlUiSurface surface = new QmlUiSurface("dwm/Main.qml");
            assertTrue("scene must open; " + surface.lastError(), surface.open(w, h));
            surface.setFramebufferId(0);

            // MC's GUI posture, which is what a real drawScreen hands us.
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glViewport(0, 0, w, h);

            String before = snapshot();

            // Two frames: the first lays the scene out, the second paints a settled one. The scene
            // is Main.qml, whose MenuPanel fill is itself translucent, so what lands on screen is
            // Skia compositing under whatever alpha-test state the guard established.
            GL11.glClearColor(TEST_BASE / 255.0F, TEST_BASE / 255.0F, TEST_BASE / 255.0F, 1.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            surface.frame(w, h, System.nanoTime());
            surface.frame(w, h, System.nanoTime());

            // The alpha test must have been OFF while Skia drew. Asserted by inspecting the state
            // the guard installs rather than by drawing a probe quad afterwards: by the time frame()
            // returns, leave() has correctly restored MC's own configuration, so a post-frame draw
            // measures MC's posture and not the one Skia painted under. That mistake made the first
            // version of this test fail against a working fix.
            GlStateGuard.enter();
            // Both readings come from INSIDE the guarded span, so they describe the state Skia
            // paints under. The pixel goes first and the flags after it, so the two describe the
            // same instant: reading the flags first would let a re-arm between them go unnoticed.
            int lifted = lowAlphaProbe(w, h);
            boolean alphaTestDuringSkiaDraw = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            float refDuringSkiaDraw = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            GlStateGuard.leave();

            // The pixel is asserted first because it is the property the user has; the flag check
            // below only explains WHY when it fails.
            assertTrue("a fill at alpha " + PLATE_ALPHA + " must reach the framebuffer over an 0x"
                    + Integer.toHexString(TEST_BASE) + " base -- got 0x"
                    + Integer.toHexString(lifted) + ". Unchanged means the fragment was rejected, "
                    + "which is the defect itself rather than a proxy for it.",
                lifted > TEST_BASE);
            assertTrue("GL_ALPHA_TEST must be DISABLED for the span Skia draws in. MC enables it at "
                    + "GREATER 0.1 for its GUI, i.e. a cutoff of 25/255, and a foreign renderer "
                    + "inherits it -- so a SettingsCard's #0DFFFFFF plate (alpha 13) was discarded "
                    + "by the GPU while its text (alpha 255/197) drew normally. Measured on a live "
                    + "client with an alpha ladder: 13/16/18/20/22/24 all left the destination "
                    + "untouched, 26 and 30 composited. (ref seen: " + refDuringSkiaDraw + ")",
                !alphaTestDuringSkiaDraw);

            assertEquals("and the guard must still hand the state back untouched",
                before, snapshot());
            assertEquals("no GL error", 0, GL11.glGetError());

            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /** The opaque grey the probe composites over, matching Fluent.panelFill's 0x2a. */
    private static final int TEST_BASE = 0x2A;

    /** CardBackgroundFillColorDefault's alpha: the value MC's 0.1 cutoff was discarding. */
    private static final int PLATE_ALPHA = 13;

    /**
     * Draw a white quad at {@link #PLATE_ALPHA} over the cleared base and return its red channel.
     *
     * <p>Immediate-mode GL rather than Skia, and called from INSIDE the guarded span: the subject is
     * the pipeline state the guard installs, not Skia's own correctness. If the alpha test is still
     * armed the quad is discarded and the destination stays at the base. Matrices are saved and
     * restored so the surrounding state comparison still sees an untouched transform.
     */
    private static int lowAlphaProbe(int w, int h) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, PLATE_ALPHA / 255.0F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(0, 40);
        GL11.glVertex2f(40, 40);
        GL11.glVertex2f(40, 0);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();

        java.nio.ByteBuffer px = BufferUtils.createByteBuffer(4);
        // Bottom-left origin; sample inside the quad.
        GL11.glReadPixels(20, h - 20 - 1, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        return px.get(0) & 0xFF;
    }

    /** Everything worth comparing, as one string so a leak anywhere fails the assertion. */
    private static String snapshot() {
        FloatBuffer colour = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, colour);
        IntBuffer viewport = BufferUtils.createIntBuffer(16);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        return "tex2D=" + GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
            + " blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
            + " depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
            + " alpha=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
            + " cull=" + GL11.glIsEnabled(GL11.GL_CULL_FACE)
            + " src=" + GL11.glGetInteger(GL11.GL_BLEND_SRC)
            + " dst=" + GL11.glGetInteger(GL11.GL_BLEND_DST)
            + " texbind=" + GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            // 0x8CA6 is GL_FRAMEBUFFER_BINDING; wrapping the wrong target is the other way this
            // integration goes wrong, so it belongs in the same comparison.
            + " fbo=" + GL11.glGetInteger(0x8CA6)
            + " program=" + GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            // The buffer bindings. These belong in the comparison because leaking them is what
            // actually killed the client in a real game frame: MC draws the world through
            // client-side vertex arrays, so a leftover ARRAY_BUFFER makes glVertexPointer's heap
            // pointer be read as an offset into that buffer — SIGSEGV inside the driver, which no
            // Java-level assertion elsewhere could ever observe.
            + " arrayBuf=" + GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
            + " elemBuf=" + GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)
            // Attribute array enables. Skia switches on the ones its shaders need and leaves them
            // on, which a probe measured as [] going in and [0 1] coming out. On a compatibility
            // profile these feed the same vertex puller as the fixed-function pointers, so one left
            // enabled keeps Skia's stale pointer attached for the game's next draw — the same shape
            // as the buffer-binding leak that crashed the client.
            + " attribs=" + enabledAttribArrays()
            // Pixel store: documented as NOT saveable on the attribute stack, and measured to leak
            // (Skia leaves 1 where the default is 4). Harmless for this client's 4-byte-per-pixel
            // uploads, but it is the third leak found in the state glPushAttrib cannot reach and the
            // first two crashed the game, so it is asserted rather than argued about.
            + " unpackAlign=" + GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
            // Probed and found clean, kept in the comparison so a future Skija cannot start leaking
            // them silently. MC's world draw depends on the client-array enables in particular.
            + " clientArrays=" + GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY)
            + "/" + GL11.glIsEnabled(GL11.GL_COLOR_ARRAY)
            + "/" + GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY)
            + " activeTex=" + GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
            + " samplers=" + samplerBindings()
            + " scissor=" + GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
            + " stencil=" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
            + " viewport=" + viewport.get(2) + "x" + viewport.get(3)
            + " colour=" + String.format("%.2f,%.2f,%.2f,%.2f",
                colour.get(0), colour.get(1), colour.get(2), colour.get(3));
    }

    /**
     * Sampler-object bindings on units 0..3. Skia leaves a sampler on unit 0; that is
     * outside glPushAttrib, and omitting it from the comparison is how the leak that
     * corrupts MC's textured screens survived a "whole-state" assertion.
     */
    private static String samplerBindings() {
        try {
            org.lwjgl.opengl.GLCapabilities caps = GL.getCapabilities();
            if (caps == null || caps.glBindSampler == 0L) {
                return "n/a";
            }
        } catch (Throwable t) {
            return "n/a";
        }
        int saved = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        StringBuilder b = new StringBuilder("[");
        for (int u = 0; u < 4; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            b.append(GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)).append(',');
        }
        GL13.glActiveTexture(saved);
        return b.append(']').toString();
    }

    /** The indices of every enabled generic vertex attribute array, as a stable string. */
    private static String enabledAttribArrays() {
        int max = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
        StringBuilder on = new StringBuilder("[");
        for (int i = 0; i < max; i++) {
            if (GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0) {
                on.append(i).append(',');
            }
        }
        return on.append(']').toString();
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(854, 480));
            Display.create();
            Display.update();
            return true;
        } catch (Throwable t) {
            System.out.println("[IT] no display (" + t + ") — skipping");
            return false;
        }
    }

    private static void destroyDisplay() {
        try {
            Display.destroy();
        } catch (Throwable ignored) {
            // Nothing useful to do with a dead display during teardown.
        }
    }
}
