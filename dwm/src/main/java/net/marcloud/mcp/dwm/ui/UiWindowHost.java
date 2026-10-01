package net.marcloud.mcp.dwm.ui;

/**
 * What a UI surface can ask of whatever is displaying it.
 *
 * <p>The counterpart to {@link UiInput}: that carries events INTO a scene, this carries requests
 * back OUT. Both are in plain JVM types for the same reason — the SPI must not name a backend or a
 * host type, so either side can be replaced.
 *
 * <p><b>A request, not a command.</b> Windows draws the same line: a caption button sends
 * {@code WM_SYSCOMMAND} and the window manager decides what to do with it, because only the manager
 * knows what else is on screen and what window state means in context. A frame that carried out its
 * own close would have to know things a frame has no business knowing.
 *
 * <p><b>Minimise is deliberately NOT here.</b> It was, and the only implementation answered it by
 * dismissing the screen while keeping the Skia surface alive for an instant reopen — a promise
 * nothing in this tree could keep. The only opener ({@code DwmHotkey} through
 * {@code DwmEntry.createScreen}) builds a NEW screen for every press and Minecraft drops the old
 * instance, so the "kept" surface was unreachable the moment the screen went away: never rendered,
 * never re-shown, never closed, and a whole {@code DirectContext} stranded with it. Of the two
 * honest answers — cache screens and re-display them, or admit a game has no taskbar to restore a
 * hidden window from — the module took the second. The verb comes back with a host that can
 * actually restore what it hides; until then a control that implies it would be the same defect as
 * the inert switches {@code PageSettings} refuses to offer.
 */
public interface UiWindowHost {

    /** Dismiss the UI and release what it was holding. */
    void close();
}
