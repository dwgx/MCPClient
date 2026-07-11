package net.marcloud.mcp.core.flt.seam.events;

import net.marcloud.mcp.core.ke.event.GameEvent;

/**
 * Fired when the Netty tap observes an outbound packet on the channel. The
 * {@code rawMsg} is the Netty message object before encoding (typically a
 * Packet). Observers may inspect but must not mutate it (wire bytes frozen).
 */
public final class SeamPacketOutboundEvent extends GameEvent {

    private final Object rawMsg;

    public SeamPacketOutboundEvent(Object rawMsg) {
        this.rawMsg = rawMsg;
    }

    public Object rawMsg() {
        return rawMsg;
    }
}
