package net.marcloud.mcp.core.compat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import net.marcloud.mcp.core.alpc.AlpcProtocol;
import net.marcloud.mcp.core.alpc.CompatCandidate;
import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.alpc.CompatTicket;
import net.marcloud.mcp.core.io.http.Json;

/**
 * Game-JVM client for the compat ALPC channel. Mirrors {@code SeRemoteMonitor}
 * transport (auth bootstrap + newline-JSON + fail-closed null on any error) but
 * speaks only {@code compatHello} / {@code compatTicket}.
 *
 * <p>Authorization root (v2): short-TTL Ed25519 tickets verified with a pinned
 * authority public key. Session material from X25519 is retained only for binding
 * (session id match) — not as a forgeable arm-MAC secret.
 */
public final class CompatAuthorityClient {

    private final String host;
    private final int port;
    private final String authToken;
    private final int timeoutMillis;
    private final PublicKey authorityPub;
    private final AtomicLong reqId = new AtomicLong();

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    private String sessionId;
    private long lastEpoch;
    private boolean established;

    public CompatAuthorityClient(
            String host, int port, String authToken, int timeoutMillis, PublicKey authorityPub) {
        this.host = host;
        this.port = port;
        this.authToken = authToken;
        this.timeoutMillis = timeoutMillis;
        this.authorityPub = authorityPub;
    }

    /**
     * X25519 hello + verify server transcript signature with the pinned public key.
     * @return false on any failure (fail-closed)
     */
    public synchronized boolean handshake() {
        if (authorityPub == null) {
            return false;
        }
        try {
            KeyPair clientEph = CompatCrypto.generateX25519();
            byte[] clientNonce = CompatCrypto.randomNonce();
            byte[] clientPubSpki = clientEph.getPublic().getEncoded();

            Map<String, Object> req = new LinkedHashMap<>();
            req.put(AlpcProtocol.K_METHOD, AlpcProtocol.M_COMPAT_HELLO);
            req.put(AlpcProtocol.K_COMPAT_PROTOCOL_VER, AlpcProtocol.COMPAT_PROTOCOL_VER);
            req.put(AlpcProtocol.K_COMPAT_CLIENT_PUB, CompatCrypto.b64(clientPubSpki));
            req.put(AlpcProtocol.K_COMPAT_CLIENT_NONCE, CompatCrypto.b64(clientNonce));

            Map<String, Object> resp = call(req);
            if (resp == null) {
                return false;
            }
            String serverPubB64 = str(resp.get(AlpcProtocol.K_COMPAT_SERVER_PUB));
            String serverNonceB64 = str(resp.get(AlpcProtocol.K_COMPAT_SERVER_NONCE));
            String sid = str(resp.get(AlpcProtocol.K_COMPAT_SESSION));
            String sigB64 = str(resp.get(AlpcProtocol.K_COMPAT_TRANSCRIPT_SIG));
            if (serverPubB64.isEmpty() || serverNonceB64.isEmpty()
                    || sid.isEmpty() || sigB64.isEmpty()) {
                return false;
            }
            byte[] serverPubSpki = CompatCrypto.unb64(serverPubB64);
            byte[] serverNonce = CompatCrypto.unb64(serverNonceB64);
            byte[] sig = CompatCrypto.unb64(sigB64);
            byte[] transcript = CompatCrypto.transcriptBytes(
                    AlpcProtocol.COMPAT_PROTOCOL_VER,
                    clientPubSpki,
                    serverPubSpki,
                    clientNonce,
                    serverNonce,
                    sid);
            if (!CompatCrypto.ed25519Verify(authorityPub, transcript, sig)) {
                System.err.println("[CompatAuthorityClient] transcript sig rejected");
                return false;
            }
            // Derive session key for binding (not used as authz root).
            PublicKey serverEphPub = CompatCrypto.decodeSpki("X25519", serverPubSpki);
            byte[] z = CompatCrypto.x25519Shared(clientEph.getPrivate(), serverEphPub);
            CompatCrypto.deriveSessionKey(z, clientNonce, serverNonce, sid);
            this.sessionId = sid;
            this.established = true;
            return true;
        } catch (Exception e) {
            System.err.println("[CompatAuthorityClient] handshake failed: " + e);
            closeSocket();
            established = false;
            sessionId = null;
            return false;
        }
    }

    /**
     * Request tickets for candidates; return verified-authorized patchIds.
     * Empty set on ANY failure (poison-batch on a single bad ticket).
     */
    public synchronized Set<String> authorize(List<CompatCandidate> candidates) {
        return authorize(candidates, "");
    }

    public synchronized Set<String> authorize(List<CompatCandidate> candidates, String clientVer) {
        if (!established || sessionId == null || authorityPub == null) {
            return Set.of();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put(AlpcProtocol.K_METHOD, AlpcProtocol.M_COMPAT_TICKET);
            req.put(AlpcProtocol.K_COMPAT_SESSION, sessionId);
            req.put(AlpcProtocol.K_COMPAT_CLIENT_VER, clientVer == null ? "" : clientVer);
            List<Map<String, Object>> wire = new ArrayList<>();
            Map<String, CompatCandidate> byId = new LinkedHashMap<>();
            for (CompatCandidate c : candidates) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patchId", c.patchId());
                row.put("contentHash", c.contentHash());
                wire.add(row);
                byId.put(c.patchId(), c);
            }
            req.put(AlpcProtocol.K_COMPAT_PATCHES, wire);

            Map<String, Object> resp = call(req);
            if (resp == null) {
                return Set.of();
            }
            if (!sessionId.equals(str(resp.get(AlpcProtocol.K_COMPAT_SESSION)))) {
                return Set.of();
            }
            Object ticketsObj = resp.get(AlpcProtocol.K_COMPAT_TICKETS);
            if (!(ticketsObj instanceof List<?> list)) {
                return Set.of();
            }
            Set<String> ok = new LinkedHashSet<>();
            long now = System.currentTimeMillis();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> raw)) {
                    return Set.of(); // poison batch
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) raw;
                CompatTicket t = parseTicket(m);
                if (t == null) {
                    return Set.of();
                }
                if (!sessionId.equals(t.sessionId())) {
                    return Set.of();
                }
                CompatCandidate expected = byId.get(t.patchId());
                if (expected == null || !expected.contentHash().equals(t.contentHash())) {
                    return Set.of();
                }
                if (t.expEpochMs() < now) {
                    return Set.of();
                }
                if (t.epoch() < lastEpoch) {
                    return Set.of();
                }
                byte[] input = CompatCrypto.ticketSigningInput(
                        AlpcProtocol.COMPAT_PROTOCOL_VER,
                        t.sessionId(),
                        t.patchId(),
                        t.contentHash(),
                        t.minClientVer(),
                        t.epoch(),
                        t.expEpochMs(),
                        t.nonce());
                if (!CompatCrypto.ed25519Verify(authorityPub, input, t.sig())) {
                    return Set.of();
                }
                if (t.epoch() > lastEpoch) {
                    lastEpoch = t.epoch();
                }
                ok.add(t.patchId());
            }
            return Set.copyOf(ok);
        } catch (Exception e) {
            System.err.println("[CompatAuthorityClient] authorize failed: " + e);
            closeSocket();
            return Set.of();
        }
    }

    public synchronized boolean isEstablished() {
        return established;
    }

    public synchronized void close() {
        closeSocket();
        established = false;
        sessionId = null;
    }

    private static CompatTicket parseTicket(Map<String, Object> m) {
        try {
            String sid = str(m.get("sessionId"));
            String pid = str(m.get("patchId"));
            String ch = str(m.get("contentHash"));
            String minVer = str(m.get("minClientVer"));
            long epoch = longVal(m.get("epoch"));
            long exp = longVal(m.get("expEpochMs"));
            String nonceB64 = str(m.get("nonce"));
            String keyId = str(m.get("keyId"));
            String sigB64 = str(m.get("sig"));
            if (sid.isEmpty() || pid.isEmpty() || ch.isEmpty()
                    || nonceB64.isEmpty() || sigB64.isEmpty()) {
                return null;
            }
            return new CompatTicket(
                    sid, pid, ch, minVer, epoch, exp,
                    CompatCrypto.unb64(nonceB64), keyId, CompatCrypto.unb64(sigB64));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> call(Map<String, Object> params) {
        try {
            ensureConnected();
            Map<String, Object> req = new LinkedHashMap<>(params);
            req.put(AlpcProtocol.K_ID, reqId.getAndIncrement());
            out.write(Json.write(req));
            out.newLine();
            out.flush();
            String line = in.readLine();
            if (line == null) {
                throw new IOException("EOF from P-SECURE");
            }
            return Json.readObject(line);
        } catch (Exception e) {
            System.err.println("[CompatAuthorityClient] call failed (fail-closed): " + e);
            closeSocket();
            return null;
        }
    }

    private void ensureConnected() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(InetAddress.getByName(host), port), timeoutMillis);
        s.setSoTimeout(timeoutMillis);
        s.setTcpNoDelay(true);
        BufferedReader bin = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter bout = new BufferedWriter(
                new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        bout.write(Json.write(Map.of(AlpcProtocol.K_AUTH, authToken == null ? "" : authToken)));
        bout.newLine();
        bout.flush();
        String ack = bin.readLine();
        if (ack == null || !Boolean.TRUE.equals(Json.readObject(ack).get(AlpcProtocol.K_AUTHED))) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw new IOException("P-SECURE auth rejected");
        }
        this.socket = s;
        this.in = bin;
        this.out = bout;
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        socket = null;
        in = null;
        out = null;
        established = false;
        sessionId = null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long longVal(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            return Long.parseLong(s);
        }
        throw new IllegalArgumentException("not a long: " + o);
    }
}
