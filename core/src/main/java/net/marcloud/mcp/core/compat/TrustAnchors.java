package net.marcloud.mcp.core.compat;

import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pinned public keyring the {@link Ed25519PatchSigner} verifies patch integrity
 * signatures against — the client-side trust anchor for Phase A (offline integrity).
 * A patch's signature must be a valid Ed25519 signature under the key named by its
 * {@code keyId}; {@link #lookup} returns that key, or {@code null} when the keyId is
 * unknown/revoked (fail-closed: an unknown key means "not trusted", never "trust
 * anything").
 *
 * <p><b>Fail-safe default is an EMPTY anchor set.</b> With no keys, {@link #lookup}
 * always returns null, so {@link Ed25519PatchSigner} verifies ZERO patches — exactly
 * the inert posture {@link UnsignedPatchSigner} shipped. A real keyring is injected
 * only when genuine kernel keys exist; until then the engine arms nothing, and an
 * un-authenticated patch that rewrites a vanilla class cannot slip in. This is the
 * correct conservative default: "no signer key" must mean "apply nothing".
 *
 * <p>Only PUBLIC keys live here — embedding a public key grants no forging power, so
 * it does not violate the no-long-term-secret-in-client threat model. The private
 * signing key lives only in the build tool / authority, never in the game JVM.
 */
public final class TrustAnchors {

    private final Map<String, PublicKey> byKeyId;

    private TrustAnchors(Map<String, PublicKey> byKeyId) {
        this.byKeyId = byKeyId;
    }

    /** The fail-safe empty keyring: trusts nothing (default shipped posture). */
    public static TrustAnchors empty() {
        return new TrustAnchors(Collections.emptyMap());
    }

    /**
     * A keyring pinned to the given keyId-&gt;PublicKey map. A defensive copy is taken;
     * a null or empty map yields the {@link #empty()} posture. Keys with a blank keyId
     * or null value are rejected (a malformed anchor must not silently weaken trust).
     */
    public static TrustAnchors of(Map<String, PublicKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return empty();
        }
        Map<String, PublicKey> copy = new LinkedHashMap<>();
        for (Map.Entry<String, PublicKey> e : keys.entrySet()) {
            String id = e.getKey();
            PublicKey k = e.getValue();
            if (id == null || id.isBlank() || k == null) {
                throw new IllegalArgumentException("trust anchor entry must have a non-blank keyId and non-null key");
            }
            copy.put(id, k);
        }
        return new TrustAnchors(Collections.unmodifiableMap(copy));
    }

    /**
     * The public key registered under {@code keyId}, or {@code null} when unknown /
     * revoked. Null MUST be treated fail-closed by the caller (not trusted). Never
     * throws.
     */
    public PublicKey lookup(String keyId) {
        if (keyId == null) {
            return null;
        }
        return byKeyId.get(keyId);
    }

    /** True when no keys are pinned — the fail-safe "trust nothing" posture. */
    public boolean isEmpty() {
        return byKeyId.isEmpty();
    }

    /** Number of pinned keys (diagnostics/tests). */
    public int size() {
        return byKeyId.size();
    }
}
