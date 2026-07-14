package net.marcloud.mcp.core.compat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TUF L3 — the SNAPSHOT role's signed document: the complete, consistent set of patches that
 * should be present right now, each pinned to its patchId + version. This is what stops a
 * <b>mix-and-match / freeze</b> attack: every patch is individually signed (L0-L2), but an
 * attacker delivering DATA could still serve a valid-but-old SUBSET — drop the security patch,
 * keep the rest — and each piece would verify. The snapshot signs the WHOLE collection, so the
 * client detects "the set I was given does not match the set the authority says is current".
 *
 * <p>Paired with {@link TimestampMetadata}: snapshot says WHAT the current consistent set is;
 * timestamp says THIS snapshot is the freshest (short expiry), blocking replay of an old but
 * internally-consistent snapshot to freeze you before a fix ships.
 *
 * <p><b>Honest scope.</b> With today's IN-CODE registration there is no data-delivery channel
 * and thus no mix-and-match attacker, so L3's enforcement value is LATENT — the logic is real
 * and tested, and it becomes load-bearing the instant patches ship as data. Building it now
 * completes the chain's structure so the data-delivery switch is a config change, not a
 * re-architecture. The document is signed by a root-authorized key (verified via {@link
 * TufTrust} against {@link RootMetadata}), reusing the L2 verify-to-root spine.
 *
 * <p><b>Canonical bytes.</b> {@link #signingBytes} is deterministic, domain-separated,
 * length-prefixed (same discipline as {@link PatchCanonicalizer} / {@link RootMetadata}), with
 * entries sorted by patchId so map order cannot change the hash. A monotonic {@link #version}
 * lets a newer snapshot supersede an older one (rollback protection at the collection level).
 */
public final class SnapshotMetadata {

    static final String DOMAIN = "MCP-COMPAT-SNAPSHOT";
    private static final byte SEP = 0x1f;

    private final int version;
    /** patchId -> pinned version string of every patch in the current consistent set. */
    private final Map<String, String> patchVersions;

    public SnapshotMetadata(int version, Map<String, String> patchVersions) {
        if (version < 1) {
            throw new IllegalArgumentException("snapshot version must be >= 1");
        }
        this.version = version;
        this.patchVersions = Collections.unmodifiableMap(new LinkedHashMap<>(
                patchVersions == null ? Map.of() : patchVersions));
    }

    public int version() {
        return version;
    }

    /** The pinned version for {@code patchId} in this snapshot, or null if not in the set. */
    public String versionOf(String patchId) {
        return patchId == null ? null : patchVersions.get(patchId);
    }

    /** True if {@code patchId} is part of this snapshot's consistent set. */
    public boolean contains(String patchId) {
        return patchId != null && patchVersions.containsKey(patchId);
    }

    /** All patchIds in this snapshot's set. */
    public List<String> patchIds() {
        return new ArrayList<>(patchVersions.keySet());
    }

    public int size() {
        return patchVersions.size();
    }

    /**
     * Deterministic bytes the snapshot signature covers: domain tag, version, then each
     * {@code (patchId, version)} pair in patchId-sorted order (length-prefixed). Sorting makes
     * the encoding independent of map iteration order.
     */
    public byte[] signingBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256);
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.UTF_8));
        out.write(SEP);
        putLen(out, Integer.toString(version));
        List<String> ids = new ArrayList<>(patchVersions.keySet());
        Collections.sort(ids);
        putLen(out, Integer.toString(ids.size()));
        for (String id : ids) {
            putLen(out, id);
            putLen(out, patchVersions.get(id));
        }
        return out.toByteArray();
    }

    private static void putLen(java.io.ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >>> 24) & 0xFF);
        out.write((b.length >>> 16) & 0xFF);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.writeBytes(b);
    }
}
