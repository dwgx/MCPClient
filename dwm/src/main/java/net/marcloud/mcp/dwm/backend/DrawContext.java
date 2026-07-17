package net.marcloud.mcp.dwm.backend;

/**
 * The ONLY drawing vocabulary the UI ever sees — the locked decoupling boundary.
 * Components, theme, and compositor emit geometry exclusively through this
 * interface; a concrete backend (imgui first) maps these primitives to its own
 * draw list. No imgui / OpenGL type ever crosses this line, so swapping the
 * backend changes nothing above it.
 *
 * <p>Coordinates are in device-independent pixels (DIP); the backend resolves the
 * scale from {@link FrameMetrics}. Colors are packed ARGB {@code int}
 * (0xAARRGGBB). A {@code DrawContext} is valid only between a backend's
 * {@code beginFrame}/{@code endFrame}.
 *
 * <p>Capability degradation (see {@link BackendCaps}) is the backend's job, not
 * the caller's: UI always calls the richest primitive it wants and gets the
 * best-available fidelity (e.g. per-corner radius -&gt; uniform radius -&gt; sharp
 * rect; path -&gt; polygon approximation; layer opacity -&gt; alpha folded into
 * vertex colors). UI never queries caps to decide what to draw.
 */
public interface DrawContext {

    void rect(float x, float y, float w, float h, int argb);

    void roundedRect(float x, float y, float w, float h, float radius, int argb);

    /** Rounded rect with independent per-corner radii. */
    void roundedRect(float x, float y, float w, float h, Corners perCorner, int argb);

    void rectStroke(float x, float y, float w, float h, float thickness, int argb);

    /**
     * Stroke a ROUNDED rectangle's outline — the missing primitive that forced components to
     * fake a rounded border with the SHARP {@link #rectStroke}, poking white/opaque corners
     * past the fill's rounded corners. Backends with a native rounded stroke (Skia) draw a
     * true rounded outline; the {@code default} degrades to {@link #rectStroke} for backends
     * that lack one (visually identical to the old behaviour, so no regression — callers that
     * WANT clean corners get them on the high-fidelity backends without any backend crashing).
     */
    default void roundedRectStroke(float x, float y, float w, float h, float radius,
                                   float thickness, int argb) {
        rectStroke(x, y, w, h, thickness, argb);
    }

    void line(float x0, float y0, float x1, float y1, float thickness, int argb);

    void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s);

    void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb);

    /** Fill/stroke an arbitrary polyline+arc path: ripple arcs, checkmarks, MD3 shapes. */
    void path(PathSpec path, PaintSpec paint);

    void pushClip(float x, float y, float w, float h);

    void popClip();

    /** Layer alpha in [0,1] for fades / MD3 state layers. Backends without native
     *  layer opacity fold it into emitted vertex colors. */
    void pushOpacity(float alpha);

    void popOpacity();

    /** Per-corner radii for {@link #roundedRect(float, float, float, float, Corners, int)}. */
    record Corners(float topLeft, float topRight, float bottomRight, float bottomLeft) {}
}
