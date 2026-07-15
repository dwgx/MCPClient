package net.marcloud.mcp.core.flt.seam.summarize;

/**
 * A reference-free projector of a single decoded Minecraft packet into a short,
 * human/LLM-legible {@code String} (PHASE P.3). Invoked SYNCHRONOUSLY inside the
 * Netty tap on the still-live packet object, before the message continues down the
 * pipeline — so an implementation may read fields but MUST NOT store, wrap, or
 * return the packet or any mutable member. The only output is a {@code String};
 * the live reference dies with the tap callback frame (wire-frozen / L7 contract).
 *
 * <p>Implementations are stateless singletons and should not throw (the registry
 * guards every call, but a throwing summarizer just degrades to the next
 * candidate). Field reads should be cheap: summarizers run on the Netty worker
 * thread for every packet.
 */
public interface PacketSummarizer {

    /**
     * Fast pre-filter keyed on the packet's fully-qualified class name. Pure and
     * allocation-free; called on the hot path before {@link #summarize}.
     */
    boolean handles(String packetClassName);

    /**
     * Read fields off the live {@code packet} and return an immutable summary
     * String, or {@code null} to decline this instance (the registry then falls
     * through to the next candidate). Never retains the packet.
     */
    String summarize(Object packet);
}
