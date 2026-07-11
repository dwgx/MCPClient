package net.marcloud.mcp.board;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A feature unit — a chip soldered onto the {@link Board}. A Chip is the neutral,
 * general-purpose home for ANY in-game feature: a startup-screen replacement, a
 * premium-account login flow, a HUD element, an automation. There is
 * deliberately NO cheat/legit layering (design doc 06 §4/§7 rule 4) — a
 * {@link #category()} label and a {@link #pin() keybind} are optional attributes
 * only.
 *
 * <p>Lifecycle: {@code onLoad()} once when the chip is added to a {@link Matrix};
 * then {@code onEnable()}/{@code onDisable()} each time it is toggled; then
 * {@code onUnload()} once when removed. Subclasses override the hooks they need;
 * all default to no-ops so a minimal chip is just an {@code id()}.
 *
 * <p>FROZEN framework contract (design doc 06 §7): do not change these
 * signatures; new features only subclass.
 */
public abstract class Chip {

    /** Sentinel {@link #pin()} value meaning "no key bound". */
    public static final int NO_PIN = -1;

    private boolean enabled;
    private int pin = NO_PIN;

    /**
     * The auto-subscription bag. Every {@link Trace.Subscription} handed to
     * {@link #track(Trace.Subscription)} lands here and is cancelled automatically
     * when the chip is disabled, so {@code enabled == subscribed} holds even if a
     * subclass forgets to cancel in {@link #onDisable()}. Copy-on-write so the
     * game thread can publish while a chip enables/disables concurrently.
     */
    private final CopyOnWriteArrayList<Trace.Subscription> subscriptions =
            new CopyOnWriteArrayList<Trace.Subscription>();

    /**
     * Stable unique identifier for this chip (used by {@link Matrix#byId}).
     * Defaults to the simple class name; override for a custom id.
     */
    public String id() {
        return getClass().getSimpleName();
    }

    /** Human-readable display name. Defaults to {@link #id()}. */
    public String name() {
        return id();
    }

    /**
     * Optional free-form category label (e.g. "startup", "login", "render",
     * "automation"). Purely descriptive — NOT a privilege/cheat tier. Defaults
     * to {@code null} (uncategorised).
     */
    public String category() {
        return null;
    }

    // ---- lifecycle ---------------------------------------------------------

    /** Called once when this chip is added to a {@link Matrix}. Default no-op. */
    protected void onLoad() {
    }

    /** Called each time the chip is enabled. Default no-op. */
    protected void onEnable() {
    }

    /** Called each time the chip is disabled. Default no-op. */
    protected void onDisable() {
    }

    /** Called once when this chip is removed from a {@link Matrix}. Default no-op. */
    protected void onUnload() {
    }

    // ---- auto-subscription bag ---------------------------------------------

    /**
     * Register a {@link Trace.Subscription} to be auto-cancelled when this chip is
     * disabled, and return it for chaining. Typical use in {@link #onEnable()}:
     *
     * <pre>{@code
     * track(trace.subscribe(TickSignal.class, this::onTick));
     * }</pre>
     *
     * <p>This makes {@code enabled == subscribed} a framework invariant: a
     * subclass that tracks its subscriptions on enable never leaks them, even if
     * it forgets to cancel in {@link #onDisable()}. A {@code null} handle is
     * ignored (returned as-is). The bag is emptied on every disable, so tracking
     * again on the next enable is the intended re-subscription path.
     *
     * @param s the subscription to track (may be {@code null})
     * @return {@code s}, unchanged, for chaining
     */
    protected final Trace.Subscription track(Trace.Subscription s) {
        if (s != null) {
            subscriptions.add(s);
        }
        return s;
    }

    // ---- toggle state ------------------------------------------------------

    /** {@code true} while this chip is enabled. */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * Enable or disable the chip, firing {@link #onEnable()}/{@link #onDisable()}
     * only on an actual state change. Callback faults are isolated so a bad chip
     * cannot corrupt the toggle state.
     */
    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        try {
            if (enabled) {
                onEnable();
            } else {
                onDisable();
                // Auto-cancel every tracked subscription so enabled == subscribed
                // holds even when onDisable forgot to cancel. Still inside the
                // fault-isolation guard: a throwing cancel cannot corrupt state.
                cancelTracked();
            }
        } catch (Throwable e) {
            System.err.println("[Chip] " + id() + (enabled ? " onEnable" : " onDisable")
                    + " threw: " + e);
        }
    }

    /**
     * Cancel and drop every tracked subscription. Each cancel is individually
     * fault-isolated so one bad handle cannot leave the rest live, and the bag is
     * always emptied so the next enable starts from a clean slate.
     */
    private void cancelTracked() {
        if (subscriptions.isEmpty()) {
            return;
        }
        for (Trace.Subscription s : subscriptions) {
            try {
                s.cancel();
            } catch (Throwable e) {
                System.err.println("[Chip] " + id() + " subscription cancel threw: " + e);
            }
        }
        subscriptions.clear();
    }

    /** Flip the enabled state and return the new value. */
    public final boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    // ---- optional keybind hook (full Pin type arrives later) ---------------

    /**
     * The bound key code for this chip, or {@link #NO_PIN} if none. This is the
     * optional keybind hook; the richer {@code Pin} type layers on top later
     * without changing this contract.
     */
    public final int pin() {
        return pin;
    }

    /** Bind (or clear, with {@link #NO_PIN}) the key that toggles this chip. */
    public final void setPin(int keyCode) {
        this.pin = keyCode;
    }

    // ---- internal lifecycle bridge (used by Matrix) ------------------------

    /** Invoke {@link #onLoad()} with fault isolation. Called by {@link Matrix}. */
    final void fireLoad() {
        try {
            onLoad();
        } catch (Throwable e) {
            System.err.println("[Chip] " + id() + " onLoad threw: " + e);
        }
    }

    /** Invoke {@link #onUnload()} with fault isolation. Called by {@link Matrix}. */
    final void fireUnload() {
        try {
            onUnload();
        } catch (Throwable e) {
            System.err.println("[Chip] " + id() + " onUnload threw: " + e);
        }
    }
}
