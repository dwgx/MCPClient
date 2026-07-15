package net.marcloud.mcp.core.compat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * TUF L0 — content binding. Computes a patch's {@code contentHash} from what its
 * {@link CompatPatch#transform} ACTUALLY DOES, not from an author-supplied label.
 *
 * <p>The hash is {@code sha256( DOMAIN | targetClass | transform(canary) )}, where
 * {@code canary} is the patch's deterministic {@link CompatPatch#canaryClassBytes()}
 * behavior anchor. Two builds of the same transform logic produce the same
 * transformed canary bytes, hence the same hash; a swapped or altered transform
 * produces different bytes, hence a different hash. The domain tag and targetClass
 * are folded in so a transform that happens to emit identical bytes for a different
 * target cannot collide.
 *
 * <p><b>Honest boundary (NOT a signature over behavior).</b> This L0 hash is an
 * UNSIGNED equality check: {@link #forPatch} recomputes it and {@code
 * matchesExpected} compares it to the patch's {@link CompatPatch#expectedCanaryHash()}
 * — a constant that lives in the SAME patch class as {@code transform()}. The Ed25519
 * signature covers a stable manifest LABEL (see {@link PatchCanonicalizer}), NOT this
 * behavior hash. So an attacker with classpath/code-exec can edit {@code transform()}
 * AND its sibling {@code expectedCanaryHash} constant together and defeat L0 — it is
 * drift-detection (catches accidental/version changes), not adversarial binding. It
 * does NOT by itself "close KI-10"; see known-issues KI-10 for the full boundary.
 *
 * <p><b>Fail-closed:</b> a patch with no canary (default {@link
 * CompatPatch#canaryClassBytes()} returns null), or whose transform returns null /
 * throws on its own canary, has NO computable behavior hash — {@link #forPatch}
 * returns null. The engine treats a null behavior hash as "unbound" and, under the
 * L0-strict posture, refuses to arm it: a patch that cannot prove what it does does
 * not run.
 *
 * <p><b>Honest boundary:</b> the canary fingerprints the transform's effect on ONE
 * known input, not its whole behavior. It detects a swapped/mutated transform, not
 * full functional equivalence. This is the in-code content anchor; the data-delivery
 * endgame (L2/L3) signs over the delivered payload bytes directly.
 */
public final class ContentHash {

    /** Domain tag separating an L0 behavior hash from any other sha256 use. */
    static final String DOMAIN = "MCP-COMPAT-L0";

    private ContentHash() {
    }

    /**
     * The behavior-bound content hash for {@code patch}, or {@code null} if it has no
     * canary / its transform does not produce deterministic changed bytes for that
     * canary. Never throws.
     *
     * @return lowercase-hex {@code sha256(DOMAIN | targetClass | transform(canary))},
     *         or null when the patch provides no computable behavior anchor
     */
    public static String forPatch(CompatPatch patch) {
        if (patch == null) {
            return null;
        }
        byte[] canary;
        byte[] transformed;
        try {
            canary = patch.canaryClassBytes();
            if (canary == null || canary.length == 0) {
                return null; // no behavior anchor
            }
            transformed = patch.transform(canary);
            // The transform MUST change its own canary — a null/identity result means
            // the canary does not exercise the rewrite, so it anchors nothing.
            if (transformed == null || java.util.Arrays.equals(transformed, canary)) {
                return null;
            }
        } catch (Throwable t) {
            // A transform that throws on its own canary cannot be content-bound.
            return null;
        }
        String target = patch.manifest() == null ? "" : safeTarget(patch);
        return sha256Hex(DOMAIN, target, transformed);
    }

    private static String safeTarget(CompatPatch patch) {
        try {
            String t = patch.manifest().targetClass();
            return t == null ? "" : t;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * True if the behavior hash recomputed from {@code patch}'s canary equals the
     * author-pinned {@link CompatPatch#expectedCanaryHash()}. This is the L0 gate: it
     * proves the transform still produces the fingerprint the author pinned — a
     * swapped/mutated transform yields a different hash and fails, even with a valid
     * signature over the (unchanged) manifest label.
     *
     * <p>Returns false if the patch pins no expected hash (null/blank) OR its canary
     * yields no computable behavior hash — under the L0 gate a patch that DECLARES a
     * canary but cannot match its pinned fingerprint does not arm. A patch with NO
     * canary at all is handled by the caller (exempt / signature-only).
     */
    public static boolean matchesExpected(CompatPatch patch) {
        if (patch == null) {
            return false;
        }
        String expected;
        try {
            expected = patch.expectedCanaryHash();
        } catch (Throwable t) {
            return false;
        }
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String recomputed = forPatch(patch);
        // Plain equals: both are our own hex strings, not secrets.
        return expected.equals(recomputed);
    }

    /** {@code sha256( domain 0x1f target 0x1f transformedBytes )}, lowercase hex. */
    private static String sha256Hex(String domain, String target, byte[] transformed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(domain.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1f);
            md.update(target.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1f);
            md.update(transformed);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
