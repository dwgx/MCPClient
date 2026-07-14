package net.marcloud.mcp.core.compat;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * TUF L2 — the "验证到根" resolver. Given a {@link RootMetadata} document, the root signatures
 * over it, and the baked-in {@link RootTrust root public keys}, this verifies the FULL chain
 * to the root and derives the effective patch-verification {@link TrustAnchors} — the set of
 * targets keys the root has authorized to sign patches. Any broken link (root signature
 * invalid, threshold unmet, unknown root key) collapses the whole document to the fail-safe
 * empty anchor set: trust nothing.
 *
 * <p>The verification order (each step gates the next):
 * <ol>
 *   <li>The root document's {@link RootMetadata#signingBytes} must carry at least
 *       {@code rootThreshold} valid signatures, each from a DISTINCT root key that is BOTH
 *       named in the document's own root-key set AND present in the baked-in {@link RootTrust}
 *       (a document cannot bless a root key the client was not shipped to trust — that would
 *       let a document authorize its own root).</li>
 *   <li>Only then are the document's targets keys treated as authorized; {@link #effectiveAnchors}
 *       returns a {@link TrustAnchors} of exactly those, which {@link Ed25519PatchSigner} uses
 *       to verify patch signatures. So a patch arms only if its signing key was authorized by a
 *       properly root-signed document — verified all the way to the baked root.</li>
 * </ol>
 *
 * <p><b>Fail-closed everywhere.</b> Null/short signature lists, duplicate signers counted once,
 * a signer not in the baked root trust, or any crypto error → the threshold is not met → empty
 * anchors. Never throws.
 */
public final class TufTrust {

    private TufTrust() {
    }

    /**
     * Verify {@code meta} is signed to the baked root and return the authorized targets keys as
     * a {@link TrustAnchors}. Returns {@link TrustAnchors#empty()} (trust nothing) if the root
     * signature threshold is not met against {@code bakedRootKeys}.
     *
     * @param meta          the root authorization document (may be null → empty)
     * @param rootSignatures keyId → detached Ed25519 signature over {@code meta.signingBytes()}
     * @param bakedRootKeys  the ultimate trust: root keyId → public key shipped in the client
     */
    public static TrustAnchors effectiveAnchors(
            RootMetadata meta,
            Map<String, byte[]> rootSignatures,
            Map<String, PublicKey> bakedRootKeys) {
        if (!isRootSignedToBakedTrust(meta, rootSignatures, bakedRootKeys)) {
            return TrustAnchors.empty();
        }
        // Root-verified: expose the document's targets keys as the patch-verification anchors.
        Map<String, PublicKey> anchors = new LinkedHashMap<>();
        for (String keyId : meta.targetsKeyIds()) {
            PublicKey k = meta.authorizedTargetsKey(keyId);
            if (k != null) {
                anchors.put(keyId, k);
            }
        }
        return TrustAnchors.of(anchors);
    }

    /**
     * True iff {@code meta} carries at least {@code rootThreshold} valid signatures, each from a
     * DISTINCT key that is both declared in the document's root-key set and pinned in
     * {@code bakedRootKeys}. This is the single gate that anchors the whole chain to the
     * baked-in root; everything downstream (targets authorization, patch arming) rests on it.
     */
    public static boolean isRootSignedToBakedTrust(
            RootMetadata meta,
            Map<String, byte[]> rootSignatures,
            Map<String, PublicKey> bakedRootKeys) {
        try {
            if (meta == null || rootSignatures == null || bakedRootKeys == null
                    || bakedRootKeys.isEmpty()) {
                return false;
            }
            byte[] signed = meta.signingBytes();
            int valid = 0;
            java.util.Set<String> counted = new java.util.HashSet<>();
            for (Map.Entry<String, byte[]> e : rootSignatures.entrySet()) {
                String keyId = e.getKey();
                byte[] sig = e.getValue();
                if (keyId == null || sig == null || counted.contains(keyId)) {
                    continue; // a duplicate signer is counted once
                }
                // The signer must be a root key BOTH the document declares AND the client
                // was shipped to trust — a document cannot introduce a root key out of thin air.
                PublicKey docRoot = meta.rootKey(keyId);
                PublicKey bakedRoot = bakedRootKeys.get(keyId);
                if (docRoot == null || bakedRoot == null) {
                    continue;
                }
                // Defense in depth: the document's declared root key must equal the baked one.
                if (!java.util.Arrays.equals(docRoot.getEncoded(), bakedRoot.getEncoded())) {
                    continue;
                }
                if (CompatCrypto.ed25519Verify(bakedRoot, signed, sig)) {
                    counted.add(keyId);
                    valid++;
                }
            }
            return valid >= meta.rootThreshold();
        } catch (Throwable t) {
            return false; // any error -> not root-signed -> fail closed
        }
    }
}
