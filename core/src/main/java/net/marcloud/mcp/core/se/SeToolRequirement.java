package net.marcloud.mcp.core.se;

import java.util.Set;

/**
 * The per-tool security requirement, resolved by name from side tables rather
 * than stored on the {@link net.marcloud.mcp.core.io.Capability} record
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
public record SeToolRequirement(Ring requiredRing,
                         IntegrityLevel writesResourceAt,
                         Privilege requiredPrivilege,
                         Set<CapabilitySid> requiredCaps) {

    public SeToolRequirement {
        requiredCaps = (requiredCaps == null) ? Set.of() : Set.copyOf(requiredCaps);
    }

    /**
     * The requirement for a tool, composed from the standing tables. Unlisted
     * tools get only their ring requirement (L3 no-write, no privilege, plus the
     * capability set which is empty for unlisted built-ins). This is what keeps
     * the pre-Phase-2 behavior identical for every existing tool.
     */
    public static SeToolRequirement forTool(String toolName, boolean builtIn) {
        // Generated (AI-authored, !builtIn) tools are arbitrary in-process Java: the
        // handler runs via reflection in this JVM and can reach any R-1 capability
        // (Instrumentation, Unsafe, reflection into core.agent). It is therefore
        // exactly as dangerous as eval_java and MUST carry the maximal gate,
        // UNCONDITIONALLY — never derived from the by-name side tables, which only
        // list known built-ins and would leave an unlisted generated name ungated
        // (the ring-model-collapse the audit found). This closes CRITICAL#1.
        if (!builtIn) {
            return new SeToolRequirement(Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                    Privilege.SE_RUN_GENERATED, Set.of(CapabilitySid.CAP_TOOL_CREATE));
        }
        Ring ring = Ring.forBuiltin(toolName, Ring.R3);
        IntegrityLevel writes = L3_WRITES.get(toolName);
        Privilege priv = L4_PRIVILEGE.get(toolName);
        Set<CapabilitySid> caps = CapabilityCatalog.requiredFor(toolName, builtIn);
        return new SeToolRequirement(ring, writes, priv, caps);
    }

    /** Read-only views of the L3/L4 side-table keys (for drift-guard tests). */
    static Set<String> l3WriteNames() {
        return L3_WRITES.keySet();
    }

    static Set<String> l4PrivilegeNames() {
        return L4_PRIVILEGE.keySet();
    }

    // ---- L3: the integrity of the resource each mutating tool writes ----
    // Read-only / bookkeeping tools are absent (writesResourceAt == null → no L3 gate).
    private static final java.util.Map<String, IntegrityLevel> L3_WRITES = java.util.Map.ofEntries(
            java.util.Map.entry("redefine_class", IntegrityLevel.HIGH),   // rewrites net.minecraft.* (HIGH)
            java.util.Map.entry("eval_java", IntegrityLevel.SYSTEM),      // arbitrary in-proc code
            java.util.Map.entry("send_raw_packet", IntegrityLevel.HIGH),  // network connection
            java.util.Map.entry("send_chat", IntegrityLevel.HIGH),
            // typed send_* tools (W6): outward packet sends, same integrity as send_raw_packet
            java.util.Map.entry("send_client_status", IntegrityLevel.HIGH),
            java.util.Map.entry("send_held_item", IntegrityLevel.HIGH),
            java.util.Map.entry("send_close_window", IntegrityLevel.HIGH),
            java.util.Map.entry("send_dig", IntegrityLevel.HIGH),
            // GUI interaction drives real handlers (windowClick → server, button actions)
            java.util.Map.entry("gui_click_element", IntegrityLevel.HIGH),
            java.util.Map.entry("gui_type_text", IntegrityLevel.HIGH),
            java.util.Map.entry("gui_press_key", IntegrityLevel.HIGH),
            java.util.Map.entry("act_set", IntegrityLevel.HIGH),
            java.util.Map.entry("act_cancel", IntegrityLevel.HIGH),
            java.util.Map.entry("create_tool", IntegrityLevel.MEDIUM_PLUS),
            java.util.Map.entry("rollback_tool", IntegrityLevel.MEDIUM_PLUS),
            java.util.Map.entry("memory_write", IntegrityLevel.LOW),
            java.util.Map.entry("memory_delete", IntegrityLevel.LOW),
            // LOW#16: narrative mutators write LOW-integrity story/goal state
            java.util.Map.entry("set_goal", IntegrityLevel.LOW),
            java.util.Map.entry("push_subgoal", IntegrityLevel.LOW),
            java.util.Map.entry("complete_goal", IntegrityLevel.LOW),
            java.util.Map.entry("narrate", IntegrityLevel.LOW),
            // ---- Phase 2 mutating tools ----
            java.util.Map.entry("install_hook", IntegrityLevel.HIGH),     // rewrites game class bytecode
            java.util.Map.entry("uninstall_hook", IntegrityLevel.HIGH),
            java.util.Map.entry("write_field", IntegrityLevel.HIGH),      // mutates live game state
            java.util.Map.entry("invoke_method", IntegrityLevel.HIGH),
            java.util.Map.entry("open_module", IntegrityLevel.SYSTEM),    // cracks module boundaries
            java.util.Map.entry("eval_ephemeral", IntegrityLevel.SYSTEM), // arbitrary in-proc code
            java.util.Map.entry("seam_netty_install", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_netty_uninstall", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_glfw_key_hook", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_glfw_mouse_hook", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_tick_enable", IntegrityLevel.HIGH),
            java.util.Map.entry("seam_tick_disable", IntegrityLevel.HIGH),
            // C6 native debugger — thread control is the most invasive write class
            java.util.Map.entry("debug_suspend_thread", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_pop_frame", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_force_return", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_set_breakpoint", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_clear_breakpoint", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_single_step", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_read_local", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_write_local", IntegrityLevel.SYSTEM),
            java.util.Map.entry("debug_watch_field", IntegrityLevel.SYSTEM));

    // ---- L4: the privilege each dangerous verb requires enabled ----
    private static final java.util.Map<String, Privilege> L4_PRIVILEGE = java.util.Map.ofEntries(
            java.util.Map.entry("redefine_class", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("eval_java", Privilege.SE_CREATE_TOOL),  // HIGH#4: arbitrary in-proc code needs L4
            java.util.Map.entry("send_raw_packet", Privilege.SE_NET_RAW),
            java.util.Map.entry("send_chat", Privilege.SE_NET_RAW),
            // typed send_* tools: they put packets on the wire exactly like
            // send_raw_packet, so disable_privilege(SE_NET_RAW) MUST shut them off too.
            java.util.Map.entry("send_client_status", Privilege.SE_NET_RAW),
            java.util.Map.entry("send_held_item", Privilege.SE_NET_RAW),
            java.util.Map.entry("send_close_window", Privilege.SE_NET_RAW),
            java.util.Map.entry("send_dig", Privilege.SE_NET_RAW),
            java.util.Map.entry("gui_click_element", Privilege.SE_GUI_INTERACT),
            java.util.Map.entry("gui_type_text", Privilege.SE_GUI_INTERACT),
            java.util.Map.entry("gui_press_key", Privilege.SE_GUI_INTERACT),
            java.util.Map.entry("create_tool", Privilege.SE_CREATE_TOOL),
            java.util.Map.entry("rollback_tool", Privilege.SE_CREATE_TOOL),
            java.util.Map.entry("capture_screen", Privilege.SE_SCREEN_CAP),
            java.util.Map.entry("gui_snapshot_image", Privilege.SE_SCREEN_CAP),
            // ---- Phase 2 dangerous verbs ----
            java.util.Map.entry("install_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("uninstall_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("write_field", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("invoke_method", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("open_module", Privilege.SE_DEBUG_CLASS),
            java.util.Map.entry("eval_ephemeral", Privilege.SE_CREATE_TOOL),
            java.util.Map.entry("seam_netty_install", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_netty_uninstall", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_glfw_key_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_glfw_mouse_hook", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_tick_enable", Privilege.SE_SEAM_INJECT),
            java.util.Map.entry("seam_tick_disable", Privilege.SE_SEAM_INJECT),
            // C6 native debugger — all require SE_DEBUG_CONTROL enabled
            java.util.Map.entry("debug_suspend_thread", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_pop_frame", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_force_return", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_set_breakpoint", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_clear_breakpoint", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_single_step", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_read_local", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_write_local", Privilege.SE_DEBUG_CONTROL),
            java.util.Map.entry("debug_watch_field", Privilege.SE_DEBUG_CONTROL));
}
