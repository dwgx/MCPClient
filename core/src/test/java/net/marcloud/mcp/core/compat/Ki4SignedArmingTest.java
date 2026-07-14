package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.compat.patches.Ki4LocalServerChannelPatch;

import org.junit.Test;

/**
 * Adversarial teeth for the SIGN-ONLY compat trust model — the trust root of the compat
 * supply chain. There is exactly ONE arming path: a valid Ed25519 signature verified
 * against {@link TrustAnchors}. In-code registration confers NO trust; empty anchors arm
 * nothing. Every test is non-vacuous and fails on a bypass:
 *
 * <ul>
 *   <li>empty anchors → KI-4 does NOT arm (fail-safe intact, even though KI-4 is in-code
 *       registered);</li>
 *   <li>the baked kernel anchor + KI-4's real shipped signature → KI-4 ARMS, target
 *       {@code net/minecraft/network/NetworkSystem} in the dispatch index (headless);</li>
 *   <li>tampered signature / wrong keyId / truncated sig on KI-4 → does NOT arm;</li>
 *   <li>an UNSIGNED patch (no signature) → does NOT arm, regardless of its publisher /
 *       status fields — the only wall is the signature, and nothing in the manifest can
 *       substitute for it.</li>
 * </ul>
 *
 * <p>KI-4 arms here because it carries a REAL kernel signature that verifies against the
 * baked public key, NOT because it is registered in-code — the whole point of the sign-only
 * model. These fail on any code that arms in-code patches without checking the signature.
 */
public final class Ki4SignedArmingTest {

    private static final String NETWORKSYSTEM_INTERNAL = "net/minecraft/network/NetworkSystem";

    /** The shipped verifier: real Ed25519 signer keyed on the baked-in kernel public key. */
    private static Ed25519PatchSigner shippedVerifier() {
        return new Ed25519PatchSigner(Compat.defaultTrustAnchors());
    }

    // ---- 1. empty anchors -> KI-4 does NOT arm (fail-safe) ------------------

    @Test
    public void emptyAnchorsKi4DoesNotArm() {
        // KI-4 is in-code registered AND carries a real signature, but under EMPTY anchors
        // the signer trusts no key, so verify() fails and KI-4 must NOT arm. Proves in-code
        // registration grants no signature-free trust — the fail-safe posture.
        CompatDatabase db = Compat.defaultDatabase();
        CompatEngine engine = CompatEngine.build(db, new Ed25519PatchSigner(TrustAnchors.empty()), null);
        assertTrue("under empty anchors KI-4 must not arm (fail-safe)",
                engine.armedPatchIds().isEmpty());
        assertNull("and its target must not dispatch",
                engine.apply(NETWORKSYSTEM_INTERNAL, new byte[]{1}));
    }

    @Test
    public void unsignedSignerKi4DoesNotArm() {
        // Same fail-safe on the UnsignedPatchSigner (trusts nothing, cannot sign).
        CompatDatabase db = Compat.defaultDatabase();
        CompatEngine engine = CompatEngine.build(db, new UnsignedPatchSigner(), null);
        assertTrue("under a trust-nothing signer KI-4 must not arm",
                engine.armedPatchIds().isEmpty());
    }

    // ---- 2. baked anchor + real signature -> KI-4 ARMS (headless) -----------

    @Test
    public void bakedAnchorKi4ArmsViaRealSignature() {
        // The load-bearing effect: build the engine from the SHIPPED defaultDatabase() with
        // the SHIPPED baked-anchor signer. KI-4's real kernel signature verifies against the
        // baked public key, so it ARMS — through the normal verify path, no in-code bypass.
        // FAILS on any build where KI-4's signature does not actually verify against the
        // baked anchor (e.g. a placeholder/empty anchor, or a mismatched signature string).
        CompatDatabase db = Compat.defaultDatabase();
        CompatEngine engine = CompatEngine.build(db, shippedVerifier(), null);

        String ki4Id = new Ki4LocalServerChannelPatch().manifest().patchId();
        assertTrue("KI-4 must arm via its real signature against the baked kernel anchor",
                engine.armedPatchIds().contains(ki4Id));
        assertTrue("KI-4 target must be in the armed dispatch index",
                engine.armedInternalNames().contains(NETWORKSYSTEM_INTERNAL));
    }

    @Test
    public void ki4SignatureVerifiesUnderBakedAnchorDirectly() {
        // Directly assert the shipped signature verifies under the baked anchor (isolates
        // "KI-4 ships a genuinely valid kernel signature" from the engine gauntlet).
        PatchManifest ki4 = new Ki4LocalServerChannelPatch().manifest();
        assertNotNull("KI-4 must ship signed", ki4.signature());
        assertTrue("KI-4's shipped signature must verify under the baked kernel anchor",
                shippedVerifier().verify(ki4));
    }

    // ---- 3. tampered / wrong-key / truncated signature on KI-4 -> no arm ----

    /** A KI-4 patch whose manifest carries an arbitrary (bad) signature, in-code registered. */
    private static CompatPatch ki4WithSignature(String signature) {
        PatchManifest base = new Ki4LocalServerChannelPatch().manifest();
        PatchManifest tampered = base.withSignature(signature);
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return tampered; }
            @Override public byte[] transform(byte[] original) { return new byte[]{7}; }
        };
    }

    private static void assertDoesNotArm(CompatPatch p, String why) {
        CompatDatabase db = new CompatDatabase();
        db.register(p);
        CompatEngine engine = CompatEngine.build(db, shippedVerifier(), null);
        assertTrue(why, engine.armedPatchIds().isEmpty());
    }

    @Test
    public void ki4TamperedSignatureDoesNotArm() {
        // Flip one bit of the real signature body -> verify fails -> no arm.
        String real = new Ki4LocalServerChannelPatch().manifest().signature();
        int colon = real.indexOf(':', "ed25519:v1:".length());
        String head = real.substring(0, colon + 1);
        byte[] raw = java.util.Base64.getUrlDecoder().decode(real.substring(colon + 1));
        raw[0] ^= 0x01;
        String tampered = head + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertDoesNotArm(ki4WithSignature(tampered),
                "a tampered KI-4 signature must not arm");
    }

    @Test
    public void ki4WrongKeyIdDoesNotArm() {
        // Re-stamp the wire form with a keyId the baked anchor does not know -> lookup null
        // -> no arm. (The signature body is otherwise the real one.)
        String real = new Ki4LocalServerChannelPatch().manifest().signature();
        int colon = real.indexOf(':', "ed25519:v1:".length());
        String body = real.substring(colon + 1);
        String wrongKeyId = "ed25519:v1:unknown-attacker-key:" + body;
        assertDoesNotArm(ki4WithSignature(wrongKeyId),
                "a KI-4 signature under an unknown keyId must not arm");
    }

    @Test
    public void ki4TruncatedSignatureDoesNotArm() {
        // Replace the 64-byte sig body with a short one -> length check denies -> no arm.
        String real = new Ki4LocalServerChannelPatch().manifest().signature();
        int colon = real.indexOf(':', "ed25519:v1:".length());
        String head = real.substring(0, colon + 1);
        String truncated = head + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{1, 2, 3});
        assertDoesNotArm(ki4WithSignature(truncated),
                "a truncated KI-4 signature must not arm");
    }

    // ---- 4. an UNSIGNED patch never arms, whatever its manifest claims ------

    @Test
    public void unsignedPatchNeverArmsRegardlessOfManifestFields() {
        // There is no origin to forge anymore; the only wall is the signature. A patch with
        // NO signature must not arm even if it sets publisher="kernel", status VERIFIED, and
        // targets a normal vanilla class — nothing in the manifest substitutes for a real
        // kernel signature. FAILS on any code that arms in-code/unsigned patches.
        PatchManifest unsigned = new PatchManifest.Builder()
                .code("MCP-EVIL").name("attacker").version("1.0.0.0").kiRef("KI-evil")
                .targetClass("net.minecraft.client.Foo").platformCondition("")
                .publisher("kernel")                       // mimic the kernel publisher
                .builtAt("2026-07-14T00:00:00Z").status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex("attacker-transform"), null); // NO signature
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return unsigned; }
            @Override public byte[] transform(byte[] original) { return new byte[]{9}; }
        };
        CompatDatabase db = new CompatDatabase();
        db.register(p);
        CompatEngine engine = CompatEngine.build(db, shippedVerifier(), null);
        assertTrue("an unsigned patch must never arm, whatever its manifest claims",
                engine.armedPatchIds().isEmpty());
        assertNull("and it must never dispatch its transform",
                engine.apply("net/minecraft/client/Foo", new byte[]{1}));
    }

    @Test
    public void wrongKeySignedPatchDoesNotArmUnderBakedAnchor() {
        // A patch signed by a NON-kernel key must be rejected by the baked kernel anchor —
        // only the real kernel private key (outside the repo) can mint an arming signature.
        var wrongKp = net.marcloud.mcp.core.alpc.CompatCrypto.generateEd25519();
        Ed25519PatchSigner tool =
                new Ed25519PatchSigner(TrustAnchors.empty(), wrongKp.getPrivate(), KernelTrustAnchor.KEY_ID);
        PatchManifest signedByWrongKey = tool.sign(
                new PatchManifest.Builder()
                        .code("MCP-KI9998").name("t").version("1.0.0.0").kiRef("KI-x")
                        .targetClass("net.minecraft.client.Foo").platformCondition("")
                        .publisher("kernel").builtAt("2026-07-14T00:00:00Z")
                        .status(PatchManifest.Status.VERIFIED).build(),
                PatchManifest.sha256Hex("wrong-key-transform"));
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return signedByWrongKey; }
            @Override public byte[] transform(byte[] original) { return new byte[]{7}; }
        };
        CompatDatabase db = new CompatDatabase();
        db.register(p);
        CompatEngine engine = CompatEngine.build(db, shippedVerifier(), null);
        assertFalse("a wrong-key-signed patch must not arm under the baked anchor",
                engine.armedPatchIds().contains(signedByWrongKey.patchId()));
        assertTrue(engine.armedPatchIds().isEmpty());
    }

    // ---- a valid non-KI-4 signed patch DOES arm (proves the path is live) ---

    @Test
    public void validKernelSignedPatchArms() {
        // Sanity in the positive direction with a fresh keypair we fully control: a patch
        // signed by a key the anchor trusts ARMS. Guards against a signer that rejects
        // everything (which would make the fail-safe tests vacuously pass).
        var kp = net.marcloud.mcp.core.alpc.CompatCrypto.generateEd25519();
        String keyId = "test-kernel-key";
        Ed25519PatchSigner tool = new Ed25519PatchSigner(TrustAnchors.empty(), kp.getPrivate(), keyId);
        PatchManifest signed = tool.sign(
                new PatchManifest.Builder()
                        .code("MCP-KI9997").name("t").version("1.0.0.0").kiRef("KI-y")
                        .targetClass("net.minecraft.client.Bar").platformCondition("")
                        .publisher("kernel").builtAt("2026-07-14T00:00:00Z")
                        .status(PatchManifest.Status.VERIFIED).build(),
                PatchManifest.sha256Hex("good-transform"));
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return signed; }
            @Override public byte[] transform(byte[] original) { return new byte[]{5}; }
        };
        CompatDatabase db = new CompatDatabase();
        db.register(p);
        Ed25519PatchSigner verifier = new Ed25519PatchSigner(TrustAnchors.of(Map.of(keyId, kp.getPublic())));
        CompatEngine engine = CompatEngine.build(db, verifier, null);
        assertTrue("a validly kernel-signed patch must arm",
                engine.armedPatchIds().contains(signed.patchId()));
    }
}
