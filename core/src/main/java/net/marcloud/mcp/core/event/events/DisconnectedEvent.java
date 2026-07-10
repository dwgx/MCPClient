package net.marcloud.mcp.core.event.events;

import net.marcloud.mcp.core.event.GameEvent;
import net.minecraft.util.IChatComponent;

/**
 * Fired when the connection drops, at the {@code NetworkManager.onDisconnect}
 * seam. Carries the termination reason ({@link IChatComponent}) — the raw
 * material for the user's "why was I kicked?" use case. The MCP disconnect
 * reporter combines this with the recent packet log into a report for the AI.
 */
public final class DisconnectedEvent extends GameEvent {

    private final IChatComponent reason;

    public DisconnectedEvent(IChatComponent reason) {
        this.reason = reason;
    }

    public IChatComponent reason() {
        return reason;
    }

    /** Plain-text kick reason, or "(no reason)" when absent. */
    public String reasonText() {
        return reason == null ? "(no reason)" : reason.getUnformattedText();
    }
}
