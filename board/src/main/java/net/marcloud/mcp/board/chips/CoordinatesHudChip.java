package net.marcloud.mcp.board.chips;

import net.marcloud.mcp.board.Chip;

/**
 * A neutral "coordinates HUD" {@link Chip}: while enabled it marks that the player's
 * position should be shown on-screen; while disabled it clears the mark. Purely an
 * observational overlay flag — it reads nothing sensitive, changes no server-visible state,
 * and writes no save data. The actual HUD draw is the render layer's job (dwm); this chip
 * is only the enable switch and a reflective position read for callers that want it.
 *
 * <p>Reflection target (vanilla 1.8.9): {@code Minecraft.getMinecraft().thePlayer} with its
 * {@code posX/posY/posZ}. Absent (headless / no world / mapping drift), the position read
 * degrades to {@code null} and the chip still tracks its enabled flag without throwing.
 */
public final class CoordinatesHudChip extends Chip {

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";

    @Override
    public String category() {
        return "Interface";
    }

    /**
     * The player's current {@code [x, y, z]} read reflectively, or {@code null} when there is
     * no live player (headless / not in a world / mapping drift). A pure read — never mutates.
     */
    public double[] position() {
        try {
            Class<?> mc = Class.forName(MC_CLASS);
            Object instance = mc.getMethod("getMinecraft").invoke(null);
            if (instance == null) {
                return null;
            }
            Object player = mc.getField("thePlayer").get(instance);
            if (player == null) {
                return null;
            }
            double x = player.getClass().getField("posX").getDouble(player);
            double y = player.getClass().getField("posY").getDouble(player);
            double z = player.getClass().getField("posZ").getDouble(player);
            return new double[] {x, y, z};
        } catch (Throwable e) {
            return null;
        }
    }
}
