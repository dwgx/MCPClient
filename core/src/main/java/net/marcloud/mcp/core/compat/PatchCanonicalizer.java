package net.marcloud.mcp.core.compat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Produces the canonical byte string an {@link Ed25519PatchSigner} signs and verifies
 * over — the Phase A (offline integrity) signing input. Deterministic and
 * cross-end-identical: the build tool signs exactly these bytes, the client
 * recomputes and verifies exactly these bytes.
 *
 * <p><b>Domain-separated + length-prefixed (injection-proof).</b> The input opens with
 * a fixed domain tag {@code "MCP-COMPAT-PATCH"} followed by a {@code 0x1f} unit
 * separator, then each covered field is emitted as a 4-byte big-endian length followed
 * by its UTF-8 bytes. Length-prefixing is what makes it injection-proof: a field whose
 * value itself contains {@code 0x1f} (or any delimiter) cannot be re-parsed into a
 * different field split, because the boundaries come from the lengths, not from
 * scanning for a delimiter. The domain tag prevents a signature made for some other
 * purpose (a ticket, a transcript) from ever verifying as a patch signature.
 *
 * <p>Covered fields, in fixed order: {@code targetClass}, {@code contentHash},
 * {@code keyId}. These bind the signature to WHAT the patch rewrites
 * ({@code targetClass}) and to the manifest's declared {@code contentHash}, under a
 * specific signing key ({@code keyId}). Note this is deliberately NOT the
 * pipe-delimited {@code derivePatchId} string — that is a content-address for
 * identity, not a signed, unambiguous byte layout.
 *
 * <p><b>HONEST BOUNDARY (do not oversell — red-team finding #1):</b> {@code
 * contentHash} is the value the manifest carries, supplied by the patch author via
 * {@link PatchManifest#withTransform}; nothing in the current code recomputes it from
 * the actual bytes {@link CompatPatch#transform} emits. So the signature authenticates
 * a <em>manifest label</em>, NOT the executable transform. This is safe TODAY only
 * because patches are registered <em>in-code</em> (classpath {@link CompatPatch}
 * objects): an attacker who can supply a malicious {@code transform()} body already has
 * core code-execution, so the signature adds no defense against them. It is NOT safe
 * once patches are delivered as DATA (the TUF endgame): then {@code verify()} would
 * pass on the triple while the transform payload is unbound. Before any data-channel
 * patch delivery ships, {@code contentHash} MUST be recomputed as {@code sha256(the
 * exact transform/payload bytes)} and re-derived + compared at load time (see
 * known-issues). Until then, integrity rests on in-code registration, not this
 * signature.
 */
public final class PatchCanonicalizer {

    /** Domain tag: separates patch signatures from any other Ed25519 use in the system. */
    static final String DOMAIN = "MCP-COMPAT-PATCH";
    /** Unit separator between the domain tag and the length-prefixed field block. */
    static final byte SEP = 0x1f;

    private PatchCanonicalizer() {
    }

    /**
     * The canonical signing input for {@code m}. Requires a bound manifest (contentHash
     * present); {@code keyId} is the identity of the signing key the signature will be
     * verified under.
     *
     * @param m     the bound manifest (targetClass + contentHash)
     * @param keyId the signing key identity (bound into the signature, so a signature
     *              cannot be replayed as if made under a different key)
     * @return the exact bytes to Ed25519-sign / verify
     * @throws IllegalArgumentException if the manifest is unbound or keyId is blank
     */
    public static byte[] signingInput(PatchManifest m, String keyId) {
        if (m == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        if (m.contentHash() == null || m.contentHash().isBlank()) {
            throw new IllegalArgumentException("cannot canonicalize an unbound manifest (no contentHash)");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.UTF_8));
        out.write(SEP);
        putLenPrefixed(out, m.targetClass());
        putLenPrefixed(out, m.contentHash());
        putLenPrefixed(out, keyId);
        return out.toByteArray();
    }

    /** Emit a 4-byte big-endian length followed by the UTF-8 bytes of {@code s}. */
    private static void putLenPrefixed(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >>> 24) & 0xFF);
        out.write((b.length >>> 16) & 0xFF);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.writeBytes(b);
    }
}
