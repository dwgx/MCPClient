package net.marcloud.mcp.dwm.gl;

import org.lwjgl.opengl.GL11;

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

/**
 * The pure-Java handwritten-GL {@link RenderBackend} — the DrawContext-axis backend that
 * lets the DWM MD3 component tree render on MC 1.8.9's own fixed-function GL. Where
 * {@code GlContentBackend} (the ContentBackend axis) draws its own static panel, this
 * exposes a {@link GlDrawContext} the {@code UiComposer} drives the component tree into,
 * so the SAME {@code MaterialButton} renders here as on the imgui / Skiko backends.
 *
 * <p>Per frame the compositor calls {@link #beginFrame} (set up a pixel-space y-down
 * ortho + blend, snapshot GL via {@link GlStateGuard}) → {@link #draw()} (the component
 * tree emits primitives) → {@link #endFrame} (pop matrices, restore GL + MC shadow via
 * the guard). All GL is on the render thread with the context current; the whole frame is
 * fault-isolated by the compositor so a GL fault never breaks the game thread.
 *
 * <p><b>Fidelity (honest).</b> Immediate mode + fixed function: rounded rects are
 * triangle-fan approximations and text is a placeholder bar (no glyph atlas yet). Enough
 * to prove the DrawContext axis end-to-end and render an MD3 button's shape / state layer
 * / ripple / clip; imgui and Skiko backends give real text + higher-fidelity shapes.
 */
public final class GlRenderBackend implements RenderBackend {

    private final GlStateGuard guard = new GlStateGuard();
    private final GlDrawContext dc = new GlDrawContext();

    private volatile int fbW = 1;
    private volatile int fbH = 1;
    private boolean inFrame;
    private boolean entered;        // guard.enter() done; endFrame must guard.leave()
    private boolean matricesPushed; // projection+modelview pushed; must pop

    private boolean loggedFirstFrame;

    @Override
    public String id() {
        return "gl";
    }

    @Override
    public BackendCaps caps() {
        // per-corner radius IS now supported (GlDrawContext walks a per-corner triangle-fan
        // perimeter), so advertise it true. No native path/clip-beyond-scissor/layer-opacity
        // /shadow; scissor clip IS supported but the SPI's 'clip' cap advertises
        // rounded/arbitrary clip which we approximate with a rect scissor — keep it false so
        // callers do not assume rounded clipping. A generous max texture size placeholder.
        return new BackendCaps(false, true, true, true, false, 4096);
    }

    @Override
    public void onAttach(BackendHost host) {
        try {
            fbW = Math.max(1, host.framebufferWidthPx());
            fbH = Math.max(1, host.framebufferHeightPx());
            System.err.println("[GlRenderBackend] attached: fb=" + fbW + "x" + fbH);
        } catch (Throwable t) {
            System.err.println("[GlRenderBackend] onAttach faulted (backend disabled): " + t);
        }
    }

    @Override
    public void onDetach() {
        // Immediate mode owns no persistent GL objects.
    }

    @Override
    public void beginFrame(FrameInput in, FrameMetrics metrics) {
        this.fbW = Math.max(1, metrics.widthPx());
        this.fbH = Math.max(1, metrics.heightPx());
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            System.err.println("[GlRenderBackend] first frame: fb=" + fbW + "x" + fbH + " (UI drawing)");
        }
        // ATOMIC begin: guard.enter() first, then any throwable in the GL setup unwinds
        // (pop the matrices actually pushed + guard.leave) and rethrows, so a RETURNED
        // beginFrame always means "endFrame is owed" and a THROWN one means "already
        // cleaned up". This is what makes guard.enter/leave exception-safe even when
        // setup faults before inFrame — the black/white/invisible desync must never be
        // reintroduced by an orphaned guard.
        guard.enter();
        entered = true;
        try {
            // Pixel-space y-down ortho, own matrix scope. 2D overlay: depth off, blend on,
            // cull/alpha off (GlStateGuard restores + shadow-writes all of these on leave).
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            matricesPushed = true;
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0, fbW, fbH, 0.0, -1.0, 1.0);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            dc.begin(fbH);
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

    /** Balance whatever beginFrame did: scissor off, pop pushed matrices, guard.leave. */
    private void unwind() {
        try {
            dc.reset(); // scissor off + clip/opacity stacks cleared
            if (matricesPushed) {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            }
        } catch (Throwable t) {
            System.err.println("[GlRenderBackend] unwind faulted: " + t);
        } finally {
            matricesPushed = false;
            inFrame = false;
            entered = false;
            guard.leave(); // raw-GL restore + MC shadow write-through — ALWAYS
        }
    }

    @Override
    public TextureHandle uploadTexture(TextureData rgba) {
        return new TextureHandle(0L); // no texture path yet
    }

    @Override
    public void freeTexture(TextureHandle h) {
        // no-op
    }

    @Override
    public FontHandle loadFont(FontSpec spec) {
        return new FontHandle(0L); // placeholder text; no real font atlas yet
    }

    @Override
    public TextMetrics measureText(FontHandle f, CharSequence s, float sizePx) {
        // Route through the bitmap font's own advance so measure == what text() draws
        // (single source of truth). ADVANCE_RATIO is kept at the historical 0.55, so this
        // is numerically identical to the pre-glyph value — no layout shift, GL pill width
        // stays equal to the imgui / skiko widths.
        float w = GlBitmapFont.measureWidth(s, sizePx);
        return new TextMetrics(w, sizePx * 0.8f, sizePx * 0.2f);
    }
}
