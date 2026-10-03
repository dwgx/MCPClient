package net.marcloud.mcp.core.io.transport;

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
import net.marcloud.mcp.core.io.IoManager;

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

    private final IoManager registry;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile McpSyncServer currentServer;
    private volatile Socket currentClient;
    private volatile boolean running;


    /**
     * The handshake instructions — the one sentence of prose this server sends a model, and the
     * only place the codebase tells a model what its own capabilities are.
     *
     * <p><b>It names exactly ONE tool, {@code list_capabilities}, and that tool is model-facing
     * by the 2026-10-02 ruling.</b> Two things the sentence must not do, both load-bearing:
     * <ul>
     *   <li><b>Name a kernel-layered verb.</b> This string named {@code create_tool} until that
     *       same ruling, in the clause "and create_tool to grow new ones at runtime". Keeping it
     *       was not "a sentence with one stale half": it was an instruction to a weak model to
     *       reach for the one verb that MANUFACTURES the kernel surface, delivered by the server
     *       itself, immediately after that verb was deliberately hidden. That is the failure
     *       {@code create_tool} being kernel-layered exists to prevent, reintroduced through the
     *       handshake.</li>
     *   <li><b>Imply the model can extend the tool set.</b> It cannot. Promotion is
     *       operator-only ({@code -Dmcp.core.promote}, {@code mcp_promote.txt}, and
     *       {@code McpCore.promote}'s own javadoc says there is deliberately no model-reachable
     *       route to it), so any such promise is false — and a weak model told it will spend its
     *       turns hunting for a verb that is not on its surface.</li>
     * </ul>
     *
     * <p><b>What the sentence still owes the model, and why {@code list_capabilities} pays
     * it.</b> A model that has just connected has been handed no inventory, so without a way to
     * discover what it can call this sentence is its whole orientation. "Use
     * list_capabilities to see every tool you can call" delivers that, and it is <b>true</b>
     * rather than a promise: {@code list_capabilities} enumerates the model-facing surface, so
     * it reports exactly the model-facing set — pinned by
     * {@code ToolLayeringTest.listCapabilitiesReportsExactlyTheModelFacingSet}, which fails if
     * the tool is ever handed the audited registry instead.
     *
     * <p>The wording is "every tool you can call", not "all tools": tools are registered on more
     * than one layer and a kernel-layered one is not callable, and the subject of this sentence is
     * what the model can do. **No count is written here on purpose.** The measured split moved
     * (this line once said 84 registered / 51 callable, and said it for months) because nothing
     * checked it: the gate above pins the SET, which is the claim that matters, and a number
     * beside it is prose nothing can check — the same stale-gate-row shape the next paragraph
     * exists to avoid. Read the split off {@link ToolRegistry#modelFacingNames()} against
     * {@link ToolRegistry#declaredNames()}, not off this comment.
     *
     * <p><b>Why a constant and not a literal in the builder chain.</b> A string buried in a call
     * chain is prose nothing can check. {@code TheHandshakeSentenceNamesNoKernelVerbTest} reads
     * the tool names out of THIS value and asks {@link ToolRegistry#layerOf} about each, so a
     * sentence naming a kernel-layered verb goes red against the layer table itself rather than
     * against a list of forbidden names copied into the test — which would be the stale-gate-row
     * shape.
     */
    public static final String INSTRUCTIONS =
            "Drive and observe a running Minecraft 1.8.9 client. "
                    + "Use list_capabilities to see every tool you can call.";

    public SocketTransportServer(IoManager registry) {
        this(registry, DEFAULT_PORT);
    }

    public SocketTransportServer(IoManager registry, int port) {
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
                .instructions(INSTRUCTIONS)
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
            // Unbind FIRST so the registry never keeps pointing at a server we're
            // about to close. Otherwise a create_tool/rollback via the still-live
            // HTTP door (between disconnect and the next connect) would call
            // addTool on a CLOSED McpSyncServer, throw, and commit nothing. With
            // the ref cleared, register() takes its in-memory-only commit path.
            registry.bindServer(null);
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
