package net.marcloud.mcp.core.alpc;

/**
 * Short-TTL Ed25519-signed authorization assertion for one patch (crypto-core v2).
 * Private half lives only in the P-SECURE authority; client verifies with a pinned
 * public key and never treats a session MAC as the authz root.
 */
public record CompatTicket(
        String sessionId,
        String patchId,
        String contentHash,
        String minClientVer,
        long epoch,
        long expEpochMs,
        byte[] nonce,
        String keyId,
        byte[] sig) {
}
