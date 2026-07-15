package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The player died (server {@code S42PacketCombatEvent} with
 * {@code ENTITY_DIED}, carrying the death message). Republished onto the
 * {@link net.marcloud.mcp.board.Trace} so chips can react to a death.
 *
 * <p><b>Tier-2, honestly typed — NOT YET WIRED.</b> This is the honest typed shape
 * for a death (a single death-message {@link #message()} String). It is shipped so
 * the vocabulary exists, but the mcp-core→board bridge does NOT publish it today:
 * the bridge sees only a reference-free packet <em>summary</em>, and there is no
 * PHASE-P summarizer for {@code S42PacketCombatEvent}, so its summary is just the
 * generic class name — the death message is not honestly available to the bridge.
 *
 * <p><b>To wire it honestly:</b> add an {@code S42PacketCombatEvent} summarizer
 * that, for the {@code ENTITY_DIED} event id, emits e.g.
 * {@code "death msg=\"<...>\""}, then have {@code BoardWorldEventBridge} parse the
 * message out of that summary. Until then this signal exists as a typed contract
 * only.
 *
 * <p>Immutable; not cancellable — the death already happened. Mirrors
 * {@link KeySignal}'s shape.
 */
public final class DeathSignal extends Signal {

    private final String message;

    /**
     * @param message the server's death message (never {@code null}; coerced to
     *                {@code ""} when absent)
     */
    public DeathSignal(String message) {
        this.message = message == null ? "" : message;
    }

    /** The server's death message. Never {@code null}. */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "DeathSignal{message=\"" + message + "\"}";
    }
}
