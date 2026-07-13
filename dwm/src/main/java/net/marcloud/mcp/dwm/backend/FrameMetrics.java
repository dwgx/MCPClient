package net.marcloud.mcp.dwm.backend;

/**
 * Per-frame geometry + timing context handed to the compositor. {@code scale} is
 * the DIP-&gt;pixel factor; {@code dtSeconds} advances animation timelines.
 */
public record FrameMetrics(int widthPx, int heightPx, float scale, float dtSeconds, long frameId) {
}
