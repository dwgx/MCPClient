package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

import org.junit.Test;

/**
 * TUF revocation must be effective: a root document that drops a targets key disarms every patch.
 *
 * <p>This file was written the other way round. It began as {@code TrustAnchorFallbackGapTest},
 * pinning a KNOWN GAP — {@link Compat#defaultTrustAnchors()} fell back to
 * {@link KernelTrustAnchor#anchors()} when the root derivation yielded nothing, and since both paths
 * pinned the same keyId and the byte-identical key, a revocation fell through to a fallback that put
 * the revoked key straight back. The gap is now closed by deleting the fallback, so the assertions
 * are inverted: what was documented as a defect is the property under test.
 *
 * <p>Inverting rather than replacing is deliberate. The measurements that justified the change live
 * here, and the shape of the old defect is why some assertions look as they do — particularly
 * {@link #bothPathsWouldHavePinnedTheSameKernelKey()}, which records why a fallback could not have
 * been a narrower emergency anchor set even in principle.
 *
 * <p><b>The cost this pins.</b> Revocation working means any missing, damaged or unverifiable root
 * resource now disarms EVERY patch. That is the fail-closed direction and the client still runs —
 * compat patches are enhancements, not the game — but it makes rebuilding after a key ceremony
 * mandatory rather than advisable. {@link #aDamagedRootChainDisarmsEverything()} states that cost as
 * a test, so nobody reintroduces a fallback believing it is free.
 */
public final class TrustAnchorRevocationTest {

    /** The keyId both trust paths used to pin — the reason a fallback could undo a revocation. */
    private static final String KERNEL_KEY_ID = KernelTrustAnchor.KEY_ID;

    private static final String COMPAT_SOURCE =
            "src/main/java/net/marcloud/mcp/core/compat/Compat.java";

    /**
     * L2 works in isolation: a root document that authorizes NO targets key derives no anchors.
     *
     * <p>The baseline the rest of the file rests on. The revocation is signed for real by a
     * test-only root key, so this is not "a broken document fails" — the chain VERIFIES and the
     * document's verdict is genuinely "no key may sign patches".
     */
    @Test
    public void aValidlySignedRevocationDerivesNoAnchors() {
        KeyPair root = CompatCrypto.generateEd25519();
        assertNotNull("the JDK must provide Ed25519", root);
        RootMetadata revocation = revocationDocument(root);
        byte[] sig = CompatCrypto.ed25519Sign(root.getPrivate(), revocation.signingBytes());
        Map<String, byte[]> sigs = Map.of(TEST_ROOT_KEY_ID, sig);
        Map<String, PublicKey> baked = Map.of(TEST_ROOT_KEY_ID, root.getPublic());

        assertTrue("the revocation must be validly root-signed, or this measures a broken "
                + "document rather than a revocation",
            TufTrust.isRootSignedToBakedTrust(revocation, sigs, baked));
        assertTrue("a document authorizing no targets key must derive no anchors",
            TufTrust.effectiveAnchors(revocation, sigs, baked).isEmpty());
    }

    /**
     * A revoked derivation arms nothing — the property the fallback used to override.
     *
     * <p>Measured separately from the composition test below so that one cannot be read as "the
     * patches were unarmable anyway".
     */
    @Test
    public void underTheRevocationVerdictNothingArms() {
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
            new Ed25519PatchSigner(TrustAnchors.empty()), null);

        assertTrue("with no derived anchor every shipped patch must stay disarmed",
            engine.armedPatchIds().isEmpty());
    }

    /**
     * THE CLOSED GAP: the production composition no longer restores a revoked key.
     *
     * <p>Applies {@code defaultTrustAnchors()}'s rule to a revoked derivation. Before the change
     * this yielded the kernel key and armed all three shipped patches; now the empty verdict stands.
     */
    @Test
    public void theProductionCompositionHonoursARevocation() {
        KeyPair root = CompatCrypto.generateEd25519();
        RootMetadata revocation = revocationDocument(root);
        byte[] sig = CompatCrypto.ed25519Sign(root.getPrivate(), revocation.signingBytes());
        TrustAnchors composed = TufTrust.effectiveAnchors(revocation,
            Map.of(TEST_ROOT_KEY_ID, sig), Map.of(TEST_ROOT_KEY_ID, root.getPublic()));

        assertTrue("a revoked derivation must stay empty through the composition -- restoring it "
                + "from a baked anchor is what made revocation unenforceable", composed.isEmpty());
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
            new Ed25519PatchSigner(composed), null);
        assertEquals("and no patch may arm under a revocation", 0, engine.armedPatchIds().size());
    }

    /**
     * The cost of an effective revocation, stated so it cannot be forgotten.
     *
     * <p>There is no degraded mode any more: an empty derivation disarms everything, whatever the
     * reason. Anyone tempted to reintroduce a fallback for build convenience should read this as the
     * thing they would be trading away.
     */
    @Test
    public void aDamagedRootChainDisarmsEverything() {
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
            new Ed25519PatchSigner(TrustAnchors.empty()), null);

        assertTrue("a damaged root chain must disarm every patch rather than degrade to a baked "
                + "anchor; the client still runs, it just loses the patches",
            engine.armedPatchIds().isEmpty());
        assertNotNull("and the engine must still build -- fail-closed is not fail-hard",
            engine.armedInternalNames());
    }

    /**
     * Why no fallback could have been safe: both paths pinned the same key bytes.
     *
     * <p>Kept from the gap version because it explains the mechanism rather than the symptom. The
     * derived anchor and the baked key are not merely both trusted — they are identical, so falling
     * back to the baked one was indistinguishable from ignoring the root document. A fallback to a
     * genuinely narrower anchor set would not have had that property, which is the only reason the
     * distinction is worth recording.
     */
    @Test
    public void bothPathsWouldHavePinnedTheSameKernelKey() {
        PublicKey derived = RootTrust.effectiveAnchors().lookup(KERNEL_KEY_ID);
        PublicKey baked = KernelTrustAnchor.anchors().lookup(KERNEL_KEY_ID);
        assertNotNull("the shipped root chain must currently derive the kernel key; if this is "
                + "null the ceremony is broken and Ki11SigningContractTest explains why", derived);
        assertNotNull("and the baked key must still load, since the ceremony tools read it", baked);
        assertTrue("the derived and baked keys are byte-identical, which is why falling back to the "
                + "baked one could never have honoured a revocation",
            Arrays.equals(derived.getEncoded(), baked.getEncoded()));
    }

    /**
     * The shipped chain still derives, so closing the gap did not disarm the real client.
     *
     * <p>The other half of the posture change: fail-closed is only acceptable if the shipped
     * resources actually verify. If this fails, the client has silently lost every patch.
     */
    @Test
    public void theShippedChainStillDerivesTheKernelKey() {
        TrustAnchors shipped = Compat.defaultTrustAnchors();

        assertFalse("the shipped root chain must derive anchors, or every patch is disarmed",
            shipped.isEmpty());
        assertNotNull("and they must contain the kernel keyId the patches are signed under",
            shipped.lookup(KERNEL_KEY_ID));
    }

    /**
     * Keeps the hand-applied composition above honest: the fallback must stay gone.
     *
     * <p>This is the assertion that fails if anyone reintroduces the fallback, which is the signal
     * that matters — without it the tests above would keep passing against their own copy of a rule
     * the product no longer follows. It pins shape rather than behaviour, because the shipped
     * resources derive successfully and so cannot exercise the empty branch from outside.
     */
    @Test
    public void defaultTrustAnchorsHasNoFallbackBranch() throws Exception {
        String body = defaultTrustAnchorsBody();

        assertTrue("defaultTrustAnchors must derive anchors from the root chain",
            body.contains("RootTrust.effectiveAnchors()"));
        assertFalse("the KernelTrustAnchor fallback must stay removed: it pinned the same keyId as "
                + "the derivation, so restoring it would make revocation unenforceable again. If "
                + "this fails because a fallback was reintroduced, invert this file back to the gap "
                + "version and expect 3 armed patches under a revocation.",
            body.contains("KernelTrustAnchor"));
    }

    // ---- helpers ---------------------------------------------------------------

    private static final String TEST_ROOT_KEY_ID = "test-root-ed25519-v1";

    /**
     * A root document that authorizes NO targets key — a TUF revocation of every signing key.
     * Signed by a test-only keypair generated in-process, so no key material enters the repo.
     */
    private static RootMetadata revocationDocument(KeyPair root) {
        return new RootMetadata(RootTrust.loadMetadata().version() + 1, 1,
            Map.of(TEST_ROOT_KEY_ID, root.getPublic()), Map.of());
    }

    /**
     * The method BODY only. The javadoc above it names {@link KernelTrustAnchor} in prose while
     * describing the removed fallback, so scanning the whole file would match the documentation
     * instead of the code and fail even though the fallback is gone.
     */
    private static String defaultTrustAnchorsBody() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(COMPAT_SOURCE)),
            StandardCharsets.UTF_8);
        int start = src.indexOf("public static TrustAnchors defaultTrustAnchors() {");
        assertTrue("defaultTrustAnchors must exist in " + COMPAT_SOURCE
                + " (absolute: " + Paths.get(COMPAT_SOURCE).toAbsolutePath() + ")", start >= 0);
        int end = src.indexOf("\n    }", start);
        assertTrue("the method body must be delimited", end > start);
        return src.substring(start, end);
    }
}
