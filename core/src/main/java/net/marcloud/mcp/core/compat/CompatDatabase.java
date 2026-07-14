package net.marcloud.mcp.core.compat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of known compat patches — the NT AppCompat {@code .sdb} (shim
 * database) analogue. Patches are registered in-code (each confirmed KI contributes
 * one patch), and {@link CompatEngine} reads the database at premain to decide what
 * to verify, filter, and apply.
 *
 * <p>Populated once at startup (before the game classes load) and then read on the
 * class-loading thread as each target class arrives, so access is synchronized and
 * snapshots are returned as immutable copies. Lookups the engine's transformer needs
 * are indexed by target class (dotted FQN).
 *
 * <p><b>Registration confers NO trust.</b> Adding a patch here only makes it VISIBLE
 * to {@link CompatEngine} and {@code list_compat_patches}; it does NOT arm the patch and
 * grants NO signature-free trust. There is exactly ONE arming path in the engine — a
 * valid Ed25519 signature verified against {@link TrustAnchors}. An unsigned in-code
 * patch registers but never applies (fail-safe: empty anchors arm nothing).
 */
public final class CompatDatabase {

    private final Map<String, CompatPatch> byId = new LinkedHashMap<>();

    /**
     * Register a patch. A patch must be {@linkplain PatchManifest#isBound() bound}
     * (transform hash + content-addressed id) so it has a stable {@code patchId};
     * re-registering the same id is rejected to avoid silent shadowing.
     *
     * <p>Registration does not arm the patch and grants no trust: {@link CompatEngine#build}
     * still requires a valid signature against {@link TrustAnchors}, so an unsigned patch
     * registered here will not apply.
     */
    public synchronized void register(CompatPatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        PatchManifest m = patch.manifest();
        if (m == null) {
            throw new IllegalArgumentException("patch manifest must not be null");
        }
        if (!m.isBound()) {
            throw new IllegalArgumentException(
                    "patch '" + m.code() + "' is unbound (no transform hash / patchId); "
                    + "call PatchManifest.withTransform(...) before registering");
        }
        if (byId.containsKey(m.patchId())) {
            throw new IllegalArgumentException(
                    "duplicate patchId " + m.patchId() + " (code " + m.code() + ")");
        }
        byId.put(m.patchId(), patch);
    }

    /**
     * Immutable snapshot of all registered patches, in registration order. The
     * {@link CompatEngine} builds its own target-class dispatch index from this
     * snapshot at premain; the database itself is a flat catalog, not a dispatcher.
     */
    public synchronized List<CompatPatch> all() {
        return List.copyOf(byId.values());
    }

    /** The patch with this content-addressed id, or null. */
    public synchronized CompatPatch byPatchId(String patchId) {
        return byId.get(patchId);
    }

    /** Number of registered patches. */
    public synchronized int size() {
        return byId.size();
    }
}
