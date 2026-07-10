package net.marcloud.mcp.core.registry;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * One registered capability = the "tool = code + description + schema" triple
 * that every self-extending agent system (Voyager, CREATOR, CRAFT) converges on,
 * plus health stats and lineage. Immutable snapshot; a new version is a new
 * Capability (the archive keeps old ones for rollback / stepping-stones, à la
 * the Darwin Gödel Machine).
 *
 * @param name        tool name (unique key)
 * @param spec        the MCP tool spec (schema + handler) actually registered
 * @param source      Java source if this capability was hot-loaded (null for built-ins)
 * @param description NL description (also stored in the MCP tool)
 * @param version     monotonically increasing per name (1 = first)
 * @param stats       live health record (shared across versions of the same name)
 * @param builtIn     true for compiled-in tools, false for AI-authored ones
 */
public record Capability(String name,
                         SyncToolSpecification spec,
                         String source,
                         String description,
                         int version,
                         ToolStats stats,
                         boolean builtIn) {
}
