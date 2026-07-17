package net.marcloud.mcp.core.hotkey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A tiny, self-contained hotkey system that is DELIBERATELY not wired to Minecraft's own
 * {@code KeyBinding} registry — it owns its own key→action bindings and edge-detects presses
 * from a per-frame "keys currently down" snapshot. This is the same choice every reference
 * client makes (Wurst's {@code KeybindManager}, Southside's {@code @Binding}): the client's
 * hotkeys are its own layer, independent of the game's control settings, so a bind can never
 * collide with or be rebound by vanilla options.
 *
 * <p><b>Edge detection.</b> {@link #onKeysDown(Set)} is fed the set of scancodes down THIS
 * poll. A binding fires once on the up→down transition of its key (a press), never again while
 * the key is held, and re-arms once released. This turns a level signal (held) into an edge
 * (pressed) without needing the game's event queue — the same non-consuming discipline the DWM
 * launcher already uses, so it never drains events MC's own input loop consumes.
 *
 * <p><b>Pure + headless.</b> No GL, no MC, no reflection here — just scancodes and
 * {@link Runnable}s. {@link HotkeyCoordinator} owns the live wiring (tick heartbeat + reading
 * the shim keyboard). Each action is fault-isolated so one throwing binding can neither break
 * the poll nor stop the other bindings from firing.
 */
public final class HotkeyManager {

    private static final class Binding {
        final int scancode;
        final String label;
        final Runnable action;

        Binding(int scancode, String label, Runnable action) {
            this.scancode = scancode;
            this.label = label;
            this.action = action;
        }
    }

    private final List<Binding> bindings = new ArrayList<>();
    /** Scancodes that were down on the previous poll — the edge-detect baseline. */
    private Set<Integer> prevDown = new HashSet<>();

    /**
     * Bind {@code action} to fire once each time {@code scancode} is pressed (up→down).
     * A null action or a negative scancode is ignored (never a silent half-binding).
     *
     * @param scancode the LWJGL/DirectInput scancode (e.g. RSHIFT = 0x36)
     * @param label    a short human name for diagnostics
     * @param action   run once per press, on the polling thread; must be fault-tolerant
     */
    public void bind(int scancode, String label, Runnable action) {
        if (action == null || scancode < 0) {
            return;
        }
        bindings.add(new Binding(scancode, label, action));
    }

    /** Number of registered bindings (for diagnostics/tests). */
    public int bindingCount() {
        return bindings.size();
    }

    /**
     * Feed this poll's down-key set; fire the action of every binding whose key just
     * transitioned from up to down. Returns the number of actions fired this poll (for
     * tests). Each action is fault-isolated. A null set is treated as "nothing down".
     */
    public int onKeysDown(Set<Integer> down) {
        Set<Integer> now = down == null ? Set.of() : down;
        int fired = 0;
        for (Binding b : bindings) {
            boolean isDown = now.contains(b.scancode);
            boolean wasDown = prevDown.contains(b.scancode);
            if (isDown && !wasDown) {
                fired++;
                try {
                    b.action.run();
                } catch (Throwable t) {
                    System.err.println("[HotkeyManager] binding '" + b.label + "' threw: " + t);
                }
            }
        }
        // Snapshot for the next edge comparison (copy — the caller may reuse its set).
        prevDown = new HashSet<>(now);
        return fired;
    }
}
