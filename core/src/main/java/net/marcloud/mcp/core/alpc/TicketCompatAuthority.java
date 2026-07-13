package net.marcloud.mcp.core.alpc;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process ticket authority for tests and the default P-SECURE wiring until an
 * out-of-band catalog lands. Holds an Ed25519 long-term key (private never leaves
 * this object) and a mutable allowlist of {@code patchId|contentHash} keys.
 *
 * <p>Session map is idle-reaped; tickets carry a monotonic epoch for revoke.
 */
public final class TicketCompatAuthority implements CompatAuthority {

    public static final String DEFAULT_KEY_ID = "auth-ed25519-1";

    private final PrivateKey identityPriv;
    private final PublicKey identityPub;
    private final String keyId;
    private final long ticketTtlMs;
    private final long sessionTtlMs;
    private final AtomicLong epoch = new AtomicLong(1);
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    /** Live allowlist: keys are {@code patchId + "\\0" + contentHash}. */
    private final Set<String> allow = ConcurrentHashMap.newKeySet();

    public TicketCompatAuthority(KeyPair identityEd25519) {
        this(identityEd25519, DEFAULT_KEY_ID, CompatCrypto.DEFAULT_TICKET_TTL_MS, 60_000L);
    }

    public TicketCompatAuthority(
            KeyPair identityEd25519, String keyId, long ticketTtlMs, long sessionTtlMs) {
        this.identityPriv = Objects.requireNonNull(identityEd25519).getPrivate();
        this.identityPub = identityEd25519.getPublic();
        this.keyId = keyId == null ? DEFAULT_KEY_ID : keyId;
        this.ticketTtlMs = ticketTtlMs;
        this.sessionTtlMs = sessionTtlMs;
    }

    public PublicKey identityPublicKey() {
        return identityPub;
    }

    public byte[] identityPublicSpki() {
        return identityPub.getEncoded();
    }

    public void allow(String patchId, String contentHash) {
        allow.add(key(patchId, contentHash));
    }

    public void deList(String patchId, String contentHash) {
        allow.remove(key(patchId, contentHash));
    }

    public void clearAllowlist() {
        allow.clear();
    }

    /** Bump global epoch so previously issued tickets become rejectable by clients. */
    public long bumpEpoch() {
        return epoch.incrementAndGet();
    }

    public long currentEpoch() {
        return epoch.get();
    }

    @Override
    public CompatHello hello(byte[] clientPubSpki, byte[] clientNonce) {
        if (clientPubSpki == null || clientNonce == null
                || clientNonce.length != CompatCrypto.NONCE_LEN) {
            return null;
        }
        try {
            reapSessions();
            PublicKey clientPub = CompatCrypto.decodeSpki("X25519", clientPubSpki);
            KeyPair serverEph = CompatCrypto.generateX25519();
            byte[] serverNonce = CompatCrypto.randomNonce();
            byte[] z = CompatCrypto.x25519Shared(serverEph.getPrivate(), clientPub);
            String sessionId = UUID.randomUUID().toString();
            byte[] sessionKey = CompatCrypto.deriveSessionKey(
                    z, clientNonce, serverNonce, sessionId);
            byte[] serverPubSpki = serverEph.getPublic().getEncoded();
            byte[] transcript = CompatCrypto.transcriptBytes(
                    AlpcProtocol.COMPAT_PROTOCOL_VER,
                    clientPubSpki,
                    serverPubSpki,
                    clientNonce,
                    serverNonce,
                    sessionId);
            byte[] sig = CompatCrypto.ed25519Sign(identityPriv, transcript);
            sessions.put(sessionId, new SessionEntry(sessionKey, System.currentTimeMillis()));
            return new CompatHello(serverPubSpki, serverNonce, sessionId, sig, keyId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public CompatTicketIssue issueTickets(
            String sessionId, List<CompatCandidate> candidates, String clientVer) {
        if (sessionId == null || sessionId.isBlank() || candidates == null) {
            return CompatTicketIssue.empty();
        }
        reapSessions();
        SessionEntry se = sessions.get(sessionId);
        if (se == null) {
            return CompatTicketIssue.empty();
        }
        String minVer = clientVer == null ? "" : clientVer;
        long now = System.currentTimeMillis();
        long exp = now + ticketTtlMs;
        long ep = epoch.get();
        List<CompatTicket> out = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (CompatCandidate c : candidates) {
            if (!allow.contains(key(c.patchId(), c.contentHash()))) {
                reasons.put(c.patchId(), "not in allowlist");
                continue;
            }
            byte[] nonce = CompatCrypto.randomNonce();
            byte[] input = CompatCrypto.ticketSigningInput(
                    AlpcProtocol.COMPAT_PROTOCOL_VER,
                    sessionId,
                    c.patchId(),
                    c.contentHash(),
                    minVer,
                    ep,
                    exp,
                    nonce);
            byte[] sig = CompatCrypto.ed25519Sign(identityPriv, input);
            out.add(new CompatTicket(
                    sessionId,
                    c.patchId(),
                    c.contentHash(),
                    minVer,
                    ep,
                    exp,
                    nonce,
                    keyId,
                    sig));
        }
        return new CompatTicketIssue(List.copyOf(out), Map.copyOf(reasons));
    }

    private void reapSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().createdAtMs > sessionTtlMs);
    }

    private static String key(String patchId, String contentHash) {
        return patchId + "\0" + contentHash;
    }

    private record SessionEntry(byte[] sessionKey, long createdAtMs) {
    }
}
