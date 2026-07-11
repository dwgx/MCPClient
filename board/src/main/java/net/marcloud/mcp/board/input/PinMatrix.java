package net.marcloud.mcp.board.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.KeySignal;

/**
 * The keybind registry — the matrix of {@link Pin}s wired to a {@link Trace}.
 * Attach it to a trace and every {@link KeySignal} on that trace is routed to
 * the pins bound to its key code, driving their targets (toggle or hold). This
 * is the input counterpart to the feature {@link net.marcloud.mcp.board.Matrix
 * Matrix}.
 *
 * <p>One key may bind several pins (they all fire, in registration order).
 * Routing is synchronous on the publishing (game) thread, mirroring the frozen
 * {@link Trace} contract; a faulting pin is isolated so it cannot break the
 * publisher or starve the other pins on the same key.
 *
 * <p>Headless-testable: publish a synthetic {@link KeySignal} (or call
 * {@link #route(KeySignal)} directly) with no live window.
 *
 * <p>Not a frozen contract class: the input subsystem layers this on top of the
 * frozen skeleton without changing it.
 */
public final class PinMatrix {

    private final List<Pin> pins = new ArrayList<Pin>();

    private Trace attachedTrace;
    private final Trace.Listener<KeySignal> listener = new Trace.Listener<KeySignal>() {
        @Override
        public void on(KeySignal signal) {
            route(signal);
        }
    };

    /**
     * Subscribe this matrix to {@code trace} so published {@link KeySignal}s are
     * routed to its pins. Idempotent per matrix — re-attaching to the same trace
     * is a no-op; attaching to a different trace detaches the previous one first.
     * Returns this matrix for chaining.
     */
    public PinMatrix attach(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        if (trace == attachedTrace) {
            return this;
        }
        detach();
        trace.subscribe(KeySignal.class, listener);
        attachedTrace = trace;
        return this;
    }

    /** Unsubscribe from the attached trace (if any). Idempotent. */
    public void detach() {
        if (attachedTrace != null) {
            attachedTrace.unsubscribe(listener);
            attachedTrace = null;
        }
    }

    /** {@code true} while this matrix is subscribed to a trace. */
    public boolean isAttached() {
        return attachedTrace != null;
    }

    // ---- registry ----------------------------------------------------------

    /** Register {@code pin}. Returns the pin. */
    public Pin add(Pin pin) {
        if (pin == null) {
            throw new IllegalArgumentException("pin must not be null");
        }
        pins.add(pin);
        return pin;
    }

    /** Bind {@code chip} to {@code keyCode} as a press-to-toggle pin; registers and returns it. */
    public Pin bindToggle(int keyCode, Chip chip) {
        return add(Pin.toggle(keyCode, chip));
    }

    /** Bind {@code chip} to {@code keyCode} as an active-while-held pin; registers and returns it. */
    public Pin bindHold(int keyCode, Chip chip) {
        return add(Pin.hold(keyCode, chip));
    }

    /** Remove {@code pin}. Returns {@code true} if it was present. */
    public boolean remove(Pin pin) {
        return pins.remove(pin);
    }

    /**
     * Remove every pin bound to {@code keyCode}. Returns the number removed.
     */
    public int removeByKey(int keyCode) {
        int before = pins.size();
        List<Pin> survivors = new ArrayList<Pin>(pins.size());
        for (Pin p : pins) {
            if (!p.matches(keyCode)) {
                survivors.add(p);
            }
        }
        pins.clear();
        pins.addAll(survivors);
        return before - pins.size();
    }

    /** All pins bound to {@code keyCode}, in registration order (possibly empty). */
    public List<Pin> byKey(int keyCode) {
        List<Pin> hits = new ArrayList<Pin>();
        for (Pin p : pins) {
            if (p.matches(keyCode)) {
                hits.add(p);
            }
        }
        return hits;
    }

    /** An unmodifiable snapshot of all registered pins, in registration order. */
    public List<Pin> all() {
        return Collections.unmodifiableList(new ArrayList<Pin>(pins));
    }

    /** Number of registered pins. */
    public int size() {
        return pins.size();
    }

    /** Remove every pin. Does not detach from the trace. */
    public void clear() {
        pins.clear();
    }

    // ---- routing -----------------------------------------------------------

    /**
     * Route {@code signal} to every matching pin, in registration order,
     * synchronously. A faulting pin is isolated (logged to stderr) so it cannot
     * break the others or the publisher. Returns the number of pins that acted.
     * Called automatically for signals on the attached trace; may also be called
     * directly (e.g. in tests).
     */
    public int route(KeySignal signal) {
        if (signal == null) {
            return 0;
        }
        int acted = 0;
        // Snapshot so a pin that mutates the registry mid-dispatch can't disturb it.
        List<Pin> snapshot = new ArrayList<Pin>(pins);
        for (Pin pin : snapshot) {
            try {
                if (pin.handle(signal)) {
                    acted++;
                }
            } catch (Throwable e) {
                System.err.println("[PinMatrix] pin " + pin + " threw on "
                        + signal + ": " + e);
            }
        }
        return acted;
    }
}
