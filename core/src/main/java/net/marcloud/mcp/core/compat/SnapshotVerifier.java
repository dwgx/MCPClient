package net.marcloud.mcp.core.compat;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.List;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * TUF L3 — verifies a {@link SnapshotMetadata} + {@link TimestampMetadata} pair against
 * root-authorized keys and enforces consistency + freshness. This is the collection-level
 * rollback protection: it answers "is this the complete, current, fresh set of patches the
 * authority blessed?" — not just "is each patch individually signed?" (that is L0-L2).
 *
 * <p>The full L3 check (each step gates the next):
 * <ol>
 *   <li><b>snapshot signed to root:</b> the snapshot signature verifies under a key present in
 *       the effective {@link TrustAnchors} (which {@link TufTrust} derived by verifying the root
 *       document — so the snapshot key is itself root-authorized).</li>
 *   <li><b>timestamp signed to root:</b> likewise for the timestamp signature.</li>
 *   <li><b>timestamp binds this snapshot:</b> the timestamp's snapshotVersion + snapshotHash
 *       match the snapshot presented — no pairing a fresh timestamp with a different snapshot.</li>
 *   <li><b>timestamp fresh:</b> not expired relative to now — a stale (replayed) timestamp is
 *       rejected, blocking a freeze attack.</li>
 *   <li><b>armed set matches snapshot:</b> the patchIds the engine actually armed equal the
 *       snapshot's set — no mix-and-match subset (an armed patch missing from the snapshot, or a
 *       snapshot patch missing from the armed set, is an inconsistency).</li>
 * </ol>
 *
 * <p>Fail-closed: any failed step returns a rejection reason; a null reason means the collection
 * is verified consistent + fresh. Never throws.
 *
 * <p><b>Honest scope:</b> latent under in-code registration (no data-delivery attacker to mix-
 * and-match), load-bearing once patches ship as data. The logic is real and tested now so the
 * data switch needs no re-architecture.
 */
public final class SnapshotVerifier {

    private SnapshotVerifier() {
    }

    /** sha256 hex of a snapshot's canonical bytes — what a timestamp pins. */
    public static String snapshotHashHex(SnapshotMetadata snapshot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(snapshot.signingBytes());
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Verify the snapshot+timestamp pair and that {@code armedPatchIds} matches the snapshot's
     * set. Returns {@code null} if fully verified, else a human-readable rejection reason.
     *
     * @param snapshot        the current patch-set document
     * @param snapshotSigKeyId keyId whose anchor verifies the snapshot signature
     * @param snapshotSig      snapshot signature bytes
     * @param timestamp       the freshness attestation
     * @param timestampSigKeyId keyId whose anchor verifies the timestamp signature
     * @param timestampSig     timestamp signature bytes
     * @param anchors         root-derived trust anchors (from {@link TufTrust})
     * @param armedPatchIds   the patchIds the engine actually armed
     * @param nowEpochMs      current wall-clock time for the freshness check
     */
    public static String rejectionReason(
            SnapshotMetadata snapshot, String snapshotSigKeyId, byte[] snapshotSig,
            TimestampMetadata timestamp, String timestampSigKeyId, byte[] timestampSig,
            TrustAnchors anchors, List<String> armedPatchIds, long nowEpochMs) {
        try {
            if (snapshot == null || timestamp == null || anchors == null) {
                return "missing snapshot/timestamp/anchors";
            }
            // 1. snapshot signed by a root-authorized key
            PublicKey snapKey = anchors.lookup(snapshotSigKeyId);
            if (snapKey == null || snapshotSig == null
                    || !CompatCrypto.ed25519Verify(snapKey, snapshot.signingBytes(), snapshotSig)) {
                return "snapshot signature not valid under a root-authorized key";
            }
            // 2. timestamp signed by a root-authorized key
            PublicKey tsKey = anchors.lookup(timestampSigKeyId);
            if (tsKey == null || timestampSig == null
                    || !CompatCrypto.ed25519Verify(tsKey, timestamp.signingBytes(), timestampSig)) {
                return "timestamp signature not valid under a root-authorized key";
            }
            // 3. timestamp binds THIS snapshot (version + hash)
            if (timestamp.snapshotVersion() != snapshot.version()) {
                return "timestamp snapshotVersion " + timestamp.snapshotVersion()
                        + " != snapshot version " + snapshot.version();
            }
            if (!snapshotHashHex(snapshot).equals(timestamp.snapshotHashHex())) {
                return "timestamp snapshotHash does not match the presented snapshot";
            }
            // 4. timestamp fresh
            if (timestamp.isExpired(nowEpochMs)) {
                return "timestamp expired (stale/replayed) — refusing a possibly-frozen set";
            }
            // 5. armed set == snapshot set (no mix-and-match subset/superset)
            java.util.Set<String> snap = new java.util.HashSet<>(snapshot.patchIds());
            java.util.Set<String> armed = new java.util.HashSet<>(
                    armedPatchIds == null ? List.of() : armedPatchIds);
            if (!snap.equals(armed)) {
                java.util.Set<String> missingFromArmed = new java.util.HashSet<>(snap);
                missingFromArmed.removeAll(armed);
                java.util.Set<String> extraInArmed = new java.util.HashSet<>(armed);
                extraInArmed.removeAll(snap);
                return "armed set does not match snapshot — missing " + missingFromArmed
                        + ", unexpected " + extraInArmed;
            }
            return null; // consistent + fresh + root-authorized
        } catch (Throwable t) {
            return "snapshot/timestamp verification error: " + t;
        }
    }
}
