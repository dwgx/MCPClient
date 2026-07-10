package net.marcloud.mcp.core.security;

import java.util.Set;

/**
 * The per-tool security requirement, resolved by name from side tables rather
 * than stored on the {@link net.marcloud.mcp.core.registry.Capability} record
 * (keeps the record stable and lets requirements evolve independently).
 *
 * <p>A tool's requirement is the AND of all present dimensions:
 * <ul>
 *   <li>{@code requiredRing} (L2) — always present (from {@link Ring}).</li>
 *   <li>{@code writesResourceAt} (L3) — the integrity of the resource this tool
 *       WRITES, or {@code null} if it writes nothing (read-only / bookkeeping).</li>
 *   <li>{@code requiredPrivilege} (L4) — a privilege that must be enabled, or null.</li>
 *   <li>{@code requiredCaps} (L5) — capability SIDs the subject must all hold.</li>
 * </ul>
 *
 * <p>{@link #forTool} composes the requirement for a tool from the existing
 * {@link Ring} and {@link CapabilityCatalog} tables plus a small L3/L4 map, with
 * <b>safe defaults</b> so an unlisted tool enforces only its ring — preserving
 * today's behavior exactly.
 *
 * @param requiredRing     the ring the subject's clearance must reach (L2)
 * @param writesResourceAt integrity of the written resource (L3), or null
 * @param requiredPrivilege privilege that must be enabled (L4), or null
 * @param requiredCaps     capability SIDs the subject must hold (L5)
 */
public record ToolPolicy(Ring requiredRing,
                         IntegrityLevel writesResourceAt,
                         Privilege requiredPrivilege,
                         Set<CapabilitySid> requiredCaps) {

    public ToolPolicy {
        requiredCaps = (requiredCaps == null) ? Set.of() : Set.copyOf(requiredCaps);
    }

    /**
     * The requirement for a tool, composed from the standing tables. Unlisted
     * tools get only their ring requirement (L3 no-write, no privilege, plus the
     * capability set which is empty for unlisted built-ins). This is what keeps
     * the pre-Phase-2 behavior identical for every existing tool.
     */
    public static ToolPolicy forTool(String toolName, boolean builtIn) {
        Ring ring = Ring.forBuiltin(toolName, builtIn ? Ring.R3 : Ring.DEFAULT_GENERATED);
        IntegrityLevel writes = L3_WRITES.get(toolName);
        Privilege priv = L4_PRIVILEGE.get(toolName);
        Set<CapabilitySid> caps = CapabilityCatalog.requiredFor(toolName, builtIn);
        return new ToolPolicy(ring, writes, priv, caps);
    }

    // ---- L3: the integrity of the resource each mutating tool writes ----
    // Read-only / bookkeeping tools are absent (writesResourceAt == null → no L3 gate).
    private static final java.util.Map<String, IntegrityLevel> L3_WRITES = java.util.Map.ofEntries(
            java.util.Map.entry("redefine_class", IntegrityLevel.HIGH),   // rewrites net.minecraft.* (HIGH)
            java.util.Map.entry("eval_java", IntegrityLevel.SYSTEM),      // arbitrary in-proc code
            java.util.Map.entry("send_raw_packet", IntegrityLevel.HIGH),  // network connection
            java.util.Map.entry("send_chat", IntegrityLevel.HIGH),
            java.util.Map.entry("create_tool", IntegrityLevel.MEDIUM_PLUS),
            java.util.Map.entry("rollback_tool", IntegrityLevel.MEDIUM_PLUS),
            java.util.Map.entry("memory_write", IntegrityLevel.LOW),
            java.util.Map.entry("memory_delete", IntegrityLevel.LOW),
            // ---- Phase 2 mutating tools ----
            java.util.Map.entry("install_hook", IntegrityLevel.HIGH),     // rewrites game class bytecode
            java.util.Map.entry("uninstall_hook", IntegrityLevel.HIGH),
            java.util.Map.entry("write_field", IntegrityLevel.HIGH),      // mutates live game state
            java.util.Map.entry("invoke_method", IntegrityLevel.HIGH),
            java.util.Map.entry("open_module", IntegrityLevel.SYSTEM),    // cracks module boundaries
            java.util.Map.entry("eval_ephemeral", IntegrityLevel.SYSTEM), // arbitrary in-proc code
            java.util.Map.entry("seam_netty_install", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_glfw_key_hook", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_glfw_mouse_hook", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_tick_enable", IntegrityLevel.HIGH));

    // ---- L4: the privilege each dangerous verb requires enabled ----
    private static final java.util.Map<String, Privilege> L4_PRIVILEGE = java.util.Map.ofEntries(
            java.util.Map.entry("redefine_class", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("send_raw_packet", Privilege.SE_NET_RAW),
            java.util.Map.entry("create_tool", Privilege.SE_CREATE_TOOL),
            java.util.Map.entry("capture_screen", Privilege.SE_SCREEN_CAP),
            // ---- Phase 2 dangerous verbs ----
            java.util.Map.entry("install_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("uninstall_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("write_field", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("invoke_method", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("open_module", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("eval_ephemeral", Privilege.SE_CREATE_TOOL),
            java.util.Map.entry("seam_netty_install", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_glfw_key_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_glfw_mouse_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_tick_enable", Privilege.SE_SEAM_INJECT));
}
