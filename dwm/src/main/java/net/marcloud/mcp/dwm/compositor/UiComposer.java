package net.marcloud.mcp.dwm.compositor;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.BackendRegistry;
import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.RenderBackend;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.FrameComponentContext;

/**
 * The render-frame driver for the {@link RenderBackend} "form axis" — the missing
 * animator that walks the MD3 component tree into a backend's {@link DrawContext} each
 * frame. It is the DrawContext-axis twin of {@code ComposeCompositor} (which drives the
 * self-rendering {@code ContentBackend} axis): where that hands a tree renderer its own
 * canvas, this OWNS the tree walk and emits primitives, so the SAME MD3 components render
 * on whatever {@link RenderBackend} is active (imgui, a future Skia backend, or the
 * headless {@link net.marcloud.mcp.dwm.backend.NullBackend} floor).
 *
 * <p>Per render frame, on the game thread with GL current, {@link #driveFrame}:
 * <ol>
 *   <li>reconciles the attached backend against {@link BackendRegistry#active()}
 *       (attach a newly-activated backend, detach the outgoing one);</li>
 *   <li>{@link Compositor#beginFrame} — advances every animation timeline once
 *       (tick-before-draw);</li>
 *   <li>{@link RenderBackend#beginFrame} → build a {@link FrameComponentContext} bound to
 *       the backend's live {@link DrawContext} → {@code root.render(...)} →
 *       {@link RenderBackend#endFrame} (flush to GPU);</li>
 *   <li>{@link Compositor#endFrame} — grace-period GC of stale animation state.</li>
 * </ol>
 *
 * <p><b>Fault isolation.</b> Every backend + component call is wrapped swallow-all
 * (Throwable), mirroring {@code ComposeCompositor}: a backend/GL/component fault must
 * NEVER break the game render thread. The animation frame is always closed even if the
 * draw throws, so the state store's tick/gc invariant holds. When no backend is active
 * (registry returns the {@code NullBackend} floor), the tree still ticks + renders into a
 * no-op {@link DrawContext} — cheap and harmless — so animations stay warm for an
 * instant backend swap.
 */
public final class UiComposer {

    private final BackendHost host;
    private final BackendRegistry registry;
    private final Compositor compositor;
    private final FrameComponentContext ctx;
    private final Component root;

    /** The backend currently bound via onAttach, or null before the first reconcile. */
    private RenderBackend attached;

    private int lastFbW = -1;
    private int lastFbH = -1;

    /**
     * @param host       the live window/GL facts (passed to each backend's onAttach)
     * @param registry   the hot-swap point; {@link BackendRegistry#active()} is the backend to drive
     * @param compositor the animation-state lifecycle owner (tick-before-draw / gc-after-draw)
     * @param ctx        the reused per-frame component context (rebound each frame)
     * @param root       the root MD3 component to render each frame
     */
    public UiComposer(BackendHost host, BackendRegistry registry, Compositor compositor,
                      FrameComponentContext ctx, Component root) {
        if (host == null || registry == null || compositor == null || ctx == null || root == null) {
            throw new IllegalArgumentException("host, registry, compositor, ctx and root must not be null");
        }
        this.host = host;
        this.registry = registry;
        this.compositor = compositor;
        this.ctx = ctx;
        this.root = root;
    }

    /** The backend currently attached, or null. */
    public RenderBackend attached() {
        return attached;
    }

    /**
     * Drive one UI frame. Safe to call every game render frame; fault-isolated so a
     * backend or component fault yields a skipped frame, never a broken game thread.
     *
     * @param in        the frame's input snapshot
     * @param scale     DIP-&gt;pixel factor for {@link FrameMetrics}
     * @param dtSeconds seconds since the previous frame (animation advance)
     */
    public void driveFrame(FrameInput in, float scale, float dtSeconds) {
        RenderBackend backend = reconcileActive();
        if (backend == null) {
            return; // no backend (not even a Null floor) — nothing to drive
        }

        int w = host.framebufferWidthPx();
        int h = host.framebufferHeightPx();
        if (w <= 0) {
            w = 1;
        }
        if (h <= 0) {
            h = 1;
        }
        FrameInput input = in == null ? FrameInput.none() : in;
        FrameMetrics metrics = new FrameMetrics(w, h, scale, dtSeconds, compositor.frameId());

        // tick-before-draw: advance animation timelines once before geometry is read.
        // CRITICAL bracketing: once backend.beginFrame() runs it has done guard.enter()
        // + pushed matrices / bound a canvas, so backend.endFrame() (which runs
        // guard.leave() + pops + restores MC's shadow) MUST run on EVERY exit path — a
        // component throwing mid-tree must NOT orphan the guard, or the black/white/
        // invisible GL-state-desync class returns on the next frame. So endFrame() is in
        // finally, not the try body. compositor.endFrame() (animation GC) likewise.
        boolean framed = false;
        boolean backendBegun = false;
        try {
            compositor.beginFrame(dtSeconds);
            framed = true;

            backend.beginFrame(input, metrics);
            backendBegun = true;
            DrawContext draw = backend.draw();
            if (draw != null) {
                ctx.bind(draw, input, metrics);
                // Full-viewport root; components lay out within (0,0,w,h) DIP.
                root.render(ctx, 0f, 0f, w, h);
            }
            lastFbW = w;
            lastFbH = h;
        } catch (Throwable t) {
            System.err.println("[DWM UiComposer] UI frame faulted (frame skipped): " + t);
        } finally {
            // Close the backend frame FIRST (guard.leave + matrix/scissor restore + MC
            // shadow write-through), then the animation frame. Both fault-isolated so
            // one failing cannot skip the other.
            if (backendBegun) {
                try {
                    backend.endFrame();
                } catch (Throwable t) {
                    System.err.println("[DWM UiComposer] backend endFrame faulted: " + t);
                }
            }
            if (framed) {
                try {
                    compositor.endFrame();
                } catch (Throwable t) {
                    System.err.println("[DWM UiComposer] endFrame (state gc) faulted: " + t);
                }
            }
        }
    }

    /**
     * Bring the attached backend in line with the registry's active backend: detach the
     * outgoing, attach the incoming (fault-isolated). Returns the backend now attached,
     * or null if none/attach failed.
     */
    private RenderBackend reconcileActive() {
        RenderBackend active = registry.active();
        if (active == attached) {
            return attached;
        }
        if (attached != null) {
            try {
                attached.onDetach();
            } catch (Throwable t) {
                System.err.println("[DWM UiComposer] onDetach faulted: " + t);
            }
            attached = null;
        }
        if (active != null) {
            try {
                active.onAttach(host);
                attached = active;
                lastFbW = -1;
                lastFbH = -1;
            } catch (Throwable t) {
                System.err.println("[DWM UiComposer] onAttach faulted (backend disabled): " + t);
                attached = null;
            }
        }
        return attached;
    }

    /** Whether the active backend has a live animation wanting another frame. */
    public boolean shouldRenderContinuously() {
        return compositor.shouldRenderContinuously();
    }

    /** Detach the current backend (e.g. at shutdown). Fault-isolated. */
    public void detachActive() {
        if (attached != null) {
            try {
                attached.onDetach();
            } catch (Throwable t) {
                System.err.println("[DWM UiComposer] onDetach faulted: " + t);
            }
            attached = null;
        }
    }
}
