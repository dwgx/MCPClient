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
 * Pins the KNOWN GAP in {@link Compat#defaultTrustAnchors()}: the L2 root layer cannot deny what
 * the baked kernel key permits, so TUF revocation does not work.
 *
 * <p><b>These assertions describe a DEFECT, not a wanted property.</b> Nothing here argues the
 * fallback is correct. The file exists because the gap was found by measurement, recorded only in
 * a javadoc, and covered by no test at all — so a future refactor could have "cleaned up" the
 * fallback either way with no signal. What is pinned is the behaviour as it stands today, stated
 * plainly enough that the owner can read the consequence off the assertions.
 *
 * <p><b>The gap.</b> {@code defaultTrustAnchors()} derives anchors by verifying the shipped root
 * document up to the baked root key ({@link RootTrust} -&gt; {@link TufTrust}), then falls back to
 * {@link KernelTrustAnchor#anchors()} when that derivation yields nothing. Both paths pin the SAME
 * keyId ({@code mcp-kernel-ed25519-v1}) and, as {@link #bothPathsPinTheSameKernelKey()} proves, the
 * byte-identical key. So a root document that revokes the kernel key by dropping it from
 * {@code targetsKeys} — or one an attacker merely damages, which needs no forgery — falls through
 * to a fallback that restores exactly the key the revocation removed. Revocation is therefore
 * INEFFECTIVE: {@link RootTrust} is correctly fail-closed, and the composition above it discards
 * that verdict.
 *
 * <p><b>Why the composition is tested by hand.</b> The shipped resources currently derive
 * successfully, so no black-box call can drive the real {@code defaultTrustAnchors()} into its
 * empty-derivation branch. Shadowing {@code root-metadata.json} on the test classpath would reach
 * it, but that swaps the shipped trust material for a fixture and makes the test assert something
 * about the fixture instead of the product. Instead the revocation is built from a test-only root
 * keypair, the production composition rule is applied to it here, and
 * {@link #defaultTrustAnchorsStillCarriesTheFallbackBranch()} pins that this file's copy of the
 * rule is still the rule {@code Compat} runs — the same guard-the-model pattern
 * {@code DwmHotkeyEdgeTest.theProductionOrderMatchesWhatIsTestedHere} uses.
 *
 * <p><b>If the owner closes the gap</b> by deleting the two fallback lines so the method becomes
 * just {@code return RootTrust.effectiveAnchors();}, this file must be inverted rather than
 * deleted: {@link #defaultTrustAnchorsStillCarriesTheFallbackBranch()} must assert the body no
 * longer mentions {@link KernelTrustAnchor}, and
 * {@link #theProductionCompositionArmsEveryPatchDespiteRevocation()} must assert that a revoked
 * derivation arms ZERO patches (the verdict
 * {@link #underTheRevocationVerdictAloneNothingArms()} already measures). The cost of that change
 * is also pinned here: any damaged or missing root resource would then disarm every patch, which
 * is a posture decision, not a bug fix.
 */
public final class TrustAnchorFallbackGapTest {

    /** The keyId both trust paths pin — the reason the fallback can undo a revocation. */
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
     * Under the revocation's own verdict, no shipped patch arms — revocation would work.
     *
     * <p>This is the behaviour the fallback overrides, measured separately so the gap test below
     * cannot be read as "the patches were unarmable anyway".
     */
    @Test
    public void underTheRevocationVerdictAloneNothingArms() {
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
            new Ed25519PatchSigner(TrustAnchors.empty()), null);

        assertTrue("with the revoked derivation as the only anchor source every shipped patch "
                + "must stay disarmed", engine.armedPatchIds().isEmpty());
    }

    /**
     * THE GAP: the fallback re-pins the very keyId a revocation drops.
     *
     * <p>Not a claim that this is desirable. It is the mechanism — the fallback is not a narrower
     * emergency anchor set, it is the same key under the same id, so falling back is
     * indistinguishable from never having revoked.
     */
    @Test
    public void theFallbackRestoresTheKeyIdRevocationRemoves() {
        KeyPair root = CompatCrypto.generateEd25519();
        RootMetadata revocation = revocationDocument(root);
        assertFalse("the revocation must not authorize the kernel key",
            revocation.authorizesTargets(KERNEL_KEY_ID));

        TrustAnchors fallback = KernelTrustAnchor.anchors();
        assertFalse("KNOWN GAP: the fallback is non-empty even though the root document revoked "
                + "every targets key", fallback.isEmpty());
        assertNotNull("KNOWN GAP: the fallback pins the exact keyId the revocation dropped ("
                + KERNEL_KEY_ID + "), so revocation is ineffective",
            fallback.lookup(KERNEL_KEY_ID));
    }

    /**
     * THE GAP end to end: the production composition arms every shipped patch after a revocation.
     *
     * <p>Applies {@code Compat.defaultTrustAnchors()}'s rule — derived anchors when non-empty, else
     * {@link KernelTrustAnchor#anchors()} — to a revoked derivation, and measures what the engine
     * then does. All three shipped patches verify, which is the concrete cost of the gap: an
     * attacker who damages the root document (no forgery needed) leaves patch arming untouched.
     */
    @Test
    public void theProductionCompositionArmsEveryPatchDespiteRevocation() {
        KeyPair root = CompatCrypto.generateEd25519();
        RootMetadata revocation = revocationDocument(root);
        byte[] sig = CompatCrypto.ed25519Sign(root.getPrivate(), revocation.signingBytes());
        TrustAnchors derived = TufTrust.effectiveAnchors(revocation,
            Map.of(TEST_ROOT_KEY_ID, sig), Map.of(TEST_ROOT_KEY_ID, root.getPublic()));
        assertTrue("premise: the revoked derivation is empty", derived.isEmpty());

        // Compat.defaultTrustAnchors()'s rule, verbatim; the source pin below keeps this honest.
        TrustAnchors composed = derived.isEmpty() ? KernelTrustAnchor.anchors() : derived;

        assertFalse("KNOWN GAP: the composition returns anchors even though the root verdict was "
                + "'trust nothing'", composed.isEmpty());
        assertNotNull("KNOWN GAP: and those anchors contain the revoked kernel keyId",
            composed.lookup(KERNEL_KEY_ID));

        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
            new Ed25519PatchSigner(composed), null);
        assertEquals("KNOWN GAP: all three shipped patches still arm after revocation, so the L2 "
                + "layer cannot deny what the baked kernel key permits. If the owner removes the "
                + "fallback this becomes 0 and this assertion must be updated to expect that.",
            3, engine.armedPatchIds().size());
    }

    /**
     * Why the branch verdict cannot change the outcome: both paths pin the same key bytes.
     *
     * <p>Provable on the shipped resources with nothing stubbed. The derived anchor and the baked
     * fallback are not merely both "trusted" — they are the identical key under the identical id,
     * so {@code defaultTrustAnchors()} yields the kernel key whichever branch runs. That is the
     * root of the gap; a fallback to a genuinely narrower anchor set would not have it.
     */
    @Test
    public void bothPathsPinTheSameKernelKey() {
        PublicKey derived = RootTrust.effectiveAnchors().lookup(KERNEL_KEY_ID);
        PublicKey baked = KernelTrustAnchor.anchors().lookup(KERNEL_KEY_ID);
        assertNotNull("the shipped root chain must currently derive the kernel key; if this is "
                + "null the ceremony is broken and Ki11SigningContractTest explains why", derived);
        assertNotNull("and the baked fallback must load it", baked);
        assertTrue("KNOWN GAP: derivation and fallback are the byte-identical key under the same "
                + "keyId, so the root document's verdict cannot change what is trusted",
            Arrays.equals(derived.getEncoded(), baked.getEncoded()));

        TrustAnchors shipped = Compat.defaultTrustAnchors();
        assertNotNull("so defaultTrustAnchors() returns the kernel keyId regardless of which "
                + "branch it took", shipped.lookup(KERNEL_KEY_ID));
    }

    /**
     * Keeps the hand-applied composition above honest: the fallback must still be in the product.
     *
     * <p>This is the assertion that FAILS the moment the owner deletes the fallback, which is the
     * signal they need — without it the tests above would keep passing against their own copy of a
     * rule the product no longer follows. It pins shape, not behaviour, because the shipped
     * resources derive successfully and so cannot exercise the empty branch from outside.
     *
     * <p>When the fallback is removed, invert this: assert the body does NOT mention
     * {@link KernelTrustAnchor}.
     */
    @Test
    public void defaultTrustAnchorsStillCarriesTheFallbackBranch() throws Exception {
        String body = defaultTrustAnchorsBody();

        int derivation = body.indexOf("RootTrust.effectiveAnchors()");
        int fallback = body.indexOf("return KernelTrustAnchor.anchors();");
        assertTrue("defaultTrustAnchors must still derive anchors from the root chain",
            derivation >= 0);
        assertTrue("KNOWN GAP pinned: defaultTrustAnchors must still end in the "
                + "KernelTrustAnchor fallback. If this fails because the fallback was removed, the "
                + "gap is CLOSED — invert this test and update "
                + "theProductionCompositionArmsEveryPatchDespiteRevocation to expect 0 armed.",
            fallback >= 0);
        assertTrue("the derivation must be attempted BEFORE the fallback, or the root chain is "
                + "dead code rather than merely overridable", derivation < fallback);
        assertTrue("and the derived anchors must still be returned when non-empty",
            body.contains("return viaRoot;"));
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
     * describing this very gap, so scanning the whole file would match the documentation instead
     * of the code and pass even with the fallback deleted.
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
