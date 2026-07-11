package net.marcloud.mcp.core.flt.seam.events;

import net.marcloud.mcp.core.ke.event.GameEvent;

/**
 * Fired when the Netty tap observes an inbound packet on the channel. The
 * {@code rawMsg} is the Netty message object before MC decoding (typically a
 * ByteBuf or a decoded Packet). Observers may inspect but must not mutate it
 * (wire bytes frozen).
 */
public final class SeamPacketInboundEvent extends GameEvent {

    private final Object rawMsg;

    public SeamPacketInboundEvent(Object rawMsg) {
        this.rawMsg = rawMsg;
    }

    public Object rawMsg() {
        return rawMsg;
    }
}
