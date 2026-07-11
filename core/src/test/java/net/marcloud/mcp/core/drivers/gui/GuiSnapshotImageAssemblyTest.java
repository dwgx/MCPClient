package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.Test;

/**
 * Headless coverage for the gui_snapshot_image ASSEMBLY (annotate → encode →
 * base64 → ImageContent), which had zero tests because it was welded to the live
 * glReadPixels capture. The capture is now the only live step; the assembly runs
 * against a synthetic frame here.
 *
 * <p>Complements {@link SoMOverlayTest} (overlay geometry): this proves the frame
 * actually becomes a valid base64 PNG in the CallToolResult, and that a
 * screen-less snapshot yields text-only (no image), matching the live path's
 * {@code screen() != null} guard.
 */
public class GuiSnapshotImageAssemblyTest {

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0x101010);
            }
        }
        return img;
    }

    private static GuiElement button(String id, int x, int y, int w, int h) {
        return new GuiElement(id, GuiElement.KIND_BUTTON, GuiElement.ROLE_PUSHBUTTON,
                "label-" + id, "", new Bounds(x, y, w, h),
                new Point(x + w / 2, y + h / 2), null, List.of("click"), null);
    }

    private static GuiSnapshot snapshotWith(List<GuiElement> els) {
        Viewport vp = GuiSnapshotService.viewport(400, 300, 1, 400, 300);
        return new GuiSnapshot(1, "GuiTestScreen", true, false, "Test", vp, els, "fp", List.of());
    }

    @Test
    public void buildAnnotatedPngProducesDecodablePng() throws Exception {
        byte[] png = GuiTools.buildAnnotatedPng(solid(400, 300),
                snapshotWith(List.of(button("b0", 10, 20, 100, 20))), 1024);
        assertTrue("non-empty PNG", png.length > 0);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull("assembled bytes must decode as PNG", back);
        assertEquals(400, back.getWidth());
        assertEquals(300, back.getHeight());
    }

    @Test
    public void assembleResultCarriesJsonTextAndBase64Image() throws Exception {
        GuiSnapshot snap = snapshotWith(List.of(button("b0", 10, 20, 100, 20)));
        byte[] png = GuiTools.buildAnnotatedPng(solid(400, 300), snap, 1024);

        CallToolResult r = GuiTools.assembleResult(snap, png);
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));

        boolean sawText = false;
        boolean sawImage = false;
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                sawText = true;
                assertTrue("text is the snapshot JSON", t.text().contains("\"screen\""));
            } else if (c instanceof ImageContent img) {
                sawImage = true;
                assertEquals("image/png", img.mimeType());
                // The declared base64 must round-trip to the exact PNG bytes.
                byte[] decoded = Base64.getDecoder().decode(img.data());
                assertEquals("base64 length matches the PNG", png.length, decoded.length);
                assertNotNull(ImageIO.read(new ByteArrayInputStream(decoded)));
            }
        }
        assertTrue("JSON text content present", sawText);
        assertTrue("PNG image content present", sawImage);
    }

    @Test
    public void assembleResultWithoutImageIsTextOnly() {
        // Mirrors the live guard: no screen open → png is null → text-only result.
        GuiSnapshot snap = new GuiSnapshot(0, null, false, false, null,
                GuiSnapshotService.viewport(400, 300, 1, 400, 300), List.of(), "fp", List.of());
        CallToolResult r = GuiTools.assembleResult(snap, null);
        long images = r.content().stream().filter(c -> c instanceof ImageContent).count();
        assertEquals("no image when frame is absent", 0, images);
        assertTrue("still has the JSON text",
                r.content().stream().anyMatch(c -> c instanceof TextContent));
    }
}
