package net.marcloud.mcp.board.persist;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A tolerant, nestable, typed key/value bag — the medium a {@link Persistable}
 * reads from and writes to. It is a thin typed facade over a
 * {@link LinkedHashMap} (insertion-ordered for deterministic files) whose values
 * are scalars ({@link String}, {@link Long}, {@link Double}, {@link Boolean}) or
 * nested {@code DataView}s. The engine ({@link Store}) and the {@link Json} codec
 * translate between this map and disk.
 *
 * <p><b>Tolerance is the whole point.</b> Every getter takes a default and NEVER
 * throws: a missing key, a {@code null}, or a value of the wrong stored type all
 * yield the supplied default (with best-effort coercion — a number stored as a
 * string still reads back as a number, a {@code "true"} string as a boolean).
 * This is what lets an old envelope (missing fields) or a future one (extra
 * fields) load without error. Unknown keys are simply left untouched.
 *
 * <p>Not thread-safe: like the rest of the board, mutate from one thread.
 */
public final class DataView {

    private final Map<String, Object> map;

    /** A fresh, empty view backed by an insertion-ordered map. */
    public DataView() {
        this(new LinkedHashMap<String, Object>());
    }

    /** Wrap an existing map (used by {@link Json} and {@link Store}). */
    DataView(Map<String, Object> map) {
        this.map = map == null ? new LinkedHashMap<String, Object>() : map;
    }

    /** The backing map — package-private so the codec/engine can (de)serialize it. */
    Map<String, Object> raw() {
        return map;
    }

    // ---- scalar writers (chainable) ----------------------------------------

    /** Store an {@code int} (kept as a long on disk). Returns {@code this}. */
    public DataView putInt(String key, int value) {
        map.put(key, Long.valueOf(value));
        return this;
    }

    /** Store a {@code long}. Returns {@code this}. */
    public DataView putLong(String key, long value) {
        map.put(key, Long.valueOf(value));
        return this;
    }

    /** Store a {@code double}. Returns {@code this}. */
    public DataView putDouble(String key, double value) {
        map.put(key, Double.valueOf(value));
        return this;
    }

    /** Store a {@code boolean}. Returns {@code this}. */
    public DataView putBoolean(String key, boolean value) {
        map.put(key, Boolean.valueOf(value));
        return this;
    }

    /** Store a string. A {@code null} value stores JSON {@code null}. Returns {@code this}. */
    public DataView putString(String key, String value) {
        map.put(key, value);
        return this;
    }

    // ---- nesting ------------------------------------------------------------

    /**
     * Get the nested view under {@code key}, creating (and storing) an empty one
     * if absent or if the existing value is not a view. Use this when WRITING a
     * nested structure. Never returns {@code null}.
     */
    @SuppressWarnings("unchecked")
    public DataView child(String key) {
        Object existing = map.get(key);
        if (existing instanceof Map) {
            return new DataView((Map<String, Object>) existing);
        }
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        map.put(key, nested);
        return new DataView(nested);
    }

    /**
     * Get the nested view under {@code key} for READING, WITHOUT mutating this
     * view: returns a detached empty view if the key is absent or not a view, so
     * callers never NPE and a missing sub-object reads as "all defaults".
     */
    @SuppressWarnings("unchecked")
    public DataView getView(String key) {
        Object existing = map.get(key);
        if (existing instanceof Map) {
            return new DataView((Map<String, Object>) existing);
        }
        return new DataView();
    }

    /** {@code true} if {@code key} holds a nested view. */
    public boolean hasView(String key) {
        return map.get(key) instanceof Map;
    }

    // ---- scalar readers (tolerant, defaulting, coercing) --------------------

    /** {@code true} if {@code key} is present (any value, including null). */
    public boolean has(String key) {
        return map.containsKey(key);
    }

    /** Read a string, or {@code def} if absent/null. Non-strings are stringified. */
    public String getString(String key, String def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof String) {
            return (String) v;
        }
        return String.valueOf(v);
    }

    /** Read an int, coercing numbers/numeric strings; {@code def} on absence/mismatch. */
    public int getInt(String key, int def) {
        return (int) getLong(key, def);
    }

    /** Read a long, coercing numbers/numeric strings; {@code def} on absence/mismatch. */
    public long getLong(String key, long def) {
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return (long) Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** Read a double, coercing numbers/numeric strings; {@code def} on absence/mismatch. */
    public double getDouble(String key, double def) {
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** Read a boolean, coercing {@code "true"}/{@code "false"} and numbers; {@code def} otherwise. */
    public boolean getBoolean(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return def;
    }

    // ---- introspection ------------------------------------------------------

    /** An unmodifiable snapshot of the keys, in insertion order. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(map.keySet());
    }

    /** Number of keys held at this level. */
    public int size() {
        return map.size();
    }

    /** {@code true} if this view holds no keys. */
    public boolean isEmpty() {
        return map.isEmpty();
    }
}
