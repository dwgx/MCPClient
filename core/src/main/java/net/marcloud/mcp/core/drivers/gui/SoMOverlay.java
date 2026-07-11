package net.marcloud.mcp.core.drivers.gui;

import net.marcloud.mcp.core.drivers.video.ScreenCapture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Set-of-Marks (SoM) annotator for the GUI. Given the raw captured frame, the
 * extracted {@link GuiElement} list, and the {@link Viewport} (whose {@code
 * scaleFactor} maps scaled-GUI coordinates to framebuffer pixels), this draws an
 * outlined, labelled box at every element's bounds so an LLM can cross-reference
 * the JSON element list against the image by the SAME id ({@code b0}/{@code s13}/
 * {@code t0}/...).
 *
 * <p>Coordinate transform: element bounds live in scaled-GUI space (top-left
 * origin, the same space {@code mouseClicked} consumes); the framebuffer the frame
 * was read from is {@code scaleFactor}× larger. So a scaled-GUI rectangle maps to
 * framebuffer pixels by multiplying every component by {@code scaleFactor}. Both
 * spaces are top-left origin ({@code ScreenCapture} already flips the FBO's
 * bottom-left rows), so no vertical flip is applied here.
 *
 * <p>Marks are OUTLINE-ONLY (no fill) so the underlying pixels stay visible. The
 * annotation is done at full capture resolution; downscaling happens afterwards in
 * {@code ScreenCapture.encodePng}, so the marks scale down with the image and stay
 * aligned to their elements.
 */
public final class SoMOverlay {

    private SoMOverlay() {
    }

    /** Box outline / label-background palette. High-contrast for legibility over game pixels. */
    private static final Color OUTLINE = new Color(0xFF, 0x2D, 0x2D); // red
    private static final Color LABEL_BG = new Color(0x00, 0x00, 0x00, 0xC0); // translucent black
    private static final Color LABEL_FG = new Color(0xFF, 0xFF, 0x00); // yellow

    /**
     * Return a NEW image that is {@code frame} with a numbered box drawn at each
     * element's bounds (mapped scaled-GUI → framebuffer px via the viewport's
     * {@code scaleFactor}). The input frame is not mutated. Elements with a null
     * bounds are skipped. A {@code scaleFactor <= 0} is treated as 1.
     *
     * @param frame    the raw captured frame (full framebuffer resolution)
     * @param elements the elements to mark; each drawn with its {@link GuiElement#id() id}
     * @param viewport geometry carrying the GUI→framebuffer {@code scaleFactor}
     */
    public static BufferedImage annotate(BufferedImage frame, List<GuiElement> elements,
                                         Viewport viewport) {
        if (frame == null) {
            throw new IllegalArgumentException("frame is null");
        }
        int sf = (viewport == null || viewport.scaleFactor() <= 0) ? 1 : viewport.scaleFactor();

        // Copy so the raw frame is untouched (tests assert on both), and ensure a
        // graphics-friendly type.
        BufferedImage out = new BufferedImage(frame.getWidth(), frame.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(frame, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Stroke + font scale with the GUI scale so marks read the same at any scaleFactor.
            float stroke = Math.max(1f, sf);
            g.setStroke(new BasicStroke(stroke));
            int fontPx = Math.max(8, 6 * sf);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPx));

            if (elements != null) {
                for (GuiElement el : elements) {
                    if (el == null || el.bounds() == null) {
                        continue;
                    }
                    drawMark(g, el, sf, fontPx);
                }
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void drawMark(Graphics2D g, GuiElement el, int sf, int fontPx) {
        Bounds b = el.bounds();
        int x = b.x() * sf;
        int y = b.y() * sf;
        int w = b.w() * sf;
        int h = b.h() * sf;

        // Outline-only box at the element's framebuffer-space bounds.
        g.setColor(OUTLINE);
        g.drawRect(x, y, w, h);

        // Label = the element id (b0/s13/t0...) so the image cross-references the JSON.
        String label = el.id();
        var fm = g.getFontMetrics();
        int tw = fm.stringWidth(label);
        int th = fm.getHeight();
        int pad = Math.max(1, sf);
        // Anchor the label at the box top-left, nudged inside so it stays on-image
        // even for an element flush against the top edge.
        int bgX = x;
        int bgY = Math.max(0, y - th);
        g.setColor(LABEL_BG);
        g.fillRect(bgX, bgY, tw + 2 * pad, th);
        g.setColor(LABEL_FG);
        g.drawString(label, bgX + pad, bgY + fm.getAscent());
    }
}
