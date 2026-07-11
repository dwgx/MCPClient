package net.marcloud.mcp.core.io.transport;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.drivers.action.ActionManager;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.drivers.world.DisconnectTracker;
import net.marcloud.mcp.core.drivers.world.PacketLog;

/**
 * Shared context handed to every MCP tool: the game façade, the control surface,
 * the hot-load engine, the packet log, and the disconnect tracker. Bundling
 * these keeps individual tool definitions small and free of wiring.
 */
public record ToolContext(GameAccess game,
                          ActionManager actions,
                          LdrEngine hotLoad,
                          PacketLog packetLog,
                          DisconnectTracker disconnects) {
}
