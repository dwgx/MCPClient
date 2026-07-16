package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Teeth for {@link Ed25519PatchSigner} + {@link TrustAnchors} + {@link PatchCanonicalizer}
 * — the real Phase A (offline integrity) trust gate. Each test is non-vacuous: it fails
 * on the old fail-safe stub (which trusted nothing) OR on a naive signer that skips a
 * check. A real fixture Ed25519 keypair signs real manifests through the build-tool
 * {@code sign()} path, so "verify true" genuinely exercises Ed25519.
 *
 * <p>The load-bearing case is {@link #emptyAnchors_trustNothing}: it proves the shipped
 * default (empty keyring) still arms zero patches even for a validly-signed one — the
 * fail-safe posture {@link UnsignedPatchSigner} had, now preserved by the real signer.
 */
public final class Ed25519PatchSignerTest {

    private static KeyPair KP_A;
    private static KeyPair KP_B;
    private static final String KEY_A = "kernel-ed25519-A";
    private static final String KEY_B = "kernel-ed25519-B";

    @BeforeClass
    public static void keys() {
        KP_A = CompatCrypto.generateEd25519();
        KP_B = CompatCrypto.generateEd25519();
    }

    private static PatchManifest.Builder base() {
        return new PatchManifest.Builder()
                .code("MCP-KI0001").name("mipmap zero-fill").version("1.0.0.0").kiRef("KI-1")
                .targetClass("net.minecraft.client.renderer.texture.TextureUtil")
                .platformCondition("LWJGL3").publisher("kernel").builtAt("2026-07-13T00:00:00Z");
    }

    /** Sign a manifest through the build-tool path with keypair {@code kp} under {@code keyId}. */
    private static PatchManifest signWith(KeyPair kp, String keyId) {
        Ed25519PatchSigner tool = new Ed25519PatchSigner(TrustAnchors.empty(), kp.getPrivate(), keyId);
        return tool.sign(base().build(), PatchManifest.sha256Hex("the-transform-bytes"));
    }

    private static Ed25519PatchSigner verifierTrusting(String keyId, PublicKey pub) {
        return new Ed25519PatchSigner(TrustAnchors.of(Map.of(keyId, pub)));
    }

    // ---- the happy path really uses Ed25519 ----

    @Test
    public void sigOk_verifies() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        assertTrue("signature body attached", signed.signature().startsWith(Ed25519PatchSigner.SCHEME_PREFIX));
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertTrue("a validly-signed patch under a trusted key verifies", v.verify(signed));
    }

    // ---- the fail-safe default: empty anchors trust nothing ----

    @Test
    public void emptyAnchors_trustNothing() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        // The SHIPPED default: no pinned keys. Even a genuinely-signed patch must NOT
        // verify — this is the UnsignedPatchSigner posture, preserved.
        Ed25519PatchSigner shipped = new Ed25519PatchSigner(TrustAnchors.empty());
        assertFalse("empty keyring arms nothing (fail-safe default)", shipped.verify(signed));
    }

    // ---- tamper / wrong key / downgrade / unbound all deny ----

    @Test
    public void sigBad_deny() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        // Decode the base64url signature body, flip a byte, re-encode. Flipping a raw
        // signature byte reliably invalidates it (unlike flipping a trailing base64
        // char, whose unused low bits can decode identically).
        String sig = signed.signature();
        int colon = sig.indexOf(':', Ed25519PatchSigner.SCHEME_PREFIX.length());
        String head = sig.substring(0, colon + 1);
        String body = sig.substring(colon + 1);
        byte[] raw = java.util.Base64.getUrlDecoder().decode(body);
        raw[0] ^= 0x01; // flip one bit of the signature
        String tampered = head + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        PatchManifest bad = signed.withSignature(tampered);
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertFalse("a tampered signature must be rejected", v.verify(bad));
    }

    @Test
    public void wrongKeyId_deny() {
        // Signed under key A, but the verifier only trusts key B under a different id.
        PatchManifest signed = signWith(KP_A, KEY_A);
        Ed25519PatchSigner v = verifierTrusting(KEY_B, KP_B.getPublic());
        assertFalse("signature under an untrusted keyId must be rejected", v.verify(signed));
    }

    @Test
    public void keyIdPresentButWrongPublicKey_deny() {
        // Anchor holds KEY_A's id but bound to the WRONG public key (B's) -> verify fails.
        PatchManifest signed = signWith(KP_A, KEY_A);
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_B.getPublic());
        assertFalse("right keyId, wrong public key -> rejected", v.verify(signed));
    }

    @Test
    public void downgrade_deny() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        // Mangle the scheme/version prefix: ed25519:v1: -> ed25519:v2:
        String mangled = signed.signature().replaceFirst("ed25519:v1:", "ed25519:v2:");
        assertNotEquals(signed.signature(), mangled);
        PatchManifest down = signed.withSignature(mangled);
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertFalse("unknown scheme/version prefix must be rejected (downgrade block)", v.verify(down));
    }

    @Test
    public void unbound_deny() {
        PatchManifest unbound = base().build(); // never withTransform -> not bound, no sig
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertFalse("an unbound/unsigned manifest is never trusted", v.verify(unbound));
    }

    @Test
    public void neverThrows_onGarbage() {
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertFalse("null manifest -> false, no throw", v.verify(null));
        // Bound manifest but garbage signature strings -> false, no throw.
        PatchManifest bound = base().build().withTransform(PatchManifest.sha256Hex("x"), "not-a-signature");
        assertFalse(v.verify(bound));
        assertFalse(v.verify(bound.withSignature("ed25519:v1:")));            // truncated
        assertFalse(v.verify(bound.withSignature("ed25519:v1:onlykey")));     // no sig body
        assertFalse(v.verify(bound.withSignature("ed25519:v1:k:!!!notb64!"))); // bad base64
    }

    // ---- canonicalizer is injection-proof (length-prefixed, not delimiter-scanned) ----

    @Test
    public void canonicalizer_injectionSafe() {
        // A naive delimiter-join of (targetClass | contentHash) would collide for these
        // two: "Foo" + "hashvalue" vs "Foohash" + "value" both concatenate to
        // "Foohashvalue". Length-prefixing keeps the field boundaries explicit, so the
        // two signing inputs MUST differ (no cross-field injection).
        PatchManifest m1 = base().targetClass("net.minecraft.Foo").build()
                .withTransform("hashvalue", null);
        PatchManifest m2 = base().targetClass("net.minecraft.Foohash").build()
                .withTransform("value", null);
        byte[] a = PatchCanonicalizer.signingInput(m1, "kid");
        byte[] b = PatchCanonicalizer.signingInput(m2, "kid");
        assertFalse("length-prefixed inputs for different field splits must differ",
                java.util.Arrays.equals(a, b));
    }

    @Test
    public void canonicalizer_domainSeparated() {
        PatchManifest m = base().build().withTransform("h", null);
        byte[] input = PatchCanonicalizer.signingInput(m, "kid");
        byte[] domain = PatchCanonicalizer.DOMAIN.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] head = java.util.Arrays.copyOf(input, domain.length);
        assertArrayEquals("signing input must open with the domain tag", domain, head);
    }

    @Test
    public void clientSignerCannotSign() {
        Ed25519PatchSigner client = new Ed25519PatchSigner(TrustAnchors.empty());
        try {
            client.sign(base().build(), PatchManifest.sha256Hex("x"));
            fail("a verify-only client signer must not be able to sign");
        } catch (UnsupportedOperationException expected) {
            // correct: signing is a build-tool operation
        }
    }

    // ---- red-team F1: mutating a signed-but-formerly-unsigned field breaks verify ----

    @Test
    public void f1_statusFlipBreaksSignature() {
        // Sign a VERIFIED manifest, then re-present it as DISABLED (or vice versa).
        // Before F1 the signature covered only (target, contentHash, keyId), so a
        // status flip verified. Now status is signed -> mutation must deny.
        PatchManifest signed = signWith(KP_A, KEY_A); // status defaults VERIFIED
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertTrue(v.verify(signed));
        // Rebuild the same bound manifest but DISABLED, carrying the same signature.
        PatchManifest flipped = base().status(PatchManifest.Status.DISABLED).build()
                .withTransform(PatchManifest.sha256Hex("the-transform-bytes"), signed.signature());
        assertFalse("status flip must invalidate the signature (F1)", v.verify(flipped));
    }

    @Test
    public void f1_publisherRelabelBreaksSignature() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        PatchManifest relabeled = base().publisher("attacker").build()
                .withTransform(PatchManifest.sha256Hex("the-transform-bytes"), signed.signature());
        // publisher feeds derivePatchId AND is now signed -> mutation denies.
        assertFalse("publisher re-label must invalidate the signature (F1)", v.verify(relabeled));
    }

    @Test
    public void f1_kiRefRelabelBreaksSignature() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        PatchManifest relabeled = base().kiRef("KI-999").build()
                .withTransform(PatchManifest.sha256Hex("the-transform-bytes"), signed.signature());
        assertFalse("kiRef re-label must invalidate the signature (F1)", v.verify(relabeled));
    }

    // ---- red-team F1 FOLLOW-UP: supersedes + platformCondition now signed ----

    @Test
    public void f1followup_supersedesTamperBreaksSignature() {
        // Sign a manifest that supersedes patch-X, then re-present the SAME signature over
        // a manifest that supersedes patch-Y. supersedes drives the engine's supersededIds
        // (a superseded predecessor is not armed), so an MITM rewrite must not verify.
        // Before this fix supersedes was UNSIGNED -> verify returned true (bug); now it denies.
        Ed25519PatchSigner tool = new Ed25519PatchSigner(TrustAnchors.empty(), KP_A.getPrivate(), KEY_A);
        PatchManifest signed = tool.sign(base().supersedes("patch-X").build(),
                PatchManifest.sha256Hex("the-transform-bytes"));
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertTrue("the honestly-signed supersedes=patch-X manifest verifies", v.verify(signed));
        // Reuse the SAME signature string over a manifest that supersedes patch-Y.
        PatchManifest tampered = base().supersedes("patch-Y").build()
                .withTransform(PatchManifest.sha256Hex("the-transform-bytes"), signed.signature());
        assertFalse("supersedes rewrite must invalidate the signature (F1 follow-up)",
                v.verify(tampered));
    }

    @Test
    public void f1followup_platformConditionTamperBreaksSignature() {
        // base() sets platformCondition="LWJGL3". Sign it, then re-present the same
        // signature over a manifest whose platformCondition was flipped. platformCondition
        // feeds appliesToRuntime (whether the transform runs at all), so a rewrite that
        // makes a patch fire on a runtime it was never vetted for must not verify.
        PatchManifest signed = signWith(KP_A, KEY_A); // platformCondition="LWJGL3"
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        assertTrue(v.verify(signed));
        PatchManifest tampered = base().platformCondition("any").build()
                .withTransform(PatchManifest.sha256Hex("the-transform-bytes"), signed.signature());
        assertFalse("platformCondition rewrite must invalidate the signature (F1 follow-up)",
                v.verify(tampered));
    }

    @Test
    public void f1followup_supersedesChangesCanonicalBytes() {
        // Pure canonical form: two manifests differing ONLY in supersedes must produce
        // different signing inputs. Before this fix the field was not emitted -> bytes
        // were equal (bug); now they must differ.
        PatchManifest x = base().supersedes("patch-X").build().withTransform("h", null);
        PatchManifest y = base().supersedes("patch-Y").build().withTransform("h", null);
        byte[] a = PatchCanonicalizer.signingInput(x, "kid");
        byte[] b = PatchCanonicalizer.signingInput(y, "kid");
        assertFalse("supersedes must be part of the canonical signing input",
                java.util.Arrays.equals(a, b));
    }

    @Test
    public void f1followup_platformConditionChangesCanonicalBytes() {
        // Two manifests differing ONLY in platformCondition must produce different signing
        // inputs. Before this fix the field was not emitted -> bytes were equal (bug).
        PatchManifest x = base().platformCondition("LWJGL3").build().withTransform("h", null);
        PatchManifest y = base().platformCondition("LWJGL2").build().withTransform("h", null);
        byte[] a = PatchCanonicalizer.signingInput(x, "kid");
        byte[] b = PatchCanonicalizer.signingInput(y, "kid");
        assertFalse("platformCondition must be part of the canonical signing input",
                java.util.Arrays.equals(a, b));
    }

    // ---- red-team F7/F8: keyId charset + signature length ----

    @Test
    public void f7_colonInKeyIdRejectedAtSign() {
        try {
            new Ed25519PatchSigner(TrustAnchors.empty(), KP_A.getPrivate(), "kernel:primary")
                    .sign(base().build(), PatchManifest.sha256Hex("x"));
            fail("a keyId containing ':' must be rejected at sign time (F7)");
        } catch (IllegalArgumentException expected) {
            // correct: keyId must be a strict token
        }
    }

    @Test
    public void f8_wrongLengthSignatureRejected() {
        PatchManifest signed = signWith(KP_A, KEY_A);
        Ed25519PatchSigner v = verifierTrusting(KEY_A, KP_A.getPublic());
        // Replace the 64-byte sig body with a short one -> length check denies.
        String head = signed.signature().substring(0,
                signed.signature().indexOf(':', Ed25519PatchSigner.SCHEME_PREFIX.length()) + 1);
        String shortSig = head + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{1, 2, 3});
        assertFalse("a non-64-byte signature must be rejected (F8)",
                v.verify(signed.withSignature(shortSig)));
    }
}
