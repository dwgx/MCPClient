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
 *       game JVM (eval_java, redefine_class, <b>send_raw_packet</b>). Ultimate
 *       power; can rewrite the running game.</li>
 *   <li><b>R0 (KERNEL)</b> — modify the agent's own tool set (create_tool,
 *       rollback_tool). Self-modification.</li>
 *   <li><b>R1 (SYSTEM)</b> — outward effects on the game/network (send_chat, the
 *       typed {@code do_*} senders, act_set). Changes shared/server-visible
 *       state.</li>
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
            // A write that moves items: the same exposure as do_click_slot, and gated the same
            // way. Its distinguishing property is that it CONFIRMS against the server's container,
            // which does not lower the ring -- reading the world back is how it avoids lying, not
            // an extra privilege.
            Map.entry("transfer_item", R1),
            Map.entry("do_set_creative_slot", R1),
            Map.entry("do_use_entity", R1),
            Map.entry("do_entity_action", R1),
            // Spends the player's lapis and XP and writes an enchantment into the server's copy
            // of the item. Outward network effect, same tier as the rest of the typed do_* tools.
            Map.entry("do_enchant_item", R1),
            // R2 observe: live game/GL reads on the game thread
            Map.entry("scan_surroundings", R2),
            Map.entry("world_view", R2),
            Map.entry("find_block", R2),
            // A read of one loaded cell: the same exposure as world_view, which reads a whole
            // region of the same world. Ring-only, so no L3/L4 row -- it writes nothing and asks
            // for no privilege.
            Map.entry("inspect_block", R2),
            // Reads identity and connection state the client already holds: the same exposure as
            // read_player_state, which sits at R3. Ring-only -- it writes nothing and asks for no
            // privilege, so it needs no L3 or L4 row.
            Map.entry("server_info", R3),
            // Opens the project's own overlay panel. It changes what is on screen and nothing
            // else -- no packet leaves the client, no world state is written -- so R3 with no L3
            // or L4 row, like the other screen-affecting tools.
            Map.entry("open_overlay", R3),
            // Opens the ESC menu: the root of the pause/options tree. Same shape as
            // open_overlay -- it changes what is on screen (and, in singleplayer, pauses the
            // world, which is the pause menu's own vanilla behaviour) but sends no packet and
            // writes no world state, so R3 with no L3 or L4 row. Everything below it is driven by
            // gui_click_element, which keeps its own R1 ring and SE_GUI_INTERACT gate.
            Map.entry("open_pause_menu", R3),
            // craft_plan reads the static recipe table plus the live inventory and mutates
            // nothing, so it sits with the other observers rather than with the actuators.
            Map.entry("craft_plan", R2),
            // dev_probe reads live connection/world/GL state on the game thread — an observer
            // like the three above, and it REGISTERS as R2 (DevTools passes an R2 fallback, the
            // REST catalog lists it as R2). It must be declared here too: enforcement re-derives
            // the ring by name with an R3 fallback, so leaving it out did not merely mislabel it
            // — a subject dropped to R3 could still probe the live game while every visible
            // surface said R2 (audit H8).
            Map.entry("dev_probe", R2),
            Map.entry("act_set", R1),
            // Same family as act_set: it drives the live player through vanilla's own
            // KeyBinding dispatch, so it gets the same ring rather than a name-shaped guess.
            Map.entry("press_key_binding", R1),
            Map.entry("act_plan", R1),
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
            // The shelter counter: a read of integers the tick seam already published. It takes
            // no world read and makes no game-thread marshal at call time, so R3 like the other
            // ledger readers rather than R2 like the tools that sample live state.
            Map.entry("night_shelter", R3),
            // The other two north-star rulers, on the same reasoning as night_shelter above:
            // both are LEDGER readers over integers and floats the tick seam already published.
            // Neither marshals onto the game thread at call time, neither reads the world at
            // call time, and both are read-only, so R3 rather than R2.
            Map.entry("night_health", R3),
            Map.entry("night_box", R3),
            Map.entry("packet_view", R3),
            // Inbound chat reader over the SAME Netty-tap feed as packet_view: it
            // re-reads the tap's typed S02PacketChat projection out of the ChatLog
            // ring. R3 like its siblings (local, read-only) — reading chat grants no
            // more than reading the packet it was carried in.
            Map.entry("chat_read", R3),
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
            // Folded manifest entries (ADR-0004). These are the names the agent actually calls.
            // The declared ring is the MAXIMUM over the cluster, and the cluster boundary is
            // drawn at the ring boundary precisely so that maximum equals every member's ring.
            //
            // The eleven concrete names BELOW are deliberately kept. Once a name is absent
            // from this table, forBuiltin() falls back to R3 -- so dropping "debug_read_local"
            // when the manifest stopped listing it would silently hand a hypervisor tool to
            // user clearance. That is the exact failure the ring model exists to prevent, and
            // it is why this table is keyed by the union of called AND callable names.
            Map.entry("debug_manage", R_MINUS_1),
            Map.entry("debug_handle", R0),
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
            // is wired): open/close a handle over a thread. Kernel-level self-mgmt
            // (R0, not R-1): these mint no thread control by themselves, but they now
            // carry the handle-op family's L3/L4/L5 rows and run the same guard()
            // preamble as the nine ops above — so the debugger must be present and
            // SE_DEBUG_CONTROL/CAP_DEBUG_CONTROL must be held (audit H9).
            // Kept alongside "debug_handle" above. The folded tool is what the manifest lists,
            // but forBuiltin() must keep answering for the concrete names too, or a stale or
            // hand-rolled call to debug_open_thread would resolve to the R3 fallback.
            Map.entry("debug_open_thread", R0),
            Map.entry("debug_close_handle", R0));
}
