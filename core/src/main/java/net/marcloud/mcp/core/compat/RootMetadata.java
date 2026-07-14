package net.marcloud.mcp.core.compat;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TUF L2 — the ROOT role's signed authorization document. This is the "验证到根" layer: it
 * names which TARGETS keys are authorized to sign patches, and is itself signed by the ROOT
 * key(s). The client trusts exactly ONE thing at the bottom — the baked-in root public key
 * — and everything else (which targets key may sign, hence which patch may arm) is derived by
 * verifying this document up to that root. Break any link (bad root signature, unauthorized
 * targets key) and the whole chain is refused.
 *
 * <p><b>Roles (TUF-aligned, minimal).</b>
 * <ul>
 *   <li><b>root</b> — the ultimate trust anchor. Its public key(s) ship baked in
 *       ({@link RootTrust}). Its private key(s) live offline and sign THIS document. Rotating
 *       the targets key is done by re-issuing this document under the SAME root, so a
 *       compromised targets key does not require re-shipping the client.</li>
 *   <li><b>targets</b> — the keys authorized (by this document) to sign patch manifests. The
 *       existing {@code mcp-kernel-ed25519-v1} key is a targets key.</li>
 * </ul>
 *
 * <p><b>Threshold (M-of-N), pre-provisioned.</b> {@link #rootThreshold} is how many distinct
 * root-key signatures this document must carry to be valid. It ships {@code 1} (single root
 * key) but the field + the multi-signature list are already here, so raising to 2-of-3 later
 * is a data change, not a schema change — no code churn when the operational maturity to hold
 * multiple offline root keys arrives.
 *
 * <p><b>Canonical bytes.</b> {@link #signingBytes} is the deterministic, domain-separated,
 * length-prefixed serialization the root signatures are computed over (same discipline as
 * {@link PatchCanonicalizer}): a re-ordered or mutated field cannot keep a valid signature.
 * A monotonic {@link #version} lets a newer root document supersede an older one (root
 * rotation), mirroring the L1 patch chain's 不可回退 at the metadata layer.
 */
public final class RootMetadata {

    /** Domain tag separating a root-metadata signature from a patch or ticket signature. */
    static final String DOMAIN = "MCP-COMPAT-ROOT";
    private static final byte SEP = 0x1f;

    private final int version;
    private final int rootThreshold;
    /** keyId -> root PUBLIC key (the keys allowed to sign THIS document). */
    private final Map<String, PublicKey> rootKeys;
    /** keyId -> targets PUBLIC key (the keys this document authorizes to sign patches). */
    private final Map<String, PublicKey> targetsKeys;

    public RootMetadata(int version, int rootThreshold,
                        Map<String, PublicKey> rootKeys, Map<String, PublicKey> targetsKeys) {
        if (version < 1) {
            throw new IllegalArgumentException("root metadata version must be >= 1");
        }
        if (rootThreshold < 1) {
            throw new IllegalArgumentException("root threshold must be >= 1");
        }
        this.version = version;
        this.rootThreshold = rootThreshold;
        this.rootKeys = Collections.unmodifiableMap(new LinkedHashMap<>(
                rootKeys == null ? Map.of() : rootKeys));
        this.targetsKeys = Collections.unmodifiableMap(new LinkedHashMap<>(
                targetsKeys == null ? Map.of() : targetsKeys));
        if (this.rootThreshold > this.rootKeys.size()) {
            throw new IllegalArgumentException(
                    "threshold " + rootThreshold + " exceeds root key count " + this.rootKeys.size());
        }
    }

    public int version() {
        return version;
    }

    public int rootThreshold() {
        return rootThreshold;
    }

    /** keyIds of the root keys (the keys that may sign this document). */
    public List<String> rootKeyIds() {
        return new ArrayList<>(rootKeys.keySet());
    }

    /** The root public key for {@code keyId}, or null. */
    public PublicKey rootKey(String keyId) {
        return keyId == null ? null : rootKeys.get(keyId);
    }

    /** The targets public key this document authorizes for {@code keyId}, or null if not authorized. */
    public PublicKey authorizedTargetsKey(String keyId) {
        return keyId == null ? null : targetsKeys.get(keyId);
    }

    /** True if {@code keyId} is an authorized targets key in this document. */
    public boolean authorizesTargets(String keyId) {
        return keyId != null && targetsKeys.containsKey(keyId);
    }

    /** keyIds of the authorized targets keys. */
    public List<String> targetsKeyIds() {
        return new ArrayList<>(targetsKeys.keySet());
    }

    /**
     * The deterministic bytes the root signatures are computed over: domain tag, then
     * length-prefixed version, threshold, and the (sorted, keyId-tagged) root + targets key
     * SPKIs. Sorting the keyIds makes the encoding independent of map iteration order so the
     * same logical document always hashes identically.
     */
    public byte[] signingBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256);
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.UTF_8));
        out.write(SEP);
        putLenPrefixed(out, Integer.toString(version));
        putLenPrefixed(out, Integer.toString(rootThreshold));
        // Root keys, keyId-sorted for determinism.
        List<String> rids = new ArrayList<>(rootKeys.keySet());
        Collections.sort(rids);
        putLenPrefixed(out, Integer.toString(rids.size()));
        for (String id : rids) {
            putLenPrefixed(out, id);
            putLenPrefixedBytes(out, rootKeys.get(id).getEncoded());
        }
        // Targets keys, keyId-sorted for determinism.
        List<String> tids = new ArrayList<>(targetsKeys.keySet());
        Collections.sort(tids);
        putLenPrefixed(out, Integer.toString(tids.size()));
        for (String id : tids) {
            putLenPrefixed(out, id);
            putLenPrefixedBytes(out, targetsKeys.get(id).getEncoded());
        }
        return out.toByteArray();
    }

    private static void putLenPrefixed(java.io.ByteArrayOutputStream out, String s) {
        putLenPrefixedBytes(out, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void putLenPrefixedBytes(java.io.ByteArrayOutputStream out, byte[] b) {
        out.write((b.length >>> 24) & 0xFF);
        out.write((b.length >>> 16) & 0xFF);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.writeBytes(b);
    }
}
