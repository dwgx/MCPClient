package net.marcloud.mcp.core.se;

import java.util.Map;

/**
 * Protection rings for MCP tools, modeled on CPU privilege levels (R-1 hypervisor
 * … R3 user). <b>Lower ring number = higher privilege = more dangerous.</b> A tool
 * may run only when the system's current clearance is at least as privileged as
 * the tool's ring (i.e. {@code clearance.level <= tool.level}).
 *
 * <ul>
 *   <li><b>R_MINUS_1 (HYPERVISOR)</b> — run/redefine arbitrary code inside the
 *       game JVM (eval_java, redefine_class). Ultimate power; can rewrite the
 *       running game.</li>
 *   <li><b>R0 (KERNEL)</b> — modify the agent's own tool set (create_tool,
 *       rollback_tool). Self-modification.</li>
 *   <li><b>R1 (SYSTEM)</b> — outward effects on the game/network (send_raw_packet,
 *       send_chat). Changes shared/server-visible state.</li>
 *   <li><b>R2 (OBSERVE)</b> — reads live game/GL state on the game thread
 *       (scan_surroundings, capture_screen, read_player_state). Can stall the game
 *       thread. AI-authored tools default here.</li>
 *   <li><b>R3 (USER)</b> — local, read-only / bookkeeping (recent_packets,
 *       disconnect_report, memory_*, narrative_*, introspection). Safest.</li>
 * </ul>
 *
 * <p><b>Honest boundary:</b> rings gate <i>named tools</i> by declared privilege;
 * they are not a code sandbox. eval_java (and any generated tool that reaches
 * game internals) is arbitrary code — which is exactly why those live at R-1/R0
 * and are the tools a lowered clearance actually locks out.
 */
public enum Ring {

    R_MINUS_1(-1, "HYPERVISOR"),
    R0(0, "KERNEL"),
    R1(1, "SYSTEM"),
    R2(2, "OBSERVE"),
    R3(3, "USER");

    private final int level;
    private final String label;

    Ring(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public int level() {
        return level;
    }

    public String label() {
        return label;
    }

    /** Human tag like "R-1 HYPERVISOR" / "R2 OBSERVE". */
    public String tag() {
        return "R" + level + " " + label;
    }

    /**
     * Default ring for AI-authored (create_tool / eval) tools: <b>hypervisor</b>.
     * Generated Java runs in-process and can reach any R-1 capability (reflection,
     * Instrumentation, Unsafe), so it is exactly as dangerous as {@code eval_java}
     * and must sit at the same ring. A lowered clearance then genuinely locks it
     * out. (Was R2 — that let generated code execute at observe-level with no
     * privilege gate, collapsing the ring model. See SeToolRequirement's generated-tool
     * hard gate.)
     */
    public static final Ring DEFAULT_GENERATED = R_MINUS_1;

    /** Ring assigned to a built-in tool by name; falls back to {@code fallback}. */
    public static Ring forBuiltin(String toolName, Ring fallback) {
        Ring r = BUILTIN_RINGS.get(toolName);
        return r != null ? r : fallback;
    }

    /** Read-only view of the declared built-in ring names (for drift-guard tests). */
    static java.util.Set<String> declaredBuiltinNames() {
        return BUILTIN_RINGS.keySet();
    }

    /**
     * Declared rings for the built-in tools. Anything not listed is treated as
     * R3 (safest) by callers via {@link #forBuiltin}.
     */
    private static final Map<String, Ring> BUILTIN_RINGS = Map.ofEntries(
            // R-1 hypervisor: arbitrary code / rewrite the running game
            Map.entry("eval_java", R_MINUS_1),
            Map.entry("redefine_class", R_MINUS_1),
            // send_raw_packet compiles + reflectively runs caller-supplied Java (loadNew
            // + run()) — the SAME arbitrary-in-proc-code power class as eval_java, not a
            // mere network-effect tool. It is gated as code-exec (R-1 + SYSTEM +
            // SE_CREATE_TOOL + CAP_TOOL_CREATE), NOT at the weaker R1/SE_NET_RAW send
            // tier the typed do_* tools use. (The board PacketSendSignal veto still fires
            // on the actual send at runtime; that is an advisory layer, not the gate.)
            Map.entry("send_raw_packet", R_MINUS_1),
            // R0 kernel: modify the agent's own tools
            Map.entry("create_tool", R0),
            Map.entry("rollback_tool", R0),
            // R1 system: outward game/network effects
            Map.entry("send_chat", R1),
            // typed do_* tools (W6/W7): outward network effects, same ring as send_raw_packet
            Map.entry("do_client_status", R1),
            Map.entry("do_select_slot", R1),
            Map.entry("do_close_container", R1),
            Map.entry("do_dig", R1),
            Map.entry("do_set_abilities", R1),
            Map.entry("do_place_block", R1),
            Map.entry("do_click_slot", R1),
            Map.entry("do_set_creative_slot", R1),
            Map.entry("do_use_entity", R1),
            Map.entry("do_entity_action", R1),
            // R2 observe: live game/GL reads on the game thread
            Map.entry("scan_surroundings", R2),
            Map.entry("world_view", R2),
            Map.entry("find_block", R2),
            // craft_plan reads the static recipe table plus the live inventory and mutates
            // nothing, so it sits with the other observers rather than with the actuators.
            Map.entry("craft_plan", R2),
            Map.entry("act_set", R1),
            Map.entry("act_cancel", R1),
            Map.entry("act_status", R3),
            Map.entry("capture_screen", R2),
            Map.entry("read_player_state", R2),
            Map.entry("gui_snapshot", R2),
            Map.entry("gui_snapshot_image", R2),
            Map.entry("gui_trajectory", R3),
            // R1 system: GUI interaction drives real handlers → server-visible effects
            Map.entry("gui_click_element", R1),
            Map.entry("gui_type_text", R1),
            Map.entry("gui_press_key", R1),
            // R0 kernel: self privilege/capability management (enable/disable/grant/revoke)
            Map.entry("enable_privilege", R0),
            Map.entry("disable_privilege", R0),
            Map.entry("grant_capability", R0),
            Map.entry("revoke_capability", R0),
            // R3 user: local read-only / bookkeeping
            Map.entry("recent_packets", R3),
            Map.entry("disconnect_report", R3),
            Map.entry("list_capabilities", R3),
            Map.entry("get_tool_source", R3),
            Map.entry("memory_write", R3),
            Map.entry("memory_search", R3),
            Map.entry("memory_delete", R3),
            Map.entry("set_goal", R3),
            Map.entry("push_subgoal", R3),
            Map.entry("complete_goal", R3),
            Map.entry("narrate", R3),
            Map.entry("get_story", R3),
            // compat: read-only view of loaded startup patches
            Map.entry("list_compat_patches", R3),
            // PHASE T observe: read-only timeline spine
            Map.entry("clock_now", R3),
            Map.entry("timeline_tail", R3),
            Map.entry("packets_tail", R3),
            Map.entry("packet_get", R3),
            Map.entry("packet_view", R3),
            // permission tools themselves
            Map.entry("drop_privilege", R3),
            Map.entry("restore_privilege", R3),
            Map.entry("list_permissions", R3),
            // ---- Phase 2 capability tools ----
            // C1 INTROSPECT: read-only self-model
            Map.entry("list_classes", R3),
            Map.entry("describe_class", R3),
            Map.entry("find_method", R3),
            Map.entry("list_hooks", R3),
            // C3 INTERCEPT: install=hypervisor (hook any method), uninstall=kernel
            Map.entry("install_hook", R_MINUS_1),
            Map.entry("uninstall_hook", R0),
            // C5 MUTATE-STATE: read=kernel, write/invoke/module=hypervisor
            Map.entry("read_field", R0),
            Map.entry("write_field", R_MINUS_1),
            Map.entry("invoke_method", R_MINUS_1),
            Map.entry("open_module", R_MINUS_1),
            // C7 SYNTHESIZE: arbitrary code (hidden class) = hypervisor
            Map.entry("eval_ephemeral", R_MINUS_1),
            // C8 SEAM: runtime MITM injection = hypervisor
            Map.entry("seam_netty_install", R_MINUS_1),
            Map.entry("seam_netty_uninstall", R0),
            Map.entry("seam_glfw_key_hook", R_MINUS_1),
            Map.entry("seam_glfw_mouse_hook", R_MINUS_1),
            Map.entry("seam_tick_enable", R_MINUS_1),
            Map.entry("seam_tick_disable", R0),
            // C6 CONTROL-EXEC: native JVMTI debugger — pause/rewrite live thread
            // state, strictly hypervisor.
            Map.entry("debug_suspend_thread", R_MINUS_1),
            Map.entry("debug_pop_frame", R_MINUS_1),
            Map.entry("debug_force_return", R_MINUS_1),
            Map.entry("debug_set_breakpoint", R_MINUS_1),
            Map.entry("debug_clear_breakpoint", R_MINUS_1),
            Map.entry("debug_single_step", R_MINUS_1),
            Map.entry("debug_read_local", R_MINUS_1),
            Map.entry("debug_write_local", R_MINUS_1),
            Map.entry("debug_watch_field", R_MINUS_1),
            // C6 L6 handle lifecycle (only registered when the object-handle layer
            // is wired): open/close a handle over a thread. Kernel-level self-mgmt,
            // no native agent, no thread control by themselves.
            Map.entry("debug_open_thread", R0),
            Map.entry("debug_close_handle", R0));
}
