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
 * {@code keyId}, {@code status}, {@code kiRef}, {@code publisher}, {@code version}
 * (see {@link #signingInput} for why the last four are bound — red-team F1). These
 * bind the signature to WHAT the patch rewrites ({@code targetClass}), the declared
 * {@code contentHash}, the signing key ({@code keyId}), the engine-enforced
 * {@code status}, and the identity/provenance inputs. Note this is deliberately NOT
 * the pipe-delimited {@code derivePatchId} string — that is a content-address for
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
     * <p>Covered fields, in fixed order: domain tag, then length-prefixed
     * {@code targetClass}, {@code contentHash}, {@code keyId}, {@code status},
     * {@code kiRef}, {@code publisher}, {@code version}. The last four were added to
     * close red-team finding F1 (canonical input under-binding): before, a valid
     * signature over the first three left {@code status} (an engine-enforced security
     * gate) and the {@code patchId}-derive inputs ({@code kiRef}, {@code publisher})
     * UNSIGNED — so a manifest signed while {@code DISABLED} could be re-presented as
     * {@code VERIFIED}, and the same integrity triple could mint a different
     * {@code patchId} for the online ticket channel. Binding them makes the signature
     * cover every field the engine treats as a decision or that feeds patch identity.
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
        ByteArrayOutputStream out = new ByteArrayOutputStream(160);
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.UTF_8));
        out.write(SEP);
        putLenPrefixed(out, m.targetClass());
        putLenPrefixed(out, m.contentHash());
        putLenPrefixed(out, keyId);
        // F1: bind the engine-enforced status + the patchId-derive inputs so none can
        // be mutated while keeping a valid signature. status enum name is stable.
        putLenPrefixed(out, m.status().name());
        putLenPrefixed(out, m.kiRef());
        putLenPrefixed(out, m.publisher());
        putLenPrefixed(out, m.version());
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
