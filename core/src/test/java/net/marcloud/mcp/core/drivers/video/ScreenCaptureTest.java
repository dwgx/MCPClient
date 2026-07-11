package net.marcloud.mcp.core.drivers.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * Headless tests for the PNG encode/downscale half of {@link ScreenCapture} — the
 * part that needs no GL context (the {@code captureFrame} glReadPixels read is
 * irreducibly live and cannot be exercised headless). Closes a real zero-coverage
 * gap: nothing referenced encodePng/downscale before.
 *
 * <p>The frame is a synthetic {@link BufferedImage}, so these run in the forked
 * surefire JVM with no display.
 */
public class ScreenCaptureTest {

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0x3366CC);
            }
        }
        return img;
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull("bytes must be a decodable PNG", back);
        return back;
    }

    @Test
    public void encodesToADecodablePng() throws Exception {
        byte[] png = ScreenCapture.encodePng(solid(320, 200), ScreenCapture.DEFAULT_MAX_EDGE);
        assertTrue("non-empty PNG", png.length > 0);
        // PNG magic number.
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
        BufferedImage back = decode(png);
        // Below the max edge → dimensions preserved.
        assertEquals(320, back.getWidth());
        assertEquals(200, back.getHeight());
    }

    @Test
    public void downscalesLongEdgeToMaxEdgePreservingAspect() throws Exception {
        // 2000x1000, maxEdge 1000 → long edge clamped to 1000, aspect 2:1 kept.
        byte[] png = ScreenCapture.encodePng(solid(2000, 1000), 1000);
        BufferedImage back = decode(png);
        assertEquals("long edge clamped to maxEdge", 1000, back.getWidth());
        assertEquals("aspect ratio preserved", 500, back.getHeight());
    }

    @Test
    public void portraitLongEdgeIsHeight() throws Exception {
        // Tall image: the long edge is the height, so height clamps to maxEdge.
        byte[] png = ScreenCapture.encodePng(solid(600, 1800), 900);
        BufferedImage back = decode(png);
        assertEquals("height (long edge) clamped", 900, back.getHeight());
        assertEquals("width scaled proportionally", 300, back.getWidth());
    }

    @Test
    public void smallerThanMaxEdgeIsNotUpscaled() throws Exception {
        byte[] png = ScreenCapture.encodePng(solid(128, 64), 1024);
        BufferedImage back = decode(png);
        assertEquals(128, back.getWidth());
        assertEquals(64, back.getHeight());
    }

    @Test
    public void maxEdgeIsFlooredToAvoidDegenerateSize() throws Exception {
        // encodePng floors maxEdge at 64; a tiny request must not produce a 0-size image.
        byte[] png = ScreenCapture.encodePng(solid(2000, 2000), 1);
        BufferedImage back = decode(png);
        assertEquals(64, back.getWidth());
        assertEquals(64, back.getHeight());
    }
}
