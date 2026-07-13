package net.marcloud.mcp.dwm.backend;

/**
 * A hot-swappable rendering backend — the "form axis" of DWM. Everything above the
 * SPI (compositor, theme, components, UI) depends ONLY on this interface plus
 * {@link DrawContext}; concrete backends (imgui first, a future hi-fi one later)
 * live behind {@link BackendRegistry}. Swapping the backend changes nothing above
 * this line, and a missing backend degrades to {@code NullBackend} rather than
 * throwing (the "reflect, miss, degrade to no-op" idiom Board already uses).
 *
 * <p>Lifecycle: {@link #onAttach} binds to the live window/GL context, then each
 * frame runs {@link #beginFrame} -&gt; draw via {@link #draw()} -&gt;
 * {@link #endFrame}; {@link #onDetach} releases GPU-owned resources. Every
 * lifecycle call MUST be fault-isolated by the compositor so a bad backend cannot
 * corrupt the game frame.
 */
public interface RenderBackend {

    /** Stable id, e.g. {@code "imgui"}, {@code "null"}, a future {@code "hi-fi"}. */
    String id();

    /** What this backend can actually do (drives in-backend degradation). */
    BackendCaps caps();

    /** Bind to the live window/GL context. */
    void onAttach(BackendHost host);

    /** Release GPU-owned resources (fonts, textures, throwaway buffers). */
    void onDetach();

    void beginFrame(FrameInput in, FrameMetrics metrics);

    /** The per-frame primitive recorder. Valid only between begin/endFrame. */
    DrawContext draw();

    /** Flush recorded primitives to the GPU. */
    void endFrame();

    TextureHandle uploadTexture(TextureData rgba);

    void freeTexture(TextureHandle h);

    FontHandle loadFont(FontSpec spec);

    /** MUST work even in a headless NullBackend (fixed-advance approximation). */
    TextMetrics measureText(FontHandle f, CharSequence s, float sizePx);
}
