package net.marcloud.mcp.core.flt.seam.summarize;

/**
 * The P.5 default: covers every packet with no specific summarizer by returning
 * its simple class name. Guarantees a legible {@code kind} for unregistered
 * packets and is the registry's terminal fallback (never returns null).
 */
public final class GenericPacketSummarizer implements PacketSummarizer {

    @Override
    public boolean handles(String packetClassName) {
        return true;
    }

    @Override
    public String summarize(Object packet) {
        if (packet == null) {
            return "null";
        }
        return packet.getClass().getSimpleName();
    }
}
