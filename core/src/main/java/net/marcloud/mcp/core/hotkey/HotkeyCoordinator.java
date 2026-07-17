package net.marcloud.mcp.core.hotkey;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * Live wiring for the {@link HotkeyManager}: on each {@link TickEvent} it polls the shim
 * keyboard (reflectively — {@code org.lwjgl.input.Keyboard.isKeyDown}) for the set of keys
 * currently down and feeds it to the manager, which edge-detects presses and fires actions.
 * The tick is our heartbeat, so this adds NO new bytecode-injection surface — it rides the
 * existing {@code Minecraft.runTick} seam that already publishes {@link TickEvent}.
 *
 * <p><b>Default binding.</b> RSHIFT toggles the DWM launcher, reached by reflection into
 * {@code net.marcloud.mcp.dwm.skiko.DesktopLauncher.toggle()} — core never compile-links a
 * dwm module (the detachable-auxiliary contract). dwm-skiko absent ⇒ the binding resolves to
 * a no-op and nothing happens on RSHIFT, exactly like {@code BoardClockBridge} degrades when
 * board is absent.
 *
 * <p>Opt-in: only armed when {@code -Dmcp.core.overlay=true} (the launcher is an experimental
 * live feature). Everything is fault-isolated: a reflection miss or a shim fault can never
 * disturb the tick/publish thread.
 */
public final class HotkeyCoordinator {

    private static final String ENV_FLAG = "mcp.core.overlay";
    private static final String KEYBOARD = "org.lwjgl.input.Keyboard";
    private static final String LAUNCHER = "net.marcloud.mcp.dwm.skiko.DesktopLauncher";

    /** RSHIFT (LWJGL/DirectInput scancode) — the launcher's open/close key. */
    private static final int KEY_RSHIFT = 0x36;
    /** Probe the low scancode range that covers every bindable key (letters, digits, mods). */
    private static final int MAX_SCANCODE = 256;

    private final EventBus bus;
    private final HotkeyManager manager = new HotkeyManager();

    // Cached shim reflection (resolved once on attach).
    private Method keyboardIsKeyDown;
    private boolean resolved;

    public HotkeyCoordinator(EventBus bus) {
        this.bus = bus;
    }

    /**
     * Arm the hotkey system: bind the default keys and subscribe to the tick heartbeat.
     * No-op when the overlay flag is off, the bus is null, or the shim keyboard is absent.
     * Safe to call once at boot; never throws.
     */
    public void attach() {
        if (!Boolean.parseBoolean(System.getProperty(ENV_FLAG, "false"))) {
            return; // opt-in only
        }
        if (bus == null) {
            return;
        }
        try {
            Class<?> kb = Class.forName(KEYBOARD);
            keyboardIsKeyDown = kb.getMethod("isKeyDown", int.class);
            resolved = true;
        } catch (Throwable t) {
            System.err.println("[HotkeyCoordinator] shim Keyboard unavailable — hotkeys disabled: " + t);
            return;
        }
        // Default binding: RSHIFT toggles the launcher (reflective, degrades to no-op).
        manager.bind(KEY_RSHIFT, "toggle-launcher", HotkeyCoordinator::toggleLauncher);
        bus.subscribe(TickEvent.class, this::onTick);
        System.err.println("[HotkeyCoordinator] armed (" + manager.bindingCount()
                + " binding(s); RSHIFT -> launcher).");
    }

    /** Poll the shim keyboard each tick and feed the down-set to the manager. Never throws. */
    private void onTick(TickEvent event) {
        if (!resolved) {
            return;
        }
        try {
            Set<Integer> down = new HashSet<>();
            for (int k = 0; k < MAX_SCANCODE; k++) {
                Object r = keyboardIsKeyDown.invoke(null, k);
                if (Boolean.TRUE.equals(r)) {
                    down.add(k);
                }
            }
            manager.onKeysDown(down);
        } catch (Throwable t) {
            // A shim fault must never disturb the tick thread.
        }
    }

    /** Reflectively toggle the DWM launcher; no-op if dwm-skiko is not on the classpath. */
    private static void toggleLauncher() {
        try {
            Class.forName(LAUNCHER).getMethod("toggle").invoke(null);
        } catch (ClassNotFoundException e) {
            // dwm-skiko absent — expected in a headless / no-overlay build.
        } catch (Throwable t) {
            System.err.println("[HotkeyCoordinator] launcher toggle failed: " + t);
        }
    }

    /** Package-visible for tests: the underlying manager (to assert bindings/edges). */
    HotkeyManager manager() {
        return manager;
    }
}
