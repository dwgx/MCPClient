package net.marcloud.mcp.board.chips;

import net.marcloud.mcp.board.Chip;

/**
 * A neutral "FPS meter" {@link Chip}: while enabled it marks that the client frame rate
 * should be shown on-screen. Purely diagnostic and observational — it reads the client's
 * public frame counter and changes nothing. The HUD draw is the render layer's job (dwm);
 * this chip is the enable switch plus a reflective FPS read.
 *
 * <p>Reflection target (vanilla 1.8.9): the static public {@code Minecraft.getDebugFPS()}
 * accessor (the backing {@code debugFPS} field is private, so the getter is used). Absent or
 * renamed (headless / mapping drift), the read degrades to {@code -1} and the chip still
 * tracks its enabled flag without throwing.
 */
public final class FpsMeterChip extends Chip {

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";

    @Override
    public String category() {
        return "diagnostic";
    }

    /**
     * The client's current frames-per-second read reflectively via the static public
     * {@code Minecraft.getDebugFPS()}, or {@code -1} when the game is absent / mapping drifted.
     * A pure read — never mutates.
     */
    public int fps() {
        try {
            Class<?> mc = Class.forName(MC_CLASS);
            Object v = mc.getMethod("getDebugFPS").invoke(null);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable e) {
            return -1;
        }
    }
}
