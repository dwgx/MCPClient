package net.marcloud.mcp.dwm.backend;

/**
 * The graceful-degradation floor: a backend that draws NOTHING but still computes
 * real (fixed-advance) {@link TextMetrics}, so layout runs headless and when the
 * imgui backend jar is absent the UI still ticks without throwing. This is the
 * "reflect, miss, degrade to no-op" idiom applied to rendering.
 *
 * <p>Its {@link DrawContext} is a no-op recorder. {@link #measureText} uses a
 * monospace approximation (0.6em advance) — good enough for headless layout/tests,
 * never used for real on-screen text (a real backend replaces it).
 */
public final class NullBackend implements RenderBackend {

    private static final DrawContext NOOP = new NoopDrawContext();

    @Override
    public String id() {
        return "null";
    }

    @Override
    public BackendCaps caps() {
        return BackendCaps.minimal();
    }

    @Override
    public void onAttach(BackendHost host) {
        // no-op
    }

    @Override
    public void onDetach() {
        // no-op
    }

    @Override
    public void beginFrame(FrameInput in, FrameMetrics metrics) {
        // no-op
    }

    @Override
    public DrawContext draw() {
        return NOOP;
    }

    @Override
    public void endFrame() {
        // no-op
    }

    @Override
    public TextureHandle uploadTexture(TextureData rgba) {
        return new TextureHandle(0L);
    }

    @Override
    public void freeTexture(TextureHandle h) {
        // no-op
    }

    @Override
    public FontHandle loadFont(FontSpec spec) {
        return new FontHandle(0L);
    }

    @Override
    public TextMetrics measureText(FontHandle f, CharSequence s, float sizePx) {
        int n = s == null ? 0 : s.length();
        float w = n * sizePx * 0.6f;         // fixed monospace-ish advance
        return new TextMetrics(w, sizePx * 0.8f, sizePx * 0.2f);
    }

    /** A DrawContext that records nothing. */
    private static final class NoopDrawContext implements DrawContext {
        @Override public void rect(float x, float y, float w, float h, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, float radius, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, Corners perCorner, int argb) { }
        @Override public void rectStroke(float x, float y, float w, float h, float thickness, int argb) { }
        @Override public void line(float x0, float y0, float x1, float y1, float thickness, int argb) { }
        @Override public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) { }
        @Override public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) { }
        @Override public void path(PathSpec path, PaintSpec paint) { }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public void pushOpacity(float alpha) { }
        @Override public void popOpacity() { }
    }
}
