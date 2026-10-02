package net.marcloud.mcp.core.drivers.gui;

/**
 * What ONE click can honestly establish. Deliberately has no {@code SENT}:
 * {@code transfer_item} encodes the same rule for a shift-click
 * ({@code ToolRegistry.TransferVerdict}), and "the packet went out" is not an
 * outcome — the server applies the click to ITS container and pushes the result
 * back over C0F, so only a re-read proves anything.
 *
 * <p>The distinction that matters: {@link #CONFIRMED} means the world was observed
 * to change. {@link #NOT_CONFIRMED} means the handler was driven and nothing was
 * observed, which is reported as {@code [UNVERIFIED]} and never as done.
 */
public enum ClickVerdict {

    /**
     * A re-read showed the target's state actually change: for a slot, the
     * container's contents differ from what they were before the click (the
     * server's resync landed); for a button, the screen's structure changed.
     */
    CONFIRMED,

    /**
     * The handler ran and nothing observable changed within the confirmation
     * budget. For a slot this means the server rejected it or the resync has not
     * landed; for a button it means the screen simply looks the same afterwards,
     * which is most buttons and is NOT evidence of failure.
     */
    NOT_CONFIRMED,

    /** The slot could not be read back at all, so nothing is claimed either way. */
    UNREADABLE,

    /** The reference no longer matches the live screen. NOTHING was clicked. */
    REFUSED_STALE,

    /** The element id resolves to nothing on the live screen. NOTHING was clicked. */
    NO_ELEMENT,

    /**
     * The click point lands outside the container panel, where vanilla turns it into
     * {@code Container.slotClick(mode 0, slotId -999)} — a throw of the carried stack
     * into the world, with no undo. Refused; NOTHING was clicked.
     */
    REFUSED_DESTRUCTIVE
}
