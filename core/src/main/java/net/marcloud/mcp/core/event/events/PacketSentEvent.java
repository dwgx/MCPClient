package net.marcloud.mcp.core.event.events;

import net.marcloud.mcp.core.event.GameEvent;
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
}
