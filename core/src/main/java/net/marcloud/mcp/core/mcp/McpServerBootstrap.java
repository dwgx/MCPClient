package net.marcloud.mcp.core.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Assembles and starts the MCP server that exposes the game to an AI client.
 *
 * <p>First transport: <b>stdio</b> (the default MCP client connection, e.g.
 * Claude Desktop launching this as a subprocess). HTTP/SSE is a sibling
 * bootstrap added next; both build the same {@link ToolRegistry} tool set.
 *
 * <p>The server is created in sync mode ({@code McpServer.sync}) and declares
 * tools capability. Tools are supplied from {@link ToolRegistry}.
 */
public final class McpServerBootstrap {

    private final ToolContext ctx;
    private volatile McpSyncServer server;

    public McpServerBootstrap(ToolContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Build and start the stdio MCP server. Returns the running server; keep the
     * reference to {@link #close()} it on shutdown.
     */
    public McpSyncServer startStdio() {
        McpJsonMapper json = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(json);

        server = McpServer.sync(transport)
                .serverInfo("mcp-core", "1.8.9")
                .instructions("Drive and observe a running Minecraft 1.8.9 client. "
                        + "Read player state, inspect recent packets, send chat, and "
                        + "eval Java live inside the game JVM.")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(new ToolRegistry(ctx).all())
                .build();
        return server;
    }

    /** Shut the server down if running. */
    public void close() {
        McpSyncServer s = server;
        if (s != null) {
            s.close();
        }
    }
}
