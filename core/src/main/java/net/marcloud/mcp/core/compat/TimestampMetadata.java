package net.marcloud.mcp.core.compat;

import java.nio.charset.StandardCharsets;

/**
 * TUF L3 — the TIMESTAMP role's signed document: a short-lived attestation that a given
 * {@link SnapshotMetadata} version is the freshest one, expiring at {@link #expiresAtEpochMs}.
 * Its job is anti-freeze / anti-replay: a snapshot is internally consistent and validly signed
 * forever, so without a freshness pointer an attacker could replay an OLD snapshot to keep you
 * on a pre-fix patch set. The timestamp is re-issued frequently with a short TTL, so a stale
 * one is detected as expired and rejected.
 *
 * <p>Binds to the snapshot by BOTH version and a hash of the snapshot's canonical bytes, so it
 * cannot be paired with a different snapshot of the same version number. Signed by a
 * root-authorized key (verified via {@link TufTrust}), reusing the L2 verify-to-root spine.
 *
 * <p><b>Honest scope</b> (same as {@link SnapshotMetadata}): latent under in-code registration;
 * load-bearing once patches ship as data. The freshness check uses wall-clock, so it assumes a
 * roughly-correct client clock — acceptable for the dev-tier posture and documented as such.
 */
public final class TimestampMetadata {

    static final String DOMAIN = "MCP-COMPAT-TIMESTAMP";
    private static final byte SEP = 0x1f;

    private final int snapshotVersion;
    private final String snapshotHashHex;
    private final long issuedAtEpochMs;
    private final long expiresAtEpochMs;

    public TimestampMetadata(int snapshotVersion, String snapshotHashHex,
                             long issuedAtEpochMs, long expiresAtEpochMs) {
        if (snapshotVersion < 1) {
            throw new IllegalArgumentException("snapshotVersion must be >= 1");
        }
        if (snapshotHashHex == null || snapshotHashHex.isBlank()) {
            throw new IllegalArgumentException("snapshotHashHex required");
        }
        if (expiresAtEpochMs <= issuedAtEpochMs) {
            throw new IllegalArgumentException("expiry must be after issuance");
        }
        this.snapshotVersion = snapshotVersion;
        this.snapshotHashHex = snapshotHashHex;
        this.issuedAtEpochMs = issuedAtEpochMs;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    public int snapshotVersion() {
        return snapshotVersion;
    }

    public String snapshotHashHex() {
        return snapshotHashHex;
    }

    public long issuedAtEpochMs() {
        return issuedAtEpochMs;
    }

    public long expiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    /** True if this timestamp has expired relative to {@code nowEpochMs} (fail-closed = stale). */
    public boolean isExpired(long nowEpochMs) {
        return nowEpochMs >= expiresAtEpochMs;
    }

    /** Deterministic bytes the timestamp signature covers. */
    public byte[] signingBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(128);
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.UTF_8));
        out.write(SEP);
        putLen(out, Integer.toString(snapshotVersion));
        putLen(out, snapshotHashHex);
        putLen(out, Long.toString(issuedAtEpochMs));
        putLen(out, Long.toString(expiresAtEpochMs));
        return out.toByteArray();
    }

    private static void putLen(java.io.ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >>> 24) & 0xFF);
        out.write((b.length >>> 16) & 0xFF);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.writeBytes(b);
    }
}
