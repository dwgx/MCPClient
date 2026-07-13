package net.marcloud.mcp.core.alpc;

/**
 * Server result of {@code compatHello}: ephemeral X25519 pub + nonces already
 * mixed into the session binding, plus the long-term Ed25519 signature over the
 * transcript (identity binding — prevents UKS/MITM on the handshake).
 */
public record CompatHello(
        byte[] serverPubSpki,
        byte[] serverNonce,
        String sessionId,
        byte[] transcriptSig,
        String keyId) {
}
