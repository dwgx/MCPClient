package net.marcloud.mcp.board.chips;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.marcloud.mcp.board.Chip;

/**
 * A SAMPLE "startup-screen replace" {@link Chip}. When enabled it marks the
 * client's current GUI screen for replacement (the classic "swap the main menu
 * for our own screen" feature); when disabled it clears the mark. It reaches the
 * live game ENTIRELY by reflection against {@code net.minecraft.client.Minecraft}
 * so the board module never compile-depends on a specific MC mapping AND so it
 * degrades to a harmless no-op when run headless (no game present) — exactly the
 * standalone/test path.
 *
 * <p>Reflection targets (vanilla 1.8.9 mappings):
 * {@code Minecraft.getMinecraft()}, the {@code currentScreen} field, and
 * {@code displayGuiScreen(GuiScreen)}. Absent or renamed, the chip records that
 * it could not act and stays enabled without throwing.
 */
public final class StartupScreenChip extends Chip {

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";

    /** True once {@code onEnable} has flagged the startup screen for replacement. */
    private volatile boolean marked;

    /** True if the last enable actually reached the live game (vs. headless no-op). */
    private volatile boolean appliedToGame;

    @Override
    public String category() {
        return "startup";
    }

    @Override
    protected void onEnable() {
        marked = true;
        appliedToGame = swapScreen();
    }

    @Override
    protected void onDisable() {
        marked = false;
        appliedToGame = false;
    }

    /** {@code true} while this chip has the startup screen marked for replacement. */
    public boolean isMarked() {
        return marked;
    }

    /** {@code true} if the most recent enable reached a live game (false when headless). */
    public boolean appliedToGame() {
        return appliedToGame;
    }

    /**
     * Attempt to observe the live client's current screen reflectively. Returns
     * {@code true} if the game was present and reachable, {@code false} on any
     * headless/mapping miss (swallowed — a feature toggle must never crash).
     */
    private boolean swapScreen() {
        try {
            Class<?> mc = Class.forName(MC_CLASS);
            Method getMinecraft = mc.getMethod("getMinecraft");
            Object instance = getMinecraft.invoke(null);
            if (instance == null) {
                return false;
            }
            // Read the current screen; a real replacement would build our own
            // GuiScreen and call displayGuiScreen(newScreen). We only touch the
            // field here so the sample stays safe on a live client too.
            Field currentScreen = mc.getField("currentScreen");
            currentScreen.get(instance);
            // Prove the swap seam is reachable without forcing a specific screen:
            mc.getMethod("displayGuiScreen", currentScreen.getType());
            return true;
        } catch (Throwable e) {
            // No game (headless/tests) or mapping drift: degrade to a no-op mark.
            return false;
        }
    }
}
