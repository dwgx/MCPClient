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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

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
            + " viewport=" + viewport.get(2) + "x" + viewport.get(3)
            + " colour=" + String.format("%.2f,%.2f,%.2f,%.2f",
                colour.get(0), colour.get(1), colour.get(2), colour.get(3));
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
