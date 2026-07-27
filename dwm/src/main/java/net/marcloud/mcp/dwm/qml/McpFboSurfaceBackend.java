package net.marcloud.mcp.dwm.qml;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.timer_err.qml4j.render.SurfaceBackend;

/**
 * qml4j {@link SurfaceBackend} that renders into MINECRAFT'S OWN framebuffer instead of a
 * GLFW window — the seam that lets a qml4j scene composite over the live game frame.
 *
 * <p><b>Not a window backend.</b> qml4j's reference {@code GlfwSurfaceBackend} owns a window
 * and swaps buffers in {@link #present()}; this one does neither. It wraps the framebuffer id
 * MC currently has bound, fed in each frame by the driver, and {@link #present()} only
 * flushes Skia — MC swaps downstream. On macOS this is not merely an optimisation: GLFW owns
 * the process main thread under {@code -XstartOnFirstThread} and AppKit requires window event
 * loops to live there, so a second window system has no thread to run on. Rendering into MC's
 * framebuffer is the only shape available, and it happens to be the one dwm's own contract
 * asks for.
 *
 * <p><b>Per-frame retarget is the resize fix.</b> {@link #frameTarget} runs every frame with
 * the live size and FBO id; when either moved, the surface AND the {@link DirectContext} are
 * rebuilt. A resize makes MC delete and recreate its framebuffer's GL objects, often under a
 * recycled id, so anything cached across that change leaves Skia's resource cache keyed to
 * freed objects — which shows up as "the world goes black, but only after a resize". Nothing
 * is kept across a size or FBO change.
 *
 * <p><b>Stencil is 0 on purpose.</b> MC's {@code framebufferMc} has no stencil attachment, and
 * asking {@link BackendRenderTarget#makeGL} for one the target lacks makes the wrap return
 * null. (A probe against the <i>default</i> framebuffer succeeds with 8 bits, which is exactly
 * the trap: the number that works there is wrong here.)
 *
 * <p><b>GL isolation is the driver's job.</b> Skija issues raw GL that disturbs the state MC's
 * {@code GlStateManager} shadows; {@link QmlGuiScreen} brackets each frame with
 * {@link GlStateGuard} and this class calls {@code resetGLAll} on the way out. This class owns
 * only the surface lifecycle.
 *
 * <p>Every native call is fault-isolated: a fault leaves {@code surface == null} and frames
 * no-op rather than throwing on the render thread.
 */
public final class McpFboSurfaceBackend implements SurfaceBackend {

    private DirectContext context;
    private BackendRenderTarget target;
    private Surface surface;

    private int width = 1;
    private int height = 1;
    /** MC's currently-bound framebuffer; -1 = unknown. */
    private int fboId = -1;
    /**
     * Set once {@link #dispose()} closed the native objects; guards against a use-after-free
     * if dispose ever races a mid-flight frame (a shutdown hook, say).
     */
    private volatile boolean disposed;

    /**
     * Whether a wrap has been attempted at the current size/FBO id, successful or not. Lets the
     * first frame build a surface while still trying a FAILING wrap only once per parameter set.
     */
    private boolean attempted;

    @Override
    public void init(int w, int h) {
        this.width = Math.max(1, w);
        this.height = Math.max(1, h);
        try {
            // Do NOT call GL.createCapabilities() here: MC already established the GL
            // capabilities on this thread. We only need Skia's own context object.
            context = DirectContext.makeGL();
        } catch (Throwable t) {
            System.err.println("[dwm] FBO backend init faulted (inert): " + t);
            context = null;
        }
    }

    /**
     * Point the surface at MC's framebuffer for this frame, rebuilding when size or FBO id
     * moved. Called at the top of each frame, before qml4j renders.
     *
     * <p>A non-positive {@code liveFboId} means "keep the last valid one": a queried 0 or -1 is
     * the default framebuffer or unknown, never MC's own, and adopting it would wrap the wrong
     * target.
     */
    public void frameTarget(int w, int h, int liveFboId) {
        if (disposed) {
            return;
        }
        int nw = Math.max(1, w);
        int nh = Math.max(1, h);
        boolean fboMoved = liveFboId > 0 && liveFboId != fboId;
        boolean sizeMoved = nw != width || nh != height;
        // Never having built a surface is its own reason to build one. init() records the size
        // without wrapping, so a caller whose framebuffer is the DEFAULT one (id 0 — every live
        // IT, and any host not rendering into an FBO of its own) moved neither the id nor the
        // size and fell into the early return below, forever: no surface, so the driver never
        // reached composite(), so qml4j never rendered and never even laid the scene out. The
        // symptom was silence rather than an error, which is why tests asserting "frames do not
        // throw" could not see it; a resize was the only thing that ever recovered it.
        //
        // Guarded by attempted rather than by hasSurface(), so a wrap that genuinely FAILS is
        // tried exactly once at these params instead of thrashing a Skia context
        // create/destroy every frame — the hazard the unchanged-params return below exists for.
        if (!attempted && !fboMoved && !sizeMoved) {
            width = nw;
            height = nh;
            rebuild();
            return;
        }
        if (!fboMoved && !sizeMoved) {
            // Unchanged. Reuse the wrap if we have one; if the last wrap FAILED at these exact
            // params, do not retry now — that thrashes a full Skia context create/destroy every
            // frame while the target stays incomplete. Wait for a real size/FBO change.
            return;
        }
        width = nw;
        height = nh;
        if (fboMoved) {
            fboId = liveFboId;
        }
        rebuild();
    }

    /** Rebuild context + surface over the current size / FBO id. Fault-isolated. */
    private void rebuild() {
        // Marked before the work, not after: a wrap that throws must still count as attempted,
        // or the retry guard above would let it run again on the very next frame.
        attempted = true;
        closeSurface();
        // The offscreen layer's GPU objects belong to the context about to be dropped, so it has
        // to go too — reusing it against a new context is the same stale-cache fault that turns
        // the world black after a resize.
        layer.close();
        // Rebuild the context too, not just the surface: on a genuine resize MC deleted and
        // recreated its framebuffer's GL objects, so a context whose cache is keyed to the OLD
        // ones would discard the world pixels MC just drew.
        try {
            if (context != null) {
                context.close();
            }
        } catch (Throwable ignored) {
            // Closing a context whose GL objects are already gone is not actionable.
        }
        context = null;
        try {
            context = DirectContext.makeGL();
            int fb = fboId > 0 ? fboId : 0;
            target = BackendRenderTarget.makeGL(width, height, 0, 0, fb,
                    FramebufferFormat.GR_GL_RGBA8);
            surface = Surface.makeFromBackendRenderTarget(
                    context, target,
                    SurfaceOrigin.BOTTOM_LEFT,
                    ColorType.RGBA_8888,
                    ColorSpace.getSRGB());
            if (surface == null) {
                System.err.println("[dwm] wrap returned null for FBO " + fb
                        + " at " + width + "x" + height + " — UI inert until it changes.");
            }
        } catch (Throwable t) {
            System.err.println("[dwm] FBO rebuild faulted (inert): " + t);
            surface = null;
        }
    }

    /**
     * The display's DPI scale, applied to the canvas so the scene can be authored in logical
     * units. Set by the driver each frame from {@code Display.getContentScaleX()}.
     */
    private float uiScale = 1.0F;

    /** The offscreen layer the scene is painted into, when compositing is enabled. */
    private final RedirectionSurface layer = new RedirectionSurface();

    /**
     * True while qml4j should paint into the offscreen layer rather than straight at MC's
     * framebuffer. Set by the driver for the frames on which the scene actually needs redrawing.
     */
    private boolean sceneToLayer;

    /**
     * Set the logical-to-device scale. Never touches the surface, so it is free to change.
     *
     * <p>Must be the DPI content scale, not the framebuffer/window ratio: the two agree on a
     * Retina Mac but not on Windows, where the ratio is 1.0 at every DPI setting.
     */
    void setUiScale(float scale) {
        this.uiScale = (scale > 0.0F && !Float.isInfinite(scale)) ? scale : 1.0F;
    }

    /**
     * Prepare the offscreen layer for a scene repaint.
     *
     * @return true if the scene should be painted into the layer this frame; false means
     *         compositing is unavailable and the caller should paint direct instead
     */
    boolean beginLayerScene() {
        sceneToLayer = layer.ensure(context, width, height);
        return sceneToLayer;
    }

    /** Finish a layer repaint, caching the result for reuse on subsequent frames. */
    void endLayerScene() {
        layer.endScene();
        sceneToLayer = false;
    }

    /** True when a cached scene image exists and can be composited without a repaint. */
    boolean hasLayerSnapshot() {
        return layer.hasSnapshot();
    }

    /**
     * Blit the cached scene over MC's frame. The cheap half of the loop, run every frame.
     *
     * <p>Both surfaces come from the same {@link DirectContext}, so this stays on the GPU — no
     * readback — and it is what lets an idle UI cost one textured quad instead of a full repaint.
     */
    void compositeLayer() {
        if (disposed || surface == null || !layer.hasSnapshot()) {
            return;
        }
        try {
            Canvas canvas = surface.getCanvas();
            // The snapshot is already in device pixels, so composite with an identity transform:
            // the UI scale was applied when the scene was painted into the layer.
            canvas.resetMatrix();
            canvas.drawImage(layer.snapshot(), 0.0F, 0.0F);
        } catch (Throwable t) {
            System.err.println("[dwm] composite faulted: " + t);
        }
    }

    @Override
    public Canvas acquireCanvas() {
        // No clear() of MC's framebuffer, unlike an opaque window backend: this composites OVER
        // the finished game frame, so clearing to opaque black would erase the game.
        if (disposed) {
            return null;
        }
        // Scene repaints go to the offscreen layer; it clears itself to transparent and is
        // reused for as long as nothing in the scene changes.
        if (sceneToLayer) {
            Canvas canvas = layer.beginScene();
            if (canvas != null) {
                if (uiScale != 1.0F) {
                    canvas.scale(uiScale, uiScale);
                }
                return canvas;
            }
            // Layer unavailable: fall through and paint direct rather than lose the frame.
            sceneToLayer = false;
        }
        if (surface == null) {
            return null;
        }
        Canvas canvas = surface.getCanvas();
        // getCanvas() hands back the same canvas every frame, so the scale has to be reset or
        // it compounds — frame two would draw at 4x, frame three at 8x.
        canvas.resetMatrix();
        if (uiScale != 1.0F) {
            canvas.scale(uiScale, uiScale);
        }
        return canvas;
    }

    @Override
    public DirectContext recordingContext() {
        return context;
    }

    @Override
    public void present() {
        // Flush Skia's queued draws into MC's FBO; do NOT swap buffers, MC swaps downstream.
        // resetGLAll tells Skia that outside code will touch GL next; GlStateGuard.leave()
        // then restores what MC expects.
        try {
            if (!disposed && context != null && surface != null) {
                context.flush();
                context.resetGLAll();
            }
        } catch (Throwable t) {
            System.err.println("[dwm] present faulted: " + t);
        }
    }

    @Override
    public void resize(int w, int h) {
        frameTarget(w, h, fboId);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /** Whether a live surface is currently wrapped. Used by the driver's fault checks. */
    public boolean hasSurface() {
        return surface != null && !disposed;
    }

    private void closeSurface() {
        try {
            if (surface != null) {
                surface.close();
            }
        } catch (Throwable ignored) {
            // Already-freed natives are not actionable during teardown.
        }
        surface = null;
        try {
            if (target != null) {
                target.close();
            }
        } catch (Throwable ignored) {
            // As above.
        }
        target = null;
    }

    @Override
    public void dispose() {
        disposed = true;
        layer.close();
        closeSurface();
        try {
            if (context != null) {
                context.close();
            }
        } catch (Throwable ignored) {
            // As above.
        }
        context = null;
    }
}
