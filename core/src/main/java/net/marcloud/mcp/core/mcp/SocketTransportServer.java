package net.marcloud.mcp.core.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import net.marcloud.mcp.core.registry.CapabilityRegistry;

/**
 * Hosts the MCP server over a loopback TCP socket instead of process stdio.
 *
 * <p>Why: the game owns the console (log4j writes to stdout), so a real stdio
 * MCP server would corrupt the JSON-RPC stream. We reuse the SDK's proven
 * newline-delimited JSON-RPC codec ({@link StdioServerTransportProvider}) but
 * feed it a <b>socket's</b> input/output streams. An external AI client (or a
 * thin bridge) connects to the port; nothing touches the game's stdout.
 *
 * <p>First cut: single client, accept-then-serve on a daemon thread, re-accept
 * after disconnect. Bound to loopback only (dev-use, permissions wide open per
 * the project goal). Every failure is contained — the game never crashes if the
 * MCP endpoint has trouble.
 */
public final class SocketTransportServer {

    /** Default MCP port (avoids common MC ports). */
    public static final int DEFAULT_PORT = 25599;

    private final CapabilityRegistry registry;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile McpSyncServer currentServer;
    private volatile Socket currentClient;
    private volatile boolean running;

    public SocketTransportServer(CapabilityRegistry registry) {
        this(registry, DEFAULT_PORT);
    }

    public SocketTransportServer(CapabilityRegistry registry, int port) {
        this.registry = registry;
        this.port = port;
    }

    /** Bind the port and start accepting clients on a daemon thread. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        // Bind IPv4 loopback explicitly (127.0.0.1). getLoopbackAddress() can
        // resolve to IPv6 ::1 on some hosts, surprising clients that dial 127.0.0.1.
        InetAddress loopback;
        try {
            loopback = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            loopback = InetAddress.getLoopbackAddress();
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(loopback, port), 1);
        running = true;
        Thread t = new Thread(this::acceptLoop, "mcp-core-socket");
        t.setDaemon(true);
        t.start();
        System.err.println("[MCP Core] socket transport listening on 127.0.0.1:" + port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                serveClient(client);
            } catch (Throwable t) {
                // Catch everything: a single bad connection (or an SDK/init
                // RuntimeException in serveClient) must not kill the accept loop
                // and leave the server silently deaf.
                if (running) {
                    System.err.println("[MCP Core] accept/serve failed (continuing): " + t);
                }
                // if serverSocket was closed, running is false and we exit
            }
        }
    }

    private void serveClient(Socket client) throws IOException {
        // Close/replace any previous session so its server + transport thread +
        // socket don't leak on reconnect.
        closeCurrent();

        client.setTcpNoDelay(true);
        currentClient = client;
        McpJsonMapper json = new JacksonMcpJsonMapperSupplier().get();
        // Reuse the SDK's stdio JSON-RPC codec over the socket's streams.
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                json, client.getInputStream(), client.getOutputStream());
        // tools(listChanged=true): announce runtime-added capabilities.
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("mcp-core", "1.8.9")
                .instructions("Drive and observe a running Minecraft 1.8.9 client. "
                        + "Use list_capabilities to see all tools, and create_tool to "
                        + "grow new ones at runtime.")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(registry.currentSpecs())
                .build();
        currentServer = server;
        // Bind so runtime create_tool / rollback push live to this client.
        registry.bindServer(server);
        System.err.println("[MCP Core] client connected: " + client.getRemoteSocketAddress());
    }

    /** Close the current server + client socket, if any. */
    private void closeCurrent() {
        McpSyncServer s = currentServer;
        if (s != null) {
            try {
                s.close();
            } catch (RuntimeException e) {
                System.err.println("[MCP Core] closing previous server: " + e);
            }
            currentServer = null;
        }
        Socket c = currentClient;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
            currentClient = null;
        }
    }

    /** Stop accepting and close the current server + socket. */
    public synchronized void close() {
        running = false;
        closeCurrent();
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    public int port() {
        return port;
    }
}
