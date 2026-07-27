package net.marcloud.mcp.core.compat.patches;

import java.lang.reflect.Method;

/**
 * The target of KI-11's injected call: one edge-detected hotkey that opens the DWM screen.
 *
 * <p>Called once per keyboard event from inside vanilla's own dispatch, so the event stream is
 * authoritative. That is the whole reason this rides a compat patch instead of polling: an earlier
 * design read {@code Keyboard.isKeyDown} on the 20 Hz tick, and polling a level signal drops any
 * press that begins and ends between two ticks. Here there is nothing to drop — vanilla calls us
 * for each event it dequeues.
 *
 * <p><b>Everything is reflective and everything degrades.</b> This class lives in {@code core} and
 * must not link {@code dwm} (a detachable auxiliary) or the game. A missing dwm, a renamed method
 * or a headless JVM leaves every method a no-op, the way {@code BoardClockBridge} degrades when
 * board is absent. It also must not throw: it runs inlined on the game thread, inside the keyboard
 * loop, where an exception would take the client down.
 *
 * <p><b>Opt-in.</b> Nothing happens unless {@code -Dmcp.dwm.hotkey} names a scancode, or
 * {@code -Dmcp.core.overlay=true} selects the default. The patch itself is inert without it, so
 * arming KI-11 does not by itself change how the game behaves.
 */
public final class DwmHotkey {

    /** Names a scancode to bind, overriding the default. */
    private static final String KEY_PROPERTY = "mcp.dwm.hotkey";
    /** The existing opt-in flag the launcher scripts already use for experimental UI. */
    private static final String ENABLE_PROPERTY = "mcp.core.overlay";

    /** RSHIFT in LWJGL2/DirectInput scancodes — the key the previous launcher used. */
    private static final int DEFAULT_KEY = 0x36;

    private static final String DWM_ENTRY = "net.marcloud.mcp.dwm.DwmEntry";
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String GUI_SCREEN = "net.minecraft.client.gui.GuiScreen";
    private static final String KEYBOARD = "org.lwjgl.input.Keyboard";

    /**
     * The bound scancode, or -1 when the hotkey is disabled. Resolved once: reading a system
     * property per keystroke would be wasteful, and the binding is not meant to change mid-run.
     */
    private static final int BOUND_KEY = resolveBoundKey();

    /** Whether the bound key was down on the previous event, so a hold does not re-fire. */
    private static boolean wasDown;

    /** Set after the first failure to reach the game, so a broken mapping is reported once. */
    private static boolean reported;

    private DwmHotkey() {
    }

    /**
     * Called from the injected {@code INVOKESTATIC} at the top of
     * {@code Minecraft.dispatchKeypresses}, once per keyboard event.
     *
     * <p>Takes no arguments and returns void deliberately: a no-operand static call is
     * stack-neutral, so the injection needs no new local, creates no branch target, and leaves the
     * method's existing stack map frames valid. The event being dispatched is read from
     * {@code Keyboard}'s current-event accessors, which is where vanilla itself reads it.
     */
    public static void onKeyEvent() {
        if (BOUND_KEY < 0) {
            return;
        }
        try {
            Class<?> keyboard = Class.forName(KEYBOARD);
            int key = (Integer) keyboard.getMethod("getEventKey").invoke(null);
            boolean down = (Boolean) keyboard.getMethod("getEventKeyState").invoke(null);

            if (key != BOUND_KEY) {
                return;
            }
            // Edge, not level: fire on the up-to-down transition only, so holding the key does not
            // reopen the screen every event. The release re-arms it.
            if (!down) {
                wasDown = false;
                return;
            }
            if (wasDown) {
                return;
            }
            wasDown = true;
            toggleScreen();
        } catch (Throwable t) {
            // Inlined on the render thread inside the keyboard loop: a fault here must never
            // escape into the game. Reported once so a genuine mapping break is still visible.
            reportOnce("hotkey dispatch failed", t);
        }
    }

    /**
     * Show the DWM screen, or dismiss it if it is already showing.
     *
     * <p>Toggle rather than open: the same key that summons the UI should put it away, and the
     * screen's own Escape handling is not available while the game has focus.
     */
    private static void toggleScreen() {
        try {
            Class<?> dwm = Class.forName(DWM_ENTRY);
            Class<?> mcClass = Class.forName(MINECRAFT);
            Object mc = mcClass.getMethod("getMinecraft").invoke(null);
            if (mc == null) {
                return;
            }
            Class<?> screenClass = Class.forName(GUI_SCREEN);
            Method display = mcClass.getMethod("displayGuiScreen", screenClass);

            Object current = mcClass.getField("currentScreen").get(mc);
            if (current != null && screenClass.isInstance(current)
                    && current.getClass().getName().startsWith("net.marcloud.mcp.dwm.")) {
                // Ours is already up: put it away rather than stacking another.
                display.invoke(mc, (Object) null);
                return;
            }
            if (current != null) {
                // Some other screen owns the input (a menu, the chat box). Replacing it would
                // discard whatever the player was doing, so leave it alone.
                return;
            }

            Object screen = dwm.getMethod("createScreen").invoke(null);
            if (screen == null) {
                // dwm present but its backend could not construct — DwmEntry already logged why.
                return;
            }
            display.invoke(mc, screen);
        } catch (ClassNotFoundException absent) {
            // dwm is not on the classpath. Normal for a detachable auxiliary: no UI, no complaint.
        } catch (Throwable t) {
            reportOnce("could not open the dwm screen", t);
        }
    }

    /**
     * The scancode to bind, or -1 for disabled.
     *
     * <p>An unparseable or out-of-range value disables the hotkey rather than silently falling back
     * to the default: a typo in a launch flag should be visible, not quietly ignored.
     */
    private static int resolveBoundKey() {
        String explicit = System.getProperty(KEY_PROPERTY);
        if (explicit != null && !explicit.trim().isEmpty()) {
            try {
                int code = Integer.decode(explicit.trim());
                if (code >= 0 && code < 256) {
                    return code;
                }
                System.err.println("[MCP KI-11] " + KEY_PROPERTY + "=" + explicit
                        + " is outside the scancode range; hotkey disabled.");
            } catch (NumberFormatException e) {
                System.err.println("[MCP KI-11] " + KEY_PROPERTY + "=" + explicit
                        + " is not a number; hotkey disabled.");
            }
            return -1;
        }
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))
                ? DEFAULT_KEY : -1;
    }

    /** The bound scancode, or -1 when disabled. Diagnostics and tests. */
    public static int boundKey() {
        return BOUND_KEY;
    }

    /** Clear the edge-detect state. Tests only; a fresh screen should re-arm cleanly. */
    static void resetEdgeState() {
        wasDown = false;
    }

    private static void reportOnce(String what, Throwable t) {
        if (reported) {
            return;
        }
        reported = true;
        System.err.println("[MCP KI-11] " + what + " (reported once): " + t);
    }
}
