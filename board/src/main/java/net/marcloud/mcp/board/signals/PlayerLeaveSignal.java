package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * Another player left the server / disappeared from the tab list (server
 * {@code S38PacketPlayerListItem} with {@code REMOVE_PLAYER}). Republished onto
 * the {@link net.marcloud.mcp.board.Trace} so chips can track who is online — the
 * counterpart to {@link PlayerJoinSignal}.
 *
 * <p><b>Tier-2, honestly typed — genuinely NOT WIRED, and the reason is the wire
 * format, not a missing summarizer.</b> An earlier version of this comment blamed the
 * absence of a PHASE-P summarizer for {@code S38PacketPlayerListItem}. That summarizer
 * exists, and it is what publishes {@link PlayerJoinSignal}. The real blocker: a
 * {@code REMOVE_PLAYER} entry carries only a UUID and never a name — the client decodes
 * it as {@code GameProfile(uuid, null)} — so the reference-free summary the bridge reads
 * honestly has no name to map.
 *
 * <p>That is the same rule the PHASE-E contract enforces elsewhere: emit nothing rather
 * than a fabricated value. So this signal ships as a typed contract only. Wiring it
 * honestly needs either a name the server actually sends, or a different value type
 * (the UUID), which is a contract change and not a bridge change.
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
