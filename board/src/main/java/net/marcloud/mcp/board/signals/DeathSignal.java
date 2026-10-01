package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The player died (server {@code S42PacketCombatEvent} with
 * {@code ENTITY_DIED}, carrying the death message). Republished onto the
 * {@link net.marcloud.mcp.board.Trace} so chips can react to a death.
 *
 * <p><b>Tier-2, honestly typed — WIRED.</b> The mcp-core→board bridge publishes this
 * from inbound {@code S42PacketCombatEvent}, for the {@code ENTITY_DIED} event id
 * only: the combat summarizer emits a {@code death="..."} field and the bridge parses
 * the message out of it. The non-death combat events emit nothing rather than
 * guessing.
 *
 * <p>The earlier version of this comment described that summarizer and that parse as
 * work still to be done, including the exact field spelling to use. It is done, and it
 * is done almost exactly as written here — the comment outlived the code, which is the
 * drift this repository keeps paying for. {@code BoardWorldEventBridge}'s class javadoc
 * is the authority on what is wired.
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
