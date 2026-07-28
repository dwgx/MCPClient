package net.marcloud.mcp.dwm.ui;

/**
 * What a UI surface can ask of whatever is displaying it.
 *
 * <p>The counterpart to {@link UiInput}: that carries events INTO a scene, this carries requests
 * back OUT. Both are in plain JVM types for the same reason — the SPI must not name a backend or a
 * host type, so either side can be replaced.
 *
 * <p><b>These are requests, not commands.</b> Windows draws the same line: a caption button sends
 * {@code WM_SYSCOMMAND} and the window manager decides what to do with it, because only the manager
 * knows what else is on screen and what window state means in context. A frame that carried out its
 * own minimise would have to know things a frame has no business knowing.
 *
 * <p>An implementation may legitimately do nothing for a verb it cannot honour. There is no taskbar
 * inside a game, so "minimise" has no universal meaning — which is exactly why it is the host's
 * answer and not the frame's.
 */
public interface UiWindowHost {

    /**
     * Put the UI away while keeping it alive.
     *
     * <p>Distinct from {@link #close()} on purpose: the surface stays open, so state is kept and
     * reopening costs nothing. Collapsing the two would erase the only difference between the two
     * caption buttons.
     */
    void minimize();

    /** Dismiss the UI and release what it was holding. */
    void close();
}
