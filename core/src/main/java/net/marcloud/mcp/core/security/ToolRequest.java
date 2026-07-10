package net.marcloud.mcp.core.security;

import java.util.Map;

/**
 * An access-decision request: the tool being invoked plus its argument map. This
 * is the unit the {@link PolicyEngine} evaluates against a {@link
 * SecurityContext}. Kept minimal — the engine looks up the tool's declared
 * requirements from the side tables ({@link Ring}, {@link CapabilityCatalog},
 * {@link ToolPolicy}) by name, so the request only carries what the caller knows.
 *
 * @param toolName  the tool's registered name
 * @param arguments the raw argument map (may be null; treated as empty)
 * @param builtIn   whether this is a built-in tool (affects default capability set)
 */
public record ToolRequest(String toolName, Map<String, Object> arguments, boolean builtIn) {

    public ToolRequest {
        arguments = (arguments == null) ? Map.of() : arguments;
    }
}
