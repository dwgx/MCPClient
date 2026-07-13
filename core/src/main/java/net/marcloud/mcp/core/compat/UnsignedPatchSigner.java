package net.marcloud.mcp.core.compat;

/**
 * The shipped {@link PatchSigner} until the crypto core is designed — a
 * <b>fail-safe stub</b>: it holds no kernel key, so it trusts nothing and cannot
 * sign anything.
 *
 * <p>Consequence, by design: with this signer the {@link CompatEngine} verifies
 * <b>zero</b> patches, so <b>no patch is auto-applied</b>. The default posture is
 * therefore inert — a patch cannot slip in un-gated before there is a real
 * cryptographic wall to gate it. This is the correct conservative default: an
 * un-authenticated patch that rewrites a vanilla class is exactly what the
 * signature exists to prevent, so "no signer key" must mean "apply nothing", never
 * "apply everything".
 *
 * <p><b>SUPERSEDED:</b> the real crypto core now ships as {@link Ed25519PatchSigner}
 * (Ed25519 integrity over the canonical signing input, verified against
 * {@link TrustAnchors}). Constructed with {@link TrustAnchors#empty()} that signer has
 * the SAME inert posture as this class — trusts nothing until a genuine keyring is
 * injected — so this type remains only as a zero-trust reference and the degenerate
 * baseline. New wiring should use {@code Ed25519PatchSigner(TrustAnchors.empty())}.
 */
public final class UnsignedPatchSigner implements PatchSigner {

    @Override
    public boolean verify(PatchManifest manifest) {
        // No kernel key: nothing is trusted. Fail closed.
        return false;
    }

    @Override
    public PatchManifest sign(PatchManifest manifest, String transformHash) {
        throw new UnsupportedOperationException(
                "PatchSigner crypto core is not yet designed (see 07-COMPAT-SHIM.md 附录). "
                + "UnsignedPatchSigner holds no kernel key and cannot sign patches.");
    }
}
