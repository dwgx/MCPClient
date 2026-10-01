package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * Another player joined the server / appeared in the tab list (server
 * {@code S38PacketPlayerListItem} with {@code ADD_PLAYER}). Republished onto the
 * {@link net.marcloud.mcp.board.Trace} so chips can track who is online.
 *
 * <p><b>Tier-2, honestly typed — WIRED.</b> The mcp-core→board bridge publishes this
 * from inbound {@code S38PacketPlayerListItem}, for {@code ADD_PLAYER} entries only:
 * the player-list summarizer emits {@code playerList action=... names=a,b,c} and the
 * bridge emits one signal per named entry, because a single packet can add several.
 *
 * <p><b>{@link PlayerLeaveSignal} is genuinely NOT wired, and the reason is not the one
 * this class used to give.</b> The old text blamed the missing
 * {@code S38PacketPlayerListItem} summarizer — which exists, and which is what publishes
 * <i>this</i> signal. The real reason is on the wire: a {@code REMOVE_PLAYER} entry
 * carries only a UUID and never a name (the client decodes
 * {@code GameProfile(uuid, null)}), so the summary honestly has no name to map and the
 * bridge cannot build the signal without fabricating one. Do not emit it that way.
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
