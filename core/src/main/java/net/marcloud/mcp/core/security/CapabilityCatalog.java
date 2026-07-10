package net.marcloud.mcp.core.security;

import static net.marcloud.mcp.core.security.CapabilitySid.CAP_CLASS_REDEFINE;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_MEMORY_READ;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_NETWORK_RECV_TAP;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_NETWORK_SEND;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_SCREEN_CAP;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_STORE_WRITE;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_TOOL_CREATE;
import static net.marcloud.mcp.core.security.CapabilitySid.CAP_WORLD_READ;

import java.util.Map;
import java.util.Set;

/**
 * L5 declared required-capability sets for the built-in tools — the direct
 * analog of {@link Ring} {@code BUILTIN_RINGS}. A tool not listed here touches no
 * gated resource class, so it requires no capability (still ring-gated at L2).
 *
 * <p>As Phase-2 tools land they are added here (retransform_* →
 * CAP_CLASS_RETRANSFORM; field taps → CAP_MEMORY_READ; mutate → CAP_MEMORY_WRITE;
 * debugger ops → CAP_DEBUG_CONTROL; seam_* → CAP_SEAM_INJECT).
 */
public final class CapabilityCatalog {

    private CapabilityCatalog() {
    }

    private static final Map<String, Set<CapabilitySid>> REQUIRED = Map.ofEntries(
            // observe
            Map.entry("read_player_state", Set.of(CAP_WORLD_READ)),
            Map.entry("scan_surroundings", Set.of(CAP_WORLD_READ)),
            Map.entry("capture_screen", Set.of(CAP_SCREEN_CAP)),
            Map.entry("recent_packets", Set.of(CAP_NETWORK_RECV_TAP)),
            Map.entry("disconnect_report", Set.of(CAP_NETWORK_RECV_TAP)),
            // outward network effects
            Map.entry("send_chat", Set.of(CAP_NETWORK_SEND)),
            Map.entry("send_raw_packet", Set.of(CAP_NETWORK_SEND)),
            // synthesize / redefine
            Map.entry("eval_java", Set.of(CAP_TOOL_CREATE)),
            Map.entry("create_tool", Set.of(CAP_TOOL_CREATE)),
            Map.entry("rollback_tool", Set.of(CAP_TOOL_CREATE)),
            Map.entry("redefine_class", Set.of(CAP_CLASS_REDEFINE)),
            // durable store
            Map.entry("memory_write", Set.of(CAP_STORE_WRITE)),
            Map.entry("memory_delete", Set.of(CAP_STORE_WRITE)));

    /** AI-authored tools default to observe-tier (they reach state via GameBridge at R2). */
    public static final Set<CapabilitySid> DEFAULT_GENERATED = Set.of(CAP_WORLD_READ, CAP_MEMORY_READ);

    /**
     * Required capability set for a tool. Unlisted built-ins require nothing;
     * unlisted AI-authored tools default to observe-tier.
     */
    public static Set<CapabilitySid> requiredFor(String toolName, boolean builtIn) {
        Set<CapabilitySid> r = REQUIRED.get(toolName);
        if (r != null) {
            return r;
        }
        return builtIn ? Set.of() : DEFAULT_GENERATED;
    }
}
