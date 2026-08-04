package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * The trust chain's checks, driven at the point where each one can actually fail.
 *
 * <p>Four mutations of this subsystem survived all 957 tests. Each breaks a rule the code states
 * plainly; none was covered where it could be false:
 *
 * <table>
 *   <tr><th>mutation</th><th>consequence</th></tr>
 *   <tr><td>{@code TufTrust}: a signing keyId absent from the baked keyring falls back to the
 *       document's own declared key</td>
 *       <td>a root document authorizes its own root; every patch under it arms</td></tr>
 *   <tr><td>{@code RootMetadata.signingBytes}: {@code rootThreshold} leaves the signed bytes</td>
 *       <td>M-of-N is unauthenticated; a 2-of-3 document is republished as 1-of-3 with one
 *       signature dropped, reusing the original signature</td></tr>
 *   <tr><td>{@code PatchCanonicalizer}: {@code version} leaves the signed bytes</td>
 *       <td>a signature minted at one version verifies at any other, handing an attacker the field
 *       the chain uses for rollback/supersedes</td></tr>
 *   <tr><td>{@code SnapshotVerifier} step 5: set equality becomes {@code armed.containsAll(snap)}
 *       </td><td>an armed patch the authority never blessed rides along and the verifier reports
 *       consistent</td></tr>
 * </table>
 *
 * <p><b>Why the existing suite could not see them.</b> Two distinct blind spots, both worth
 * recognising elsewhere:
 *
 * <ul>
 *   <li><b>A field pinned to a constant is invisible to sign-then-verify.</b> Every existing test
 *       signs an object and verifies THAT object, so a field dropped from the canonical bytes still
 *       round-trips. Only RE-PRESENTATION -- sign one value, verify a different one under the same
 *       signature -- can see it. All shipped patches declare version 1.0.0.0 and the shipped root
 *       document is threshold 1, so the shipped bytes are byte-identical either way and nothing
 *       visibly breaks.</li>
 *   <li><b>A branch is only reachable in a configuration no test builds.</b> The baked-root
 *       membership check needs a NON-EMPTY baked map that is MISSING the signing keyId; the existing
 *       tests either bake the key they sign with, or pass an empty map that short-circuits earlier.
 *       So the check was asserted only where it could not be false.</li>
 * </ul>
 */
public class PatchTrustIsPinnedAtItsBoundaryTest {

    private static final String ROOT_ID = "root-1";
    private static final String EVIL_ROOT_ID = "evil-root";
    private static final String TARGETS_ID = "targets-1";
    private static final String KEY_ID = "targets-1";

    /**
     * A root document must not be able to introduce a root key of its own.
     *
     * <p>The attack the fallback enables, end to end: append {@code evil-root} to the document's
     * declared root keys, self-sign the document with it, ship a signature map keyed to it. Threshold
     * 1 is met, the document's targets keys become trust anchors, and every patch signed by the
     * attacker's targets key arms -- while the engine logs an ordinary "N patch(es) armed". The
     * byte-equality defence downstream passes trivially, because under the fallback both references
     * ARE the same key.
     */
    @Test
    public void aRootDocumentCannotAuthorizeARootKeyTheClientWasNotShippedToTrust() {
        KeyPair genuineRoot = CompatCrypto.generateEd25519();
        KeyPair evilRoot = CompatCrypto.generateEd25519();
        KeyPair attackerTargets = CompatCrypto.generateEd25519();

        Map<String, PublicKey> declaredRoots = new LinkedHashMap<>();
        declaredRoots.put(ROOT_ID, genuineRoot.getPublic());
        declaredRoots.put(EVIL_ROOT_ID, evilRoot.getPublic()); // introduced out of thin air
        RootMetadata forged = new RootMetadata(1, 1, declaredRoots,
                Map.of(TARGETS_ID, attackerTargets.getPublic()));

        byte[] selfSig = CompatCrypto.ed25519Sign(evilRoot.getPrivate(), forged.signingBytes());

        // The baked keyring is NON-EMPTY and trusts the genuine root only. This is the configuration
        // the membership check exists for, and the one no existing test builds: the tests either bake
        // the key they sign with, or pass an empty map, which fails earlier for a different reason.
        Map<String, PublicKey> baked = Map.of(ROOT_ID, genuineRoot.getPublic());

        assertFalse("a signature by a root key the client was never shipped to trust must not "
                + "count, even though the document itself declares that key -- otherwise a root "
                + "document authorizes its own root and the whole L2 chain is decorative",
                TufTrust.isRootSignedToBakedTrust(forged, Map.of(EVIL_ROOT_ID, selfSig), baked));

        assertTrue("and no trust anchor may be derived from it",
                TufTrust.effectiveAnchors(forged, Map.of(EVIL_ROOT_ID, selfSig), baked).isEmpty());

        // The positive half, so the assertion above cannot be satisfied by a verifier that refuses
        // everything -- which would pass the deny case and break arming entirely.
        RootMetadata honest = new RootMetadata(1, 1, Map.of(ROOT_ID, genuineRoot.getPublic()),
                Map.of(TARGETS_ID, attackerTargets.getPublic()));
        byte[] realSig = CompatCrypto.ed25519Sign(genuineRoot.getPrivate(), honest.signingBytes());
        assertTrue("a genuinely baked root signature must still authorize",
                TufTrust.isRootSignedToBakedTrust(honest, Map.of(ROOT_ID, realSig), baked));
    }

    /**
     * The M-of-N threshold must be authenticated, not merely declared.
     *
     * <p>Re-presentation is the only test that can see this. Sign a 2-of-3 document, then republish
     * the SAME document with the threshold rewritten to 1 and one signature dropped: if the threshold
     * is outside the signed bytes, the surviving signature still verifies and one signature now
     * satisfies the rewritten threshold. That silently voids the property the class sells -- that
     * raising to 2-of-3 later is a data change rather than a schema change.
     */
    @Test
    public void theRootThresholdIsCoveredByTheRootSignature() {
        KeyPair rootA = CompatCrypto.generateEd25519();
        KeyPair rootB = CompatCrypto.generateEd25519();
        KeyPair targets = CompatCrypto.generateEd25519();
        Map<String, PublicKey> roots = new LinkedHashMap<>();
        roots.put(ROOT_ID, rootA.getPublic());
        roots.put("root-2", rootB.getPublic());
        Map<String, PublicKey> targetsMap = Map.of(TARGETS_ID, targets.getPublic());
        Map<String, PublicKey> baked = new LinkedHashMap<>(roots);

        RootMetadata strict = new RootMetadata(1, 2, roots, targetsMap);
        byte[] sigA = CompatCrypto.ed25519Sign(rootA.getPrivate(), strict.signingBytes());
        assertTrue("premise: one signature does not meet a threshold of two",
                !TufTrust.isRootSignedToBakedTrust(strict, Map.of(ROOT_ID, sigA), baked));

        // Same document, threshold rewritten down, ONE original signature reused.
        RootMetadata weakened = new RootMetadata(1, 1, roots, targetsMap);
        assertFalse("a signature minted over a 2-of-3 document must not verify over the same "
                + "document rewritten to 1-of-3: if rootThreshold is outside the signed bytes, an "
                + "attacker downgrades M-of-N while reusing the original signature",
                TufTrust.isRootSignedToBakedTrust(weakened, Map.of(ROOT_ID, sigA), baked));

        // And the canonical bytes themselves must differ, which is the underlying property.
        assertFalse("the threshold must change the signed bytes",
                java.util.Arrays.equals(strict.signingBytes(), weakened.signingBytes()));
    }

    /**
     * A patch manifest's version must be covered by its signature.
     *
     * <p>Every shipped patch declares 1.0.0.0, so with version outside the canonical bytes the shipped
     * signatures all still verify and nothing looks wrong. The exposure is re-presentation: take a real
     * signature and present the manifest at a different version. {@code patchId} does not include the
     * version, so the identity is unchanged -- which hands the attacker the field the chain uses for
     * rollback and supersedes comparison, letting a replayed manifest outrank a genuinely newer patch.
     */
    @Test
    public void aPatchVersionIsCoveredByItsSignature() {
        String keyId = "kernel-1";
        PatchManifest atOne = manifest("1.0.0.0");
        PatchManifest atNine = manifest("9.9.9.9");

        assertFalse("version must change the signing input, or a signature minted at one version "
                + "verifies at every other one while patchId stays identical (derivePatchId does "
                + "not include the version)",
                java.util.Arrays.equals(PatchCanonicalizer.signingInput(atOne, keyId),
                        PatchCanonicalizer.signingInput(atNine, keyId)));

        // Driven through real Ed25519 too, so the claim is about verification and not only about
        // two byte arrays being unequal.
        KeyPair signer = CompatCrypto.generateEd25519();
        byte[] sig = CompatCrypto.ed25519Sign(signer.getPrivate(),
                PatchCanonicalizer.signingInput(atOne, keyId));
        assertTrue("premise: the signature verifies over the version it was minted at",
                CompatCrypto.ed25519Verify(signer.getPublic(),
                        PatchCanonicalizer.signingInput(atOne, keyId), sig));
        assertFalse("and must NOT verify once the version is rewritten under the same signature",
                CompatCrypto.ed25519Verify(signer.getPublic(),
                        PatchCanonicalizer.signingInput(atNine, keyId), sig));
    }

    /**
     * An armed patch the snapshot never blessed must reject the collection.
     *
     * <p>The class names both directions of the inconsistency it guards -- an armed patch missing from
     * the snapshot, or a snapshot patch missing from the armed set -- but only the first was driven.
     * A subset check still rejects that one, so the surviving mutation left the "unexpected extra
     * armed patch" half uncovered: the half where a rogue patch rides along and the verifier answers
     * null, meaning consistent, fresh and root-authorized.
     */
    @Test
    public void anUnexpectedArmedPatchRejectsTheCollection() {
        KeyPair signer = CompatCrypto.generateEd25519();
        TrustAnchors anchors = TrustAnchors.of(Map.of(KEY_ID, signer.getPublic()));
        SnapshotMetadata snap = new SnapshotMetadata(1, Map.of("cp-a", "1.0.0.0"));
        long now = 1_000_000L;
        TimestampMetadata ts = new TimestampMetadata(snap.version(),
                SnapshotVerifier.snapshotHashHex(snap), now, now + 120_000L);
        byte[] snapSig = CompatCrypto.ed25519Sign(signer.getPrivate(), snap.signingBytes());
        byte[] tsSig = CompatCrypto.ed25519Sign(signer.getPrivate(), ts.signingBytes());

        String extra = SnapshotVerifier.rejectionReason(snap, KEY_ID, snapSig, ts, KEY_ID, tsSig,
                anchors, List.of("cp-a", "cp-rogue"), now + 1000);
        assertNotNull("an armed patch absent from the root-authorized snapshot must reject the "
                + "whole collection; a subset test passes it and reports the set consistent", extra);
        assertTrue("and the reason must name the unexpected patch, since that is the operator's "
                + "only pointer to what rode along. Got: " + extra, extra.contains("cp-rogue"));

        String exact = SnapshotVerifier.rejectionReason(snap, KEY_ID, snapSig, ts, KEY_ID, tsSig,
                anchors, List.of("cp-a"), now + 1000);
        assertNull("while the exactly-matching set still passes: a rejection test alone is met by "
                + "a verifier that rejects everything. " + exact, exact);
    }

    /**
     * A manifest differing ONLY in version, bound to a fixed transform hash.
     *
     * <p>The same transform hash for both, deliberately: {@code derivePatchId} is built from
     * targetClass + contentHash + kiRef + publisher and does NOT include the version, so both
     * manifests carry the identical {@code patchId}. That is the whole exposure -- the chain cannot
     * tell them apart by identity, so only the signature can, and only if the version is inside it.
     */
    private static PatchManifest manifest(String version) {
        return new PatchManifest.Builder()
                .code("MCP-KI0001").name("mipmap zero-fill").version(version).kiRef("KI-1")
                .targetClass("net.minecraft.client.renderer.texture.TextureUtil")
                .platformCondition("LWJGL3").publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z")
                .build()
                .withTransform(PatchManifest.sha256Hex("the-transform-bytes"), null);
    }
}
