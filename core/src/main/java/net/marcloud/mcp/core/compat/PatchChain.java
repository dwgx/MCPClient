package net.marcloud.mcp.core.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TUF L1 — chain structure ("承前 + 不可回退"). Turns the flat {@code supersedes} pointer
 * into a tamper-evident, monotonic version chain, giving the compat database blockchain-grade
 * rigor: each patch's {@code supersedes} names its predecessor by the predecessor's
 * <em>content-addressed</em> {@link PatchManifest#patchId() patchId} (a hash pointer — if the
 * predecessor's content changes, its patchId changes and the link breaks), and a superseding
 * patch must carry a strictly higher {@link PatchManifest#version() version} than the
 * predecessor it replaces.
 *
 * <p>Two guarantees this enforces, per target class:
 * <ul>
 *   <li><b>承前 (prev link):</b> {@code supersedes}, when non-blank, must be a well-formed
 *       patchId ({@code cp-…}). It points at the exact predecessor block; a mismatch (the
 *       predecessor was altered → its patchId changed) is a broken link.</li>
 *   <li><b>不可回退 (monotonicity / rollback protection):</b> if the predecessor named by
 *       {@code supersedes} is PRESENT in the database, the superseding patch's version MUST be
 *       strictly greater. A patch that supersedes a present patch with an equal-or-lower
 *       version is a rollback masquerading as an update — rejected.</li>
 * </ul>
 *
 * <p><b>Tip-only shipping is allowed.</b> A predecessor that is ABSENT (the old patch class
 * was dropped once superseded) does not break the chain — the tip is what arms. Only a PRESENT
 * predecessor triggers the version-order check. This keeps in-code shipping practical while
 * still blocking a rollback when both versions are on the classpath.
 *
 * <p><b>Authorized forward-rollback (the escape hatch).</b> "不可回退" blocks an ATTACKER from
 * downgrading you to a known-vulnerable old version — NOT the legitimate need to back out a bad
 * new version. If v2 ships broken, you do not "roll back" (which would be indistinguishable from
 * an attack and is refused): you ship a NEW, HIGHER-versioned v3 whose transform reverts to v1's
 * behavior. Because v3 advances the version and is signed by the trust root, it legitimately
 * supersedes v2 — you move FORWARD to the old behavior. The chain stays monotonic (attacks
 * blocked) while remaining operable (bad versions recoverable). This owner-approved model needs
 * no special "revoke" state: a fix is always just the next, higher, signed link.
 *
 * <p><b>Scope honesty:</b> this is the in-code chain — it enforces order among patches
 * registered in one database. It is NOT yet the signed, delivered chain-metadata of the
 * data-delivery endgame (L2 root/targets, L3 snapshot/timestamp); those sign over the chain
 * this layer defines. Never throws; a malformed manifest yields a rejection reason, not a crash.
 */
public final class PatchChain {

    private PatchChain() {
    }

    /**
     * Decide whether {@code patch} is a valid chain link given all {@code registered} patches.
     * Returns {@code null} if the link is valid (or the patch does not supersede anything), or a
     * human-readable rejection reason if the chain rule is violated. The engine skips a patch
     * with a non-null reason.
     */
    public static String rejectionReason(PatchManifest patch, List<CompatPatch> registered) {
        if (patch == null) {
            return "null manifest";
        }
        String prev = patch.supersedes();
        if (prev == null || prev.isBlank()) {
            return null; // genesis block for its target — nothing to chain to
        }
        // 承前: the prev pointer must be a well-formed content-addressed patchId.
        if (!prev.startsWith("cp-")) {
            return "supersedes '" + prev + "' is not a well-formed content-addressed patchId (cp-…)";
        }
        // Find the predecessor among registered patches (by patchId). Absent = tip-only ship, OK.
        PatchManifest predecessor = null;
        if (registered != null) {
            for (CompatPatch cp : registered) {
                if (cp == null || cp.manifest() == null) {
                    continue;
                }
                if (prev.equals(cp.manifest().patchId())) {
                    predecessor = cp.manifest();
                    break;
                }
            }
        }
        if (predecessor == null) {
            return null; // predecessor not present — tip arms, no rollback possible here
        }
        // 承前 sanity: a chain link should replace a patch for the SAME target class.
        if (!patch.targetClass().equals(predecessor.targetClass())) {
            return "supersedes a patch for a different target (" + predecessor.targetClass()
                    + " != " + patch.targetClass() + ") — not a valid chain link";
        }
        // 不可回退: the superseding version MUST be strictly greater than the predecessor's.
        int cmp = compareVersions(patch.version(), predecessor.version());
        if (cmp <= 0) {
            return "version " + patch.version() + " does not exceed superseded version "
                    + predecessor.version() + " — a rollback cannot masquerade as an update";
        }
        return null; // valid, monotonic chain link
    }

    /**
     * Compare dotted numeric versions ({@code "1.0.0.0"}). Missing trailing segments are 0
     * ({@code "1.2" == "1.2.0"}). Non-numeric segments compare as 0 (defensive; a malformed
     * version never throws here). Returns &lt;0, 0, &gt;0 like {@link Integer#compare}.
     */
    public static int compareVersions(String a, String b) {
        String[] pa = (a == null ? "" : a).split("\\.");
        String[] pb = (b == null ? "" : b).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = seg(pa, i);
            int vb = seg(pb, i);
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int seg(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Detect a cycle in the supersedes graph of {@code registered} (a patch chain that loops,
     * e.g. A supersedes B and B supersedes A). Returns a description of the first cycle found,
     * or {@code null} if the graph is acyclic. A cycle means no well-defined tip, so the whole
     * cyclic group must be rejected by the caller.
     */
    public static String findCycle(List<CompatPatch> registered) {
        if (registered == null) {
            return null;
        }
        Map<String, String> supersedesOf = new HashMap<>(); // patchId -> its supersedes
        for (CompatPatch cp : registered) {
            if (cp == null || cp.manifest() == null) {
                continue;
            }
            PatchManifest m = cp.manifest();
            if (m.patchId() != null && m.supersedes() != null && !m.supersedes().isBlank()) {
                supersedesOf.put(m.patchId(), m.supersedes());
            }
        }
        for (String start : supersedesOf.keySet()) {
            String slow = start;
            String fast = start;
            // Floyd cycle detection over the supersedes links.
            while (fast != null && supersedesOf.get(fast) != null) {
                slow = supersedesOf.get(slow);
                fast = supersedesOf.get(supersedesOf.get(fast));
                if (slow != null && slow.equals(fast)) {
                    return "supersedes cycle involving patchId " + start;
                }
            }
        }
        return null;
    }
}
