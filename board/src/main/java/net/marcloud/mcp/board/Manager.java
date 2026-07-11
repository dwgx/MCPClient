package net.marcloud.mcp.board;

import java.util.List;

/**
 * The common registry contract every board manager exposes — {@link Matrix} (of
 * {@link Chip}s), the HUD panel manager, the keybind pin manager, and any future
 * typed subsystem manager all implement it. Extracting this lets code treat every
 * manager uniformly (e.g. "enumerate every registered manager for a debug panel",
 * or "clear all managers on shutdown") without knowing each concrete type, and
 * a new id-keyed manager reuses this surface instead of re-declaring it.
 *
 * <p>NOTE: the keybind manager (PinMatrix over Pin) deliberately does NOT
 * implement this contract — it routes by integer key code (one key to many
 * pins) with no unique string id per element, so an id-keyed collection contract
 * does not fit it. Only id-keyed managers (Matrix, HudMatrix) implement Manager.
 *
 * <p>Element type {@code T} is intentionally unconstrained (NOT {@code T extends
 * Chip}) so a manager of non-{@code Chip} elements could still implement it if it
 * is id-keyed. Chip-specific batch operations
 * ({@code enableAll}/{@code disableAll}) live on {@link Matrix}, not here.
 *
 * <p>Each element has a stable string id; managers are insertion-ordered and, by
 * board convention, mutated from the game thread only.
 *
 * @param <T> the element type this manager holds
 */
public interface Manager<T> {

    /** Add {@code element}, running any load hook. Returns the element. */
    T add(T element);

    /** Remove {@code element}, running any unload hook. {@code true} if it was present. */
    boolean remove(T element);

    /** Remove the element with {@code id} (unload hook fired). Returns it, or {@code null}. */
    T removeById(String id);

    /** The element with {@code id}, or {@code null} if absent. */
    T byId(String id);

    /** {@code true} if an element with {@code id} is present. */
    boolean contains(String id);

    /** An unmodifiable snapshot of all elements, in insertion order. */
    List<T> all();

    /** Number of elements held. */
    int size();

    /** Remove every element, running each unload hook. */
    void clear();
}
