package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * Another player left the server / disappeared from the tab list (server
 * {@code S38PacketPlayerListItem} with {@code REMOVE_PLAYER}). Republished onto
 * the {@link net.marcloud.mcp.board.Trace} so chips can track who is online — the
 * counterpart to {@link PlayerJoinSignal}.
 *
 * <p><b>Tier-2, honestly typed — NOT YET WIRED.</b> Shipped as the honest typed
 * shape (a single player {@link #name()} String), but not published by the
 * mcp-core→board bridge yet, for the same reason as {@link PlayerJoinSignal}:
 * there is no PHASE-P summarizer for {@code S38PacketPlayerListItem}, so the
 * player name is not honestly available from the packet summary the bridge sees.
 * See {@link PlayerJoinSignal} for the exact summarizer + parse work needed to
 * wire both honestly.
 *
 * <p>Immutable; not cancellable. Mirrors {@link KeySignal}'s shape.
 */
public final class PlayerLeaveSignal extends Signal {

    private final String name;

    /**
     * @param name the player's name (never {@code null}; coerced to {@code ""}
     *             when absent)
     */
    public PlayerLeaveSignal(String name) {
        this.name = name == null ? "" : name;
    }

    /** The player's name. Never {@code null}. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "PlayerLeaveSignal{name=\"" + name + "\"}";
    }
}
