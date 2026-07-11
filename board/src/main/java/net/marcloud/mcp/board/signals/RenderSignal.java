package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * A HUD render pass — the framework dispatches this on the game thread (where GL
 * is legal) each frame so enabled panels/chips can paint on top of the game.
 *
 * <p>This is the ONE canonical render signal for the whole framework. Carries the
 * current scaled screen size and the frame's partial-tick interpolation factor.
 * Argument order is {@code (screenWidth, screenHeight, partialTicks)} — width and
 * height first (the values a panel needs to lay itself out), interpolation last.
 * Kept to plain primitives so the signal has zero dependency on any
 * {@code net.minecraft.*} type.
 *
 * <p>Non-final: the HUD subsystem may subclass it to model distinct render phases
 * (e.g. pre/post-GUI) without changing this contract.
 */
public class RenderSignal extends Signal {

    private final int screenWidth;
    private final int screenHeight;
    private final float partialTicks;

    /**
     * @param screenWidth  scaled screen width in GUI pixels
     * @param screenHeight scaled screen height in GUI pixels
     * @param partialTicks interpolation fraction (0..1) since the last game tick
     */
    public RenderSignal(int screenWidth, int screenHeight, float partialTicks) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.partialTicks = partialTicks;
    }

    /** Scaled screen width in GUI pixels. */
    public final int screenWidth() {
        return screenWidth;
    }

    /** Scaled screen height in GUI pixels. */
    public final int screenHeight() {
        return screenHeight;
    }

    /** Interpolation fraction (0..1) between the previous and next game tick. */
    public final float partialTicks() {
        return partialTicks;
    }
}
