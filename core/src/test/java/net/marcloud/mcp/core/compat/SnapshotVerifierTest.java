package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * TUF L3 — snapshot/timestamp collection-level rollback protection. A patch set is accepted
 * only when the snapshot + timestamp are root-authorized-signed, the timestamp binds and is
 * fresh, and the armed set matches the snapshot. These fail on a stale timestamp, a
 * mix-and-match subset, a wrong signer, or a snapshot/timestamp mismatch (the teeth).
 */
public class SnapshotVerifierTest {

    private static final String KEY_ID = "targets-1";

    /** A root-derived anchor set trusting one signing key (as TufTrust would produce). */
    private static TrustAnchors anchorsFor(KeyPair kp) {
        return TrustAnchors.of(Map.of(KEY_ID, kp.getPublic()));
    }

    private static SnapshotMetadata snapshot(int version, Map<String, String> set) {
        return new SnapshotMetadata(version, set);
    }

    private static TimestampMetadata freshTimestamp(SnapshotMetadata snap, long now, long ttlMs) {
        return new TimestampMetadata(snap.version(), SnapshotVerifier.snapshotHashHex(snap),
                now, now + ttlMs);
    }

    @Test
    public void validPairWithMatchingArmedSetPasses() {
        KeyPair signer = CompatCrypto.generateEd25519();
        TrustAnchors anchors = anchorsFor(signer);
        SnapshotMetadata snap = snapshot(1, Map.of("cp-a", "1.0.0.0", "cp-b", "1.0.0.0"));
        long now = 1_000_000L;
        TimestampMetadata ts = freshTimestamp(snap, now, 120_000L);
        byte[] snapSig = CompatCrypto.ed25519Sign(signer.getPrivate(), snap.signingBytes());
        byte[] tsSig = CompatCrypto.ed25519Sign(signer.getPrivate(), ts.signingBytes());

        String reason = SnapshotVerifier.rejectionReason(
                snap, KEY_ID, snapSig, ts, KEY_ID, tsSig, anchors,
                List.of("cp-a", "cp-b"), now + 1000);
        assertNull("consistent, fresh, root-authorized set is accepted", reason);
    }

    @Test
    public void expiredTimestampIsRejected() {
        KeyPair signer = CompatCrypto.generateEd25519();
        TrustAnchors anchors = anchorsFor(signer);
        SnapshotMetadata snap = snapshot(1, Map.of("cp-a", "1.0.0.0"));
        long now = 1_000_000L;
        TimestampMetadata ts = freshTimestamp(snap, now, 60_000L);
        byte[] snapSig = CompatCrypto.ed25519Sign(signer.getPrivate(), snap.signingBytes());
        byte[] tsSig = CompatCrypto.ed25519Sign(signer.getPrivate(), ts.signingBytes());

        // now is PAST expiry -> stale
        String reason = SnapshotVerifier.rejectionReason(
                snap, KEY_ID, snapSig, ts, KEY_ID, tsSig, anchors,
                List.of("cp-a"), now + 60_001L);
        assertNotNull("expired timestamp must be rejected", reason);
        assertTrue(reason.contains("expired") || reason.contains("stale"));
    }

    @Test
    public void mixAndMatchArmedSetIsRejected() {
        KeyPair signer = CompatCrypto.generateEd25519();
        TrustAnchors anchors = anchorsFor(signer);
        // Snapshot says the current set is {a, b} (e.g. b is a security patch).
        SnapshotMetadata snap = snapshot(1, Map.of("cp-a", "1.0.0.0", "cp-b", "1.0.0.0"));
        long now = 1_000_000L;
        TimestampMetadata ts = freshTimestamp(snap, now, 120_000L);
        byte[] snapSig = CompatCrypto.ed25519Sign(signer.getPrivate(), snap.signingBytes());
        byte[] tsSig = CompatCrypto.ed25519Sign(signer.getPrivate(), ts.signingBytes());

        // Attacker armed only {a} — dropped the security patch b. Each piece individually valid,
        // but the COLLECTION is wrong.
        String reason = SnapshotVerifier.rejectionReason(
                snap, KEY_ID, snapSig, ts, KEY_ID, tsSig, anchors,
                List.of("cp-a"), now + 1000);
        assertNotNull("armed subset != snapshot must be rejected (mix-and-match)", reason);
        assertTrue(reason.contains("does not match snapshot"));
    }

    @Test
    public void wrongSnapshotSignerIsRejected() {
        KeyPair signer = CompatCrypto.generateEd25519();
        KeyPair attacker = CompatCrypto.generateEd25519();
        TrustAnchors anchors = anchorsFor(signer); // only trusts 'signer'
        SnapshotMetadata snap = snapshot(1, Map.of("cp-a", "1.0.0.0"));
        long now = 1_000_000L;
        TimestampMetadata ts = freshTimestamp(snap, now, 120_000L);
        // Snapshot signed by an UNTRUSTED key.
        byte[] snapSig = CompatCrypto.ed25519Sign(attacker.getPrivate(), snap.signingBytes());
        byte[] tsSig = CompatCrypto.ed25519Sign(signer.getPrivate(), ts.signingBytes());

        String reason = SnapshotVerifier.rejectionReason(
                snap, KEY_ID, snapSig, ts, KEY_ID, tsSig, anchors,
                List.of("cp-a"), now + 1000);
        assertNotNull("snapshot signed by a non-root-authorized key is rejected", reason);
        assertTrue(reason.contains("snapshot signature"));
    }

    @Test
    public void timestampBoundToDifferentSnapshotIsRejected() {
        KeyPair signer = CompatCrypto.generateEd25519();
        TrustAnchors anchors = anchorsFor(signer);
        SnapshotMetadata snapA = snapshot(1, Map.of("cp-a", "1.0.0.0"));
        SnapshotMetadata snapB = snapshot(2, Map.of("cp-a", "2.0.0.0")); // different set/version
        long now = 1_000_000L;
        // Timestamp is for snapB, but we present snapA.
        TimestampMetadata tsForB = freshTimestamp(snapB, now, 120_000L);
        byte[] snapSig = CompatCrypto.ed25519Sign(signer.getPrivate(), snapA.signingBytes());
        byte[] tsSig = CompatCrypto.ed25519Sign(signer.getPrivate(), tsForB.signingBytes());

        String reason = SnapshotVerifier.rejectionReason(
                snapA, KEY_ID, snapSig, tsForB, KEY_ID, tsSig, anchors,
                List.of("cp-a"), now + 1000);
        assertNotNull("timestamp bound to a different snapshot is rejected", reason);
        assertTrue(reason.contains("snapshotVersion") || reason.contains("snapshotHash"));
    }
}
