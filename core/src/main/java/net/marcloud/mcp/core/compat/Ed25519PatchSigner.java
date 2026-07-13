package net.marcloud.mcp.core.compat;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * The real Phase A (offline integrity) {@link PatchSigner}: a patch is trusted only if
 * its {@link PatchManifest#signature()} is a valid Ed25519 signature over the
 * {@link PatchCanonicalizer canonical signing input} under a key pinned in
 * {@link TrustAnchors}. This replaces the fail-safe stub {@link UnsignedPatchSigner}
 * while PRESERVING its default posture: constructed with {@link TrustAnchors#empty()}
 * (no keys), {@link #verify} returns false for every patch, so the shipped engine
 * still arms nothing until a genuine keyring is injected.
 *
 * <p><b>Signature wire format:</b> {@code ed25519:v1:<keyId>:<base64url-no-pad sig>}.
 * The {@code ed25519:v1:} prefix is a scheme+version tag; an unknown scheme or version
 * is rejected (downgrade block). The {@code keyId} selects the verifying key from
 * {@link TrustAnchors}; it is ALSO bound into the signed bytes (via
 * {@link PatchCanonicalizer}) so a signature cannot be replayed under a different key.
 *
 * <p><b>Fail-safe contract:</b> {@link #verify} NEVER throws — any malformed input,
 * unknown key, or verification error returns false (not trusted). Only integrity is
 * decided here; online authorization (short-TTL tickets) is a separate, orthogonal
 * gate handled outside this class.
 *
 * <p><b>Signing is build-tool-only.</b> {@link #sign} requires a private key and is
 * used by the offline patch-signing tool, never on the client. A client-side signer
 * (constructed without a private key) throws {@link UnsupportedOperationException} from
 * {@link #sign}, mirroring {@link PatchSigner}'s contract.
 */
public final class Ed25519PatchSigner implements PatchSigner {

    /** Scheme+version prefix on {@link PatchManifest#signature()}. */
    static final String SCHEME_PREFIX = "ed25519:v1:";

    private final TrustAnchors anchors;
    /** Non-null only in the build tool; null on the client (verify-only). */
    private final PrivateKey signingKey;
    /** The keyId {@link #sign} stamps; null on the client. */
    private final String signingKeyId;

    /** Verify-only client signer with the given trust anchors (no signing capability). */
    public Ed25519PatchSigner(TrustAnchors anchors) {
        this(anchors, null, null);
    }

    /**
     * Full signer. On the client, pass a null {@code signingKey} (sign() then throws).
     * The build tool passes its private key + the matching keyId.
     */
    public Ed25519PatchSigner(TrustAnchors anchors, PrivateKey signingKey, String signingKeyId) {
        this.anchors = anchors == null ? TrustAnchors.empty() : anchors;
        this.signingKey = signingKey;
        this.signingKeyId = signingKeyId;
    }

    @Override
    public boolean verify(PatchManifest manifest) {
        try {
            if (manifest == null || !manifest.isBound()) {
                return false; // unbound/unsigned -> not trusted
            }
            String sig = manifest.signature();
            if (sig == null || !sig.startsWith(SCHEME_PREFIX)) {
                return false; // unsigned or unknown scheme/version -> downgrade block
            }
            // Parse ed25519:v1:<keyId>:<b64url sig>. keyId must be present and the
            // remainder is the signature; split on the FIRST ':' after the prefix so a
            // keyId cannot contain ':' ambiguously.
            String rest = sig.substring(SCHEME_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon <= 0 || colon == rest.length() - 1) {
                return false; // missing keyId or missing signature body
            }
            String keyId = rest.substring(0, colon);
            String sigB64 = rest.substring(colon + 1);

            PublicKey pub = anchors.lookup(keyId);
            if (pub == null) {
                return false; // unknown/revoked keyId, or empty anchors -> fail-closed
            }
            byte[] sigBytes = Base64.getUrlDecoder().decode(sigB64);
            byte[] input = PatchCanonicalizer.signingInput(manifest, keyId);
            return CompatCrypto.ed25519Verify(pub, input, sigBytes);
        } catch (Throwable t) {
            // Fail-safe: any parse/crypto error means "not trusted", never a throw.
            return false;
        }
    }

    @Override
    public PatchManifest sign(PatchManifest manifest, String transformHash) {
        if (signingKey == null || signingKeyId == null) {
            throw new UnsupportedOperationException(
                    "this Ed25519PatchSigner is verify-only (no private key); "
                    + "signing is a build-tool operation");
        }
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        // Bind the transform first (derives contentHash + patchId), then sign the
        // canonical input under our keyId and attach the formatted signature.
        PatchManifest bound = manifest.withTransform(transformHash, null);
        byte[] input = PatchCanonicalizer.signingInput(bound, signingKeyId);
        byte[] sig = CompatCrypto.ed25519Sign(signingKey, input);
        String formatted = SCHEME_PREFIX + signingKeyId + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        return bound.withSignature(formatted);
    }
}
