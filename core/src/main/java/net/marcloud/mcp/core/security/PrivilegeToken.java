package net.marcloud.mcp.core.security;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L4 — an NT-style access token's privilege set: a map of {@link Privilege} to
 * its <b>enabled</b> state. A privilege absent from the map is <i>not granted</i>
 * (holding a key means granted; the boolean means enabled). A privileged op
 * checks {@link #isEnabled}, which is true only when the privilege is both
 * granted and enabled.
 *
 * <p>Thread-safe: enabled flags are {@link AtomicBoolean} so a subject can toggle
 * a privilege (enable_privilege / disable_privilege tools) while MCP worker
 * threads read it. Grants are fixed at construction (a subject cannot grant
 * itself a new privilege — that would be self-escalation; only enable/disable of
 * an already-granted privilege is allowed).
 */
public final class PrivilegeToken {

    private final EnumMap<Privilege, AtomicBoolean> granted;

    /** @param initial privilege -> initial enabled state; keys are the grants. */
    public PrivilegeToken(Map<Privilege, Boolean> initial) {
        this.granted = new EnumMap<>(Privilege.class);
        if (initial != null) {
            for (Map.Entry<Privilege, Boolean> e : initial.entrySet()) {
                granted.put(e.getKey(), new AtomicBoolean(Boolean.TRUE.equals(e.getValue())));
            }
        }
    }

    /** A token granting every privilege, all enabled — the dev/hypervisor default. */
    public static PrivilegeToken wideOpen() {
        Map<Privilege, Boolean> all = new EnumMap<>(Privilege.class);
        for (Privilege p : Privilege.values()) {
            all.put(p, true);
        }
        return new PrivilegeToken(all);
    }

    /** An empty token (no privileges granted) — the strict floor. */
    public static PrivilegeToken none() {
        return new PrivilegeToken(Map.of());
    }

    /** True if the privilege is granted (regardless of enabled state). */
    public boolean isGranted(Privilege p) {
        return granted.containsKey(p);
    }

    /** True only if the privilege is granted AND currently enabled. */
    public boolean isEnabled(Privilege p) {
        AtomicBoolean b = granted.get(p);
        return b != null && b.get();
    }

    /**
     * Enable a granted privilege. Returns false if it was never granted (a
     * subject cannot enable what it does not hold — no self-escalation).
     */
    public boolean enable(Privilege p) {
        AtomicBoolean b = granted.get(p);
        if (b == null) {
            return false;
        }
        b.set(true);
        return true;
    }

    /** Disable a granted privilege. Returns false if it was never granted. */
    public boolean disable(Privilege p) {
        AtomicBoolean b = granted.get(p);
        if (b == null) {
            return false;
        }
        b.set(false);
        return true;
    }

    /** Snapshot of granted privileges and their enabled state (for introspection). */
    public Map<Privilege, Boolean> snapshot() {
        Map<Privilege, Boolean> out = new EnumMap<>(Privilege.class);
        for (Map.Entry<Privilege, AtomicBoolean> e : granted.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return out;
    }

    /** A human-readable summary like "SE_DEBUG_CLASS(on), SE_NET_RAW(off)". */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Privilege p : Privilege.values()) {
            AtomicBoolean b = granted.get(p);
            if (b != null) {
                parts.add(p.name().toLowerCase(Locale.ROOT) + (b.get() ? "(on)" : "(off)"));
            }
        }
        return parts.isEmpty() ? "(no privileges)" : String.join(", ", parts);
    }
}
