package net.marcloud.mcp.core.ke.event.events;

import net.marcloud.mcp.core.ke.event.GameEvent;
import net.minecraft.network.Packet;

/**
 * Fired for every inbound packet, at the {@code NetworkManager.channelRead0}
 * seam (attached at runtime, not by editing MC source). Carries the live packet
 * object so observers can inspect it; do NOT mutate it.
 */
public final class PacketReceivedEvent extends GameEvent {

    private final Packet<?> packet;

    public PacketReceivedEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> packet() {
        return packet;
    }

    /** Simple class name, convenient for logging/filtering (e.g. "S01Packet...."). */
    public String packetType() {
        return packet == null ? "null" : packet.getClass().getSimpleName();
    }
}
