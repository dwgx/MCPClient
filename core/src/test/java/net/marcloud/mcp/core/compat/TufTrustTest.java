package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * TUF L2 — verify to root. A targets key is trusted for patch verification ONLY when a root
 * metadata document that authorizes it is validly signed (threshold met) by a root key the
 * client is baked to trust. These fail on any broken link (tampered document, wrong/absent
 * root signature, unmet threshold, unknown root key) — the teeth of "验证到根".
 */
public class TufTrustTest {

    private static final String ROOT_ID = "root-1";
    private static final String ROOT_ID_2 = "root-2";
    private static final String TARGETS_ID = "targets-1";

    private static RootMetadata meta(int threshold, Map<String, PublicKey> roots,
                                     Map<String, PublicKey> targets) {
        return new RootMetadata(1, threshold, roots, targets);
    }

    @Test
    public void validRootSignatureAuthorizesTargetsKey() {
        KeyPair root = CompatCrypto.generateEd25519();
        KeyPair targets = CompatCrypto.generateEd25519();
        RootMetadata m = meta(1, Map.of(ROOT_ID, root.getPublic()),
                Map.of(TARGETS_ID, targets.getPublic()));
        byte[] sig = CompatCrypto.ed25519Sign(root.getPrivate(), m.signingBytes());

        TrustAnchors anchors = TufTrust.effectiveAnchors(
                m, Map.of(ROOT_ID, sig), Map.of(ROOT_ID, root.getPublic()));

        assertFalse("root-verified document yields non-empty anchors", anchors.isEmpty());
        assertTrue("the authorized targets key is now a trust anchor",
                anchors.lookup(TARGETS_ID) != null);
    }

    @Test
    public void tamperedMetadataFailsRootSignature() {
        KeyPair root = CompatCrypto.generateEd25519();
        KeyPair targets = CompatCrypto.generateEd25519();
        RootMetadata original = meta(1, Map.of(ROOT_ID, root.getPublic()),
                Map.of(TARGETS_ID, targets.getPublic()));
        byte[] sig = CompatCrypto.ed25519Sign(root.getPrivate(), original.signingBytes());

        // Attacker swaps in a DIFFERENT targets key but reuses the old signature.
        KeyPair attacker = CompatCrypto.generateEd25519();
        RootMetadata tampered = meta(1, Map.of(ROOT_ID, root.getPublic()),
                Map.of(TARGETS_ID, attacker.getPublic()));

        TrustAnchors anchors = TufTrust.effectiveAnchors(
                tampered, Map.of(ROOT_ID, sig), Map.of(ROOT_ID, root.getPublic()));
        assertTrue("signature does not cover the tampered targets key -> empty anchors",
                anchors.isEmpty());
    }

    @Test
    public void wrongRootKeySignatureFails() {
        KeyPair root = CompatCrypto.generateEd25519();
        KeyPair wrong = CompatCrypto.generateEd25519();
        KeyPair targets = CompatCrypto.generateEd25519();
        RootMetadata m = meta(1, Map.of(ROOT_ID, root.getPublic()),
                Map.of(TARGETS_ID, targets.getPublic()));
        // Signed by the WRONG key (not the baked root).
        byte[] sig = CompatCrypto.ed25519Sign(wrong.getPrivate(), m.signingBytes());

        TrustAnchors anchors = TufTrust.effectiveAnchors(
                m, Map.of(ROOT_ID, sig), Map.of(ROOT_ID, root.getPublic()));
        assertTrue("a signature not from the baked root key -> empty anchors", anchors.isEmpty());
    }

    @Test
    public void thresholdTwoRequiresTwoDistinctValidSignatures() {
        KeyPair r1 = CompatCrypto.generateEd25519();
        KeyPair r2 = CompatCrypto.generateEd25519();
        KeyPair targets = CompatCrypto.generateEd25519();
        Map<String, PublicKey> roots = new LinkedHashMap<>();
        roots.put(ROOT_ID, r1.getPublic());
        roots.put(ROOT_ID_2, r2.getPublic());
        RootMetadata m = meta(2, roots, Map.of(TARGETS_ID, targets.getPublic()));

        byte[] s1 = CompatCrypto.ed25519Sign(r1.getPrivate(), m.signingBytes());
        byte[] s2 = CompatCrypto.ed25519Sign(r2.getPrivate(), m.signingBytes());

        Map<String, PublicKey> baked = new LinkedHashMap<>();
        baked.put(ROOT_ID, r1.getPublic());
        baked.put(ROOT_ID_2, r2.getPublic());

        // Only one signature -> threshold 2 unmet -> empty.
        assertTrue("1 of 2 -> unmet -> empty",
                TufTrust.effectiveAnchors(m, Map.of(ROOT_ID, s1), baked).isEmpty());
        // Both signatures -> threshold met -> authorized.
        assertFalse("2 of 2 -> met -> non-empty",
                TufTrust.effectiveAnchors(m, Map.of(ROOT_ID, s1, ROOT_ID_2, s2), baked).isEmpty());
    }

    @Test
    public void duplicateSignerCountsOnceAgainstThreshold() {
        KeyPair r1 = CompatCrypto.generateEd25519();
        KeyPair r2 = CompatCrypto.generateEd25519();
        KeyPair targets = CompatCrypto.generateEd25519();
        Map<String, PublicKey> roots = new LinkedHashMap<>();
        roots.put(ROOT_ID, r1.getPublic());
        roots.put(ROOT_ID_2, r2.getPublic());
        RootMetadata m = meta(2, roots, Map.of(TARGETS_ID, targets.getPublic()));
        byte[] s1 = CompatCrypto.ed25519Sign(r1.getPrivate(), m.signingBytes());
        Map<String, PublicKey> baked = new LinkedHashMap<>();
        baked.put(ROOT_ID, r1.getPublic());
        baked.put(ROOT_ID_2, r2.getPublic());
        // The signatures map is keyed by keyId, so a duplicate signer can't appear twice; but
        // supplying only r1 must not satisfy threshold 2 (proves no double-count).
        assertTrue("one distinct signer cannot satisfy threshold 2",
                TufTrust.effectiveAnchors(m, Map.of(ROOT_ID, s1), baked).isEmpty());
    }

    @Test
    public void emptyInputsFailClosed() {
        assertFalse(TufTrust.isRootSignedToBakedTrust(null, Map.of(), Map.of()));
        KeyPair root = CompatCrypto.generateEd25519();
        RootMetadata m = meta(1, Map.of(ROOT_ID, root.getPublic()), Map.of());
        assertFalse("no signatures -> not root-signed",
                TufTrust.isRootSignedToBakedTrust(m, Map.of(), Map.of(ROOT_ID, root.getPublic())));
        assertFalse("no baked root -> not root-signed",
                TufTrust.isRootSignedToBakedTrust(m,
                        Map.of(ROOT_ID, new byte[64]), Map.of()));
    }
}
