package net.marcloud.mcp.dwm.skiko;

import org.jetbrains.skia.BackendRenderTarget;
import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.ColorSpace;
import org.jetbrains.skia.DirectContext;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.FontMgr;
import org.jetbrains.skia.FontStyle;
import org.jetbrains.skia.Surface;
import org.jetbrains.skia.SurfaceColorFormat;
import org.jetbrains.skia.SurfaceOrigin;
import org.jetbrains.skia.SurfaceProps;
import org.jetbrains.skia.Typeface;

import net.marcloud.mcp.dwm.backend.BackendCaps;
import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FontSpec;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.RenderBackend;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.backend.TextureData;
import net.marcloud.mcp.dwm.backend.TextureHandle;
import net.marcloud.mcp.dwm.gl.GlStateGuard;

/**
 * Skia (Skiko) {@link RenderBackend} — the highest-fidelity DrawContext-axis backend.
 * Wraps MC's live GL framebuffer as a Skia {@link Surface} and drives the DWM MD3 tree
 * through a {@link SkikoDrawContext} (true antialiased rounded rects, per-corner radii,
 * real font text). Pure Java against {@code org.jetbrains.skia.*}; factories reached via
 * the Kotlin {@code Companion} (e.g. {@code DirectContext.Companion.makeGL()}).
 *
 * <p><b>GL reconciliation (double).</b> Skia's raw GL disturbs the same state MC's
 * GlStateManager shadows, so this is bracketed by {@link GlStateGuard} enter/leave (the
 * black/white/invisible fix, reused verbatim) AND by {@link DirectContext#resetGLAll()}
 * before and after Skia draws (Skia's own GL-state tracker reset — the Skia analog of
 * GrContext::resetContext, exactly what the proven Compose backend did).
 *
 * <p><b>FBO wrap.</b> onAttach reads the currently-bound framebuffer id + size from the
 * host, builds a {@link BackendRenderTarget#makeGL} over it with stencil 0 (MC's FBO has
 * no stencil attachment and clipRect AA needs none), and a {@link Surface} with {@code
 * BOTTOM_LEFT} origin. Resize rebuilds the
 * target. Native load is triggered on first Skia touch ({@code org.jetbrains.skiko.Library}
 * extraction, launch flags {@code -Dskiko.renderApi=OPENGL} + optional {@code
 * -Dskiko.library.path}).
 */
public final class SkikoRenderBackend implements RenderBackend {

    private static final int GR_GL_RGBA8 = 0x8058;

    private final GlStateGuard guard = new GlStateGuard();
    private final SkikoDrawContext dc = new SkikoDrawContext();

    private DirectContext ctx;
    private BackendRenderTarget rt;
    private Surface surface;
    private Font font;

    private BackendHost host;
    private int fbW = 1;
    private int fbH = 1;
    private int mcFbId;
    private boolean inFrame;
    private boolean entered;    // guard.enter() done; endFrame must guard.leave()
    private int saveBaseline = -1; // canvas save count at frame start (for rebalance)
    private boolean loggedFirstFrame;

    @Override
    public String id() {
        return "skiko";
    }

    @Override
    public BackendCaps caps() {
        // Skia does it all: path, clip, per-corner radius, layer opacity, and could do
        // native shadow (deferred). Generous max texture size.
        return new BackendCaps(true, true, true, true, false, 8192);
    }

    @Override
    public void onAttach(BackendHost host) {
        try {
            this.host = host;
            fbW = Math.max(1, host.framebufferWidthPx());
            fbH = Math.max(1, host.framebufferHeightPx());
            mcFbId = Math.max(0, host.currentFramebufferId());
            ctx = DirectContext.Companion.makeGL();
            font = defaultFont();
            buildSurface();
            System.err.println("[SkikoRenderBackend] attached: ctx=" + (ctx != null)
                    + " fb=" + fbW + "x" + fbH + " mcFbo=" + mcFbId + " surface=" + (surface != null));
        } catch (Throwable t) {
            System.err.println("[SkikoRenderBackend] onAttach faulted (backend disabled): " + t);
            onDetach();
        }
    }

    /** Load the platform default typeface at the MD3 label size (14dp). */
    private static Font defaultFont() {
        try {
            FontMgr mgr = FontMgr.Companion.getDefault();
            FontStyle normal = FontStyle.Companion.getNORMAL();
            // matchFamilyStyle(null,...) returned null on the live system, so a Font with
            // no typeface no-ops in drawString (container renders, text does not). Resolve
            // a REAL typeface: try common Windows families, then legacyMakeTypeface, then
            // the FontMgr's own first enumerated family. Only fall back to the empty Font
            // (still no text) if none resolve.
            String[] families = {"Segoe UI", "Arial", "Tahoma", "Verdana", "sans-serif"};
            Typeface tf = null;
            for (String fam : families) {
                tf = mgr.matchFamilyStyle(fam, normal);
                if (tf != null) {
                    break;
                }
            }
            if (tf == null) {
                try {
                    tf = mgr.legacyMakeTypeface("Segoe UI", normal);
                } catch (Throwable ignored) {
                }
            }
            if (tf == null && mgr.getFamiliesCount() > 0) {
                tf = mgr.matchFamilyStyle(mgr.getFamilyName(0), normal);
            }
            System.err.println("[SkikoRenderBackend] typeface resolved=" + (tf != null)
                    + " (fontMgr families=" + mgr.getFamiliesCount() + ")");
            return tf != null ? new Font(tf, 14f) : new Font();
        } catch (Throwable t) {
            System.err.println("[SkikoRenderBackend] defaultFont faulted: " + t);
            return new Font();
        }
    }

    /** (Re)build the Skia surface over MC's FBO. RGBA8, stencil 0, BOTTOM_LEFT origin. */
    private void buildSurface() {
        closeSurface();
        if (ctx == null) {
            return;
        }
        // Stencil bits = 0: MC's framebufferMc has NO stencil attachment (createFramebuffer
        // allocates a DEPTH24 renderbuffer only), and SkikoDrawContext clips with clipRect AA
        // (analytic coverage) not clipRRect (which alone would need a stencil buffer).
        // Requesting a stencil the real FBO lacks can make makeFromBackendRenderTarget return
        // null (inert overlay) or corrupt GL state; 0 matches the actual FBO.
        rt = BackendRenderTarget.Companion.makeGL(fbW, fbH, 0, 0, mcFbId, GR_GL_RGBA8);
        surface = Surface.Companion.makeFromBackendRenderTarget(
                ctx, rt,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.Companion.getSRGB(),
                new SurfaceProps());
        if (surface == null) {
            System.err.println("[SkikoRenderBackend] makeFromBackendRenderTarget returned null "
                    + "(FBO mismatch) — overlay inert this frame.");
        }
    }

    private void closeSurface() {
        try {
            if (surface != null) {
                surface.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (rt != null) {
                rt.close();
            }
        } catch (Throwable ignored) {
        }
        surface = null;
        rt = null;
    }

    @Override
    public void onDetach() {
        closeSurface();
        // Close the Font (+ its Typeface) and the DrawContext's Paint — Skiko Managed
        // objects hold native memory and must be close()'d, or a hot-swap / attach-fault
        // loop leaks native heap. Each fault-isolated; null-out so a second detach no-ops.
        try {
            if (font != null && !font.isClosed()) {
                font.close();
            }
        } catch (Throwable ignored) {
        }
        font = null;
        try {
            dc.close();
        } catch (Throwable ignored) {
        }
        try {
            if (ctx != null) {
                ctx.close();
            }
        } catch (Throwable ignored) {
        }
        ctx = null;
    }

    /**
     * Whether the live FBO id should replace the currently-wrapped one. Only a positive id
     * (a real framebufferMc) can move the target; a queried id {@code <= 0} is "unknown"
     * ({@code -1} sentinel) or the default framebuffer ({@code 0}), never MC's own FBO, so
     * adopting it would rebuild the surface over the wrong target and drop a valid wrap.
     * Pure integer decision, split out for headless coverage of the guard.
     */
    static boolean fbTargetMoved(int liveFbId, int wrappedFbId) {
        return liveFbId > 0 && liveFbId != wrappedFbId;
    }

    @Override
    public void beginFrame(FrameInput in, FrameMetrics metrics) {
        int w = Math.max(1, metrics.widthPx());
        int h = Math.max(1, metrics.heightPx());
        // Re-query the live FBO id each frame: MC can recreate framebufferMc (resize,
        // display-mode change, resource reload). Rebuild the surface if id OR size moved,
        // else Skia wraps a stale/free'd FBO (blank overlay or GL errors).
        // A queried id <= 0 is "unknown/default" (the host returns -1 when it cannot resolve
        // the binding; 0 is the default framebuffer, never MC's framebufferMc). Do NOT adopt
        // it as the wrap target: that would rebuild over the wrong FBO and drop the valid
        // surface. Keep the last valid mcFbId; only a positive id can move the target.
        int rawFb = host.currentFramebufferId();
        boolean fbMoved = fbTargetMoved(rawFb, mcFbId);
        if (fbMoved || w != fbW || h != fbH) {
            fbW = w;
            fbH = h;
            if (fbMoved) {
                mcFbId = rawFb;
            }
            buildSurface();
        }
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            System.err.println("[SkikoRenderBackend] first frame: fb=" + fbW + "x" + fbH
                    + " surface=" + (surface != null) + " (UI drawing)");
        }
        // ATOMIC begin: guard.enter() first; if setup throws, unwind (guard.leave) and
        // rethrow so guard.leave() is never orphaned.
        guard.enter();
        entered = true;
        try {
            if (ctx != null) {
                ctx.resetGLAll(); // tell Skia the outside world touched GL
            }
            Canvas canvas = surface != null ? surface.getCanvas() : null;
            // Capture the canvas save baseline so unwind can restore any unbalanced saves
            // (a component that pushClip without popClip, or throws mid-clip).
            saveBaseline = canvas != null ? canvas.getSaveCount() : -1;
            dc.bind(canvas, font);
            inFrame = true;
        } catch (Throwable t) {
            unwind();
            throw t;
        }
    }

    @Override
    public DrawContext draw() {
        return dc;
    }

    @Override
    public void endFrame() {
        // Run whenever begin entered the guard, even if setup faulted before inFrame.
        if (!entered) {
            return;
        }
        unwind();
    }

    /** Rebalance canvas saves, flush Skia, reset Skia+MC GL state. Always guard.leave. */
    private void unwind() {
        try {
            if (surface != null) {
                Canvas canvas = surface.getCanvas();
                // Restore any saves the component tree left unbalanced (H2) so the next
                // frame starts from a clean transform/clip stack.
                if (canvas != null && saveBaseline >= 0) {
                    try {
                        canvas.restoreToCount(saveBaseline);
                    } catch (Throwable ignored) {
                    }
                }
                if (ctx != null) {
                    ctx.flushAndSubmit(surface, false);
                    ctx.resetGLAll(); // Skia touched GL; reset before handing back to MC
                }
            }
        } catch (Throwable t) {
            System.err.println("[SkikoRenderBackend] unwind faulted: " + t);
        } finally {
            saveBaseline = -1;
            inFrame = false;
            entered = false;
            guard.leave(); // raw-GL restore + MC shadow write-through — ALWAYS
        }
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
        return new FontHandle(0L); // default typeface for now
    }

    @Override
    public TextMetrics measureText(FontHandle f, CharSequence s, float sizePx) {
        try {
            if (font != null && s != null) {
                float w = font.measureTextWidth(s.toString());
                return new TextMetrics(w, sizePx * 0.8f, sizePx * 0.2f);
            }
        } catch (Throwable ignored) {
        }
        int n = s == null ? 0 : s.length();
        return new TextMetrics(n * sizePx * 0.5f, sizePx * 0.8f, sizePx * 0.2f);
    }
}
