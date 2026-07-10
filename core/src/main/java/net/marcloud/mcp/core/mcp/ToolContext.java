package net.marcloud.mcp.core.mcp;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.action.ActionManager;
import net.marcloud.mcp.core.hotload.HotLoadEngine;
import net.marcloud.mcp.core.state.PacketLog;

/**
 * Shared context handed to every MCP tool: the game façade, the control surface,
 * the hot-load engine, and the packet log. Bundling these keeps individual tool
 * definitions small and free of wiring.
 */
public record ToolContext(GameAccess game,
                          ActionManager actions,
                          HotLoadEngine hotLoad,
                          PacketLog packetLog) {
}
