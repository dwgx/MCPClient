package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.Map;

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

    /**
     * Structured counterpart to {@link #summarize}: read the same fields off the
     * live {@code packet} and return them as an ordered, JSON-ready
     * {@code Map<String,Object>} (build one with {@link PacketView#of()}), so a
     * caller can hand an LLM typed fields instead of a string it must parse.
     *
     * <p>Same reference-free contract as {@link #summarize}: read synchronously in
     * the tap, copy only immutable scalars/Strings, never retain the packet. Same
     * honesty contract: omit a field that is not on the wire rather than inventing a
     * default.
     *
     * <p><b>Default: {@code null}</b> — "no structured projection". A summarizer that
     * only produces a String (the C tier, and any legacy summarizer) leaves this
     * unimplemented; the registry then reports no typed fields for that packet and
     * {@code packet_view} OMITS the entry entirely ({@code packets_tail} still shows
     * it via the String summary). A- and B-tier summarizers override this — A-tier
     * with the full field set, B-tier with a modest one.
     *
     * @return an ordered JSON-ready field map, or {@code null} if this summarizer
     *         offers no structured projection
     */
    default Map<String, Object> project(Object packet) {
        return null;
    }
}
