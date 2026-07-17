package net.marcloud.mcp.board.chips;

import java.lang.reflect.Field;

import net.marcloud.mcp.board.Chip;

/**
 * A neutral "fullbright" {@link Chip}: while enabled it raises the client's gamma so dark
 * areas render bright; while disabled it restores the exact gamma it captured on enable.
 * Purely a LOCAL rendering setting — it changes no server-visible state, sends no packet,
 * and writes no save data, so it is safe and fully reversible.
 *
 * <p>Reaches the live game ENTIRELY by reflection against
 * {@code net.minecraft.client.Minecraft.getMinecraft().gameSettings.gammaSetting} (vanilla
 * 1.8.9 mapping). Absent or renamed (headless / mapping drift), it degrades to a harmless
 * no-op that still tracks its own enabled flag without throwing — the standalone/test path.
 */
public final class FullbrightChip extends Chip {

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";
    /** Vanilla fullbright gamma; 1.0 is max in-menu, values above brighten further. */
    private static final float BRIGHT = 100.0f;

    /** Gamma captured on the most recent enable, restored on disable. */
    private volatile float savedGamma;
    /** True if the last enable actually reached the live game (vs. headless no-op). */
    private volatile boolean appliedToGame;

    @Override
    public String category() {
        return "Render";
    }

    @Override
    protected void onEnable() {
        appliedToGame = setGamma(BRIGHT, true);
    }

    @Override
    protected void onDisable() {
        if (appliedToGame) {
            setGamma(savedGamma, false);
        }
        appliedToGame = false;
    }

    /** {@code true} if the most recent enable reached a live game (false when headless). */
    public boolean appliedToGame() {
        return appliedToGame;
    }

    /**
     * Set {@code gammaSetting} to {@code value}; when {@code capture} is true, first save the
     * current gamma so {@link #onDisable()} can restore it. Returns {@code true} if the live
     * game was reachable, {@code false} on any headless/mapping miss (swallowed — a feature
     * toggle must never crash).
     */
    private boolean setGamma(float value, boolean capture) {
        try {
            Class<?> mc = Class.forName(MC_CLASS);
            Object instance = mc.getMethod("getMinecraft").invoke(null);
            if (instance == null) {
                return false;
            }
            Field gameSettingsField = mc.getField("gameSettings");
            Object gameSettings = gameSettingsField.get(instance);
            if (gameSettings == null) {
                return false;
            }
            Field gamma = gameSettings.getClass().getField("gammaSetting");
            if (capture) {
                savedGamma = gamma.getFloat(gameSettings);
            }
            gamma.setFloat(gameSettings, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
