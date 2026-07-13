package net.marcloud.mcp.dwm.compositor;

/**
 * The per-frame orchestrator: owns the {@link UiStateStore} and drives the
 * tick-before-draw / gc-after-draw discipline that lets immediate-mode drawing
 * host retained MD3 animations. It holds NO backend type (the render backend is
 * driven separately); it only sequences the animation-state lifecycle around the
 * caller's draw.
 *
 * <p>Usage per frame:
 * <pre>
 *   compositor.beginFrame(dtSeconds);   // tickAll: advance every timeline once
 *   ... UI builds the component tree, calling store().state(...) + drawing ...
 *   compositor.endFrame();              // endFrameGc: evict stale states
 * </pre>
 *
 * <p>{@link #shouldRenderContinuously()} reflects whether any animation is live,
 * so the host can pump frames continuously while something animates and fall back
 * to on-demand (redraw only on input) when idle — avoiding a permanent full-rate
 * redraw over the game.
 */
public final class Compositor {

    private final UiStateStore store;
    private long frameId;
    private boolean inFrame;

    public Compositor() {
        this(new DefaultUiStateStore());
    }

    public Compositor(UiStateStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    /** The retained animation-state store components draw against. */
    public UiStateStore store() {
        return store;
    }

    /** Current frame id (advances each endFrame). */
    public long frameId() {
        return frameId;
    }

    /**
     * Start a frame: advance every live animation timeline ONCE, before any drawing
     * reads current values (the tick-before-draw invariant). Must be paired with
     * {@link #endFrame()}.
     */
    public void beginFrame(float dtSeconds) {
        if (inFrame) {
            throw new IllegalStateException("beginFrame called twice without endFrame");
        }
        inFrame = true;
        store.tickAll(dtSeconds);
    }

    /**
     * End a frame: evict states not touched this frame (grace-period GC) and advance
     * the frame id.
     */
    public void endFrame() {
        if (!inFrame) {
            throw new IllegalStateException("endFrame called without beginFrame");
        }
        store.endFrameGc(frameId);
        frameId++;
        inFrame = false;
    }

    /**
     * Whether a live animation wants another frame. Host pumps continuously while
     * true, on-demand otherwise.
     */
    public boolean shouldRenderContinuously() {
        return store.anyAnimating();
    }
}
