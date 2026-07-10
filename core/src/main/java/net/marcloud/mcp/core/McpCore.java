package net.marcloud.mcp.core;

import net.marcloud.mcp.core.action.ActionManager;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.PacketReceivedEvent;
import net.marcloud.mcp.core.event.events.PacketSentEvent;
import net.marcloud.mcp.core.hook.HookManager;
import net.marcloud.mcp.core.hotload.HotLoadEngine;
import net.marcloud.mcp.core.mcp.SocketTransportServer;
import net.marcloud.mcp.core.mcp.ToolContext;
import net.marcloud.mcp.core.state.DisconnectTracker;
import net.marcloud.mcp.core.state.PacketLog;
import net.marcloud.mcp.core.thread.MainThreadExecutor;

/**
 * MCP Core entry point — assembles the whole stack and exposes the running game
 * over MCP.
 *
 * <p>Wiring order:
 * <ol>
 *   <li>{@link GameAccess} + {@link MainThreadExecutor} (game façade + thread marshal),</li>
 *   <li>{@link EventBus} + {@link HookManager} (observe MC networking at runtime),</li>
 *   <li>{@link PacketLog} fed from packet events,</li>
 *   <li>{@link HotLoadEngine} + {@link ActionManager} (control + live code),</li>
 *   <li>{@link McpServerBootstrap} (expose it all as MCP tools over stdio).</li>
 * </ol>
 *
 * <p>Call {@link #start()} once the game is initialized (see the launcher hook).
 */
public final class McpCore {

    private static final int PACKET_LOG_CAPACITY = 256;

    private final EventBus bus = new EventBus();
    private final GameAccess game = new GameAccess();
    private final PacketLog packetLog = new PacketLog(PACKET_LOG_CAPACITY);
    private SocketTransportServer socketServer;

    /**
     * Assemble and start Core. Installs runtime hooks (if Instrumentation is
     * present), begins recording packets, and starts the stdio MCP server.
     */
    public void start() {
        MainThreadExecutor exec = new MainThreadExecutor(game.mc());
        ActionManager actions = new ActionManager(game, exec);
        HotLoadEngine hotLoad = new HotLoadEngine(getClass().getClassLoader());

        // Feed the packet log from the event stream.
        bus.subscribe(PacketReceivedEvent.class, e -> packetLog.recordInbound(e.packetType()));
        bus.subscribe(PacketSentEvent.class, e -> packetLog.recordOutbound(e.packetType()));

        // Track disconnects for the "why was I kicked" report.
        DisconnectTracker disconnects = new DisconnectTracker(bus, packetLog);

        // Install runtime network hooks (needs -javaagent). Non-fatal if absent:
        // the MCP server still serves state/chat/eval; only live packet
        // observation requires the agent.
        HookManager hooks = new HookManager(bus);
        if (hooks.canInstall()) {
            hooks.install();
        } else {
            System.err.println("[MCP Core] Instrumentation absent — packet hooks disabled. "
                    + "Start with -javaagent:core-<ver>.jar to enable them.");
        }

        ToolContext ctx = new ToolContext(game, actions, hotLoad, packetLog, disconnects);
        // Socket transport (not stdio): the game owns the console, so a stdio
        // MCP server would corrupt the JSON-RPC stream. An AI client connects to
        // the loopback port.
        socketServer = new SocketTransportServer(ctx);
        try {
            socketServer.start();
        } catch (java.io.IOException e) {
            System.err.println("[MCP Core] could not start socket transport: " + e);
        }
    }

    /** Stop the MCP server. */
    public void stop() {
        if (socketServer != null) {
            socketServer.close();
        }
    }

    /** Marker so callers/tests can confirm the module loaded and its Java level. */
    public static String banner() {
        return """
               MCP Core initialized (Java 25 module, running on JDK %s)
               """.formatted(Runtime.version().feature());
    }
}
