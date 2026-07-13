package net.marcloud.mcp.dwm.gl;

import net.marcloud.mcp.dwm.backend.BackendCaps;
import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.ContentBackend;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;

import org.lwjgl.opengl.GL11;

/**
 * The pure-Java, handwritten-OpenGL overlay backend — implements DWM's
 * {@link ContentBackend} and draws a Material-ish panel each game render frame using MC
 * 1.8.9's own fixed-function immediate mode ({@code glBegin/glVertex/glColor}), guarded
 * by {@link GlStateGuard} so MC's own rendering survives. No Kotlin, no Compose, no
 * Skiko, no native DLL — the "not one line of Kotlin" sibling of the Compose backend.
 *
 * <p><b>Topology (honest scope).</b> This first increment draws DIRECTLY into MC's
 * currently-bound framebuffer (no offscreen FBO + composite quad). That is safe here
 * precisely because {@link GlStateGuard} does a FULL shadow write-through on leave — the
 * gap that black-screened the Compose path. The panel is a translucent rounded-ish
 * rectangle with an accent bar: enough to prove "a real overlay appears in-game and MC
 * still renders correctly the next frame." Text glyphs, input hit-testing, and the DWM
 * component tree are later increments; this backend deliberately owns its own trivial
 * tree rather than routing through {@code DrawContext} (that is the {@code ContentBackend}
 * contract).
 *
 * <p><b>Fault isolation.</b> {@link #renderFrame} wraps its whole body so a GL fault
 * never breaks the game render thread; the driver ({@code ComposeCompositor}) also
 * swallow-alls every call. On any fault the frame is skipped and the game renders
 * unaffected.
 *
 * <p>All GL touches are on the render thread with the context current (the render-frame
 * seam guarantees this). Coordinates are in framebuffer pixels; a pixel-space ortho with
 * y-down is set up per frame and restored via a local matrix push/pop so no projection
 * change leaks. The overlay never enables depth (it is a 2D HUD layer).
 */
public final class GlContentBackend implements ContentBackend {

    private final GlStateGuard guard = new GlStateGuard();

    private volatile int fbW = 1;
    private volatile int fbH = 1;
    private volatile int mcFbId;

    @Override
    public String id() {
        return "gl";
    }

    @Override
    public BackendCaps caps() {
        // Handwritten immediate mode: no path/clip/per-corner/opacity-layer/native
        // shadow; a generous max texture size (fixed pipeline still supports 2D textures,
        // though this first increment draws no textured content).
        return new BackendCaps(false, false, false, false, false, 4096);
    }

    @Override
    public void onAttach(BackendHost host) {
        try {
            mcFbId = Math.max(0, host.currentFramebufferId());
            fbW = Math.max(1, host.framebufferWidthPx());
            fbH = Math.max(1, host.framebufferHeightPx());
            System.err.println("[GlContentBackend] attached: fb=" + fbW + "x" + fbH + " mcFbo=" + mcFbId);
        } catch (Throwable t) {
            System.err.println("[GlContentBackend] onAttach faulted (overlay disabled): " + t);
        }
    }

    @Override
    public void onDetach() {
        // Nothing GPU-owned: immediate mode allocates no persistent GL objects.
    }

    @Override
    public void resize(int fbWidth, int fbHeight, int fbId, int fbFormat) {
        this.fbW = Math.max(1, fbWidth);
        this.fbH = Math.max(1, fbHeight);
        this.mcFbId = Math.max(0, fbId);
    }

    // Static overlay first increment: pointer/keyboard are not yet consumed.
    @Override
    public void submitInput(FrameInput in) {
        // no-op: static overlay
    }

    /** One-time diagnostic so a live run confirms renderFrame is actually being driven. */
    private boolean loggedFirstFrame;

    @Override
    public void renderFrame(FrameMetrics m, long nanoTime) {
        try {
            if (!loggedFirstFrame) {
                loggedFirstFrame = true;
                System.err.println("[GlContentBackend] first renderFrame: fb=" + fbW + "x" + fbH
                        + " mcFbo=" + mcFbId + " (panel drawing)");
            }
            guard.enter();
            drawPanel();
        } catch (Throwable t) {
            System.err.println("[GlContentBackend] renderFrame faulted (frame skipped): " + t);
        } finally {
            guard.leave();
        }
    }

    /**
     * Draw the overlay panel in pixel space with a local matrix scope. Immediate-mode,
     * fixed-function, no shaders, no textures — a translucent card with an accent bar in
     * the top-left corner. The surrounding {@link GlStateGuard} restores MC's expected
     * state (and shadow) after this returns.
     */
    private void drawPanel() {
        final int w = fbW;
        final int h = fbH;

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, w, h, 0.0, -1.0, 1.0); // top-left origin, y-down
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // Disable cull so the quad's winding is never discarded, and alpha-test so a
        // GL_GREATER,0.1 func left on by MC (updateCameraAndRender sets it) cannot clip
        // the panel. GlStateGuard restores + shadow-writes both on leave().
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Panel geometry (device pixels): a small card near the top-left.
        final float x = 16f;
        final float y = 16f;
        final float pw = 220f;
        final float ph = 72f;
        final float accent = 4f;

        // Card body: dark translucent (Material "surface" @ ~86% alpha).
        fillRect(x, y, pw, ph, 0.12f, 0.12f, 0.14f, 0.86f);
        // Accent bar down the left edge (Material primary-ish teal).
        fillRect(x, y, accent, ph, 0.30f, 0.69f, 0.65f, 1.0f);
        // A brighter title strip so it reads as a real UI element, not a flat quad.
        fillRect(x + accent, y, pw - accent, 20f, 0.18f, 0.18f, 0.20f, 0.92f);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
    }

    /** Immediate-mode filled rectangle at (x,y) size (w,h) with RGBA in [0,1]. */
    private static void fillRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    @Override
    public boolean wantsContinuousFrames() {
        // Static panel: on-demand redraw is enough. The render-frame seam fires every
        // frame anyway, so this only signals the driver's continuous-vs-lazy hint.
        return false;
    }

    @Override
    public boolean consumedPointer() {
        return false;
    }

    @Override
    public boolean consumedKeyboard() {
        return false;
    }
}
