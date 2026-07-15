package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A structured, reference-free projection of one decoded Minecraft packet — the
 * typed counterpart to a {@link PacketSummarizer}'s String summary (PHASE E full-
 * exposure). Where {@code summarize()} yields a compact human/LLM line
 * ({@code "health hp=20.00 food=18 sat=5.00"}), {@link PacketSummarizer#project}
 * yields the same facts as a small ordered {@code Map<String,Object>}
 * ({@code {hp:20.0, food:18, sat:5.0}}) so a caller (the {@code packet_view} tool)
 * can hand an LLM typed fields instead of a string it must parse.
 *
 * <p><b>Reference-free, like the summarizer.</b> A {@code PacketView} is built by
 * reading fields off the still-live packet SYNCHRONOUSLY inside the Netty tap and
 * copying only immutable scalars/Strings into the map — it MUST NOT store, wrap, or
 * expose the packet or any mutable member. The map it holds is the only thing that
 * outlives the tap callback frame (wire-frozen / L7 contract), exactly as the
 * String summary is.
 *
 * <p><b>Ordered + JSON-ready.</b> Backed by a {@link LinkedHashMap} so field order
 * is the deterministic order the summarizer put them in, and every value is a JSON
 * scalar ({@code String}/{@code Number}/{@code Boolean}), a nested ordered map, or a
 * {@code List} of those — the exact shape {@link net.marcloud.mcp.core.io.http.Json}
 * serializes directly, so {@code packet_view} needs no bespoke encoder.
 *
 * <p>Immutable once {@link #build()} is called; the fluent {@code put*} methods keep
 * the A-tier summarizers terse and uniform. Null values are dropped (a field that is
 * genuinely absent on the wire simply does not appear — never a fabricated default),
 * keeping the honesty contract the String summarizers already follow.
 */
public final class PacketView {

    private final Map<String, Object> fields;

    private PacketView(Map<String, Object> fields) {
        this.fields = fields;
    }

    /** The ordered, JSON-ready field map. Never null; unmodifiable. */
    public Map<String, Object> fields() {
        return fields;
    }

    /** Start a new builder. */
    public static Builder of() {
        return new Builder();
    }

    /**
     * Fluent builder for A-tier summarizers. Each {@code put} drops null values so a
     * field that is not present on the wire is simply omitted (honesty: no invented
     * defaults). Numeric doubles are rounded to a fixed scale for compact, stable
     * output via {@link #putRounded}.
     */
    public static final class Builder {
        private final Map<String, Object> m = new LinkedHashMap<>();

        private Builder() {
        }

        /** Put any JSON-ready scalar/map/list; a null value is dropped. */
        public Builder put(String key, Object value) {
            if (value != null) {
                m.put(key, value);
            }
            return this;
        }

        /** Put a double rounded to {@code scale} decimals (stable compact output). */
        public Builder putRounded(String key, double value, int scale) {
            double f = Math.pow(10, scale);
            m.put(key, Math.round(value * f) / f);
            return this;
        }

        /** Build the immutable view. */
        public PacketView build() {
            return new PacketView(java.util.Collections.unmodifiableMap(m));
        }

        /** Convenience: build and return the raw field map (what {@code project} returns). */
        public Map<String, Object> buildMap() {
            return build().fields();
        }
    }
}
