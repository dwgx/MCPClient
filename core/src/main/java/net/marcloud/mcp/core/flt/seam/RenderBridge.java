package net.marcloud.mcp.core.flt.seam;

/**
 * Static bridge the injected render-frame advice calls into — the render-frame twin
 * of {@link TickBridge}. Advice is inlined into {@code
 * EntityRenderer.updateCameraAndRender} (at method EXIT, after MC's own 2D GUI pass
 * and before the buffer swap), so it can only reach publicly-visible static members.
 * This forwards to a live {@link RenderFrameSink} wired at install time.
 *
 * <p>Why a plain sink and not an {@code EventBus}: the render-frame consumer is the
 * DWM content-overlay driver ({@code ComposeCompositor}), which lives in the
 * detachable {@code dwm} module. Core must not hard-depend on it, so the sink is a
 * neutral functional handle set at wiring time (reflectively when dwm is present,
 * left null when it is not). A null sink makes {@link #onRenderFrame} a no-op — the
 * "reflect, miss, degrade" idiom the project already uses.
 *
 * <p>Defensive: null-checks, swallows all faults (even {@link Error}). The advice
 * runs inlined on the game render thread; a fault here must NEVER disturb the frame.
 */
public final class RenderBridge {

    /** The render-frame consumer, kept GL/dwm-type-free so core stays decoupled. */
    @FunctionalInterface
    public interface RenderFrameSink {
        /** Called once per rendered frame, on the game render thread with GL current. */
        void onRenderFrame(long frameCounter);
    }

    private static volatile RenderFrameSink sink;
    private static volatile long frameCounter;

    private RenderBridge() {
    }

    /** Wire the render-frame consumer. Pass null to detach (frames become no-ops). */
    public static void setSink(RenderFrameSink s) {
        sink = s;
    }

    /** Called from injected advice at {@code updateCameraAndRender} exit. */
    public static void onRenderFrame() {
        frameCounter++;
        RenderFrameSink s = sink;
        if (s == null) {
            return;
        }
        try {
            s.onRenderFrame(frameCounter);
        } catch (Throwable ignored) {
            // Runs inlined on the render thread — never let an overlay fault (even an
            // Error) break the game frame. Mirrors TickBridge.onTick discipline.
        }
    }

    /** Current rendered-frame count (for diagnostics/tests). */
    public static long frameCounter() {
        return frameCounter;
    }

    /** Reset counter (for tests). */
    public static void resetCounter() {
        frameCounter = 0;
    }
}
