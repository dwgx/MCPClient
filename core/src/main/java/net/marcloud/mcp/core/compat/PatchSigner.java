package net.marcloud.mcp.core.compat;

/**
 * The trust boundary for compat patches: a patch is applied at startup, without a
 * ring gate, <b>only</b> if its {@link PatchManifest#signature()} is a valid HMAC
 * over its {@link PatchManifest#contentHash()} under the kernel key. Without the
 * key you cannot forge a patch the engine will load — that cryptographic wall is
 * what makes "auto-apply, un-gated" safe (see {@code 07-COMPAT-SHIM.md} §签名).
 *
 * <p><b>PLACEHOLDER — crypto core is deferred.</b> The key source, HMAC algorithm
 * parameters, {@code patchId} derivation range, and signed-field coverage are a
 * separate security design owned by dwgx and not yet defined (07 §附录). Until it
 * lands, only the interface exists; the shipped implementation is
 * {@link UnsignedPatchSigner}, which <b>fails safe</b>: it trusts nothing, so no
 * patch is auto-applied. Do NOT invent a real HMAC here.
 */
public interface PatchSigner {

    /**
     * Verify a manifest's signature against its content hash under the kernel key.
     * Fail-safe: an unbound, unsigned, or unknown-key manifest must return
     * {@code false} (not trusted). Never throws.
     */
    boolean verify(PatchManifest manifest);

    /**
     * Sign a bound manifest: compute the HMAC over {@code transformHash} under the
     * kernel key and return the manifest with its signature attached. Implementations
     * without a key must throw {@link UnsupportedOperationException}.
     */
    PatchManifest sign(PatchManifest manifest, String transformHash);
}
