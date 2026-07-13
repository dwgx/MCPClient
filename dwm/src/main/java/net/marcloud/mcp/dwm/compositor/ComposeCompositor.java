package net.marcloud.mcp.dwm.compositor;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.ContentBackend;
import net.marcloud.mcp.dwm.backend.ContentBackendRegistry;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;

/**
 * The render-frame driver for the {@link ContentBackend} sibling — the tick-before-draw
 * {@link Compositor} analogue for tree-rendering backends. Where {@link Compositor}
 * sequences the animation-state lifecycle around an immediate-mode {@code DrawContext}
 * draw, this drives a self-rendering content overlay (Compose/Skia) that owns its own
 * tree and canvas: it bypasses {@link UiStateStore} / {@code DrawContext} entirely.
 *
 * <p>Each render frame, on the game thread with GL current, {@link #driveFrame} does:
 * <ol>
 *   <li>reconcile the attached backend against {@link ContentBackendRegistry#active()}
 *       (attach a newly-activated backend, detach a deactivated one);</li>
 *   <li>if the host's framebuffer id or size changed, {@link ContentBackend#resize};</li>
 *   <li>{@link ContentBackend#submitInput} the frame's input snapshot;</li>
 *   <li>{@link ContentBackend#renderFrame};</li>
 *   <li>report whether the backend consumed the pointer/keyboard, so the caller can
 *       swallow that input from the game.</li>
 * </ol>
 *
 * <p><b>Fault isolation.</b> Every backend call is wrapped swallow-all (Throwable),
 * mirroring the {@code TickBridge}/{@code HookBridge} discipline: a Skia or GL fault in
 * a backend must NEVER break the game render thread. On any fault the frame yields a
 * no-op outcome (nothing consumed) and the game renders unaffected. When no content
 * backend is active, {@link #driveFrame} is a cheap no-op — the primitive
 * {@link net.marcloud.mcp.dwm.backend.RenderBackend} axis is independent and untouched.
 */
public final class ComposeCompositor {

    /**
     * Color-format token handed to {@link ContentBackend#resize}: {@code 0} means
     * "the adapter's default" (RGBA8), which matches MC's default framebuffer. The
     * host does not expose a GL internal format, so the adapter maps this token.
     */
    public static final int DEFAULT_FB_COLOR_FORMAT = 0;

    /** Outcome of one driven frame: what (if anything) the overlay consumed. */
    public record FrameOutcome(boolean rendered, boolean consumedPointer, boolean consumedKeyboard) {
        /** No overlay active / faulted: nothing rendered, nothing consumed. */
        public static FrameOutcome idle() {
            return new FrameOutcome(false, false, false);
        }
    }

    private final BackendHost host;
    private final ContentBackendRegistry registry;

    /** The backend currently bound via onAttach, or null when the overlay is off. */
    private ContentBackend attached;

    // Last-seen framebuffer geometry, to detect a resize. -2 = "not yet observed".
    private int lastFbId = -2;
    private int lastFbW = -1;
    private int lastFbH = -1;

    private long frameId;

    public ComposeCompositor(BackendHost host, ContentBackendRegistry registry) {
        if (host == null || registry == null) {
            throw new IllegalArgumentException("host and registry must not be null");
        }
        this.host = host;
        this.registry = registry;
    }

    /** The backend currently attached, or null when the overlay is off. */
    public ContentBackend attached() {
        return attached;
    }

    public long frameId() {
        return frameId;
    }

    /**
     * Drive one content-overlay frame. Safe to call every game render frame; a no-op
     * (returns {@link FrameOutcome#idle()}) when no content backend is active or when a
     * backend faults. Runs on the game render thread with GL current.
     *
     * @param in        the frame's input snapshot (forwarded to the backend's input model)
     * @param scale     DIP-&gt;pixel factor for {@link FrameMetrics}
     * @param dtSeconds seconds since the previous frame (animation advance)
     * @param nanoTime  the frame time the backend's animation/recomposition clock advances to
     */
    public FrameOutcome driveFrame(FrameInput in, float scale, float dtSeconds, long nanoTime) {
        ContentBackend backend = reconcileActive();
        if (backend == null) {
            return FrameOutcome.idle();
        }
        try {
            int rawFbId = host.currentFramebufferId();
            int fbId = rawFbId < 0 ? 0 : rawFbId; // unknown (-1) -> default framebuffer 0
            int w = host.framebufferWidthPx();
            int h = host.framebufferHeightPx();
            if (fbId != lastFbId || w != lastFbW || h != lastFbH) {
                backend.resize(w, h, fbId, DEFAULT_FB_COLOR_FORMAT);
                lastFbId = fbId;
                lastFbW = w;
                lastFbH = h;
            }
            backend.submitInput(in == null ? FrameInput.none() : in);
            backend.renderFrame(new FrameMetrics(w, h, scale, dtSeconds, frameId), nanoTime);
            frameId++;
            return new FrameOutcome(true, backend.consumedPointer(), backend.consumedKeyboard());
        } catch (Throwable t) {
            // Swallow-all: a backend fault must never break the game render thread.
            System.err.println("[DWM ComposeCompositor] content frame faulted (overlay skipped): " + t);
            return FrameOutcome.idle();
        }
    }

    /**
     * Bring the attached backend in line with the registry's active backend: attach a
     * newly-activated one, detach a deactivated one. A resize is forced on the next
     * frame after any attach. Fault-isolated: an attach/detach that throws leaves the
     * driver with no attached backend rather than propagating.
     *
     * @return the backend now attached, or null when the overlay is off
     */
    private ContentBackend reconcileActive() {
        ContentBackend active = registry.active();
        if (active == attached) {
            return attached;
        }
        // Detach the outgoing backend first (if any).
        if (attached != null) {
            try {
                attached.onDetach();
            } catch (Throwable t) {
                System.err.println("[DWM ComposeCompositor] onDetach faulted: " + t);
            }
            attached = null;
        }
        // Attach the incoming backend (if any).
        if (active != null) {
            try {
                active.onAttach(host);
                attached = active;
                // Force a resize on the first frame against the new backend.
                lastFbId = -2;
                lastFbW = -1;
                lastFbH = -1;
            } catch (Throwable t) {
                System.err.println("[DWM ComposeCompositor] onAttach faulted (overlay disabled): " + t);
                attached = null;
            }
        }
        return attached;
    }

    /** Whether the active backend wants continuous frames (false when the overlay is off). */
    public boolean shouldRenderContinuously() {
        ContentBackend b = attached;
        if (b == null) {
            return false;
        }
        try {
            return b.wantsContinuousFrames();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Detach the current backend (e.g. at shutdown). Fault-isolated. */
    public void detachActive() {
        if (attached != null) {
            try {
                attached.onDetach();
            } catch (Throwable t) {
                System.err.println("[DWM ComposeCompositor] onDetach faulted: " + t);
            }
            attached = null;
        }
    }
}
