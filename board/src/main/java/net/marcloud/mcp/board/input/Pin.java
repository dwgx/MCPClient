package net.marcloud.mcp.board.input;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.signals.KeySignal;

/**
 * A keybind — one leg of the board's input, tying a physical key to something
 * that turns on and off. A Pin has three parts: a key code, a {@link Mode}
 * (press-to-toggle vs. active-while-held), and a target it drives — either a
 * bound {@link Chip} or a neutral {@link Action}.
 *
 * <p>Routing is driven by {@link KeySignal}s: a {@link PinMatrix} matches a
 * signal's key code to its pins and calls {@link #handle(KeySignal)}. In
 * {@link Mode#TOGGLE} a key-down flips the target's active state; in
 * {@link Mode#HOLD} the target is active exactly while the key is down.
 *
 * <p>For a chip-bound pin the {@link Chip} is the source of truth for the
 * active state ({@link Chip#isEnabled()}), and constructing the pin syncs the
 * chip's optional {@link Chip#pin()} attribute to this key code — the documented
 * seam between the keybind subsystem and the frozen {@link Chip} contract.
 *
 * <p>Not a frozen contract class: the input subsystem layers this on top of the
 * frozen skeleton without changing it.
 */
public final class Pin {

    /** How a key drives its target. */
    public enum Mode {
        /** A key-down flips the target on/off; the key-up is ignored. */
        TOGGLE,
        /** The target is active exactly while the key is held down. */
        HOLD
    }

    /**
     * A neutral on/off target for pins that are not backed by a {@link Chip}
     * (menu actions, one-shot commands, custom state).
     */
    @FunctionalInterface
    public interface Action {
        /**
         * Drive the target. In {@link Mode#TOGGLE} it is called on each key-down
         * with the newly-flipped state; in {@link Mode#HOLD} with {@code true} on
         * press and {@code false} on release.
         */
        void set(boolean active);
    }

    /** Internal on/off target so chip- and action-backed pins share one path. */
    private interface Target {
        boolean isActive();

        void setActive(boolean active);

        Chip chip();
    }

    private final int keyCode;
    private final Mode mode;
    private final Target target;
    private boolean enabled = true;

    private Pin(int keyCode, Mode mode, Target target) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        this.keyCode = keyCode;
        this.mode = mode;
        this.target = target;
    }

    // ---- factories ---------------------------------------------------------

    /** A press-to-toggle pin bound to {@code chip}. Syncs {@link Chip#setPin(int)}. */
    public static Pin toggle(int keyCode, Chip chip) {
        return forChip(keyCode, Mode.TOGGLE, chip);
    }

    /** An active-while-held pin bound to {@code chip}. Syncs {@link Chip#setPin(int)}. */
    public static Pin hold(int keyCode, Chip chip) {
        return forChip(keyCode, Mode.HOLD, chip);
    }

    /** A pin bound to {@code chip} in the given {@code mode}. Syncs {@link Chip#setPin(int)}. */
    public static Pin forChip(int keyCode, Mode mode, final Chip chip) {
        if (chip == null) {
            throw new IllegalArgumentException("chip must not be null");
        }
        chip.setPin(keyCode);
        return new Pin(keyCode, mode, new Target() {
            @Override
            public boolean isActive() {
                return chip.isEnabled();
            }

            @Override
            public void setActive(boolean active) {
                chip.setEnabled(active);
            }

            @Override
            public Chip chip() {
                return chip;
            }
        });
    }

    /** A press-to-toggle pin driving a neutral {@link Action}. */
    public static Pin toggle(int keyCode, Action action) {
        return forAction(keyCode, Mode.TOGGLE, action);
    }

    /** An active-while-held pin driving a neutral {@link Action}. */
    public static Pin hold(int keyCode, Action action) {
        return forAction(keyCode, Mode.HOLD, action);
    }

    /** A pin driving a neutral {@link Action} in the given {@code mode}. */
    public static Pin forAction(int keyCode, Mode mode, final Action action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        return new Pin(keyCode, mode, new Target() {
            private boolean active;

            @Override
            public boolean isActive() {
                return active;
            }

            @Override
            public void setActive(boolean active) {
                this.active = active;
                action.set(active);
            }

            @Override
            public Chip chip() {
                return null;
            }
        });
    }

    // ---- routing -----------------------------------------------------------

    /**
     * Apply {@code signal} to this pin. A no-op unless the pin is enabled and the
     * signal's key code matches this pin's key. In {@link Mode#TOGGLE} a key-down
     * flips the target and a key-up is ignored; in {@link Mode#HOLD} the target
     * tracks the pressed flag. Returns {@code true} if the pin acted.
     */
    public boolean handle(KeySignal signal) {
        if (signal == null || !enabled || !matches(signal.keyCode())) {
            return false;
        }
        if (mode == Mode.TOGGLE) {
            if (signal.isPressed()) {
                target.setActive(!target.isActive());
                return true;
            }
            return false;
        }
        // HOLD: active exactly while the key is down.
        target.setActive(signal.isPressed());
        return true;
    }

    /** {@code true} if {@code keyCode} is this pin's (bound, non-sentinel) key. */
    public boolean matches(int keyCode) {
        return this.keyCode == keyCode && keyCode != Chip.NO_PIN;
    }

    // ---- attributes --------------------------------------------------------

    /** The key code that drives this pin. */
    public int keyCode() {
        return keyCode;
    }

    /** The pin's mode. */
    public Mode mode() {
        return mode;
    }

    /** The bound {@link Chip}, or {@code null} if this pin drives a raw {@link Action}. */
    public Chip chip() {
        return target.chip();
    }

    /** The current active state of the pin's target. */
    public boolean isActive() {
        return target.isActive();
    }

    /** {@code true} while this pin will respond to key signals. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Enable/disable the pin itself (without unbinding). A disabled pin ignores signals. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        Chip c = target.chip();
        return "Pin{key=" + keyCode + ", mode=" + mode
                + ", target=" + (c != null ? "chip:" + c.id() : "action") + "}";
    }
}
