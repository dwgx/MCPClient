package net.marcloud.mcp.dwm.backend;

/**
 * The tree-rendering peer of {@link RenderBackend} — the sibling seam for backends
 * that own their OWN retained UI tree instead of replaying primitives. Where a
 * {@link RenderBackend} is a RETAINED-PRIMITIVE consumer (the DWM component tree
 * walks itself and emits rect/text/path into a {@link DrawContext} the backend
 * replays), a {@code ContentBackend} is the inverse: it owns its own retained tree
 * (e.g. a Compose {@code @Composable} graph), its own measure/layout/recompose
 * loop, its own Skia canvas, and its own input model. It never receives
 * {@link DrawContext} primitives and DWM components never drive it.
 *
 * <p>This is why Compose gets a sibling SPI rather than being forced behind
 * {@link RenderBackend#draw()}: routing a tree renderer through {@code draw()} would
 * hand back a dead no-op {@code DrawContext} while all real work went through side
 * channels — an abuse of the frozen primitive contract. Both SPIs bind through the
 * same {@link BackendHost} so hot-swap and degrade semantics are shared, but they
 * are independent axes over the same window/GL context: a frame may run the active
 * {@link RenderBackend}'s primitive pass and then a {@code ContentBackend}'s content
 * pass composited last as an overlay.
 *
 * <p>Lifecycle mirrors {@link RenderBackend}: {@link #onAttach} binds to the live
 * window/GL context and builds the scene + render target; each render frame runs
 * {@link #resize} (only when the framebuffer changed) -&gt; {@link #submitInput}
 * -&gt; {@link #renderFrame}; {@link #onDetach} releases GPU-owned resources. Every
 * lifecycle call MUST be fault-isolated by the driver so a bad backend cannot
 * corrupt the game render thread. Discovery is reflective and degrade-to-absent:
 * when the adapter module (and its Kotlin/Skiko runtime) is not present, the
 * registry simply has no content backend and the host renders no overlay — it never
 * throws (the "reflect, miss, degrade to no-op" idiom Board already uses).
 */
public interface ContentBackend {

    /** Stable id, e.g. {@code "compose"}. */
    String id();

    /** What this backend can actually do (path/clip/per-corner/opacity/tint + max texture). */
    BackendCaps caps();

    /**
     * Bind to the live window/GL context and build the scene + recomposer + render
     * target. Called on the render thread with the GL context current.
     */
    void onAttach(BackendHost host);

    /** Release GPU-owned resources (scene, recomposer, surface, render target, context). */
    void onDetach();

    /**
     * Rebuild the render target for a new framebuffer geometry. Called only when the
     * host reports the framebuffer id or size changed since the last frame.
     *
     * @param fbWidth  framebuffer width in pixels
     * @param fbHeight framebuffer height in pixels
     * @param fbId     the target GL framebuffer id ({@code 0} = default framebuffer)
     * @param fbFormat the target color format token the adapter maps to a GL internal format
     */
    void resize(int fbWidth, int fbHeight, int fbId, int fbFormat);

    /** Drain a per-frame input snapshot into the backend's own input model. */
    void submitInput(FrameInput in);

    /**
     * Render one content frame: the guarded enter -&gt; frame pump -&gt; flush -&gt;
     * guarded leave sequence. Runs on the render thread with GL current.
     *
     * @param m        per-frame geometry + timing
     * @param nanoTime the frame time the animation/recomposition clock advances to
     */
    void renderFrame(FrameMetrics m, long nanoTime);

    /**
     * True when the scene has a live animation or a pending recomposition, so the
     * host should keep pumping frames rather than drawing on demand.
     */
    boolean wantsContinuousFrames();

    /**
     * True when the backend's hit-test consumed the pointer this frame (the {@code
     * io.wantCaptureMouse} analogue), so the driver swallows that click from the
     * game instead of letting it fall through to the world.
     */
    boolean consumedPointer();

    /** True when the backend consumed keyboard input this frame (swallow from the game). */
    boolean consumedKeyboard();
}
