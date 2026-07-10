package net.marcloud.mcp.core.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;

import javax.imageio.ImageIO;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Captures the current rendered frame as a PNG, entirely in memory. MUST run on
 * the game thread (= the GL context thread in the single-threaded 1.8.9 client);
 * callers marshal via GameBridge.
 *
 * <p>Replicates vanilla {@code ScreenShotHelper}'s proven read path (which we
 * can't edit): read the FBO color texture with GL_BGRA + UNSIGNED_INT_8_8_8_8_REV
 * into an IntBuffer (each pixel becomes one 0xAARRGGBB int, so BufferedImage
 * needs no channel swizzle), correcting the FBO texture-vs-visible offset and the
 * bottom-left→top-left row order. Then downscale (research: ≤1024 long edge keeps
 * LLM image tokens ~785 and Claude downsizes anything larger anyway) and PNG-encode.
 */
public final class ScreenCapture {

    private ScreenCapture() {
    }

    /** Default output long-edge target (16:9 → 1024x576, ~785 image tokens). */
    public static final int DEFAULT_MAX_EDGE = 1024;

    /**
     * Capture, downscale to fit {@code maxEdge} on the long side, and PNG-encode.
     * Returns the PNG bytes. Throws on any GL/encode failure.
     */
    public static byte[] capturePng(GameAccess game, int maxEdge) throws IOException {
        BufferedImage full = captureFrame(game);
        BufferedImage scaled = downscale(full, Math.max(64, maxEdge));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", bos); // headless-safe
        return bos.toByteArray();
    }

    /** Read the framebuffer into a BufferedImage (top-left origin, correct colors). */
    private static BufferedImage captureFrame(GameAccess game) {
        Minecraft mc = game.mc();
        Framebuffer fb = mc.getFramebuffer();

        int texW = fb.framebufferTextureWidth;
        int texH = fb.framebufferTextureHeight;
        int visW = fb.framebufferWidth;
        int visH = fb.framebufferHeight;

        int cap = texW * texH;
        IntBuffer pixelBuffer = BufferUtils.createIntBuffer(cap);
        int[] pixelValues = new int[cap];

        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        pixelBuffer.clear();

        // Read the FBO color texture (always the last finished frame).
        fb.bindFramebufferTexture();
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        fb.unbindFramebufferTexture();

        pixelBuffer.get(pixelValues);

        // Assemble the visible region, flipping bottom-left → top-left. The FBO
        // texture may be larger (POT) than the visible area; the visible frame
        // occupies the bottom visH rows, so output row y reads texture row
        // (visH-1-y). (texH-1-(y+(texH-visH)) reduces to this.)
        BufferedImage img = new BufferedImage(visW, visH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < visH; y++) {
            int srcRow = visH - 1 - y;
            for (int x = 0; x < visW; x++) {
                img.setRGB(x, y, pixelValues[srcRow * texW + x]);
            }
        }
        return img;
    }

    private static BufferedImage downscale(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) {
            return src;
        }
        double scale = (double) maxEdge / longEdge;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }
}
