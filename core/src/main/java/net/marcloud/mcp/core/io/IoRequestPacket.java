package net.marcloud.mcp.core.io;

import net.marcloud.mcp.core.se.CapabilityCatalog;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeToken;
import net.marcloud.mcp.core.se.SeToolRequirement;

import java.util.Map;

/**
 * An access-decision request: the tool being invoked plus its argument map. This
 * is the unit the {@link SeReferenceMonitor} evaluates against a {@link
 * SeToken}. Kept minimal — the engine looks up the tool's declared
 * requirements from the side tables ({@link Ring}, {@link CapabilityCatalog},
 * {@link SeToolRequirement}) by name, so the request only carries what the caller knows.
 *
 * @param toolName  the tool's registered name
 * @param arguments the raw argument map (may be null; treated as empty)
 * @param builtIn   whether this is a built-in tool (affects default capability set)
 */
public record IoRequestPacket(String toolName, Map<String, Object> arguments, boolean builtIn) {

    public IoRequestPacket {
        arguments = (arguments == null) ? Map.of() : arguments;
    }
}
