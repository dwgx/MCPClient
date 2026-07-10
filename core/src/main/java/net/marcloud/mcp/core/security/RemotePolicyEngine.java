package net.marcloud.mcp.core.security;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.marcloud.mcp.core.http.Json;
import net.marcloud.mcp.core.secure.PSecureProtocol;

/**
 * L1 VTL — the {@link PolicyEngine} that asks a separate P-SECURE process for
 * every decision over a 127.0.0.1 socket (newline-delimited JSON). This is the
 * only real wall: a rogue in-JVM hook cannot reach another address space to forge
 * a grant, and it never gets to state its own subject — it sends only the tool
 * identity and the P-SECURE process decides against the authoritative subject it
 * holds.
 *
 * <p><b>Fail-closed.</b> Any transport failure (process down, slow past the
 * timeout, malformed reply, half-close) resolves to <i>deny</i> — never allow,
 * never hang. {@code dropTo} is the one exception: a drop is always safe (it only
 * reduces privilege), so it is applied to the local cache when the remote is
 * unreachable.
 *
 * <p>Opt-in via {@code -Dmcp.core.psecure=true}; disabled by default (the
 * in-process engine is the authority). Shares the {@link
 * net.marcloud.mcp.core.http.Json} dep-free codec so the wall does not couple to
 * the MCP SDK.
 */
public final class RemotePolicyEngine implements PolicyEngine {

    private final String host;
    private final int port;
    private final String authToken;
    private final int timeoutMillis;
    private final AtomicLong reqId = new AtomicLong(1);

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    /** Most-restrictive cached clearance, used as the fail-closed fallback. */
    private volatile Ring cachedClearance = Ring.R3;

    public RemotePolicyEngine(String host, int port, String authToken, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.authToken = authToken;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public AccessDecision evaluate(SecurityContext subject, ToolRequest request) {
        // The subject argument is intentionally NOT sent: the P-SECURE process
        // owns the authoritative subject. We send only the tool identity.
        Map<String, Object> req = new LinkedHashMap<>();
        req.put(PSecureProtocol.K_METHOD, PSecureProtocol.M_EVALUATE);
        req.put(PSecureProtocol.K_TOOL, request.toolName());
        req.put(PSecureProtocol.K_BUILTIN, request.builtIn());
        Map<String, Object> r = call(req);
        if (r == null) {
            return AccessDecision.deny("L1 VTL",
                    "P-SECURE process unreachable — failing closed (deny). "
                    + "Start it or unset -Dmcp.core.psecure.");
        }
        if (Boolean.TRUE.equals(r.get(PSecureProtocol.K_ALLOW))) {
            return AccessDecision.allowed();
        }
        return AccessDecision.deny(
                String.valueOf(r.getOrDefault(PSecureProtocol.K_LAYER, "L1 VTL")),
                String.valueOf(r.getOrDefault(PSecureProtocol.K_REASON, "denied by P-SECURE")));
    }

    @Override
    public Ring clearance() {
        Map<String, Object> r = call(Map.of(PSecureProtocol.K_METHOD, PSecureProtocol.M_CLEARANCE));
        if (r == null) {
            return cachedClearance;
        }
        Ring parsed = parseRing((String) r.get(PSecureProtocol.K_CLEARANCE));
        if (parsed != null) {
            cachedClearance = parsed;
        }
        return cachedClearance;
    }

    @Override
    public Ring dropTo(Ring target) {
        Map<String, Object> r = call(Map.of(
                PSecureProtocol.K_METHOD, PSecureProtocol.M_DROP_TO,
                PSecureProtocol.K_TARGET, target.name()));
        if (r == null) {
            // Remote down: a drop only reduces privilege, so apply locally (safe).
            if (target.level() > cachedClearance.level()) {
                cachedClearance = target;
            }
            return cachedClearance;
        }
        Ring parsed = parseRing((String) r.get(PSecureProtocol.K_CLEARANCE));
        if (parsed != null) {
            cachedClearance = parsed;
        }
        return cachedClearance;
    }

    @Override
    public boolean tryRestore(Ring target, String token) {
        Map<String, Object> r = call(Map.of(
                PSecureProtocol.K_METHOD, PSecureProtocol.M_TRY_RESTORE,
                PSecureProtocol.K_TARGET, target.name(),
                PSecureProtocol.K_TOKEN, token == null ? "" : token));
        if (r == null) {
            return false; // fail-closed
        }
        boolean ok = Boolean.TRUE.equals(r.get(PSecureProtocol.K_RESULT));
        if (ok) {
            Ring parsed = parseRing((String) r.get(PSecureProtocol.K_CLEARANCE));
            if (parsed != null) {
                cachedClearance = parsed;
            }
        }
        return ok;
    }

    @Override
    public boolean restorable() {
        Map<String, Object> r = call(Map.of(PSecureProtocol.K_METHOD, PSecureProtocol.M_RESTORABLE));
        return r != null && Boolean.TRUE.equals(r.get(PSecureProtocol.K_RESTORABLE));
    }

    @Override
    public SecurityContext currentSubject() {
        // The authoritative subject lives in the P-SECURE process; locally we only
        // expose the cached clearance for display. evaluate() ignores this.
        return SecurityContext.wideOpen().withClearance(clearance());
    }

    // ---- transport (fail-closed) ------------------------------------------

    private synchronized Map<String, Object> call(Map<String, Object> params) {
        try {
            ensureConnected();
            Map<String, Object> req = new LinkedHashMap<>(params);
            req.put(PSecureProtocol.K_ID, reqId.getAndIncrement());
            out.write(Json.write(req));
            out.newLine();
            out.flush();
            String line = in.readLine();
            if (line == null) {
                throw new IOException("EOF from P-SECURE");
            }
            return Json.readObject(line);
        } catch (Exception e) {
            System.err.println("[RemotePolicyEngine] call failed (fail-closed deny): " + e);
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
        // Auth handshake: prove we know the shared secret before any decision.
        bout.write(Json.write(Map.of(PSecureProtocol.K_AUTH, authToken == null ? "" : authToken)));
        bout.newLine();
        bout.flush();
        String ack = bin.readLine();
        if (ack == null || !Boolean.TRUE.equals(Json.readObject(ack).get(PSecureProtocol.K_AUTHED))) {
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
        } catch (Exception ignored) {
            // best effort
        }
        socket = null;
        in = null;
        out = null;
    }

    private static Ring parseRing(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Ring.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void close() {
        closeSocket();
    }
}
