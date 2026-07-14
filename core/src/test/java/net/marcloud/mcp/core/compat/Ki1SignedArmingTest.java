package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.compat.patches.Ki1MipmapZeroFillPatch;

import org.junit.Test;

/**
 * Adversarial teeth for KI-1's arming under the SIGN-ONLY compat trust model. Like
 * {@code Ki4SignedArmingTest}, there is exactly ONE arming path: a valid Ed25519 signature
 * verified against {@link TrustAnchors}. Every test is non-vacuous:
 *
 * <ul>
 *   <li>empty anchors → KI-1 does NOT arm (fail-safe, even though it is in-code registered);</li>
 *   <li>the baked kernel anchor + KI-1's real shipped signature → KI-1 ARMS, target
 *       {@code net/minecraft/client/renderer/texture/TextureUtil} in the dispatch index;</li>
 *   <li>tampered signature on KI-1 → does NOT arm.</li>
 * </ul>
 *
 * <p>KI-1 arms because it carries a REAL kernel signature that verifies against the baked
 * public key, NOT because it is registered in-code. These fail on any code that arms in-code
 * patches without checking the signature, or on a KI-1 signature that does not genuinely
 * verify against the baked anchor.
 */
public final class Ki1SignedArmingTest {

    private static final String TEXTUREUTIL_INTERNAL =
            "net/minecraft/client/renderer/texture/TextureUtil";

    private static Ed25519PatchSigner shippedVerifier() {
        return new Ed25519PatchSigner(Compat.defaultTrustAnchors());
    }

    @Test
    public void emptyAnchorsKi1DoesNotArm() {
        CompatDatabase db = Compat.defaultDatabase();
        CompatEngine engine = CompatEngine.build(db, new Ed25519PatchSigner(TrustAnchors.empty()), null);
        String ki1Id = new Ki1MipmapZeroFillPatch().manifest().patchId();
        assertTrue("under empty anchors KI-1 must not arm (fail-safe)",
                !engine.armedPatchIds().contains(ki1Id));
        assertNull("and its target must not dispatch",
                engine.apply(TEXTUREUTIL_INTERNAL, new byte[]{1}));
    }

    @Test
    public void ki1SignatureVerifiesUnderBakedAnchorDirectly() {
        PatchManifest ki1 = new Ki1MipmapZeroFillPatch().manifest();
        assertNotNull("KI-1 must ship signed", ki1.signature());
        assertTrue("KI-1's shipped signature must verify under the baked kernel anchor",
                shippedVerifier().verify(ki1));
    }

    @Test
    public void bakedAnchorKi1ArmsViaRealSignature() {
        CompatDatabase db = Compat.defaultDatabase();
        CompatEngine engine = CompatEngine.build(db, shippedVerifier(), null);

        String ki1Id = new Ki1MipmapZeroFillPatch().manifest().patchId();
        assertTrue("KI-1 must arm via its real signature against the baked kernel anchor",
                engine.armedPatchIds().contains(ki1Id));
        assertTrue("KI-1 target must be in the armed dispatch index",
                engine.armedInternalNames().contains(TEXTUREUTIL_INTERNAL));
    }

    @Test
    public void ki1TamperedSignatureDoesNotArm() {
        // Flip one bit of the real signature body -> verify fails -> no arm.
        PatchManifest base = new Ki1MipmapZeroFillPatch().manifest();
        String real = base.signature();
        int colon = real.indexOf(':', "ed25519:v1:".length());
        String head = real.substring(0, colon + 1);
        byte[] raw = java.util.Base64.getUrlDecoder().decode(real.substring(colon + 1));
        raw[0] ^= 0x01;
        String tampered = head + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        PatchManifest tamperedManifest = base.withSignature(tampered);
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return tamperedManifest; }
            @Override public byte[] transform(byte[] original) { return new byte[]{7}; }
        };
        CompatDatabase db = new CompatDatabase();
        db.register(p);
        CompatEngine engine = CompatEngine.build(db, shippedVerifier(), null);
        assertTrue("a tampered KI-1 signature must not arm", engine.armedPatchIds().isEmpty());
    }
}
