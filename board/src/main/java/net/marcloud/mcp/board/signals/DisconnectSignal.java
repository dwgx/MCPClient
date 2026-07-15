package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The connection to the server dropped. The mcp-core→board bridge republishes
 * core's {@code DisconnectedEvent} onto the {@link net.marcloud.mcp.board.Trace}
 * so chips can react to a kick/disconnect (e.g. reset per-session state, note the
 * reason on a HUD).
 *
 * <p>This is a Tier-1 world signal: its single payload — the plain-text kick
 * {@link #reason()} — is cleanly available from core's
 * {@code DisconnectedEvent.reasonText()} (which already unwraps the
 * {@code IChatComponent} to unformatted text, or {@code "(no reason)"} when
 * absent). No placeholder, no stringly-typed guesswork.
 *
 * <p>Immutable; not cancellable — the drop already happened. Mirrors
 * {@link KeySignal}'s shape (one immutable payload + a single accessor).
 */
public final class DisconnectSignal extends Signal {

    private final String reason;

    /**
     * @param reason plain-text disconnect reason (never {@code null}; coerced to
     *               {@code ""} when absent)
     */
    public DisconnectSignal(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    /** The plain-text disconnect/kick reason. Never {@code null}. */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "DisconnectSignal{reason=\"" + reason + "\"}";
    }
}
