package net.marcloud.mcp.core.alpc;

import java.util.List;

/**
 * Out-of-band patch catalog + ticket issuer for the compat ALPC channel.
 * Lives in the P-SECURE process; never in the game JVM's address space for the
 * long-term private key.
 *
 * <p>Shipped default: {@link DenyAllCompatAuthority} (authorizes nothing) —
 * mirrors {@code UnsignedPatchSigner}'s fail-safe posture.
 */
public interface CompatAuthority {

    /**
     * Begin an ephemeral session: X25519 exchange + Ed25519 transcript signature.
     * Fail-closed implementations return null on bad input (caller maps to deny).
     */
    CompatHello hello(byte[] clientPubSpki, byte[] clientNonce);

    /**
     * Issue short-TTL tickets for the subset of candidates currently blessed.
     * Unknown/expired session or empty allowlist ⇒ empty issue (fail-closed).
     */
    CompatTicketIssue issueTickets(
            String sessionId, List<CompatCandidate> candidates, String clientVer);
}
