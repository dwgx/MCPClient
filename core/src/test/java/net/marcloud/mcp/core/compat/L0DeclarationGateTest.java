package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

import org.junit.Test;

/**
 * Audit H7 — the L0 content-binding gate must not fail OPEN on a patch that DECLARES a behavior
 * anchor it cannot prove.
 *
 * <p><b>The defect.</b> {@code CompatEngine} decided whether to run the L0 check from the CANARY:
 * {@code hasCanary = c != null && c.length > 0}, with a {@code catch (Throwable)} that also set it
 * false. So a patch that pins {@code expectedCanaryHash} but ships a null, empty or throwing canary
 * was classified as "anchor-less", exempted, and ARMED with zero behavior verification — the gate
 * failing open on exactly the patches it exists to stop. {@link ContentHash#matchesExpected} states
 * the opposite rule in its own javadoc: a patch that declares a canary but cannot match its pinned
 * fingerprint does not arm.
 *
 * <p><b>The rule now.</b> The exemption is decided from the DECLARATION
 * ({@link ContentHash#declaresExpectedHash}), never by probing the canary. A patch with no pinned
 * hash stays exempt — that is a real compatibility requirement (legacy patches and the harmless
 * {@code IdentityProbe} carry none), not an oversight — and the anchor-less case is asserted here
 * alongside the rejects so this file cannot be satisfied by "reject everything".
 *
 * <p><b>Non-vacuous:</b> every patch below is VERIFIED, signed by a key the test's verifier trusts
 * and applicable at runtime, so ONLY the L0 gate can stop it. On the pre-fix code the three
 * "does not arm" cases all ARMED (the canary was null/empty/throwing, hence "no canary", hence
 * exempt), so those assertions fail.
 */
public final class L0DeclarationGateTest {

    private static final KeyPair KP = CompatCrypto.generateEd25519();
    private static final String KEY_ID = "test-kernel-key";

    /** A well-formed sha256-hex pin (the value is irrelevant: nothing can reproduce it). */
    private static final String PINNED = "0000000000000000000000000000000000000000000000000000000000000000";

    private static final byte[] CANARY = {1, 2, 3, 4};
    private static final byte[] CHANGED = {9, 9, 9};

    private static Ed25519PatchSigner signingTool() {
        return new Ed25519PatchSigner(TrustAnchors.empty(), KP.getPrivate(), KEY_ID);
    }

    private static Ed25519PatchSigner verifier() {
        return new Ed25519PatchSigner(TrustAnchors.of(Map.of(KEY_ID, KP.getPublic())));
    }

    /**
     * A VERIFIED, signed, runtime-applicable patch whose canary/transform/pin are supplied.
     * The signature covers the manifest label only (that is the documented L0 boundary), so the
     * patch passes the signature gate and the L0 gate is the only thing left to decide it.
     */
    private static CompatPatch patch(String code, byte[] canary, String expected, byte[] transformOut) {
        PatchManifest signed = signingTool().sign(
                new PatchManifest.Builder().code(code).name(code).version("1.0.0.0").kiRef("KI-10")
                        .targetClass("net.minecraft.client.L0Probe" + code).platformCondition("")
                        .publisher("kernel").builtAt("2026-09-30T00:00:00Z")
                        .status(PatchManifest.Status.VERIFIED).build(),
                PatchManifest.sha256Hex(code));
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return signed; }
            @Override public byte[] canaryClassBytes() { return canary; }
            @Override public String expectedCanaryHash() { return expected; }
            @Override public byte[] transform(byte[] original) { return transformOut; }
        };
    }

    /** Build an engine around exactly this patch and report whether it armed. */
    private static boolean arms(CompatPatch p) {
        CompatDatabase db = new CompatDatabase();
        db.register(p);
        CompatEngine engine = CompatEngine.build(db, verifier(), null);
        return engine.armedPatchIds().contains(p.manifest().patchId());
    }

    @Test
    public void pinnedHashWithNoCanaryDoesNotArm() {
        CompatPatch p = patch("NOCANARY", null, PINNED, CHANGED);
        assertTrue("the patch must genuinely declare a fingerprint, or this measures the "
                + "anchor-less path instead of the declaration path",
                ContentHash.declaresExpectedHash(p));
        assertFalse("a patch that DECLARES a fingerprint it cannot produce must not arm — "
                + "pre-fix it was treated as anchor-less and armed unverified",
                arms(p));
    }

    @Test
    public void pinnedHashWithEmptyCanaryDoesNotArm() {
        CompatPatch p = patch("EMPTYCANARY", new byte[0], PINNED, CHANGED);
        assertTrue(ContentHash.declaresExpectedHash(p));
        assertFalse("an empty canary anchors nothing, but it also does not make the pinned hash "
                + "go away — pre-fix this armed unverified", arms(p));
    }

    @Test
    public void pinnedHashWithThrowingCanaryDoesNotArm() {
        PatchManifest signed = signingTool().sign(
                new PatchManifest.Builder().code("THROWCANARY").name("THROWCANARY")
                        .version("1.0.0.0").kiRef("KI-10")
                        .targetClass("net.minecraft.client.L0ProbeTHROWCANARY")
                        .platformCondition("").publisher("kernel").builtAt("2026-09-30T00:00:00Z")
                        .status(PatchManifest.Status.VERIFIED).build(),
                PatchManifest.sha256Hex("THROWCANARY"));
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return signed; }
            @Override public byte[] canaryClassBytes() {
                throw new IllegalStateException("canary unavailable");
            }
            @Override public String expectedCanaryHash() { return PINNED; }
            @Override public byte[] transform(byte[] original) { return CHANGED; }
        };
        assertTrue(ContentHash.declaresExpectedHash(p));
        assertFalse("a canary that throws is an unprovable binding, not an absent one — pre-fix "
                + "the catch turned it into \"no canary\" and the patch armed", arms(p));
    }

    @Test
    public void unreadablePinnedHashDoesNotArm() {
        PatchManifest signed = signingTool().sign(
                new PatchManifest.Builder().code("THROWPIN").name("THROWPIN")
                        .version("1.0.0.0").kiRef("KI-10")
                        .targetClass("net.minecraft.client.L0ProbeTHROWPIN")
                        .platformCondition("").publisher("kernel")
                        .builtAt("2026-09-30T00:00:00Z")
                        .status(PatchManifest.Status.VERIFIED).build(),
                PatchManifest.sha256Hex("THROWPIN"));
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return signed; }
            @Override public byte[] canaryClassBytes() { return null; }
            @Override public String expectedCanaryHash() {
                throw new IllegalStateException("declaration unreadable");
            }
            @Override public byte[] transform(byte[] original) { return CHANGED; }
        };
        assertTrue("an unreadable declaration counts as declared: we cannot tell what was "
                + "pinned, so its binding cannot be verified", ContentHash.declaresExpectedHash(p));
        assertFalse("the fail-closed answer to \"can I prove this?\" is no", arms(p));
    }

    @Test
    public void anchorlessPatchStillArms() {
        // The compatibility half: no canary AND no pinned hash is genuinely anchor-less, so the
        // patch stays signature-only, exactly as before. Without this, "reject everything" would
        // pass every assertion above.
        CompatPatch p = patch("ANCHORLESS", null, null, CHANGED);
        assertFalse(ContentHash.declaresExpectedHash(p));
        assertTrue("an anchor-less patch must keep arming (legacy patches, IdentityProbe)",
                arms(p));
    }

    @Test
    public void matchingPinStillArms() {
        // ...and a patch that pins the fingerprint of its OWN behavior still arms: compute the
        // hash of the (canary, transform) pair first, then pin it. Proves the declaration gate
        // is not a blanket refusal.
        CompatPatch probe = patch("MATCHING", CANARY, null, CHANGED);
        String pinned = ContentHash.forPatch(probe);
        assertNotNull("the probe's transform must change its canary, or there is no hash to pin",
                pinned);
        CompatPatch real = patch("MATCHING", CANARY, pinned, CHANGED);
        assertTrue("a patch whose recomputed hash matches its pinned fingerprint must arm",
                arms(real));
    }
}
