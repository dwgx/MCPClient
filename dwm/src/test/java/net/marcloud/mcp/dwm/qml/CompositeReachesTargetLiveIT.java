package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

/**
 * Asserts the composite reaches the TARGET framebuffer, not merely the offscreen layer.
 *
 * <p>This is the test whose absence let a bug through that every other assertion called healthy. In
 * a live game the surface was open, not inert, reported no error, and was wrapped around MC's real
 * framebuffer id; the offscreen layer read back with the panel fill and the accent colour present
 * across 3640 sampled points. And the screen was empty. Skia had queued the {@code drawImage} and
 * nothing ever flushed it: qml4j calls {@code present()} from inside {@code renderFrame}, which runs
 * BEFORE the blit — and the repaint was skippable back then, so on a frame the scene had not changed
 * on, nothing called present at all. The composite was recorded and dropped, every frame.
 *
 * <p>No state field could have shown that. The only witness is a pixel in the destination, which is
 * what this reads — with {@code glReadPixels} against the bound framebuffer rather than through
 * Skia, so it cannot be satisfied by the same queue that was being dropped.
 *
 * <p>The readback is legitimate here for the reason it is forbidden in the render path: it stalls
 * the pipeline, which is irrelevant once per assertion and unacceptable per frame.
 */
public class CompositeReachesTargetLiveIT {

    private static final String SCENE = "dwm/Shell.qml";

    @Test
    public void theSceneActuallyLandsInTheTargetFramebuffer() {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            int w = Display.getWidth();
            int h = Display.getHeight();

            // Clear the target to an unmistakable colour first. Anything still this colour where
            // the UI belongs means the composite never arrived.
            GL11.glClearColor(1.0F, 0.0F, 1.0F, 1.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open; " + surface.lastError(), surface.open(w, h));
            surface.setFramebufferId(0);
            surface.frame(w, h, System.nanoTime());

            assertTrue("the surface must be live; " + surface.lastError(), surface.isOpen());

            int covered = countNonMagenta(w, h);
            assertTrue("the composited scene must have reached the target framebuffer, but every "
                    + "sampled pixel is still the clear colour — the blit was queued and never "
                    + "flushed", covered > 0);

            surface.close();
            surface = null;
        } finally {
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    /**
     * EVERY frame must land in the target, including one the scene has nothing new to show on.
     *
     * <p>The sharper half, and the reason the composite is unconditional: MC redraws the whole world
     * each frame, so the destination pixels are gone by the time we run whether the scene changed or
     * not. Treating "nothing changed" as "nothing to do" would make the UI VANISH rather than go
     * stale — the failure mode a stale-frame intuition does not predict.
     *
     * <p>Repeated rather than asserted once, because "every frame" is the property: a single
     * wipe-and-frame cannot tell it apart from "the frame after the first one works", which is the
     * shape of the bug this guards.
     */
    @Test
    public void everyFrameRepaintsTheTarget() {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            int w = Display.getWidth();
            int h = Display.getHeight();

            surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open; " + surface.lastError(), surface.open(w, h));
            surface.setFramebufferId(0);
            surface.frame(w, h, System.nanoTime());

            for (int cycle = 1; cycle <= 3; cycle++) {
                GL11.glClearColor(1.0F, 0.0F, 1.0F, 1.0F);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

                // The wipe is the whole basis of the assertion below, and it is not self-evident
                // that it took: Skia has been driving this context, and a left-behind scissor or a
                // stale framebuffer binding would make the clear a no-op. Unchecked, the pixels the
                // PREVIOUS frame composited would satisfy the coverage assertion by themselves.
                assertEquals("precondition: the target must be wiped before the frame, or the "
                    + "coverage found afterwards proves nothing", 0, countNonMagenta(w, h));

                surface.frame(w, h, System.nanoTime());

                assertTrue("frame " + cycle + " left the target still wiped: every frame must "
                        + "composite the scene into it, because MC redraws the world each frame "
                        + "and a frame that skips the composite makes the UI vanish",
                        countNonMagenta(w, h) > 0);
            }

            surface.close();
            surface = null;
        } finally {
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    /**
     * How many sampled pixels are no longer the clear colour.
     *
     * <p>Sampled on a grid rather than exhaustively: the assertion is "the UI arrived somewhere",
     * and a full-frame read of a Retina framebuffer is megabytes for no extra confidence.
     */
    private static int countNonMagenta(int width, int height) {
        ByteBuffer px = BufferUtils.createByteBuffer(4);
        int covered = 0;
        for (int y = 8; y < height; y += 32) {
            for (int x = 8; x < width; x += 32) {
                px.clear();
                GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                int r = px.get(0) & 0xFF;
                int g = px.get(1) & 0xFF;
                int b = px.get(2) & 0xFF;
                // Magenta is the clear colour; anything else is something that was drawn.
                boolean stillCleared = r > 0xF0 && g < 0x10 && b > 0xF0;
                if (!stillCleared) {
                    covered++;
                }
            }
        }
        return covered;
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
            // Teardown of an already-dead display is not actionable.
        }
    }
}
