package net.marcloud.mcp.dwm.qml;

import net.marcloud.mcp.dwm.ui.UiWindowHost;

/**
 * The window-management verbs a scene's caption bar can ask for, as QML sees them.
 *
 * <p><b>Why this is its own context object rather than more methods on {@link DwmContext}.</b>
 * DwmContext is the channel to the running kernel and board — what the UI can OBSERVE about the
 * client, plus the single chip toggle. Closing a window is not knowledge about the kernel; it is a
 * request about the window. Windows keeps the same seam: the non-client area sends a
 * {@code WM_SYSCOMMAND} verb ({@code SC_CLOSE}) and the window manager decides what happens, because
 * a frame that implemented window state itself would have to know which window it belongs to and
 * what else is on screen.
 *
 * <p>So the caption bar states intent and this forwards it. Whether closing dismisses a screen,
 * hides a panel, or does nothing at all is the host's answer, not the frame's.
 *
 * <p><b>{@code minimize()} is gone, and why is worth keeping here.</b> It existed so a caption
 * button could ask to be put away without being torn down, and the only implementation answered it
 * by dismissing the screen while keeping the scene alive — a promise nothing could keep, because
 * every open builds a NEW screen and the kept surface became unreachable GPU memory. See
 * {@link UiWindowHost} for the full account. A verb returns when a host can restore what it hides;
 * until then offering a control that implies it would be the same defect as the inert switches
 * {@code PageSettings} refuses to offer.
 *
 * <p><b>Every method must be cheap and must not throw.</b> These are reached from QML signal
 * handlers on the render thread, mid input dispatch. A null host is the normal case for a scene
 * instantiated in a test, so each verb is a no-op then rather than a fault.
 */
public final class WindowCommands {

    /** The name QML binds this under. Referenced by scenes as {@code WindowHost.*}. */
    public static final String NAME = "WindowHost";

    private final UiWindowHost host;

    /**
     * @param host the host that carries out the verbs, or null when there is none (tests, or a
     *             scene rendered without a screen behind it)
     */
    WindowCommands(UiWindowHost host) {
        this.host = host;
    }

    /** {@code SC_CLOSE}: dismiss the screen and release its surface. */
    public void close() {
        if (host != null) {
            try {
                host.close();
            } catch (Throwable t) {
                System.err.println("[dwm] close failed: " + t);
            }
        }
    }

    /** True when a host is attached, so a scene can hide verbs nobody can answer. */
    public boolean hasHost() {
        return host != null;
    }
}
