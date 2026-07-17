package net.marcloud.mcp.dwm.desktop;

import net.marcloud.mcp.dwm.desktop.theme.DesktopTheme;
import net.marcloud.mcp.dwm.desktop.theme.ThemeState;
import net.marcloud.mcp.dwm.theme.MdcTheme;

/**
 * One-call wiring for the Desktop launcher, shared by all three backend entry points
 * (gl / imgui / skiko) so they build an IDENTICAL launcher. Creates the single
 * {@link ThemeState} that both the {@link DesktopTheme} (read by the component context)
 * and the launcher's settings panel mutate — so editing the theme in-UI recolors the whole
 * launcher live.
 *
 * <p>Phase 1 seeds the catalog from {@link FakeSoftware}; phase 2 swaps that source for the
 * live board chip bridge without touching this wiring.
 */
public final class DesktopBoot {

    private final DesktopTheme theme;
    private final Desktop root;

    private DesktopBoot(DesktopTheme theme, Desktop root) {
        this.theme = theme;
        this.root = root;
    }

    /**
     * Build a launcher bundle with a fresh shared theme state and a LIVE catalog backed by
     * the board {@link ChipBridge}: the software list is the board's real chip roster, read
     * fresh each frame, and clicking a row toggles the real chip. When board is offline the
     * bridge yields an empty roster and toggles are no-ops (the launcher shows an empty list
     * rather than crashing) — see {@link Desktop} for the offline hint.
     */
    public static DesktopBoot create() {
        ensureBoardStarted();
        ThemeState themeState = new ThemeState();
        DesktopTheme theme = new DesktopTheme(themeState);
        ChipBridge bridge = new ChipBridge();
        SoftwareCatalog catalog = new SoftwareCatalog(bridge::roster);
        Desktop root = new Desktop(catalog, themeState, bridge);
        return new DesktopBoot(theme, root);
    }

    /**
     * Start the board framework (idempotent) so the chip roster + toggle command are
     * published to the Backplane BEFORE the launcher's {@link ChipBridge} reads them. Core
     * deliberately does NOT call {@code Board.init()} (it is board's own job), and nothing
     * else does at runtime, so the overlay drives it here — otherwise the roster is empty and
     * the launcher shows "board offline". Reflective + fault-isolated: board absent (its jar
     * not on the classpath) or any fault degrades to a silent no-op and the launcher simply
     * shows an empty roster, never crashing the overlay arm.
     */
    private static void ensureBoardStarted() {
        try {
            Class.forName("net.marcloud.mcp.board.Board")
                    .getMethod("init")
                    .invoke(null);
        } catch (Throwable t) {
            // board jar absent / init faulted — launcher degrades to an empty roster.
        }
    }

    /** The theme to hand the component context (backed by the shared, mutable state). */
    public MdcTheme theme() {
        return theme;
    }

    /** The launcher root component. */
    public Desktop root() {
        return root;
    }
}
