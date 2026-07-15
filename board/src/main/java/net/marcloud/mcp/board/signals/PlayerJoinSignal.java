package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * Another player joined the server / appeared in the tab list (server
 * {@code S38PacketPlayerListItem} with {@code ADD_PLAYER}). Republished onto the
 * {@link net.marcloud.mcp.board.Trace} so chips can track who is online.
 *
 * <p><b>Tier-2, honestly typed — NOT YET WIRED.</b> This is the honest typed shape
 * for a join (a single player {@link #name()} String). It is shipped so the
 * vocabulary exists, but the mcp-core→board bridge does NOT publish it today: the
 * bridge sees only a reference-free packet <em>summary</em>, and there is no
 * PHASE-P summarizer for {@code S38PacketPlayerListItem}, so its summary is just
 * the generic class name — the player name and the add/remove action are not
 * honestly available to the bridge.
 *
 * <p><b>To wire it honestly:</b> add an {@code S38PacketPlayerListItem} summarizer
 * that emits the action + player name(s) (e.g. {@code "playerList action=ADD name=<...>"});
 * then {@code BoardWorldEventBridge} can map {@code ADD_PLAYER} entries to a
 * {@code PlayerJoinSignal} and {@code REMOVE_PLAYER} to a {@link PlayerLeaveSignal},
 * parsing the name from the summary. Note S38 can carry multiple entries per
 * packet, so the bridge may emit several signals from one summary. Until then this
 * signal exists as a typed contract only.
 *
 * <p>Immutable; not cancellable. Mirrors {@link KeySignal}'s shape.
 */
public final class PlayerJoinSignal extends Signal {

    private final String name;

    /**
     * @param name the player's name (never {@code null}; coerced to {@code ""}
     *             when absent)
     */
    public PlayerJoinSignal(String name) {
        this.name = name == null ? "" : name;
    }

    /** The player's name. Never {@code null}. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "PlayerJoinSignal{name=\"" + name + "\"}";
    }
}
