package net.marcloud.mcp.dwm.qml;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;

/**
 * An offscreen GPU surface the UI scene renders into, kept and re-composited across frames.
 *
 * <p>This is the piece that makes DWM's name honest: Windows gives every top-level window a
 * <em>redirection surface</em> — an offscreen buffer it draws to instead of the display — and
 * composites those surfaces into the desktop. Same idea here, for one surface: qml4j paints the
 * scene into this texture, and each game frame blits the texture over MC's framebuffer.
 *
 * <p><b>Why it is worth the extra memory.</b> It is the only way to get damage tracking in this
 * architecture. MC redraws the whole world every frame, so the composite can never be skipped —
 * skip it and the menu simply disappears. But the <em>scene render</em> can be skipped, and that
 * is the expensive half: an idle menu costs one textured blit instead of a full Skia paint of
 * every rounded rect, glyph and divider.
 *
 * <p><b>Simpler than a real compositor's damage tracking.</b> A Wayland or Windows compositor has
 * to union damage across the last N frames, because the buffer it is handed has been recycled and
 * still holds content from two frames ago (hence EGL's buffer_age). We own this surface, never
 * present it and never let it rotate, so its contents are exactly what we last drew. Level-zero
 * damage tracking — "notice nothing changed and stop rendering" — is therefore the whole of it,
 * with no buffer-age arithmetic.
 *
 * <p>Every native call is fault-isolated: a failure leaves the surface null and the caller falls
 * back to drawing direct, rather than throwing on the render thread.
 */
final class RedirectionSurface {

    private Surface surface;
    private Image snapshot;
    private int width;
    private int height;

    /**
     * Size the surface, rebuilding when the extent changed. Returns false when unusable.
     *
     * <p>Rebuilding drops the cached snapshot: it belongs to the old surface, and compositing a
     * stale one after a resize would stretch the previous frame's UI over the new geometry.
     */
    boolean ensure(DirectContext context, int w, int h) {
        if (context == null || w <= 0 || h <= 0) {
            return false;
        }
        if (surface != null && w == width && h == height) {
            return true;
        }
        close();
        width = w;
        height = h;
        try {
            // Premultiplied N32 with alpha: the scene must stay transparent where it paints
            // nothing, or the composite would cover the game with an opaque rectangle.
            //
            // N32 also MATCHES MC's framebuffer format, which is what keeps the composite a
            // plain GPU blit with no conversion. Windows does the opposite for GDI windows —
            // it keeps a second system-memory buffer precisely because GDI cannot render into
            // the DirectX format — and pays a full copy per update for it. If these two formats
            // ever diverge here, the cost arrives as unexplained slowness, not as an error.
            surface = Surface.makeRenderTarget(context, false,
                ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL));
        } catch (Throwable t) {
            System.err.println("[dwm] offscreen surface creation faulted (inert): " + t);
            surface = null;
        }
        return surface != null;
    }

    /**
     * The canvas to paint the scene into, cleared to transparent and ready for this frame.
     *
     * <p>Invalidates the cached snapshot, since the caller is about to change the contents.
     */
    Canvas beginScene() {
        if (surface == null) {
            return null;
        }
        dropSnapshot();
        Canvas canvas = surface.getCanvas();
        // Reset before clearing: getCanvas() returns the same canvas each time, so a transform
        // left from last frame would both compound and shrink the cleared area.
        canvas.resetMatrix();
        // Transparent, not opaque black — this layer composites over the live game frame.
        canvas.clear(0x00000000);
        return canvas;
    }

    /**
     * Take the scene as a GPU image and cache it for reuse on later frames.
     *
     * <p>Called once after the scene has been painted. The image shares the surface's context, so
     * the later {@code drawImage} is a GPU-side blit rather than a readback.
     *
     * <p><b>Never replace this with a pixel readback.</b> Reading video memory has to synchronise
     * with the compositor and stalls the whole GPU pipeline — it is the reason Windows calls
     * drawing to and reading from the screen a practice to avoid outright, and why a DirectDraw
     * primary access makes DWM switch itself off. Keeping both surfaces on one DirectContext is
     * what makes this cheap; a readback-and-upload version would look equivalent and be
     * catastrophic.
     */
    void endScene() {
        if (surface == null) {
            return;
        }
        try {
            snapshot = surface.makeImageSnapshot();
        } catch (Throwable t) {
            System.err.println("[dwm] snapshot faulted (inert): " + t);
            snapshot = null;
        }
    }

    /** The cached scene image, or null if there is nothing to composite yet. */
    Image snapshot() {
        return snapshot;
    }

    /** True when a scene image is available for compositing without re-rendering. */
    boolean hasSnapshot() {
        return snapshot != null;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    private void dropSnapshot() {
        try {
            if (snapshot != null) {
                snapshot.close();
            }
        } catch (Throwable ignored) {
            // An already-freed native image is not actionable.
        }
        snapshot = null;
    }

    /** Release both the snapshot and the surface. Idempotent. */
    void close() {
        dropSnapshot();
        try {
            if (surface != null) {
                surface.close();
            }
        } catch (Throwable ignored) {
            // As above.
        }
        surface = null;
    }
}
