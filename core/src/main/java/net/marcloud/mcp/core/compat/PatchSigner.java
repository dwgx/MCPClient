package net.marcloud.mcp.core.compat;

/**
 * The trust boundary for compat patches: a patch is applied at startup, without a
 * ring gate, <b>only</b> if its {@link PatchManifest#signature()} is a valid
 * <b>Ed25519 signature</b> over its canonical signing input (target class + content
 * hash + keyId; see {@link PatchCanonicalizer}) under a key pinned in
 * {@link TrustAnchors}. The private signing key never enters the client, so you
 * cannot forge a patch the engine will load — that asymmetric wall is what makes
 * "auto-apply, un-gated" safe (see {@code 07-COMPAT-SHIM.md} §签名).
 *
 * <p>The shipped implementation is {@link Ed25519PatchSigner}. It <b>fails safe</b>:
 * constructed with {@link TrustAnchors#empty()} (no pinned keys, the default) it
 * trusts nothing, so no patch is auto-applied until a genuine kernel keyring is
 * injected. {@link UnsignedPatchSigner} remains only as an inert zero-trust
 * reference. This interface decides <b>integrity</b> only; online authorization
 * (short-TTL tickets / the live {@code PatchLease}) is an orthogonal gate.
 */
public interface PatchSigner {

    /**
     * Verify a manifest's Ed25519 integrity signature against its canonical signing
     * input under a pinned {@link TrustAnchors} key. Fail-safe: an unbound, unsigned,
     * unknown-scheme, or unknown-key manifest must return {@code false} (not trusted).
     * Never throws.
     */
    boolean verify(PatchManifest manifest);

    /**
     * Sign a manifest: bind {@code transformHash} (deriving contentHash + patchId),
     * Ed25519-sign the canonical input under the signer's key, and return the manifest
     * with its formatted signature attached. A build-tool operation — implementations
     * without a private key must throw {@link UnsupportedOperationException}.
     */
    PatchManifest sign(PatchManifest manifest, String transformHash);
}
