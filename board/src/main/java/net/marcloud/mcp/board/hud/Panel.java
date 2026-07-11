package net.marcloud.mcp.board.hud;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.signals.RenderSignal;

/**
 * A HUD element — a {@link Chip} you can see. A Panel is a drawable overlay
 * (an armor bar, a coordinate readout, a watermark) with a screen position and a
 * per-frame render hook. It is a {@code Chip} so it shares the framework's
 * id/name/enable/keybind machinery and can live in a {@link HudMatrix} exactly
 * like any other feature — a HUD element is just a Chip that draws.
 *
 * <p><b>Headless-safe by contract:</b> constructing a Panel makes NO OpenGL call,
 * and neither does position resolution ({@link #resolve}). All drawing is
 * confined to {@link #onRender}, which the {@link HudMatrix} only invokes while
 * dispatching a {@link RenderSignal} on the game thread (where GL is legal). This
 * lets tests build panels, toggle them, and drive layout without a GL context.
 *
 * <p>Positioning is anchor-relative: an {@link Anchor} picks a screen corner/edge
 * and {@code offsetX}/{@code offsetY} nudge the panel inward from it, using the
 * panel's declared {@link #width()}/{@link #height()}. The resolved top-left
 * pixel is exposed via {@link #resolvedX()}/{@link #resolvedY()} after a frame.
 *
 * <p>Not part of the frozen skeleton — a HUD-subsystem base that extends the
 * frozen {@link Chip}. New HUD elements subclass {@code Panel}.
 */
public abstract class Panel extends Chip {

    /** Screen reference point a panel is positioned relative to. */
    public enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER
    }

    private Anchor anchor = Anchor.TOP_LEFT;
    private int offsetX = 2;
    private int offsetY = 2;
    private int width;
    private int height;

    private int resolvedX;
    private int resolvedY;

    /** The anchor this panel is positioned against. Never {@code null}. */
    public final Anchor anchor() {
        return anchor;
    }

    /** Set the anchor. A {@code null} argument resets to {@link Anchor#TOP_LEFT}. */
    public final void setAnchor(Anchor anchor) {
        this.anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
    }

    /** Horizontal inset from the anchor, in GUI pixels. */
    public final int offsetX() {
        return offsetX;
    }

    /** Vertical inset from the anchor, in GUI pixels. */
    public final int offsetY() {
        return offsetY;
    }

    /** Set the inset from the anchor, in GUI pixels. */
    public final void setOffset(int offsetX, int offsetY) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** Declared panel width in GUI pixels (drives right/center anchoring). */
    public int width() {
        return width;
    }

    /** Declared panel height in GUI pixels (drives bottom/center anchoring). */
    public int height() {
        return height;
    }

    /**
     * Set the panel's declared size. Subclasses whose size changes per frame
     * should call this from {@link #onRender} before drawing so the next frame
     * anchors correctly. Negative values are clamped to zero.
     */
    protected final void setSize(int width, int height) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
    }

    /** Resolved top-left X of the last frame, in GUI pixels. */
    public final int resolvedX() {
        return resolvedX;
    }

    /** Resolved top-left Y of the last frame, in GUI pixels. */
    public final int resolvedY() {
        return resolvedY;
    }

    /**
     * Compute the top-left pixel from the current anchor/offset/size against a
     * screen of the given size, storing it in
     * {@link #resolvedX()}/{@link #resolvedY()}. Pure arithmetic — NO GL — so it
     * is safe to call in a headless test. Called by {@link HudMatrix} before
     * {@link #onRender}.
     */
    public final void resolve(int screenWidth, int screenHeight) {
        int x;
        int y;
        switch (anchor) {
            case TOP_RIGHT:
                x = screenWidth - width - offsetX;
                y = offsetY;
                break;
            case BOTTOM_LEFT:
                x = offsetX;
                y = screenHeight - height - offsetY;
                break;
            case BOTTOM_RIGHT:
                x = screenWidth - width - offsetX;
                y = screenHeight - height - offsetY;
                break;
            case CENTER:
                x = (screenWidth - width) / 2 + offsetX;
                y = (screenHeight - height) / 2 + offsetY;
                break;
            case TOP_LEFT:
            default:
                x = offsetX;
                y = offsetY;
                break;
        }
        this.resolvedX = x;
        this.resolvedY = y;
    }

    /**
     * Draw this panel for the given frame. Invoked by {@link HudMatrix} only when
     * the panel is enabled and only while dispatching a {@link RenderSignal} on
     * the game thread — the one place OpenGL calls are legal. Position is already
     * resolved: read {@link #resolvedX()}/{@link #resolvedY()}. Default no-op so a
     * minimal panel need only override this.
     *
     * @param signal the frame being rendered (screen size, partial ticks)
     */
    protected void onRender(RenderSignal signal) {
    }

    /**
     * Internal render bridge used by {@link HudMatrix}: resolve position, then
     * invoke {@link #onRender} with fault isolation so a throwing panel cannot
     * break the HUD render pass or the game.
     */
    final void fireRender(RenderSignal signal) {
        resolve(signal.screenWidth(), signal.screenHeight());
        try {
            onRender(signal);
        } catch (Throwable e) {
            System.err.println("[Panel] " + id() + " onRender threw: " + e);
        }
    }
}
