package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.compat.patches.Ki11DwmHotkeyPatch;

import org.junit.Test;

/**
 * Proves the placeholder signature is the ONLY thing keeping KI-11 from arming.
 *
 * <p>KI-11 ships unsigned on purpose: the kernel private key is deliberately not on a development
 * machine, so the signature cannot be produced here. That leaves a claim worth testing in both
 * directions, because either half alone is misle:
 *
 * <ul>
 *   <li><b>It must not arm as shipped.</b> Otherwise the sign-only trust model has a hole.</li>
 *   <li><b>It must arm once validly signed.</b> Otherwise "does not arm" proves nothing — a patch
 *       that is broken for some unrelated reason (wrong status, a protected target, an L0 mismatch,
 *       a runtime condition that never matches) would pass the first test while being permanently
 *       dead. That is the vacuous-test trap, and it is the reason this file exists.</li>
 * </ul>
 *
 * <p>The second half is exercised by re-signing KI-11's own manifest with a TEST keypair and
 * trusting only that key, which walks the whole engine gauntlet — status, protected-class,
 * chain, L0 content binding, runtime applicability — with nothing stubbed but the key.
 */
public final class Ki11SigningContractTest {

    private static final String MINECRAFT_INTERNAL = "net/minecraft/client/Minecraft";
    private static final String TEST_KEY_ID = "test-ed25519-v1";

    /** The transform hash the shipped manifest is bound to. */
    private static final String TRANSFORM_HASH =
            PatchManifest.sha256Hex("ki11-dwm-hotkey-v1");

    /** KI-11 as shipped, signature placeholder included. */
    @Test
    public void shippedKi11DoesNotArmBecauseItsSignatureIsAPlaceholder() {
        CompatDatabase db = new CompatDatabase();
        db.register(new Ki11DwmHotkeyPatch());

        CompatEngine engine = CompatEngine.build(db,
            new Ed25519PatchSigner(Compat.defaultTrustAnchors()), null);

        assertTrue("the placeholder signature must not verify, so KI-11 must not arm",
            engine.armedPatchIds().isEmpty());
        assertNull("and its target must not dispatch",
            engine.apply(MINECRAFT_INTERNAL, new byte[] {1}));
    }

    /**
     * The same patch, validly signed, must arm — proving nothing ELSE is wrong with it.
     *
     * <p>This is the non-vacuous half. It signs the shipped manifest's own canonical input with a
     * test key and trusts only that key, so every other engine gate is evaluated for real.
     */
    @Test
    public void ki11ArmsOnceItCarriesAValidSignature() throws Exception {
        Ki11DwmHotkeyPatch shipped = new Ki11DwmHotkeyPatch();
        CompatPatch signed = reSigned(shipped);

        CompatDatabase db = new CompatDatabase();
        db.register(signed);
        CompatEngine engine = CompatEngine.build(db, testSigner(), null);

        assertTrue("with a trusted signature KI-11 must arm; if this fails the patch is broken "
                + "for a reason that has nothing to do with signing",
            engine.armedPatchIds().contains(signed.manifest().patchId()));
        assertTrue("and its target must be in the dispatch index",
            engine.armedInternalNames().contains(MINECRAFT_INTERNAL));
    }

    /** Armed, the engine must actually apply the transform to the target class. */
    @Test
    public void anArmedKi11ActuallyTransformsTheTargetClass() throws Exception {
        Ki11DwmHotkeyPatch shipped = new Ki11DwmHotkeyPatch();
        CompatPatch signed = reSigned(shipped);

        CompatDatabase db = new CompatDatabase();
        db.register(signed);
        CompatEngine engine = CompatEngine.build(db, testSigner(), null);

        byte[] out = engine.apply(MINECRAFT_INTERNAL, shipped.canaryClassBytes());
        assertNotNull("an armed patch must return changed bytes for its target", out);
        assertTrue("the hook must be present in the emitted bytes",
            new String(out, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("net/marcloud/mcp/core/compat/patches/DwmHotkey"));
    }

    /**
     * The L0 behaviour hash pinned in the source must match what the transform now produces.
     *
     * <p>Independent of signing: the engine refuses a patch whose recomputed canary hash differs
     * from the pinned constant, so a stale pin would block arming even with a perfect signature.
     * Asserting it here means a transform edit fails with this message rather than a confusing
     * "did not arm".
     */
    @Test
    public void theL0BehaviourHashIsPinnedCorrectly() {
        Ki11DwmHotkeyPatch patch = new Ki11DwmHotkeyPatch();
        String recomputed = ContentHash.forPatch(patch);
        assertNotNull("KI-11 provides a canary, so it must have a computable behaviour hash",
            recomputed);
        assertEquals("the pinned expectedCanaryHash is stale — regenerate it with "
                + "ContentHash.forPatch(new Ki11DwmHotkeyPatch())",
            recomputed, patch.expectedCanaryHash());
        assertTrue("and the engine's own comparison must agree",
            ContentHash.matchesExpected(patch));
    }

    /** A tampered transform must break the L0 gate even under a valid signature. */
    @Test
    public void aMutatedTransformFailsTheL0GateDespiteAValidSignature() throws Exception {
        Ki11DwmHotkeyPatch shipped = new Ki11DwmHotkeyPatch();
        PatchManifest manifest = signManifest(shipped.manifest());

        // Same manifest and a genuinely valid signature, but a transform that does something else.
        CompatPatch mutated = new CompatPatch() {
            @Override public PatchManifest manifest() {
                return manifest;
            }
            @Override public byte[] transform(byte[] original) {
                return new byte[] {9, 9, 9};
            }
            @Override public byte[] canaryClassBytes() {
                return shipped.canaryClassBytes();
            }
            @Override public String expectedCanaryHash() {
                return shipped.expectedCanaryHash();
            }
        };

        CompatDatabase db = new CompatDatabase();
        db.register(mutated);
        CompatEngine engine = CompatEngine.build(db, testSigner(), null);

        assertTrue("a swapped transform must fail L0 content binding even with a trusted "
                + "signature — the signature covers the label, not the behaviour",
            engine.armedPatchIds().isEmpty());
    }

    // ---- test signing ----------------------------------------------------------

    private static KeyPair sharedKeyPair;

    /** Generated in-process, so no key material is ever stored in the repo. */
    private static synchronized KeyPair keyPair() {
        if (sharedKeyPair == null) {
            sharedKeyPair = CompatCrypto.generateEd25519();
            assertNotNull("the JDK must provide Ed25519", sharedKeyPair);
        }
        return sharedKeyPair;
    }

    /** A signer that trusts ONLY the test key — the shipped kernel anchor is not in play. */
    private static Ed25519PatchSigner testSigner() {
        return new Ed25519PatchSigner(
            TrustAnchors.of(Map.of(TEST_KEY_ID, keyPair().getPublic())));
    }

    /** Re-sign a manifest's own canonical input with the test key. */
    private static PatchManifest signManifest(PatchManifest shipped) {
        Ed25519PatchSigner tool = new Ed25519PatchSigner(
            TrustAnchors.empty(), keyPair().getPrivate(), TEST_KEY_ID);
        // Rebuild unbound from the shipped manifest so every signed field matches it exactly;
        // the canonical input covers all of them, so a single divergence would fail to verify.
        PatchManifest unbound = new PatchManifest.Builder()
                .code(shipped.code())
                .name(shipped.name())
                .version(shipped.version())
                .kiRef(shipped.kiRef())
                .targetClass(shipped.targetClass())
                .platformCondition(shipped.platformCondition())
                .publisher(shipped.publisher())
                .builtAt(shipped.builtAt())
                .status(shipped.status())
                .supersedes(shipped.supersedes())
                .evidence(shipped.evidence())
                .build();
        return tool.sign(unbound, TRANSFORM_HASH);
    }

    /** KI-11's real transform and canary, under a manifest signed by the test key. */
    private static CompatPatch reSigned(Ki11DwmHotkeyPatch shipped) {
        PatchManifest manifest = signManifest(shipped.manifest());
        return new CompatPatch() {
            @Override public PatchManifest manifest() {
                return manifest;
            }
            @Override public byte[] transform(byte[] original) {
                return shipped.transform(original);
            }
            @Override public boolean appliesToRuntime() {
                return shipped.appliesToRuntime();
            }
            @Override public byte[] canaryClassBytes() {
                return shipped.canaryClassBytes();
            }
            @Override public String expectedCanaryHash() {
                return shipped.expectedCanaryHash();
            }
        };
    }
}
