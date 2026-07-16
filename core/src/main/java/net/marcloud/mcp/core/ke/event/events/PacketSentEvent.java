package net.marcloud.mcp.core.ke.event.events;

import net.marcloud.mcp.core.ke.event.GameEvent;
import net.minecraft.network.Packet;

/**
 * Fired for every outbound packet, at the {@code NetworkManager.sendPacket}
 * seam. Lets observers see exactly what the client sends — the read half of the
 * "observe protocol" goal.
 */
public final class PacketSentEvent extends GameEvent {

    private final Packet<?> packet;

    public PacketSentEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> packet() {
        return packet;
    }

    public String packetType() {
        return packet == null ? "null" : packet.getClass().getSimpleName();
    }

    /** Clean SSE projection: direction + packet simple name (reference-free). */
    @Override
    public java.util.Map<String, Object> streamSummary() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("dir", "OUT");
        m.put("packet", packetType());
        return m;
    }
}
