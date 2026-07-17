package net.marcloud.mcp.dwm.skiko;

import net.minecraft.client.Minecraft;

/**
 * Reflective entry point for OPENING the launcher — the single public seam core reaches by
 * reflection (no compile-time link to dwm-skiko), replacing the old {@code frameSink}
 * render-frame contract. Core's hotkey system calls {@link #toggle()} when the bound key is
 * pressed; this makes {@link DesktopGuiScreen} the current MC screen (or closes it if already
 * open), and MC's own screen lifecycle then owns render, input, resize, and focus.
 *
 * <p>Everything is fault-isolated and thread-marshalled onto the MC thread via
 * {@code Minecraft.addScheduledTask}, because the hotkey heartbeat may fire from the tick
 * thread while {@code displayGuiScreen} must run on the client thread. Absent MC / any fault
 * degrades to a silent no-op — the detachable-auxiliary contract.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    /**
     * Toggle the launcher: open a fresh {@link DesktopGuiScreen} if no launcher is showing,
     * else close the current one (back to the game). Safe to call from any thread.
     */
    public static void toggle() {
        run(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (mc.currentScreen instanceof DesktopGuiScreen) {
                mc.displayGuiScreen(null);
                mc.setIngameFocus();
            } else {
                mc.displayGuiScreen(new DesktopGuiScreen());
            }
        });
    }

    /** Force the launcher open (idempotent — a launcher already showing is left as-is). */
    public static void open() {
        run(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.currentScreen instanceof DesktopGuiScreen) {
                return;
            }
            mc.displayGuiScreen(new DesktopGuiScreen());
        });
    }

    /** Marshal {@code r} onto the MC client thread (displayGuiScreen is not thread-safe). */
    private static void run(Runnable r) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            mc.addScheduledTask(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("[DesktopLauncher] open/toggle faulted: " + t);
                }
            });
        } catch (Throwable t) {
            System.err.println("[DesktopLauncher] scheduling faulted (no-op): " + t);
        }
    }
}
