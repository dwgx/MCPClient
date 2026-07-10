package net.marcloud.mcp.core.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

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

    private final ToolContext ctx;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile McpSyncServer currentServer;
    private volatile boolean running;

    public SocketTransportServer(ToolContext ctx) {
        this(ctx, DEFAULT_PORT);
    }

    public SocketTransportServer(ToolContext ctx, int port) {
        this.ctx = ctx;
        this.port = port;
    }

    /** Bind the port and start accepting clients on a daemon thread. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
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
            } catch (IOException e) {
                if (running) {
                    System.err.println("[MCP Core] accept failed: " + e);
                }
                // loop; if serverSocket closed, running is false and we exit
            }
        }
    }

    private void serveClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            McpJsonMapper json = new JacksonMcpJsonMapperSupplier().get();
            // Reuse the SDK's stdio JSON-RPC codec over the socket's streams.
            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    json, client.getInputStream(), client.getOutputStream());
            currentServer = McpServer.sync(transport)
                    .serverInfo("mcp-core", "1.8.9")
                    .instructions("Drive and observe a running Minecraft 1.8.9 client.")
                    .capabilities(ServerCapabilities.builder().tools(true).build())
                    .tools(new ToolRegistry(ctx).all())
                    .build();
            System.err.println("[MCP Core] client connected: " + client.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("[MCP Core] failed to serve client: " + e);
        }
    }

    /** Stop accepting and close the current server + socket. */
    public synchronized void close() {
        running = false;
        McpSyncServer s = currentServer;
        if (s != null) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
            }
        }
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
