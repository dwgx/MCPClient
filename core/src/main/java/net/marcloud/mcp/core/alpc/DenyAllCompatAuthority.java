package net.marcloud.mcp.core.alpc;

import java.util.List;

/**
 * Fail-safe default: never establishes a usable session and never issues tickets.
 * Wired by the single-arg {@link AlpcServer} constructor so unconfigured authorities
 * cannot arm patches.
 */
public final class DenyAllCompatAuthority implements CompatAuthority {

    @Override
    public CompatHello hello(byte[] clientPubSpki, byte[] clientNonce) {
        return null;
    }

    @Override
    public CompatTicketIssue issueTickets(
            String sessionId, List<CompatCandidate> candidates, String clientVer) {
        return CompatTicketIssue.empty();
    }
}
