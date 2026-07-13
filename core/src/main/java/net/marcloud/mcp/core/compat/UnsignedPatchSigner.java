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
 * <p><b>TODO (dwgx-owned, 07 §附录 加密核心):</b> replace with a real HMAC signer —
 * kernel key source, algorithm parameters, {@code patchId} derivation and signed
 * field coverage. Only then does {@link #verify} return true for genuinely signed
 * patches and the engine begins applying them.
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
