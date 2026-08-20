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

    /**
     * The explicit set of tools that put a packet on the wire — the authoritative,
     * <b>prefix-independent</b> answer to "does this tool send network traffic?".
     * Every member MUST carry {@link Privilege#SE_NET_RAW} (so
     * {@code disable_privilege(SE_NET_RAW)} is a real kill switch for the whole send
     * surface) and write at HIGH integrity; conversely every SE_NET_RAW tool MUST be
     * listed here. Both directions are pinned by {@code PolicySideTableDriftTest}.
     *
     * <p>This replaces the old {@code name.startsWith("send_")} heuristic, which
     * silently stopped matching when the typed tools were renamed to the {@code do_}
     * family — a prefix is not a security property. Membership is declared, not inferred.
     */
    static final Set<String> NETWORK_SEND_TOOLS = Set.of(
            // NOTE: send_raw_packet is deliberately NOT here. It compiles + runs caller
            // Java (eval-class) and is gated as code-exec (SE_CREATE_TOOL), not SE_NET_RAW,
            // so the bidirectional SE_NET_RAW invariant must not expect it. The pure typed
            // senders below are the SE_NET_RAW surface.
            "send_chat",
            "do_client_status",
            "do_select_slot",
            "do_close_container",
            "do_dig",
            "do_set_abilities",
            "do_place_block",
            "do_click_slot",
            "do_set_creative_slot",
            "do_use_entity",
            "do_entity_action");

    /**
     * The declared network-send tool set (see {@link #NETWORK_SEND_TOOLS}). Public
     * because ToolRegistry-side tests in another package check registered tools
     * against it; the L3/L4 key views stay package-private (drift test is same-package).
     */
    public static Set<String> networkSendTools() {
        return NETWORK_SEND_TOOLS;
    }

    // ---- L3: the integrity of the resource each mutating tool writes ----
    // Read-only / bookkeeping tools are absent (writesResourceAt == null → no L3 gate).
    private static final java.util.Map<String, IntegrityLevel> L3_WRITES = java.util.Map.ofEntries(
            java.util.Map.entry("redefine_class", IntegrityLevel.HIGH),   // rewrites net.minecraft.* (HIGH)
            java.util.Map.entry("eval_java", IntegrityLevel.SYSTEM),      // arbitrary in-proc code
            // send_raw_packet compiles + runs caller Java (eval-class), so it writes at
            // SYSTEM like eval_java — NOT the HIGH network-connection tier of the typed
            // do_* senders. See Ring.java + L4 below (SE_CREATE_TOOL) + NETWORK_SEND_TOOLS.
            java.util.Map.entry("send_raw_packet", IntegrityLevel.SYSTEM),
            java.util.Map.entry("send_chat", IntegrityLevel.HIGH),
            // typed do_* tools (W6/W7): outward packet sends, same integrity as send_raw_packet
            java.util.Map.entry("do_client_status", IntegrityLevel.HIGH),
            java.util.Map.entry("do_select_slot", IntegrityLevel.HIGH),
            java.util.Map.entry("do_close_container", IntegrityLevel.HIGH),
            java.util.Map.entry("do_dig", IntegrityLevel.HIGH),
            java.util.Map.entry("do_set_abilities", IntegrityLevel.HIGH),
            java.util.Map.entry("do_place_block", IntegrityLevel.HIGH),
            java.util.Map.entry("do_click_slot", IntegrityLevel.HIGH),
            java.util.Map.entry("do_set_creative_slot", IntegrityLevel.HIGH),
            java.util.Map.entry("do_use_entity", IntegrityLevel.HIGH),
            java.util.Map.entry("do_entity_action", IntegrityLevel.HIGH),
            // GUI interaction drives real handlers (windowClick → server, button actions)
            java.util.Map.entry("gui_click_element", IntegrityLevel.HIGH),
            java.util.Map.entry("gui_type_text", IntegrityLevel.HIGH),
            java.util.Map.entry("gui_press_key", IntegrityLevel.HIGH),
            java.util.Map.entry("act_set", IntegrityLevel.HIGH),
            java.util.Map.entry("act_plan", IntegrityLevel.HIGH),
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
            // send_raw_packet compiles + runs caller Java = code-exec, gated like
            // eval_java (SE_CREATE_TOOL), NOT SE_NET_RAW. disable_privilege(SE_CREATE_TOOL)
            // — the code-exec kill switch — must shut it off, same as eval_java/create_tool.
            java.util.Map.entry("send_raw_packet", Privilege.SE_CREATE_TOOL),
            java.util.Map.entry("send_chat", Privilege.SE_NET_RAW),
            // typed do_* tools: they put packets on the wire exactly like
            // send_raw_packet, so disable_privilege(SE_NET_RAW) MUST shut them off too.
            // Membership is also asserted structurally via NETWORK_SEND_TOOLS below.
            java.util.Map.entry("do_client_status", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_select_slot", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_close_container", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_dig", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_set_abilities", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_place_block", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_click_slot", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_set_creative_slot", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_use_entity", Privilege.SE_NET_RAW),
            java.util.Map.entry("do_entity_action", Privilege.SE_NET_RAW),
            java.util.Map.entry("gui_click_element", Privilege.SE_GUI_INTERACT),
            java.util.Map.entry("gui_type_text", Privilege.SE_GUI_INTERACT),
            java.util.Map.entry("gui_press_key", Privilege.SE_GUI_INTERACT),
            // act_set/act_plan/act_cancel drive the live player (move/look/dig/attack) — they
            // produce server-visible world/player state, so they need SE_WORLD_WRITE
            // enabled. Same class of gap the W6 send_* tools had: HIGH writers (L3) that
            // shipped with no L4 privilege, so disable_privilege had no kill switch for
            // the actuation surface. SE_NET_RAW is wrong here (that gates raw packet
            // crafting); SE_GUI_INTERACT is wrong too (that gates the GUI-widget surface).
            java.util.Map.entry("act_set", Privilege.SE_WORLD_WRITE),
            java.util.Map.entry("act_plan", Privilege.SE_WORLD_WRITE),
            java.util.Map.entry("act_cancel", Privilege.SE_WORLD_WRITE),
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
