package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.Locale;

import net.minecraft.util.BlockPos;

/**
 * Shared, package-private formatting helpers for the per-category
 * {@link PacketSummarizer} families (world / movement / entity / inventory / …).
 * Keeps every summarizer's String output uniform and terse without each file
 * re-declaring the same {@code %.1f}/{@code %.2f} shims, and centralizes the
 * conventions the {@code packet_view} consumer relies on:
 *
 * <ul>
 *   <li>fixed-point wire decoding ({@link #fp32} for pos*32, {@link #angle} for
 *       byte-packed angles) — the 1.8.9 protocol's two recurring encodings;</li>
 *   <li>{@link #pos(BlockPos)} → {@code "x,y,z"} (or {@code "?"} when null), the
 *       exact shape {@code BoardWorldEventBridge} already parses;</li>
 *   <li>{@link #enumName} → a stable enum {@code name()} or {@code "?"}.</li>
 * </ul>
 *
 * <p>All helpers are null-safe and allocation-light; they run on the Netty tap
 * thread for every packet, like the summarizers themselves.
 */
final class Summ {

    private Summ() {
    }

    static String f1(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    static String f2(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    static String f3(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    /** 1.8.9 fixed-point position: wire int is world coord * 32. */
    static double fp32(int raw) {
        return raw / 32.0;
    }

    /** 1.8.9 byte-packed angle: wire byte is degrees * 256 / 360. */
    static double angle(byte raw) {
        return (raw & 0xFF) * 360.0 / 256.0;
    }

    /** {@code "x,y,z"} for a BlockPos, or {@code "?"} if null. */
    static String pos(BlockPos p) {
        return p == null ? "?" : p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** {@code e.name()} or {@code "?"} if null. */
    static String enumName(Enum<?> e) {
        return e == null ? "?" : e.name();
    }

    /** Truncate long free text (chat/sign/title) to keep summaries short. */
    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
