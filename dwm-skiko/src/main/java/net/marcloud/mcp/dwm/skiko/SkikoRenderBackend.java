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
 * host, builds a {@link BackendRenderTarget#makeGL} over it with stencil 8 (Skia needs
 * stencil), and a {@link Surface} with {@code BOTTOM_LEFT} origin. Resize rebuilds the
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

    private int fbW = 1;
    private int fbH = 1;
    private int mcFbId;
    private boolean inFrame;
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

    /** (Re)build the Skia surface over MC's FBO. RGBA8, stencil 8, BOTTOM_LEFT origin. */
    private void buildSurface() {
        closeSurface();
        if (ctx == null) {
            return;
        }
        rt = BackendRenderTarget.Companion.makeGL(fbW, fbH, 0, 8, mcFbId, GR_GL_RGBA8);
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
        try {
            if (ctx != null) {
                ctx.close();
            }
        } catch (Throwable ignored) {
        }
        ctx = null;
    }

    @Override
    public void beginFrame(FrameInput in, FrameMetrics metrics) {
        int w = Math.max(1, metrics.widthPx());
        int h = Math.max(1, metrics.heightPx());
        if (w != fbW || h != fbH) {
            fbW = w;
            fbH = h;
            buildSurface();
        }
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            System.err.println("[SkikoRenderBackend] first frame: fb=" + fbW + "x" + fbH
                    + " surface=" + (surface != null) + " (UI drawing)");
        }
        guard.enter();
        if (ctx != null) {
            ctx.resetGLAll(); // tell Skia the outside world touched GL
        }
        Canvas canvas = surface != null ? surface.getCanvas() : null;
        dc.bind(canvas, font);
        inFrame = true;
    }

    @Override
    public DrawContext draw() {
        return dc;
    }

    @Override
    public void endFrame() {
        if (!inFrame) {
            return;
        }
        try {
            if (surface != null && ctx != null) {
                ctx.flushAndSubmit(surface, false);
                ctx.resetGLAll(); // Skia touched GL; reset before we hand back to MC
            }
        } catch (Throwable t) {
            System.err.println("[SkikoRenderBackend] endFrame faulted (frame skipped): " + t);
        } finally {
            inFrame = false;
            guard.leave(); // raw-GL restore + MC shadow write-through
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
