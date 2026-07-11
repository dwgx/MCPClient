package net.marcloud.mcp.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The backplane — a neutral, in-module service registry that subsystems plug
 * into to discover each other at runtime with ZERO compile-time coupling. A
 * subsystem registers a service instance under a type (or key); another side
 * looks it up by type and gets a handle or {@code null} (graceful degradation).
 *
 * <p>This is the seam that lets Board find mcp-core (and vice versa) by
 * reflection — the caller registers/looks up by an interface or {@code Object},
 * so neither side has to import the other. Delete either subsystem and
 * {@link #find} simply returns {@code null}.
 *
 * <p>Thread-safe: registration and lookup may happen from different threads
 * (each subsystem boots on its own path).
 *
 * <p>FROZEN framework contract (design doc 06 §7).
 */
public final class Backplane {

    private static final Map<String, Object> SERVICES = new ConcurrentHashMap<String, Object>();

    private Backplane() {
    }

    /** Register {@code service} under {@code type}. Replaces any prior binding. */
    public static <S> void register(Class<S> type, S service) {
        if (type == null || service == null) {
            throw new IllegalArgumentException("type and service must not be null");
        }
        SERVICES.put(type.getName(), service);
    }

    /** Register {@code service} under an explicit string key. */
    public static void register(String key, Object service) {
        if (key == null || service == null) {
            throw new IllegalArgumentException("key and service must not be null");
        }
        SERVICES.put(key, service);
    }

    /**
     * Look up a service by type, or {@code null} if none is registered.
     * Callers should treat {@code null} as "the other subsystem is absent" and
     * degrade gracefully.
     */
    public static <S> S find(Class<S> type) {
        if (type == null) {
            return null;
        }
        Object service = SERVICES.get(type.getName());
        return service == null ? null : type.cast(service);
    }

    /** Look up a service by string key, or {@code null} if none is registered. */
    public static Object find(String key) {
        return key == null ? null : SERVICES.get(key);
    }

    /** {@code true} if a service is registered under {@code key}. */
    public static boolean has(String key) {
        return key != null && SERVICES.containsKey(key);
    }

    /** Remove the binding for {@code type}. Returns the removed service, or {@code null}. */
    public static Object unregister(Class<?> type) {
        return type == null ? null : SERVICES.remove(type.getName());
    }

    /** Remove the binding for {@code key}. Returns the removed service, or {@code null}. */
    public static Object unregister(String key) {
        return key == null ? null : SERVICES.remove(key);
    }

    /** A snapshot of the registered service keys (for diagnostics/tests). */
    public static List<String> keys() {
        return new ArrayList<String>(SERVICES.keySet());
    }

    /** Remove every registration (mainly for tests). */
    public static void clear() {
        SERVICES.clear();
    }
}
