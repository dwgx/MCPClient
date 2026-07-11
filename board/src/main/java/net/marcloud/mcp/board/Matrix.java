package net.marcloud.mcp.board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A feature manager — the matrix the {@link Chip}s are soldered onto. Holds a
 * set of chips keyed by {@link Chip#id()}, drives their load/unload lifecycle,
 * and offers batch enable/disable. A {@link Board} may own several matrices (a
 * feature matrix, a HUD matrix, a command matrix…).
 *
 * <p>Not thread-safe by contract: mutate from the game thread. Insertion order
 * is preserved for deterministic iteration.
 *
 * <p>FROZEN framework contract (design doc 06 §7).
 *
 * @param <T> the chip type this matrix holds
 */
public final class Matrix<T extends Chip> implements Manager<T> {

    private final Map<String, T> chips = new LinkedHashMap<String, T>();

    /**
     * Add a chip and fire its {@link Chip#onLoad()}. Returns the chip. Throws if
     * a chip with the same {@link Chip#id()} is already present.
     */
    public T add(T chip) {
        if (chip == null) {
            throw new IllegalArgumentException("chip must not be null");
        }
        String id = chip.id();
        if (chips.containsKey(id)) {
            throw new IllegalStateException("duplicate chip id: " + id);
        }
        chips.put(id, chip);
        chip.fireLoad();
        return chip;
    }

    /**
     * Remove {@code chip}, disabling it (if enabled) and firing
     * {@link Chip#onUnload()}. Returns {@code true} if it was present.
     */
    public boolean remove(T chip) {
        if (chip == null) {
            return false;
        }
        return removeById(chip.id()) != null;
    }

    /**
     * Remove the chip with {@code id}, disabling it (if enabled) and firing
     * {@link Chip#onUnload()}. Returns the removed chip, or {@code null}.
     */
    public T removeById(String id) {
        T chip = chips.remove(id);
        if (chip != null) {
            chip.setEnabled(false);
            chip.fireUnload();
        }
        return chip;
    }

    /** The chip with {@code id}, or {@code null} if absent. */
    public T byId(String id) {
        return chips.get(id);
    }

    /** {@code true} if a chip with {@code id} is present. */
    public boolean contains(String id) {
        return chips.containsKey(id);
    }

    /** An unmodifiable snapshot of all chips, in insertion order. */
    public List<T> all() {
        return Collections.unmodifiableList(new ArrayList<T>(chips.values()));
    }

    /** Number of chips in this matrix. */
    public int size() {
        return chips.size();
    }

    // ---- batch lifecycle ---------------------------------------------------

    /**
     * Enable every chip. Iterates a snapshot so a chip whose {@code onEnable}
     * adds or removes another chip cannot throw {@link java.util.ConcurrentModificationException}
     * and abort the batch half-applied.
     */
    public void enableAll() {
        for (T chip : new ArrayList<T>(chips.values())) {
            chip.setEnabled(true);
        }
    }

    /**
     * Disable every chip. Snapshotted for the same reason as {@link #enableAll()}
     * — a chip's {@code onDisable} may mutate the matrix.
     */
    public void disableAll() {
        for (T chip : new ArrayList<T>(chips.values())) {
            chip.setEnabled(false);
        }
    }

    /**
     * Disable and unload every chip, then empty the matrix. Fires
     * {@link Chip#onUnload()} for each.
     */
    public void clear() {
        List<T> snapshot = new ArrayList<T>(chips.values());
        chips.clear();
        for (T chip : snapshot) {
            chip.setEnabled(false);
            chip.fireUnload();
        }
    }
}
