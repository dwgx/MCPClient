package net.marcloud.mcp.core.secure;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.marcloud.mcp.core.http.Json;
import net.marcloud.mcp.core.security.AccessDecision;
import net.marcloud.mcp.core.security.PolicyEngine;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.ToolRequest;

/**
 * The P-SECURE process's server side — the authoritative privilege-decision
 * authority for L1 VTL. It owns a real {@link PolicyEngine} (the in-process
 * reference monitor) and answers decision requests from the game JVM over a
 * loopback socket, so the authority lives in a separate address space a rogue
 * in-JVM hook cannot reach.
 *
 * <p>Protocol per {@link PSecureProtocol}: a mandatory auth handshake (shared
 * secret) then newline-delimited JSON {@code evaluate}/{@code clearance}/{@code
 * dropTo}/{@code tryRestore}/{@code restorable} calls. Bound to 127.0.0.1 only.
 *
 * <p>The accept loop owns one replaceable client worker. A new connection closes
 * the previous client, and bounded reads contain silent or oversized frames.
 */
public final class PSecureServer {

    private static final int READ_TIMEOUT_MILLIS = 1000;
    private static final int MAX_FRAME_CHARS = 64 * 1024;

    private final PolicyEngine authority;
    private final int port;
    private final String authToken;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile int boundPort = -1;
    private final Object clientLock = new Object();
    private Socket activeClient;

    public PSecureServer(PolicyEngine authority, int port, String authToken) {
        this.authority = authority;
        this.port = port;
        this.authToken = authToken == null ? "" : authToken;
    }

    /** Bind + serve on a daemon thread. Pass port 0 for an ephemeral test port. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(loopback, port), 4);
        this.serverSocket = ss;
        this.boundPort = ss.getLocalPort();
        this.running = true;
        Thread t = new Thread(this::acceptLoop, "p-secure");
        t.setDaemon(true);
        t.start();
        System.err.println("[P-SECURE] listening on 127.0.0.1:" + boundPort);
    }

    /** The actually-bound port (useful when started on ephemeral port 0). */
    public int boundPort() {
        return boundPort;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                replaceClient(client);
            } catch (Throwable t) {
                if (running) {
                    System.err.println("[P-SECURE] accept failed (continuing): " + t);
                }
            }
        }
    }

    private void replaceClient(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        client.setSoTimeout(READ_TIMEOUT_MILLIS);
        synchronized (clientLock) {
            if (!running) {
                closeQuietly(client);
                return;
            }
            closeQuietly(activeClient);
            activeClient = client;
        }
        Thread worker = new Thread(() -> serveClient(client), "p-secure-client");
        worker.setDaemon(true);
        worker.start();
    }

    private void serveClient(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8))) {
            // Auth handshake: first line must carry the shared secret.
            String authLine = readLine(in);
            if (authLine == null) {
                return;
            }
            Object presented = Json.readObject(authLine).get(PSecureProtocol.K_AUTH);
            if (!authToken.equals(presented)) {
                System.err.println("[P-SECURE] auth rejected from " + c.getRemoteSocketAddress());
                return; // close without acknowledging
            }
            writeLine(out, Map.of(PSecureProtocol.K_AUTHED, true));

            String line;
            while ((line = readLine(in)) != null) {
                Map<String, Object> req = Json.readObject(line);
                writeLine(out, handle(req));
            }
        } catch (SocketTimeoutException e) {
            // A silent client loses its slot so another client can connect.
        } catch (Throwable e) {
            // client gone / bad frame — contained.
        } finally {
            synchronized (clientLock) {
                if (activeClient == client) {
                    activeClient = null;
                }
            }
        }
    }

    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == -1) {
                return line.isEmpty() ? null : line.toString();
            }
            if (c == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() >= MAX_FRAME_CHARS) {
                throw new IOException("P-SECURE frame exceeds " + MAX_FRAME_CHARS + " characters");
            }
            line.append((char) c);
        }
    }

    private Map<String, Object> handle(Map<String, Object> req) {
        Object id = req.get(PSecureProtocol.K_ID);
        String method = String.valueOf(req.get(PSecureProtocol.K_METHOD));
        Map<String, Object> resp = new LinkedHashMap<>();
        if (id != null) {
            resp.put(PSecureProtocol.K_ID, id);
        }
        switch (method) {
            case PSecureProtocol.M_EVALUATE -> {
                String tool = String.valueOf(req.get(PSecureProtocol.K_TOOL));
                boolean builtIn = Boolean.TRUE.equals(req.get(PSecureProtocol.K_BUILTIN));
                AccessDecision d = authority.evaluate(authority.currentSubject(),
                        new ToolRequest(tool, Map.of(), builtIn));
                resp.put(PSecureProtocol.K_ALLOW, d.allow());
                resp.put(PSecureProtocol.K_LAYER, d.layer());
                resp.put(PSecureProtocol.K_REASON, d.reason());
            }
            case PSecureProtocol.M_CLEARANCE ->
                    resp.put(PSecureProtocol.K_CLEARANCE, authority.clearance().name());
            case PSecureProtocol.M_DROP_TO -> {
                Ring target = parseRing(String.valueOf(req.get(PSecureProtocol.K_TARGET)));
                Ring now = (target == null) ? authority.clearance() : authority.dropTo(target);
                resp.put(PSecureProtocol.K_CLEARANCE, now.name());
            }
            case PSecureProtocol.M_TRY_RESTORE -> {
                Ring target = parseRing(String.valueOf(req.get(PSecureProtocol.K_TARGET)));
                String token = String.valueOf(req.getOrDefault(PSecureProtocol.K_TOKEN, ""));
                boolean ok = target != null && authority.tryRestore(target, token);
                resp.put(PSecureProtocol.K_RESULT, ok);
                resp.put(PSecureProtocol.K_CLEARANCE, authority.clearance().name());
            }
            case PSecureProtocol.M_RESTORABLE ->
                    resp.put(PSecureProtocol.K_RESTORABLE, authority.restorable());
            default -> {
                resp.put(PSecureProtocol.K_ALLOW, false);
                resp.put(PSecureProtocol.K_REASON, "unknown method: " + method);
            }
        }
        return resp;
    }

    private static void writeLine(BufferedWriter out, Map<String, Object> obj) throws IOException {
        out.write(Json.write(obj));
        out.newLine();
        out.flush();
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

    public synchronized void close() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        synchronized (clientLock) {
            closeQuietly(activeClient);
            activeClient = null;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
