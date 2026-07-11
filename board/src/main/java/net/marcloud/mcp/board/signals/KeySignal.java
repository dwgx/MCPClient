package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * A keyboard key changed state — one physical key going down or up. The game's
 * input seam publishes one of these per key transition; the keybind layer
 * ({@code Pin}/{@code PinMatrix}) listens and routes them to the chips bound to
 * that key.
 *
 * <p>This is the ONE canonical key signal for the whole framework. Carries an
 * LWJGL-style integer key code plus a pressed/released flag — plain primitives,
 * no {@code net.minecraft.*} or input-library type leaks. Tests can synthesise
 * them directly (or via {@link #down}/{@link #up}), so the whole keybind
 * subsystem is headless-testable without a live window.
 *
 * <p>Immutable; not cancellable — a keybind observes input, it does not veto the
 * game's own key handling.
 */
public final class KeySignal extends Signal {

    private final int keyCode;
    private final boolean pressed;

    /**
     * @param keyCode the LWJGL key code
     * @param pressed {@code true} for a key-down, {@code false} for a key-up
     */
    public KeySignal(int keyCode, boolean pressed) {
        this.keyCode = keyCode;
        this.pressed = pressed;
    }

    /** A key-down signal for {@code keyCode}. */
    public static KeySignal down(int keyCode) {
        return new KeySignal(keyCode, true);
    }

    /** A key-up signal for {@code keyCode}. */
    public static KeySignal up(int keyCode) {
        return new KeySignal(keyCode, false);
    }

    /** The LWJGL key code that changed state. */
    public int keyCode() {
        return keyCode;
    }

    /** {@code true} if this is a press (key-down); {@code false} if a release. */
    public boolean isPressed() {
        return pressed;
    }

    @Override
    public String toString() {
        return "KeySignal{keyCode=" + keyCode + ", " + (pressed ? "down" : "up") + "}";
    }
}
