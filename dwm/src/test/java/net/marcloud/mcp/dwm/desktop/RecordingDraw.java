package net.marcloud.mcp.dwm.desktop;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.TextureHandle;

/**
 * Minimal recording {@link DrawContext} for desktop-component tests: records the text
 * strings drawn and counts rounded-rects, so tests assert real draw calls happened
 * (non-vacuous) without a GL context. Balanced clip/opacity are tracked to catch leaks.
 */
final class RecordingDraw implements DrawContext {

    final List<String> texts = new ArrayList<>();
    int roundedRects;
    int rects;
    int clipDepth;
    int maxClipDepth;

    @Override
    public void rect(float x, float y, float w, float h, int argb) {
        rects++;
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, float radius, int argb) {
        roundedRects++;
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, Corners perCorner, int argb) {
        roundedRects++;
    }

    @Override
    public void rectStroke(float x, float y, float w, float h, float thickness, int argb) {
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float thickness, int argb) {
    }

    @Override
    public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) {
        texts.add(String.valueOf(s));
    }

    @Override
    public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) {
    }

    @Override
    public void path(PathSpec path, PaintSpec paint) {
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        clipDepth++;
        maxClipDepth = Math.max(maxClipDepth, clipDepth);
    }

    @Override
    public void popClip() {
        clipDepth--;
    }

    @Override
    public void pushOpacity(float alpha) {
    }

    @Override
    public void popOpacity() {
    }

    boolean drew(String needle) {
        return texts.stream().anyMatch(t -> t.contains(needle));
    }
}
