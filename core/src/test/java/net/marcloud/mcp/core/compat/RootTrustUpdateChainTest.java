package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

import org.junit.Test;

/**
 * Audit TUF gaps — the L2 root layer's REPLACEMENT rules (TUF §5.3 rollback, §5.3.10 freeze) and
 * the threshold the baked side can actually meet.
 *
 * <p><b>What was missing.</b> {@code RootMetadata}'s javadoc claimed a version-monotonicity rule
 * and nothing enforced it; a delivered document that rolled the root back (or skipped a rotation)
 * was indistinguishable from a fresh one. There was no expiry check at all. And the documented
 * "raise the threshold to 2-of-3 later" path could not work: {@link RootTrust} bakes exactly ONE
 * root key, so a document declaring a threshold of 2 was unsatisfiable by construction — every
 * patch would fall silently unarmed with the reason buried in a generic "threshold not met".
 *
 * <p><b>How the rules are pinned.</b> The rollback/freeze decisions are driven through
 * {@link RootTrust#updateRejectionReason} against the document this client ACTUALLY ships
 * ({@code RootTrust.loadMetadata()}), so "exactly one greater than the baked one" is measured
 * against the real baked version rather than a number this test made up. The positive halves
 * matter as much as the refusals: a valid successor is accepted, a validly signed successor
 * derives anchors, and the shipped chain still derives the kernel key — otherwise "refuse
 * everything" would satisfy every negative assertion in this file.
 */
public final class RootTrustUpdateChainTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000L;
    private static final long NOW = System.currentTimeMillis();
    private static final long FUTURE = NOW + 365L * DAY_MS;
    private static final long PAST = NOW - DAY_MS;

    /** The document this client ships and trusts — the "baked one" every rule is measured against. */
    private static RootMetadata baked() {
        RootMetadata baked = RootTrust.loadMetadata();
        assertNotNull("the shipped root document must parse, or every assertion below is "
                + "measuring a missing resource instead of a rule", baked);
        return baked;
    }

    /** A candidate document at {@code version} around a throwaway key, expiring at {@code expires}. */
    private static RootMetadata candidate(int version, long expires) {
        KeyPair kp = CompatCrypto.generateEd25519();
        return new RootMetadata(version, 1, Map.of(RootTrust.ROOT_KEY_ID, kp.getPublic()),
                Map.of(KernelTrustAnchor.KEY_ID, kp.getPublic()), expires);
    }

    // ---- §5.3 rollback: the version must be exactly the trusted one + 1 ----

    @Test
    public void aVersionThatSkipsARotationIsRefused() {
        RootMetadata trusted = baked();
        String reason = RootTrust.updateRejectionReason(trusted,
                candidate(trusted.version() + 2, FUTURE), NOW);
        assertNotNull("a version two ahead hides a whole rotation — TUF §5.3 requires exactly "
                + "trusted+1, not merely newer", reason);
        assertTrue("the refusal must name the rollback rule, not just return empty: " + reason,
                reason.contains("rollback"));
    }

    @Test
    public void aReplayedVersionIsRefused() {
        RootMetadata trusted = baked();
        String reason = RootTrust.updateRejectionReason(trusted,
                candidate(trusted.version(), FUTURE), NOW);
        assertNotNull("re-presenting the version we already trust is a rollback", reason);
        assertTrue("the refusal must name the rollback rule: " + reason,
                reason.contains("rollback"));
    }

    @Test
    public void exactlyTheNextVersionIsAccepted() {
        RootMetadata trusted = baked();
        RootMetadata successor = candidate(trusted.version() + 1, FUTURE);
        assertNull("the legal successor is the baked version + 1 and nothing else: "
                        + RootTrust.updateRejectionReason(trusted, successor, NOW),
                RootTrust.updateRejectionReason(trusted, successor, NOW));
    }

    // ---- §5.3.10 freeze: nothing already expired may be updated from or to ----

    @Test
    public void anExpiredDeliveredDocumentIsRefused() {
        RootMetadata trusted = baked();
        String reason = RootTrust.updateRejectionReason(trusted,
                candidate(trusted.version() + 1, PAST), NOW);
        assertNotNull("a document that is already expired cannot be installed", reason);
        assertTrue("the refusal must name the freeze rule: " + reason, reason.contains("freeze"));
        assertTrue("and say it expired: " + reason, reason.contains("expired"));
    }

    @Test
    public void anUndatedDeliveredDocumentIsRefused() {
        RootMetadata trusted = baked();
        String reason = RootTrust.updateRejectionReason(trusted,
                candidate(trusted.version() + 1, 0L), NOW);
        assertNotNull("a delivered document with no expiry has no freshness bound at all — the "
                + "field is what makes the freeze rule enforceable on received data", reason);
        assertTrue("the refusal must say the document declares no expiry: " + reason,
                reason.contains("no expiry"));
    }

    @Test
    public void anExpiredTrustedDocumentCannotAnchorAnUpdate() {
        RootMetadata expiredTrusted = candidate(baked().version(), PAST);
        String reason = RootTrust.updateRejectionReason(expiredTrusted,
                candidate(expiredTrusted.version() + 1, FUTURE), NOW);
        assertNotNull("TUF §5.3.10 checks the TRUSTED document's expiry: updating from a stale "
                + "anchor is refused even when the delivered document itself is fresh", reason);
        assertTrue("the refusal must name the trusted document's expiry: " + reason,
                reason.contains("trusted root document expired"));
    }

    // ---- threshold satisfiability ----

    @Test
    public void aThresholdTheBakedSideCannotMeetIsRefusedLoudly() {
        int bakedKeyCount = RootTrust.loadBakedRootKeys().size();
        // One above what the baked side can supply, whatever that count is: such a document can
        // never meet its own threshold, so it would disarm every patch with the reason buried in
        // a generic "threshold not met".
        int threshold = bakedKeyCount + 1;
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (int i = 0; i < threshold; i++) {
            KeyPair kp = CompatCrypto.generateEd25519();
            assertNotNull("the JDK must provide Ed25519", kp);
            keys.put("root-" + i, kp.getPublic());
        }
        RootMetadata unsatisfiable = new RootMetadata(2, threshold, keys, Map.of());

        String reason = RootTrust.thresholdRejectionReason(unsatisfiable, bakedKeyCount);
        assertNotNull("a " + threshold + "-of-N threshold against " + bakedKeyCount
                + " baked key(s) can never be met by ANY signature set — refusing loudly is what "
                + "stops it disarming every patch silently", reason);
        assertTrue("the refusal must name the threshold and the baked count: " + reason,
                reason.contains("threshold " + threshold)
                        && reason.contains(bakedKeyCount + " root key"));
        assertNull("the shipped document is satisfiable by its own baked key set and must stay so",
                RootTrust.thresholdRejectionReason(baked(), bakedKeyCount));
    }

    // ---- positive halves: the rules must not be a blanket refusal ----

    /**
     * A document that passes the replacement rules and is validly signed under the baked root key
     * derives anchors. Signed with a throwaway key injected as the baked set (the shipped root's
     * private key is, correctly, not in the tree) — the same technique
     * {@code TrustAnchorRevocationTest} uses.
     */
    @Test
    public void aValidSuccessorDerivesAnchors() {
        RootMetadata trusted = baked();
        KeyPair root = CompatCrypto.generateEd25519();
        assertNotNull("the JDK must provide Ed25519", root);
        RootMetadata successor = new RootMetadata(trusted.version() + 1, 1,
                Map.of(RootTrust.ROOT_KEY_ID, root.getPublic()),
                Map.of(KernelTrustAnchor.KEY_ID, root.getPublic()), FUTURE);
        assertNull("the successor must pass the replacement rules",
                RootTrust.updateRejectionReason(trusted, successor, NOW));

        byte[] sig = CompatCrypto.ed25519Sign(root.getPrivate(), successor.signingBytes());
        TrustAnchors anchors = TufTrust.effectiveAnchors(successor,
                Map.of(RootTrust.ROOT_KEY_ID, sig), Map.of(RootTrust.ROOT_KEY_ID, root.getPublic()));
        assertFalse("a rule-passing, validly signed successor must yield the targets keys it "
                + "authorizes", anchors.isEmpty());
        assertNotNull("and specifically the kernel keyId patches are signed under",
                anchors.lookup(KernelTrustAnchor.KEY_ID));
    }

    /**
     * The end-to-end statement of the rollback rule: the public update path refuses a skipped
     * version. (The refusal REASON is pinned by the rule tests above; this asserts the API
     * boundary refuses too, so the rules cannot be bypassed by calling the outer method.)
     */
    @Test
    public void theUpdatePathRefusesARolledBackDocument() {
        RootMetadata trusted = baked();
        Map<String, byte[]> sigs = new LinkedHashMap<>();
        sigs.put(RootTrust.ROOT_KEY_ID, new byte[64]);
        assertTrue("a document at baked+2 must be refused at the update boundary",
                RootTrust.verifyRootUpdate(candidate(trusted.version() + 2, FUTURE), sigs).isEmpty());
    }

    /**
     * The shipped chain still verifies after the new checks. Without this, a freeze/threshold
     * check that misfired on the shipped document would disarm every patch and every negative
     * assertion above would still pass.
     */
    @Test
    public void theShippedChainStillDerivesTheKernelKey() {
        TrustAnchors anchors = RootTrust.effectiveAnchors();
        assertFalse("the shipped root chain must still derive anchors — the new freeze and "
                + "threshold checks must not fire on the document this client ships",
                anchors.isEmpty());
        PublicKey kernel = anchors.lookup(KernelTrustAnchor.KEY_ID);
        assertNotNull("and it must still authorize the kernel keyId the shipped patches are "
                + "signed under", kernel);
    }
}
