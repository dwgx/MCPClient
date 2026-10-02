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
 * <p><b>Threshold (M-of-N) — and what the baked side can actually meet.</b>
 * {@link #rootThreshold} is how many distinct root-key signatures this document must carry to
 * be valid. The field, the key set and the multi-signature list are all here, so the <i>document
 * format</i> already supports 2-of-3. What it does not support is the <i>client</i> meeting it:
 * {@link RootTrust} bakes exactly ONE root key (a fixed keyId from one resource), and
 * {@link TufTrust} counts a signature only when its key is in BOTH the document and the baked
 * set — so the effective threshold is capped at the number of baked keys. Raising
 * {@code rootThreshold} above that cap is therefore a CLIENT change (ship the additional baked
 * public key(s), plus the loader that reads them), not a data-only change; {@link RootTrust}
 * refuses such a document loudly rather than letting every patch fall silently unarmed.
 *
 * <p><b>Version (TUF §5.3 rollback rule).</b> A root document that replaces a trusted one MUST
 * carry a version exactly one greater than it — not merely "newer" and not several ahead, so a
 * rollback and a skipped rotation are both detectable. {@link #isSuccessorOf(RootMetadata)}
 * states that rule; {@link RootTrust}'s update path enforces it against the baked document
 * before any signature is considered, mirroring the L1 patch chain's 不可回退 at the metadata
 * layer.
 *
 * <p><b>Expiry (TUF §5.3.10 freeze rule).</b> {@link #expiresEpochMillis} is an optional
 * epoch-millis expiry, {@code 0} meaning "not declared" (the shipped document declares none).
 * When declared it is part of {@link #signingBytes}, so a stripped or altered expiry breaks the
 * signature; {@link RootTrust} refuses a document that is already expired at verification time.
 * The field is optional rather than required because the shipped document predates it — an
 * absent expiry makes no freshness claim, while a declared one is enforced.
 *
 * <p><b>Canonical bytes.</b> {@link #signingBytes} is the deterministic, domain-separated,
 * length-prefixed serialization the root signatures are computed over (same discipline as
 * {@link PatchCanonicalizer}): a re-ordered or mutated field cannot keep a valid signature.
 * The expiry block is appended only when a document declares one, which is what keeps the
 * already-issued signature over the shipped (expiry-less) document valid; presence is itself
 * signed, so stripping a declared expiry or adding one to a document that had none changes the
 * signed input either way.
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
    /** Epoch-millis expiry, or 0 when the document declares none (see the class javadoc). */
    private final long expiresEpochMs;

    /** A document that declares no expiry (the shipped shape). */
    public RootMetadata(int version, int rootThreshold,
                        Map<String, PublicKey> rootKeys, Map<String, PublicKey> targetsKeys) {
        this(version, rootThreshold, rootKeys, targetsKeys, 0L);
    }

    /**
     * @param expiresEpochMs epoch-millis expiry, or 0 for "no expiry declared". A declared
     *                       expiry is covered by {@link #signingBytes}, so it cannot be
     *                       stripped or altered without breaking the root signature.
     */
    public RootMetadata(int version, int rootThreshold,
                        Map<String, PublicKey> rootKeys, Map<String, PublicKey> targetsKeys,
                        long expiresEpochMs) {
        if (version < 1) {
            throw new IllegalArgumentException("root metadata version must be >= 1");
        }
        if (rootThreshold < 1) {
            throw new IllegalArgumentException("root threshold must be >= 1");
        }
        if (expiresEpochMs < 0) {
            throw new IllegalArgumentException(
                    "root metadata expiry must be >= 0 (0 = no expiry declared)");
        }
        this.version = version;
        this.rootThreshold = rootThreshold;
        this.expiresEpochMs = expiresEpochMs;
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

    /** The declared expiry (epoch millis), or {@code 0} when the document declares none. */
    public long expiresEpochMillis() {
        return expiresEpochMs;
    }

    /**
     * True if this document DECLARES an expiry that has already passed at
     * {@code nowEpochMillis}. A document with no declared expiry (0) never reports
     * expired — it makes no freshness claim to violate; the caller decides whether
     * "no claim" is acceptable for the role it is playing (a baked ship-time
     * document tolerates it, a delivered one does not — see {@link RootTrust}).
     */
    public boolean expiredAt(long nowEpochMillis) {
        return expiresEpochMs > 0 && nowEpochMillis >= expiresEpochMs;
    }

    /**
     * True iff this document is the legal successor of {@code trusted}: its version is
     * EXACTLY one greater (TUF §5.3 — "the version number of the new root metadata MUST
     * be exactly the version in the trusted root metadata incremented by one"). Merely
     * being newer is not enough (that would let a rotation skip versions), and being
     * equal or older is a rollback.
     */
    public boolean isSuccessorOf(RootMetadata trusted) {
        return trusted != null && version == trusted.version() + 1;
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
        // Expiry, only when the document declares one. Unconditional encoding would change
        // the bytes the already-issued signature over the shipped (expiry-less) document
        // covers, which would disarm every patch; conditional encoding stays unambiguous
        // because presence is signed too (adding or removing the block changes the input).
        if (expiresEpochMs > 0) {
            putLenPrefixed(out, Long.toString(expiresEpochMs));
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
