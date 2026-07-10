package net.marcloud.mcp.core.state;

import java.util.List;

import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.DisconnectedEvent;

/**
 * Remembers the most recent disconnect and, combined with the {@link PacketLog},
 * answers the user's core question: "why was I kicked, and what happened right
 * before?" Subscribes to {@link DisconnectedEvent} and keeps the last reason
 * plus when it happened.
 */
public final class DisconnectTracker {

    private final PacketLog packetLog;
    private volatile String lastReason;
    private volatile long lastDisconnectMillis;

    public DisconnectTracker(EventBus bus, PacketLog packetLog) {
        this.packetLog = packetLog;
        bus.subscribe(DisconnectedEvent.class, this::onDisconnect);
    }

    private void onDisconnect(DisconnectedEvent e) {
        lastReason = e.reasonText();
        lastDisconnectMillis = System.currentTimeMillis();
    }

    /** True if a disconnect has been observed since startup. */
    public boolean hasDisconnected() {
        return lastReason != null;
    }

    /** The last kick/disconnect reason text, or null. */
    public String lastReason() {
        return lastReason;
    }

    /**
     * Build a human-readable report: the reason plus the most recent packets
     * leading up to it — the raw material for an AI to diagnose the kick.
     */
    public String report(int recentPackets) {
        StringBuilder sb = new StringBuilder();
        if (lastReason == null) {
            sb.append("No disconnect observed yet.\n");
        } else {
            sb.append("Last disconnect reason: ").append(lastReason).append('\n');
            sb.append("At (epoch ms): ").append(lastDisconnectMillis).append('\n');
        }
        List<PacketLog.Entry> recent = packetLog.recent(recentPackets);
        sb.append("Recent packets (").append(recent.size()).append("):\n");
        for (PacketLog.Entry e : recent) {
            sb.append("  ").append(e).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
