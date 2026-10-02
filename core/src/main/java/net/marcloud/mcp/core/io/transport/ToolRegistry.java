package net.marcloud.mcp.core.io.transport;

import net.marcloud.mcp.core.drivers.action.ActionManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.HookBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.drivers.world.PlayerState;
import net.marcloud.mcp.core.drivers.world.Surroundings;
import net.marcloud.mcp.core.drivers.world.WorldScanner;
import net.minecraft.network.Packet;

/**
 * Builds the set of MCP tools that expose the game to an AI. Each tool is a
 * {@link SyncToolSpecification}: a {@link Tool} (name/description/input schema)
 * plus a handler {@code (exchange, request) -> CallToolResult}.
 *
 * <p>First cut of the tool surface:
 * <ul>
 *   <li>{@code read_player_state} — observe player pos/health/etc.</li>
 *   <li>{@code recent_packets} — the packet-log ring (for "why kicked").</li>
 *   <li>{@code send_chat} — send a chat message / command.</li>
 *   <li>{@code eval_java} — compile + load + run arbitrary Java (the Kernel's REPL).</li>
 * </ul>
 * Arbitrary raw-packet send is exposed via {@code send_chat}'s sibling once
 * packet construction helpers land; the ActionManager already supports it.
 *
 * <h2>The two layers, and the rule that assigns a tool to one of them</h2>
 *
 * <p><b>THE RULE, in two questions, asked in this order.</b>
 * <ol>
 *   <li><b>Does the tool change the Kernel?</b> The Kernel is the JVM and its loaded classes,
 *       the bytecode hooks and the intercept seams, the 7-layer reference monitor, the
 *       capability registry itself, the native debug (JVMTI) bridge, and the agent's own
 *       startup patches. A tool that reaches into any of those is <b>KERNEL-LAYERED</b>.</li>
 *   <li><b>Otherwise, is its subject the Minecraft session?</b> The world, the connection to
 *       the server, the client's own screens and inputs, the player's inventory and hands, or
 *       the agent's notes about that session. Such a tool is <b>MODEL-FACING</b>.</li>
 * </ol>
 * <p><b>The default for a name nobody declared is KERNEL-LAYERED.</b> Deny by default: a new
 * provider wired into {@code McpCore.registerBuiltins} and nobody classified must not silently
 * become something the model can see and call. That default is not a licence to skip the
 * declaration, either &mdash; {@code ToolLayeringTest} requires every <i>registered</i> built-in
 * to carry an explicit row, precisely so the default can never be reached by a real tool.
 *
 * <p><b>Where the declarations are.</b> {@link #LAYERS}, below, is the single place to read the
 * whole split. Nothing else in the tree decides a layer: {@code McpCore.registerBuiltins} derives
 * what it feeds to the model-facing surface from {@link #layerOf(String)}, and the promotion
 * reader consults the same table. Adding a tool means adding a row here.
 *
 * <p><b>What this is and is not.</b> Layering is a <i>surface-shaping</i> control: it decides
 * what a name costs in the model's context and what it may reach for first. It is not a sandbox.
 * The sandbox term in this tree is the reference monitor ({@link net.marcloud.mcp.core.se.Ring}),
 * and it keeps applying to every tool on either layer, promoted or not &mdash; promotion changes
 * what a model can <i>see</i>, never what a subject is <i>allowed</i> to do.
  */
public final class ToolRegistry {

    /**
     * Which of the two surfaces a tool belongs to. See the class javadoc for the rule.
     *
     * <p>{@link #KERNEL} is the safe default for an undeclared name; see {@link #layerOf}.
     */
    public enum Layer {
        /** On the model-facing surface: advertised over MCP, callable by name. */
        GAME,
        /** Kernel-layered: registered and gated, but NOT advertised and NOT callable by name. */
        KERNEL
    }

    /**
     * The single declaration of the game/kernel split. Read this to know what the model sees.
     *
     * <p>Grouped by the provider that registers the names, and by the question-1 subject that
     * puts each group on the kernel side. Names are declared whether or not they are currently
     * registered: the eleven {@code debug_*} JVMTI action names are folded behind
     * {@code debug_manage}/{@code debug_handle} (ADR-0004) and are NOT registered, but they are
     * declared here anyway so that (a) a name-shaped probe cannot discover them by contrast and
     * (b) the reservation authority covers the whole kernel vocabulary rather than only the
     * handful that happens to be live.
     */
    private static final Map<String, Layer> LAYERS = buildLayers();

    private static Map<String, Layer> buildLayers() {
        Map<String, Layer> m = new java.util.LinkedHashMap<>();

        // ---- KERNEL: the capability registry as its own subject (MetaTools) ----
        // create_tool is here for a reason that is NOT "it is scary": it is the verb that
        // MANUFACTURES the kernel surface. Leaving it on the model surface while hiding
        // eval_java would be incoherent - the model can write the same three lines. See the
        // create_tool section of .ai-notes/docs/audits/2026-10-02-wave18-tool-layer.md.
        kernel(m, "get_tool_source", "create_tool", "rollback_tool", "redefine_class");

        // ---- KERNEL: the 7-layer reference monitor as its own subject ----
        kernel(m, "list_permissions", "drop_privilege", "restore_privilege",
                "enable_privilege", "disable_privilege", "grant_capability", "revoke_capability");

        // ---- KERNEL: the JVM's loaded classes (C1 introspect, C5 mutate-state) ----
        kernel(m, "list_classes", "describe_class", "find_method", "list_hooks",
                "read_field", "write_field", "invoke_method", "open_module");

        // ---- KERNEL: bytecode instrumentation (C3 hooks, C7 synthesize, C8 seams) ----
        kernel(m, "install_hook", "uninstall_hook", "eval_ephemeral",
                "seam_netty_install", "seam_netty_uninstall",
                "seam_glfw_key_hook", "seam_glfw_mouse_hook",
                "seam_tick_enable", "seam_tick_disable");

        // ---- KERNEL: the native debug (JVMTI) bridge (C6) ----
        // debug_manage/debug_handle are the two REGISTERED folds; the eleven names below are
        // their actions and are not registered at all (ADR-0004 folded them).
        kernel(m, "debug_manage", "debug_handle",
                "debug_suspend_thread", "debug_pop_frame", "debug_force_return",
                "debug_set_breakpoint", "debug_clear_breakpoint", "debug_single_step",
                "debug_read_local", "debug_write_local", "debug_watch_field",
                "debug_open_thread", "debug_close_handle");

        // ---- KERNEL: the agent's own REPL, and the packet sender that is code execution ----
        // send_raw_packet is here because Ring.java:91-97 already says what it is: it compiles
        // and reflectively runs caller-supplied Java, so it is the same arbitrary-in-proc-code
        // power class as eval_java, not a network-effect tool. The typed do_* senders are the
        // model-facing way to put a packet on the wire.
        kernel(m, "eval_java", "send_raw_packet");

        // ---- KERNEL: infrastructure diagnostics ----
        // Both report on the agent's own runtime (the GL context; the premain patch catalog),
        // not on the Minecraft session, so question 1 catches them.
        kernel(m, "dev_probe", "list_compat_patches");

        // ---- MODEL-FACING: ToolRegistry's own game surface (this file) ----
        // read_player_state, recent_packets, send_chat, disconnect_report, scan_surroundings,
        // world_view, find_block, inspect_block, craft_plan, capture_screen, server_info,
        // open_overlay, transfer_item, do_client_status, do_select_slot, do_close_container,
        // do_dig, do_set_abilities, do_place_block, do_set_creative_slot, do_click_slot,
        // do_use_entity, do_entity_action  (23 - the other two of all() are kernel, above)
        game(m, "read_player_state", "recent_packets", "send_chat", "disconnect_report",
                "scan_surroundings", "world_view", "find_block", "inspect_block", "craft_plan",
                "capture_screen", "server_info", "open_overlay", "transfer_item",
                "do_client_status", "do_select_slot", "do_close_container", "do_dig",
                "do_set_abilities", "do_place_block", "do_set_creative_slot", "do_click_slot",
                "do_use_entity", "do_entity_action");

        // ---- MODEL-FACING: the actuation layer (ActTools) ----
        game(m, "act_set", "act_plan", "act_cancel", "act_status", "press_key_binding");

        // ---- MODEL-FACING: the container/enchant/ESC doors ----
        game(m, "do_enchant_item", "open_pause_menu");

        // ---- MODEL-FACING: the GUI surface (GuiTools) ----
        game(m, "gui_snapshot", "gui_snapshot_image", "gui_click_element", "gui_type_text",
                "gui_press_key", "gui_trajectory");

        // ---- MODEL-FACING: reading the session's own observed traffic and chat ----
        game(m, "chat_read", "clock_now", "timeline_tail", "packets_tail", "packet_get",
                "packet_view");

        // ---- MODEL-FACING: the agent's notes about the session ----
        // Not the Kernel: a JSON file of what the model learned, and its own goal stack.
        game(m, "memory_write", "memory_search", "memory_delete",
                "set_goal", "push_subgoal", "complete_goal", "narrate", "get_story");

        // ---- MODEL-FACING: the one truthful "what can I do" verb (MetaTools) ----
        // Owner ruling, 2026-10-02: list_capabilities is model-facing even though its subject
        // is the capability registry itself, because MetaTools is HANDED the model-facing
        // registry (McpCore.java:553) and listCapabilities() iterates THAT registry's
        // capabilities(). So the one verb that answers "what can I do" lists exactly the set the
        // model can do - never a kernel name, which is exactly what a name filter is for.
        // The filter that makes that true is MetaTools.registerModelFacing; without it this row
        // would drag all five meta names onto the surface. See
        // .ai-notes/docs/audits/2026-10-02-wave19-layer-filter.md.
        game(m, "list_capabilities");

        return java.util.Collections.unmodifiableMap(m);
    }

    private static void kernel(Map<String, Layer> m, String... names) {
        for (String n : names) {
            Layer prev = m.put(n, Layer.KERNEL);
            if (prev != null) {
                throw new IllegalStateException("'" + n + "' is declared twice in LAYERS ("
                        + prev + " then KERNEL)");
            }
        }
    }

    private static void game(Map<String, Layer> m, String... names) {
        for (String n : names) {
            Layer prev = m.put(n, Layer.GAME);
            if (prev != null) {
                throw new IllegalStateException("'" + n + "' is declared twice in LAYERS ("
                        + prev + " then GAME)");
            }
        }
    }

    /**
     * The layer of {@code toolName}, or {@link Layer#KERNEL} when the name is not declared.
     *
     * <p>Deny by default, in the same spirit as {@code Ring.forBuiltin}'s R3 fallback and
     * {@code SeToolRequirement}'s safe defaults: a name this table has never heard of is not
     * something the model gets to see.
     */
    public static Layer layerOf(String toolName) {
        Layer l = LAYERS.get(toolName);
        return l == null ? Layer.KERNEL : l;
    }

    /** True when {@code toolName} is kernel-layered - i.e. hidden from the model surface. */
    public static boolean isKernelLayered(String toolName) {
        return layerOf(toolName) == Layer.KERNEL;
    }

    /**
     * True when this table <b>declares</b> {@code toolName} kernel-layered.
     *
     * <p><b>Not the same question as {@link #isKernelLayered(String)}, and the difference is
     * load-bearing.</b> That one denies by default: an undeclared name comes back {@link
     * Layer#KERNEL}. This one answers membership only, so a name nobody declared is {@code
     * false}. A consumer that must reserve the Kernel's own vocabulary without forbidding every
     * name in the world wants THIS one — {@code MetaTools}' reserved-name check is the case in
     * point, where "undeclared" has to mean "a genuinely new tool the model may create" and not
     * "kernel-layered".
     */
    public static boolean isDeclaredKernelLayered(String toolName) {
        return LAYERS.get(toolName) == Layer.KERNEL;
    }

    /** Every name this table declares, model-facing and kernel alike. */
    public static java.util.Set<String> declaredNames() {
        return LAYERS.keySet();
    }

    /** The declared model-facing names. */
    public static java.util.Set<String> modelFacingNames() {
        return namesWith(Layer.GAME);
    }

    /** The declared kernel-layered names, including names that are not registered. */
    public static java.util.Set<String> kernelLayeredNames() {
        return namesWith(Layer.KERNEL);
    }

    private static java.util.Set<String> namesWith(Layer layer) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Layer> e : LAYERS.entrySet()) {
            if (e.getValue() == layer) {
                out.add(e.getKey());
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private final ToolContext ctx;
    /** PHASE W.7: last WorldView, for world_view mode=diff. */
    private final java.util.concurrent.atomic.AtomicReference<net.marcloud.mcp.core.drivers.world.WorldView> lastWorldView =
            new java.util.concurrent.atomic.AtomicReference<>();

    public ToolRegistry(ToolContext ctx) {
        this.ctx = ctx;
    }

    /** All tool specifications, ready to hand to the server spec. */
    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.add(readPlayerState());
        tools.add(recentPackets());
        tools.add(sendChat());
        tools.add(evalJava());
        tools.add(sendRawPacket());
        tools.add(disconnectReport());
        tools.add(scanSurroundings());
        tools.add(worldView());
        tools.add(findBlock());
        tools.add(craftPlan());
        tools.add(captureScreen());
        // Typed send_* tools (packet-exposure W6): build a specific C-packet and
        // dispatch it via the same veto-guarded ActionManager.sendRawPacket path.
        tools.add(sendClientStatus());
        tools.add(sendHeldItem());
        tools.add(sendCloseWindow());
        tools.add(sendDig());
        // W7: six more typed do_* tools. The two entity ones resolve a live Entity
        // by id on the game thread (LLM only has the id); the rest build from scalars.
        tools.add(doSetAbilities());
        tools.add(doPlaceBlock());
        tools.add(doClickSlot());
        tools.add(doSetCreativeSlot());
        tools.add(doUseEntity());
        tools.add(doEntityAction());
        tools.add(inspectBlock());
        tools.add(transferItem());
        tools.add(serverInfo());
        tools.add(openOverlay());
        return tools;
    }

    /** Register all built-in game tools into the supervised capability registry. */
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            Tool t = spec.tool();
            registry.register(t.name(), spec, null, t.description(), true,
                    net.marcloud.mcp.core.se.Ring.forBuiltin(t.name(),
                            net.marcloud.mcp.core.se.Ring.R3));
        }
    }

    /**
     * Register ONLY the model-facing tools of this provider into the model-facing registry.
     *
     * <p><b>Why this provider needs its own filtered path.</b> It is the one provider in
     * {@code McpCore.registerBuiltins} whose tools straddle both layers: {@code all()} is 25
     * specs, of which {@code eval_java} and {@code send_raw_packet} are kernel-layered (the
     * latter because it compiles and reflectively runs caller-supplied Java - see
     * {@code Ring.java:91-97}). Every other provider is wholly on one side, so
     * {@code McpCore} can decide per provider from the names that provider contributed.
     * Here the decision has to be per name, and it reads the same table everything else does.
     *
     * <p>Registering the same spec into two registries is not double registration: each
     * {@link IoManager} wraps it in its own supervisor and keeps its own stats, so each surface
     * measures exactly the calls that arrived on it.
     */
    public void registerModelFacing(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            Tool t = spec.tool();
            if (isKernelLayered(t.name())) {
                continue;
            }
            registry.register(t.name(), spec, null, t.description(), true,
                    net.marcloud.mcp.core.se.Ring.forBuiltin(t.name(),
                            net.marcloud.mcp.core.se.Ring.R3));
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static Map<String, Object> objectSchema(Map<String, Object> properties,
                                                    List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required);
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * A closed set, declared as a JSON {@code enum} rather than described in prose.
     *
     * <p>These were the fields where a model had to read a sentence to learn the legal values,
     * and a sentence can be misread where an enum cannot: a live client was told
     * {@code unknown status 'start'} by a tool whose schema said only {@code type: string} and
     * listed the six legal values in its description. An enum is validated before the handler
     * runs, rejected naming the path that failed, and offered by client-side completion.
     */
    private static Map<String, Object> enumProp(String description, String... values) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        m.put("enum", List.of(values));
        return m;
    }

    private static CallToolResult ok(String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    private static String argString(Map<String, Object> args, String key) {
        Object v = (args == null) ? null : args.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer argInt(Map<String, Object> args, String key) {
        Object v = (args == null) ? null : args.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static int argIntOr(Map<String, Object> args, String key, int fallback) {
        Integer v = argInt(args, key);
        return v == null ? fallback : v;
    }

    private static boolean argBool(Map<String, Object> args, String key, boolean fallback) {
        Object v = (args == null) ? null : args.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    private static float argFloat(Map<String, Object> args, String key, float fallback) {
        Object v = (args == null) ? null : args.get(key);
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    /**
     * Dispatch a pre-built typed C-packet through the veto-guarded send path shared
     * with send_raw_packet (W5 PacketSendSignal). Central so every send_* tool
     * reports veto / not-connected / success identically.
     */
    /**
     * Did the block actually go, or did the server simply accept the packet?
     *
     * <p>"sent dig START_DESTROY_BLOCK" is a TRANSPORT fact. A live client made the difference
     * concrete: the packet went out, the tool said OK, and the block was still there while the
     * player died of something else. A caller reading OK had no way to know. The whole point of
     * this project is that it drives a REAL client, so a result that cannot distinguish "done"
     * from "attempted" is the one thing the surface must not hand back.
     *
     * <p>The judgment is by NAME, never by emptiness, and that is not a stylistic choice:
     * {@code DigController.targetGone} documents the measured reason — flowing water at
     * tickRate 5 and gravel both flow back into the hole within three ticks, so a block that
     * DID break can read as still-present, and an emptiness test would report the break as a
     * failure forever.
     *
     * <p>Only START and STOP can be confirmed this way, and only for a block action. A refusal
     * (out of reach, protected, the server's build limit) is not always distinguishable from
     * silence on the wire, so the timeout message says which of the two it observed rather than
     * inventing a reason.
     */
    private CallToolResult confirmDig(CallToolResult sent, int x, int y, int z) {
        String before = readBlockName(x, y, z);
        // One RTT plus a couple of ticks, matching CraftController.SETTLE_TICKS's reasoning:
        // a client tick, a server tick, and one tick of granularity on each end. Reading sooner
        // would score a break that is still in flight as a failure.
        long deadline = System.nanoTime() + 600_000_000L;
        String last = before;
        while (System.nanoTime() < deadline) {
            String now = readBlockName(x, y, z);
            last = now;
            boolean gone = before == null ? now == null : !before.equals(now);
            if (gone) {
                return ok("sent dig, and the block at (" + x + "," + y + "," + z + ") is now "
                        + (now == null ? "air" : now)
                        + (before == null ? "" : " (was " + before + ")")
                        + " -- confirmed in the world, not just on the wire");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming the dig at (" + x + "," + y + "," + z
                        + "); the packet was sent but the world has not changed and was not re-read");
            }
        }
        return error("the dig packet was sent and 600ms later (" + x + "," + y + "," + z + ") still "
                + "reads " + (last == null ? "nothing" : last)
                + (before == null ? "" : ", the same as before the dig") + ". Either the server "
                + "refused it -- out of reach (vanilla's 36), a protected block, or the world edit "
                + "limit -- or it is still being mined; re-read the block before retrying, because "
                + "retrying a refused dig is how a tool reports progress it did not make");
    }

    /** The block's registry name at a position, on the game thread, or null when unreadable. */
    private String readBlockName(int x, int y, int z) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                net.minecraft.client.multiplayer.WorldClient w = ctx.game().world();
                if (w == null) {
                    return null;
                }
                try {
                    var b = w.getBlockState(new net.minecraft.util.BlockPos(x, y, z)).getBlock();
                    var name = net.minecraft.block.Block.blockRegistry.getNameForObject(b);
                    return name == null ? null
                            : name.toString().replace("minecraft:", "");
                } catch (Throwable t) {
                    return null;
                }
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private CallToolResult sendTyped(Packet<?> packet, String label) {
        try {
            boolean sent = ctx.actions().sendRawPacket(packet);
            return sent ? ok("sent " + label) : error("not connected — no open channel to send on");
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            if (cause instanceof net.marcloud.mcp.core.drivers.action.ActionManager.PacketVetoedException) {
                return error("vetoed: " + cause.getMessage());
            }
            return error(label + " failed: " + e);
        }
    }

    // ---- tools -------------------------------------------------------------

    private SyncToolSpecification readPlayerState() {
        Tool tool = Tool.builder()
                .name("read_player_state")
                .title("Read player state")
                .description("[requires: in-world] Read the local player's live state: name, position "
                        + "(x,y,z), yaw/pitch, health, onGround. Returns 'not in world' "
                        + "if the player isn't spawned. "
                        // Every field here except `name` is also in world_view's self section, so
                        // this tool has been flagged as redundant more than once. It is not, and
                        // the reason it never got written down is why it kept getting flagged.
                        // SelfView carries 21 fields (velocity, food, saturation, xp, armour, air,
                        // effects, gamemode, sneaking, sprinting); this returns six as ONE LINE of
                        // plain text. A per-turn heartbeat pays for every character it parses, and
                        // this one costs a fraction of the JSON. It is the cheap poll, not a
                        // duplicate view -- and `name` is genuinely only here: SelfView has no name
                        // field at all.

                        + "USE THIS for a cheap heartbeat or a 'did anything move / am I alive' "
                        + "check; use world_view sections=['self'] when you want velocity, food, "
                        + "xp, armour, air, effects, or the gamemode. Do not assemble state from "
                        + "both in one turn -- pick the one whose shape the question needs.")
                .annotations(ToolAnnotations.builder()
                        .title("Read player state")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            // Capture on the game thread: reading live entity fields off-thread
            // yields torn/stale values (fields aren't volatile, DataWatcher is a
            // plain map the game thread mutates).
            final PlayerState s;
            try {
                s = net.marcloud.mcp.core.GameBridge.onGameThread(() -> PlayerState.capture(ctx.game()));
            } catch (Exception e) {
                return error("could not read player state: " + e.getMessage());
            }
            if (!s.present()) {
                return ok("not in world");
            }
            return ok(String.format(
                    "name=%s pos=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f health=%.1f onGround=%b",
                    s.name(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(), s.health(), s.onGround()));
        });
    }

    private SyncToolSpecification recentPackets() {
        Tool tool = Tool.builder()
                .name("recent_packets")
                .title("List recent packets")
                .description("[requires: -javaagent] List recently observed packets (inbound <- / outbound ->), "
                        + "oldest first. Useful to see what happened right before a "
                        + "disconnect/kick.")
                .annotations(ToolAnnotations.builder()
                        .title("List recent packets")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(true)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "count", Map.of("type", "integer",
                                "description", "max entries to return (default 50)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int count = 50;
            Object c = request.arguments() == null ? null : request.arguments().get("count");
            if (c instanceof Number num) {
                count = num.intValue();
            }
            var entries = ctx.packetLog().recent(count);
            if (entries.isEmpty()) {
                // Distinguish "source unavailable" from "genuinely empty": the
                // packet tap is fed by network hooks that require -javaagent
                // Instrumentation (see McpCore/FltManager). Without it, packets
                // are NEVER recorded, so an empty ring must not be reported as an
                // authoritative "no packets" — that conflates a missing sensor
                // with a real negative observation.
                if (!net.marcloud.mcp.core.boot.AgentAccess.isLoaded()) {
                    return error("packet tap unavailable: network packet hooks require "
                            + "-javaagent Instrumentation, which is not loaded, so no packets "
                            + "are being observed. This is NOT an authoritative 'no packets'.");
                }
                return ok("(no packets recorded yet)");
            }
            StringBuilder sb = new StringBuilder();
            for (var e : entries) {
                sb.append(e).append('\n');
            }
            return ok(sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification sendChat() {
        Tool tool = Tool.builder()
                .name("send_chat")
                .title("Send chat message")
                .description("[requires: in-world, connected-to-server] Send a chat message as the player. If it starts with '/', "
                        + "it runs as a command. Requires being in a world.")
                .annotations(ToolAnnotations.builder()
                        .title("Send chat message")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "message", stringProp("the chat text or /command")),
                        List.of("message")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String message = argString(request.arguments(), "message");
            if (message == null || message.isEmpty()) {
                return error("message is required");
            }
            try {
                boolean sent = ctx.actions().sendChat(message);
                return sent ? ok("sent: " + message)
                            : error("not in world — cannot send chat");
            } catch (Exception e) {
                // PHASE E.1: the veto is thrown inside the game-thread Callable, so
                // invokeAndWait wraps it in ExecutionException — unwrap to report it
                // as a veto rather than a generic failure.
                Throwable cause = e instanceof java.util.concurrent.ExecutionException
                        ? e.getCause() : e;
                if (cause instanceof net.marcloud.mcp.core.drivers.action.ActionManager.ChatVetoedException) {
                    return error("vetoed: " + cause.getMessage());
                }
                return error("send failed: " + e.getMessage());
            }
        });
    }

    private SyncToolSpecification evalJava() {
        Tool tool = Tool.builder()
                .name("eval_java")
                .title("Evaluate Java (live REPL)")
                .description("Compile and load a Java class into the running game, then "
                        + "instantiate it and call its no-arg 'run' method; returns the "
                        + "result's toString. The source must declare a public class with "
                        + "the given name and a 'public Object run()' method. This is the "
                        + "live-experiment REPL — code runs inside the game JVM on a WORKER "
                        + "thread. To read or mutate live world/player/entity state, you MUST "
                        + "marshal onto the game thread, e.g.: "
                        + "net.marcloud.mcp.core.GameBridge.onGameThread(() -> { "
                        + "return net.marcloud.mcp.core.GameBridge.game().player().posX; }). "
                        + "Touching game state directly off-thread can crash the game. "
                        + "SCOPE: this is NOT a sandbox, and the reach is the whole machine, "
                        + "not the game. Measured reachable from submitted code: spawn "
                        + "processes (ProcessBuilder, Runtime.exec), read and write any file "
                        + "the user can, enumerate the home directory, bind and connect "
                        + "sockets, and read the process environment. The ring and privilege "
                        + "gates decide whether you may CALL this tool; they do not constrain "
                        + "what the code does once it is running. The only kill switch is "
                        + "disable_privilege(SE_CREATE_TOOL), and it is all-or-nothing -- it "
                        + "also shuts off send_raw_packet and create_tool.")
                .annotations(ToolAnnotations.builder()
                        .title("Evaluate Java (live REPL)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "className", stringProp("fully-qualified class name, e.g. gen.Probe"),
                        "source", stringProp("full Java source declaring that class with "
                                + "a 'public Object run()' method")),
                        List.of("className", "source")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String className = argString(request.arguments(), "className");
            String source = argString(request.arguments(), "source");
            if (className == null || source == null) {
                return error("className and source are required");
            }
            var outcome = ctx.hotLoad().loadNew(className, source);
            if (!outcome.success()) {
                return error(outcome.message());
            }
            try {
                Class<?> c = outcome.loadedClass();
                Object inst = c.getDeclaredConstructor().newInstance();
                Object result = c.getMethod("run").invoke(inst);
                return ok(String.valueOf(result));
            } catch (NoSuchMethodException e) {
                return error("loaded " + className + " but it has no 'public Object run()' method");
            } catch (Exception e) {
                return error("run failed: " + e);
            }
        });
    }

    private SyncToolSpecification sendRawPacket() {
        Tool tool = Tool.builder()
                .name("send_raw_packet")
                .title("Send raw protocol packet")
                .description("[requires: connected-to-server] Send an ARBITRARY protocol packet down the current connection. "
                        + "Provide Java source for a class with a 'public Object run()' method "
                        + "that constructs and RETURNS a net.minecraft.network.Packet (e.g. "
                        + "'return new net.minecraft.network.play.client.C03PacketPlayer(true);'). "
                        + "The packet is compiled, then dispatched on the game thread. Raw "
                        + "protocol experiment primitive — no filtering. Requires being connected.")
                .annotations(ToolAnnotations.builder()
                        .title("Send raw protocol packet")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "className", stringProp("fully-qualified class name, e.g. gen.MakePacket"),
                        "source", stringProp("Java source with 'public Object run()' returning a Packet")),
                        List.of("className", "source")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String className = argString(request.arguments(), "className");
            String source = argString(request.arguments(), "source");
            if (className == null || source == null) {
                return error("className and source are required");
            }
            var outcome = ctx.hotLoad().loadNew(className, source);
            if (!outcome.success()) {
                return error(outcome.message());
            }
            try {
                Class<?> c = outcome.loadedClass();
                Object inst = c.getDeclaredConstructor().newInstance();
                Object result = c.getMethod("run").invoke(inst);
                if (!(result instanceof Packet<?> packet)) {
                    return error("run() must return a net.minecraft.network.Packet, got "
                            + (result == null ? "null" : result.getClass().getName()));
                }
                boolean sent = ctx.actions().sendRawPacket(packet);
                return sent
                        ? ok("sent packet: " + packet.getClass().getSimpleName())
                        : error("not connected — no open channel to send on");
            } catch (Exception e) {
                // A board veto is thrown inside the game-thread Callable, so
                // invokeAndWait wraps it in ExecutionException — unwrap to report it
                // as a veto rather than a generic failure (mirrors send_chat).
                Throwable cause = e instanceof java.util.concurrent.ExecutionException
                        ? e.getCause() : e;
                if (cause instanceof net.marcloud.mcp.core.drivers.action.ActionManager.PacketVetoedException) {
                    return error("vetoed: " + cause.getMessage());
                }
                return error("send_raw_packet failed: " + e);
            }
        });
    }

    private SyncToolSpecification disconnectReport() {
        Tool tool = Tool.builder()
                .name("disconnect_report")
                .title("Explain last disconnect")
                .description("[requires: -javaagent] Explain the last disconnect/kick: the reason text plus the "
                        + "packets observed right before it. Answers 'why was I kicked?'.")
                .annotations(ToolAnnotations.builder()
                        .title("Explain last disconnect")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(true)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "recentPackets", Map.of("type", "integer",
                                "description", "how many recent packets to include (default 20)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int n = 20;
            Object v = request.arguments() == null ? null : request.arguments().get("recentPackets");
            if (v instanceof Number num) {
                n = num.intValue();
            }
            if (ctx.disconnects() == null) {
                return error("disconnect tracking unavailable");
            }
            // GAP-4: distinguish "sensor never installed" from "genuinely no
            // disconnect". The disconnect sensor is fed by -javaagent network
            // advice (see HookBridge/McpCore); without it, a clean "no disconnect
            // observed" would conflate a dead sensor with a real negative — the
            // same fix already applied to recent_packets. Only report a negative
            // when the sensor is actually live (or a disconnect was observed).
            if (!ctx.disconnects().sensorInstalled() && !ctx.disconnects().observedAny()) {
                return error("disconnect sensor unavailable: the disconnect/kick hooks require "
                        + "-javaagent Instrumentation, which is not loaded, so disconnects are "
                        + "NOT being observed. This is NOT an authoritative 'no disconnect'.");
            }
            return ok(ctx.disconnects().report(n));
        });
    }

    private SyncToolSpecification scanSurroundings() {
        Tool tool = Tool.builder()
                .name("scan_surroundings")
                .title("Scan surroundings")
                .description("[requires: in-world] A symbolic snapshot of the player's situation — "
                        + "position, health/hunger, biome/dimension/time, inventory, the block "
                        + "column (below/legs/head), dedup'd nearby block types with counts, and "
                        + "nearby entities sorted by distance. Cheap and precise. A block shown as "
                        + "\"?\" could not be READ — an unloaded chunk or a failed registry lookup — "
                        + "not a block named ? — and is "
                        + "left out of the block-type counts rather than tallied as one. "
                        // The old text here called this a superseded compact heartbeat and told the
                        // caller to prefer world_view. That was wrong in a way that cost answers, and
                        // it is worth writing down why so nobody "simplifies" it back:
                        // world_view's blockCounts is a PER-COLUMN census, so it reports what is on
                        // the SURFACE. This one walks the whole sampled CUBE (dx, dy and dz), so it is
                        // the only tool that answers "how much of X is near me". A buried seam, ore
                        // under a roof, or dirt beneath a floor is absent from world_view entirely,
                        // and world_view's own text warns that its histogram is surface-only -- so
                        // sending the caller there for a quantity question produces a confidently
                        // wrong "there is none here".

                        + "USE THIS, not world_view, when the question is HOW MUCH of a type is near "
                        + "you: world_view's blockCounts is a per-column census and therefore "
                        + "surface-only, so a buried or roofed-over type is absent from it and its "
                        + "absence proves NOTHING. This walks the whole cube. world_view is richer in "
                        + "every other respect (columnar grid, per-slot inventory, raytrace target, "
                        + "full|diff modes) and is the right tool for those; find_block answers "
                        + "\"where is the nearest one\" with positions. (capture_screen only when "
                        + "you must SEE.)")
                .annotations(ToolAnnotations.builder()
                        .title("Scan surroundings")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "radius", Map.of("type", "integer",
                                "description", "scan cube half-size in blocks, 1-32 (default 16)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int radius = 16;
            Object v = request.arguments() == null ? null : request.arguments().get("radius");
            if (v instanceof Number num) {
                radius = num.intValue();
            }
            final int r = radius;
            try {
                Surroundings s = net.marcloud.mcp.core.GameBridge.onGameThread(
                        () -> WorldScanner.capture(ctx.game(), r));
                return ok(s.toText());
            } catch (Exception e) {
                return error("scan failed: " + e.getMessage());
            }
        });
    }

    private SyncToolSpecification worldView() {
        Tool tool = Tool.builder()
                .name("world_view")
                .title("World view (structured observation)")
                // PHASE C: the description is WorldViewLegend.HEADER, not an inline string. It
                // was 10,233 chars re-sent every turn (31.6% of this registry's per-turn
                // description tax) for a tool the agent may not touch on a given turn; the
                // per-section detail now lives behind world_view{explain:...}, below. What stayed
                // inline is every rule whose NAIVE reading changes what the agent DOES.
                .description(net.marcloud.mcp.core.drivers.world.WorldViewLegend.HEADER)
                .annotations(ToolAnnotations.builder()
                        .title("World view (structured observation)")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                // Map.ofEntries, not Map.of: this map now holds five properties and Map.of stops
                // at four pairs. ofEntries is the same immutable map without the arity ceiling, so
                // the next parameter does not have to cost a property its documentation.
                .inputSchema(objectSchema(Map.ofEntries(
                        Map.entry("profile", enumProp("how much of the world to read.",
                                "sparse", "explore", "combat")),
                        Map.entry("mode", enumProp("full reads everything. diff returns only what "
                                + "changed since last world_view of the same profile, and the FIRST "
                                + "diff call has no baseline yet and comes back as full.",
                                "full", "diff")),
                        Map.entry("radius", Map.of("type", "integer",
                                "description", "grid half-size 1-16 (default from profile)")),
                        Map.entry("sections", Map.of("type", "array", "items",
                                Map.of("type", "string"),
                                "description", "subset of "
                                        + String.join(",", net.marcloud.mcp.core.drivers.world.WorldViewCapture.SECTIONS)
                                        + "; any other name is refused, not ignored")),
                        Map.entry("explain", Map.of("type", "string",
                                "enum", net.marcloud.mcp.core.drivers.world.WorldViewLegend.sectionNames(),
                                "description", "return this section's decoding legend instead of a "
                                        + "world sample; pays for the part of the documentation you "
                                        + "have not read yet"))),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            // The on-demand half of the documentation split. Checked FIRST and answered before
            // any sampling: world_view's description points here for the sections it no longer
            // carries, so a fetch that also sampled the world would cost the agent a world scan
            // to read prose, and would fail outright when not in-world -- which is exactly when a
            // confused agent most wants the legend.
            String explain = args == null ? null : str(args.get("explain"));
            if (explain != null && !explain.isBlank()) {
                String legend = net.marcloud.mcp.core.drivers.world.WorldViewLegend.explain(explain);
                if (legend == null) {
                    // REFUSED, not ignored, for the same reason 'sections' is: a name that
                    // matched nothing would otherwise return a normal-looking world sample and the
                    // caller would believe it had been told something.
                    return error("world_view: unknown explain section " + explain
                            + "; known sections are "
                            + String.join(", ", net.marcloud.mcp.core.drivers.world.WorldViewLegend.sectionNames()));
                }
                return ok(legend);
            }
            net.marcloud.mcp.core.drivers.world.ObserveProfile prof =
                    net.marcloud.mcp.core.drivers.world.ObserveProfile.parse(
                            args == null ? null : str(args.get("profile")));
            boolean diff = args != null && "diff".equalsIgnoreCase(str(args.get("mode")));
            int radius = prof.gridRadius;
            if (args != null && args.get("radius") instanceof Number n) {
                radius = Math.max(1, Math.min(16, n.intValue()));
            }
            final int r = radius;
            List<String> sections = asStringList(args == null ? null : args.get("sections"));
            List<String> unknown = net.marcloud.mcp.core.drivers.world.WorldViewCapture
                    .unknownSections(sections);
            if (!unknown.isEmpty()) {
                // REFUSED, not ignored. An unrecognised name used to reach the sampler, match
                // nothing, and produce a view with that section silently missing -- a perfectly
                // successful reply whose only defect was the absence, which in mode=diff then reads
                // as "unchanged". The vocabulary is derived from the list the sampler itself gates
                // on, so this error cannot name a section the tool does not have.
                return error("world_view: unknown section name(s) " + unknown
                        + "; known sections are "
                        + String.join(", ", net.marcloud.mcp.core.drivers.world.WorldViewCapture.SECTIONS));
            }
            try {
                net.marcloud.mcp.core.drivers.world.WorldView v =
                        net.marcloud.mcp.core.GameBridge.onGameThread(
                                () -> net.marcloud.mcp.core.drivers.world.WorldViewCapture.capture(
                                        ctx.game(), prof, r, sections));
                String json;
                if (diff) {
                    json = net.marcloud.mcp.core.io.http.Json.write(
                            net.marcloud.mcp.core.drivers.world.WorldViewDiff.diff(lastWorldView.get(), v));
                } else {
                    json = net.marcloud.mcp.core.io.http.Json.write(
                            net.marcloud.mcp.core.drivers.world.WorldViewJson.toMap(v));
                }
                lastWorldView.set(v);
                return ok(json);
            } catch (Exception e) {
                return error("world_view failed: " + e.getMessage());
            }
        });
    }

    private SyncToolSpecification findBlock() {
        Tool tool = Tool.builder()
                .name("find_block")
                .title("Find a block by type")
                .description("[requires: in-world] WHERE a block type is, nearest first, as "
                        + "coordinates. Use this instead of scanning world_view when the question "
                        + "is 'where is the nearest X' -- world_view is ~34k tokens at radius 16, "
                        + "and its blockCounts only counts each column's SURFACE block, so a buried "
                        + "or roofed-over type is absent from it entirely and its absence proves "
                        + "NOTHING; scan_surroundings does census a volume but discards positions. "
                        + "This tool scans the volume itself, so it finds what blockCounts cannot. "
                        + "'types' is comma-separated and namespace-optional ('iron_ore' or "
                        + "'minecraft:iron_ore'), so a name read out of a world_view can be fed "
                        + "straight back. 'radius' and 'limit' outside the ranges the schema gives "
                        + "are CLAMPED to them and the reply states the radius actually searched, "
                        + "so a miss is never reported as a statement about a region that was not "
                        + "swept. The sweep covers LOADED chunks only: a position whose chunk is "
                        + "not loaded is SKIPPED rather than read, because reading it returns air "
                        + "and an ore you are looking at would be reported as nothing there. So a "
                        + "miss means 'not in the loaded part of the swept radius' -- travelling "
                        + "toward an unload boundary and finding nothing is a normal result, not "
                        + "evidence of absence. Returns block/x/y/z/dist per hit; air is never "
                        + "matched. "
                        + "'dist' is measured from the block you are standing in to the target "
                        + "block's index, NOT eye-to-block-centre the way reach is checked, so a hit "
                        + "reported just inside 4.5 can still be out of reach when it is below or "
                        + "above you -- get closer rather than trusting dist as a reach test.")
                .annotations(ToolAnnotations.builder()
                        .title("Find a block by type")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "types", Map.of("type", "string",
                                "description", "comma-separated block names, namespace optional"),
                        "radius", Map.of("type", "integer",
                                "description", "search half-size in blocks, 1-32 (default 16); a "
                                        + "value outside is clamped to the range and the reply says "
                                        + "which one was searched"),
                        "limit", Map.of("type", "integer",
                                "description", "max hits, 1-64 (default 8); a value outside is "
                                        + "clamped to the range and the reply says which one was "
                                        + "used")),
                        List.of("types")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            String types = str(args.get("types"));
            if (types == null || types.isBlank()) {
                return error("find_block needs 'types', e.g. \"iron_ore\" or \"oak_log,birch_log\"");
            }
            int radius = args.get("radius") instanceof Number n ? n.intValue() : 16;
            int limit = args.get("limit") instanceof Number n ? n.intValue() : 8;
            // Clamped HERE, before the search, so the reply can describe the region that was
            // ACTUALLY searched instead of the one that was asked for. BlockFinder.search clamps
            // too, and that belt stays, but a clamp made inside it is invisible to the message
            // built from the result -- and "no match for diamond_ore within 200 blocks" when 32
            // were swept is a fact the caller plans around: it concludes the ore is absent, then
            // travels or digs on that basis. Same numbers the search would have used, taken from
            // the same constants, so the reply cannot name a bound the search did not honour.
            int searched = Math.max(1, Math.min(radius,
                    net.marcloud.mcp.core.drivers.world.BlockFinder.MAX_RADIUS));
            int cap = Math.max(1, Math.min(limit,
                    net.marcloud.mcp.core.drivers.world.BlockFinder.MAX_LIMIT));
            String clamped = clampNote(radius, searched, limit, cap);
            try {
                List<net.marcloud.mcp.core.drivers.world.BlockFinder.Hit> hits =
                        net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                            var p = ctx.game().player();
                            var w = ctx.game().world();
                            if (p == null || w == null) {
                                return List.<net.marcloud.mcp.core.drivers.world.BlockFinder.Hit>of();
                            }
                            var feet = new net.minecraft.util.BlockPos(p.posX, p.posY, p.posZ);
                            return net.marcloud.mcp.core.drivers.world.BlockFinder.find(
                                    w, feet, types, searched, cap);
                        });
                if (hits.isEmpty()) {
                    // An explicit miss, not an empty list: "no iron_ore within 16" is a fact the
                    // caller can act on (search wider, or dig), and a bare [] reads like an error.
                    return ok("no match for \"" + types + "\" within " + searched + " blocks"
                            + clamped);
                }
                StringBuilder sb = new StringBuilder();
                for (var h : hits) {
                    sb.append(h.block()).append(' ').append(h.x()).append(',').append(h.y())
                            .append(',').append(h.z()).append("  d=").append(h.dist()).append('\n');
                }
                if (!clamped.isEmpty()) {
                    // Only when it bit: the list is already the answer, but a caller that asked for
                    // 200 hits and got 64 must know whether that is all there is or all it allowed.
                    sb.append("note:").append(clamped);
                }
                return ok(sb.toString().stripTrailing());
            } catch (Exception e) {
                return error("find_block failed: " + e.getMessage());
            }
        });
    }

    /**
     * One position, read as a decision rather than as a block id.
     *
     * <p>Exists because the observation surface had no way to ask about a specific cell.
     * {@code world_view} reports a grid of columns and {@code find_block} searches for a type;
     * neither answers "can I stand here" or "can something spawn here", which are the two
     * questions that decide where a shelter goes. The underlying {@code BlockInspector} carries the
     * vanilla rule; this handler only names it.
     *
     * <p>Read-only, so there is no confirmation step -- but the reply still separates "could not
     * read this position" from "read it and it is safe", because those collapse into the same
     * answer everywhere else in this surface.
     */
    private SyncToolSpecification inspectBlock() {
        Tool tool = Tool.builder()
                .name("inspect_block")
                .title("Inspect one block position")
                .description("Read ONE block position and answer the two questions that decide "
                        + "where to build: is the cell standable (air, with a solid floor below and "
                        + "room for a body above), and can a hostile mob spawn there. The spawn "
                        + "answer is vanilla's own gate (EntityMob.isValidLightLevel): the light AT "
                        + "the cell must be 7 or below, and under thunder the skylight factor is "
                        + "forced to 10, so MIDDAY becomes spawnable during a storm. Treat a cell "
                        + "with no floor as a refusal of NOW, not of ever -- that is the cell a mob "
                        + "walks in through. Use world_view for a whole area; use this when you "
                        + "have one specific cell in mind.")
                .annotations(ToolAnnotations.builder().title("Inspect one block position")
                        .readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(objectSchema(Map.of(
                        "x", Map.of("type", "integer", "description", "block x"),
                        "y", Map.of("type", "integer", "description", "block y"),
                        "z", Map.of("type", "integer", "description", "block z")),
                        List.of("x", "y", "z")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            Integer x = argInt(args, "x");
            Integer y = argInt(args, "y");
            Integer z = argInt(args, "z");
            if (x == null || y == null || z == null) {
                return error("x, y and z are all required, as integers: the position was read as "
                        + args);
            }
            try {
                net.marcloud.mcp.core.drivers.world.BlockInspector.Report r =
                        net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                            var w = ctx.game().world();
                            if (w == null) {
                                return null;
                            }
                            return net.marcloud.mcp.core.drivers.world.BlockInspector.inspect(
                                    w, x, y, z);
                        });
                if (r == null) {
                    return error("no world is loaded, so nothing is claimed about "
                            + x + "," + y + "," + z);
                }
                return ok(net.marcloud.mcp.core.io.http.Json.write(r));
            } catch (Exception e) {
                return error("inspect_block failed: " + e.getMessage());
            }
        });
    }

    /**
     * Move a whole stack between a container and the player's inventory, and report what the
     * server's container actually holds afterwards.
     *
     * <p>{@code do_click_slot} already speaks mode 1, and its description explains the trap: what
     * the server compares is the RESULT of your click, so a shift-click whose claim is wrong is
     * not refused -- it is APPLIED, then every slot is resynced back over you and the window is
     * LOCKED, after which every later click on it is silently dropped. That is why a chest, a
     * furnace and a crafting table were unusable: the caller had to compute the server's own
     * {@code transferStackInSlot} result to fill in one field, and getting it wrong did not fail,
     * it disabled the window.
     *
     * <p>So the claim is derived from the container that is actually open, and -- the part that
     * matters -- the answer is read back off the server's resync rather than inferred from the
     * packet having been sent. "Sent" is a statement about the wire; the slot contents are a
     * statement about the world, and only the second one says whether the stack moved.
     */
    private SyncToolSpecification transferItem() {
        Tool tool = Tool.builder()
                .name("transfer_item")
                .title("Shift-move a stack and confirm it")
                .description("[requires: connected-to-server, container open] Move the whole stack "
                        + "in one container slot into the other region (shift-click, vanilla's "
                        + "Container.transferStackInSlot), and report what the SERVER's container "
                        + "holds afterwards. This is the safe way to move items: a hand-built C0E "
                        + "shift-click whose item claim is wrong is not refused, it is applied, "
                        + "resynced over you and the window is LOCKED, after which every later "
                        + "click on it is silently dropped. This tool derives the claim and reads "
                        + "the result back. Slot numbering is vanilla's: for windowId 0, 0 is the "
                        + "crafting output, 1-4 the 2x2 grid, 5-8 armour, 9-35 main inventory, 36-44 "
                        + "hotbar; for a crafting table 1-9 is the 3x3 grid. The reply always states "
                        + "the slot before and after, so a transfer that did nothing is visible as "
                        + "such rather than as a success.")
                .annotations(ToolAnnotations.builder().title("Shift-move a stack and confirm it")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "windowId", Map.of("type", "integer",
                                "description", "window id (0 = own inventory)"),
                        "slotId", Map.of("type", "integer",
                                "description", "the slot to move FROM")),
                        List.of("windowId", "slotId")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            Integer windowId = argInt(a, "windowId");
            Integer slotId = argInt(a, "slotId");
            if (windowId == null || slotId == null) {
                return error("windowId and slotId (integers) are required");
            }
            return transferAndConfirm(windowId, slotId);
        });
    }

    /** One slot's contents as a caller reads them: an id and a count, or empty. */
    /**
     * Package-visible, not private: {@link #verdictFor} is the rule that keeps this tool honest,
     * and a rule can only be tested with the values it actually receives.
     */
    record SlotView(String item, int count) {
        static SlotView nothing() {
            return new SlotView(null, 0);
        }

        static SlotView of(net.minecraft.item.ItemStack s) {
            if (s == null || s.getItem() == null || s.stackSize <= 0) {
                return nothing();
            }
            // Item.itemRegistry, not Block.blockRegistry: a slot can hold a sword, and this call
            // is how an item id comes back for comparison against the server's own stack.
            return new SlotView(String.valueOf(
                    net.minecraft.item.Item.itemRegistry.getNameForObject(s.getItem())),
                    s.stackSize);
        }

        boolean empty() {
            return item == null || count <= 0;
        }

        /** The wire form: the server's {@code areItemStacksEqual} judges the item and the count. */
        net.minecraft.item.ItemStack stack() {
            return empty() ? null : resolveStack(item, count, 0);
        }

        @Override
        public String toString() {
            return empty() ? "-" : item + " x" + count;
        }
    }

    /** Read one slot of the container the player currently has open. */
    private SlotView readOpenSlot(int slotId) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var p = ctx.game().player();
                if (p == null || p.openContainer == null) {
                    return SlotView.nothing();
                }
                var c = p.openContainer;
                if (slotId < 0 || slotId >= c.inventorySlots.size()) {
                    return SlotView.nothing();
                }
                return SlotView.of(c.getSlot(slotId).getStack());
            });
        } catch (Exception e) {
            return SlotView.nothing();
        }
    }

    /**
     * Send the shift-click, then read the slot back until the server's resync has landed or the
     * budget expires.
     *
     * <p>The wait is not politeness. The server applies the click to ITS container and then pushes
     * every slot back over C0F; until that arrives the client still shows the pre-click state, so
     * an immediate read reports "nothing moved" for a transfer that worked.
     */
    /**
     * The verdict for one re-read of the slot, as a pure function.
     *
     * <p>Separated from the polling loop so it is reachable headlessly. The loop itself needs a
     * live container, and a rule that can only be exercised live is a rule nobody checks: with
     * the decision inside the loop, replacing "re-read and compare" with "report that it was sent"
     * -- the exact defect this tool exists to prevent, and the one this repo's own audit lists as
     * section 2.5 -- ran green here, because with no container open the empty-slot guard answers
     * first and the loop is never entered.
     */
    static TransferVerdict verdictFor(SlotView before, SlotView now) {
        if (before == null || now == null) {
            return TransferVerdict.UNREADABLE;
        }
        if (!before.equals(now)) {
            return TransferVerdict.MOVED;
        }
        return TransferVerdict.NOT_MOVED;
    }

    /** What one re-read can establish. Never {@code SENT}: "sent" is not an outcome. */
    enum TransferVerdict {
        /** The slot's contents changed, so the server's container did the thing. */
        MOVED,
        /** The slot still reads the same. The move did not happen, or is still in flight. */
        NOT_MOVED,
        /** The slot could not be read, so nothing is claimed. */
        UNREADABLE
    }

    private CallToolResult transferAndConfirm(int windowId, int slotId) {
        SlotView before = readOpenSlot(slotId);
        if (before.empty()) {
            // The numbering here is the CONTAINER's, and for the player's own window it is not
            // the inventory numbering world_view reports. Caught live: world_view listed `log` at
            // inventory index 0, this tool answered "slot 0 holds nothing", and both were right --
            // ContainerPlayer slot 0 is the CRAFTING OUTPUT, and the hotbar slot 0 is container
            // slot 36. A bare "it is empty" sends the caller off to read the container again and
            // get the same answer, so name both numbers.
            return error("container slot " + slotId + " of window " + windowId + " holds nothing, so "
                    + "there is nothing to move. NOTE slotId is the CONTAINER's numbering, not the "
                    + "inventory numbering world_view prints: in the player's own window "
                    + "(windowId 0) the inventory index is offset by 9 -- container slots 9..35 are "
                    + "the main inventory and 36..44 the hotbar, so hotbar slot 0 is container "
                    + "slot 36, and container slot 0 is the crafting output. "
                    + describeOpenWindow(windowId));
        }
        net.minecraft.item.ItemStack claim = before.stack();
        if (claim == null) {
            return error("slot " + slotId + " holds " + before.item() + " x" + before.count()
                    + ", which this client cannot resolve into an ItemStack, so no claim can be "
                    + "built and a bare packet would be rejected");
        }
        CallToolResult sent = sendTyped(
                new net.minecraft.network.play.client.C0EPacketClickWindow(
                        windowId, slotId, 0, 1, claim, (short) 0),
                "shift-click win=" + windowId + " slot=" + slotId);
        if (Boolean.TRUE.equals(sent.isError())) {
            return sent;
        }
        long deadline = System.nanoTime() + 600_000_000L;
        SlotView last = before;
        while (System.nanoTime() < deadline) {
            last = readOpenSlot(slotId);
            if (verdictFor(before, last) == TransferVerdict.MOVED) {
                return ok("shift-clicked slot " + slotId + " of window " + windowId + ": it held "
                        + before + " and the server's container now holds " + last
                        + " -- confirmed by re-reading the slot, not by the packet having been "
                        + "sent");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming the transfer from slot " + slotId
                        + "; the packet was sent and the slot still reads " + last);
            }
        }
        return error("the shift-click was sent and 600ms later slot " + slotId + " of window "
                + windowId + " still holds " + last + ". Either the destination region is full, or "
                + "window " + windowId + " is not the window the server has open, in which case it "
                + "ignores the packet outright. " + describeOpenWindow(windowId));
    }

    /** What the player actually has open, so a wrong windowId is nameable rather than mysterious. */
    private String describeOpenWindow(int askedFor) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var p = ctx.game().player();
                if (p == null || p.openContainer == null) {
                    return "No container is open, so the server has no window open either.";
                }
                int id = p.openContainer.windowId;
                return "The open container is windowId " + id + " ("
                        + p.openContainer.getClass().getSimpleName() + ")"
                        + (id == askedFor ? "."
                                : ", NOT " + askedFor + " -- a packet for a window the server does "
                                + "not have open is ignored outright.");
            });
        } catch (Exception e) {
            return "The open container could not be read (" + e + ").";
        }
    }
    /**
     * Who is on the other end of the connection, and what it says about itself.
     *
     * <p>Every one of these facts already exists in the client and none of it was reachable: the
     * server's MOTD, its declared protocol version, the latency the server-browser ping measured,
     * the online player count, and the mod brand the server pushed in a {@code MC|Brand} custom
     * payload ({@code NetHandlerPlayClient:1851-1853}). {@code disconnect_report} explains why a
     * session ended but says nothing about who was running it.
     *
     * <p>Fields are reported separately rather than merged, because they come from different times
     * and have different ages. {@code pingToServer} is written by the server-BROWSER ping before
     * the session starts; 1.8.9's play session carries no live latency figure at all, so it is
     * labelled stale rather than presented as current.
     */
    /**
     * Open or close the project's own overlay panel.
     *
     * <p>The panel is published into {@code gui_snapshot} as real vanilla buttons and clicking one
     * emits the same QML signal a person's click does (audit 2.6). But until this tool existed
     * there was no way for an agent to REACH it: the RSHIFT hotkey is driven by the GLFW
     * callback, and RSHIFT is not in vanilla's keybind array at all, so {@code press_key_binding}
     * answers {@code bindingClaimed=true} and nothing happens. The one surface a person can open
     * and an agent cannot is exactly the gap 2.6 exists to close, reopened from the other side.
     *
     * <p>It calls the SAME method the key calls. Not a parallel path: the guard that refuses to
     * replace another screen, the toggle rather than stack, and the construction through
     * {@code DwmEntry} are all shared, so the two cannot drift into operating different UIs.
     *
     * <p>Reported as an error when dwm is absent rather than a silent success, because "the
     * overlay is not on the classpath" is a launch condition the caller needs to know about --
     * only {@code run-mcp-overlay.bat} arms it.
     */
    private SyncToolSpecification openOverlay() {
        Tool tool = Tool.builder()
                .name("open_overlay")
                .title("Open or close the overlay panel")
                .description("[requires: the dwm overlay armed (scripts\\run-mcp-overlay.bat)] Open "
                        + "or close the project's own overlay panel. This is the only way an agent "
                        + "can reach it: the RSHIFT hotkey is a GLFW event, and RSHIFT is not in "
                        + "vanilla's keybind array, so press_key_binding cannot deliver it. Once "
                        + "open, every control in the panel appears in gui_snapshot as a real "
                        + "vanilla button and gui_click_element drives it through the same signal a "
                        + "human click emits. Refuses to replace another screen (a menu, the chat "
                        + "box) rather than discarding what the player was doing, and toggles "
                        + "rather than stacking a second panel. ERROR when the overlay is not "
                        + "armed, because that is a launch condition and not a result.")
                .annotations(ToolAnnotations.builder().title("Open or close the overlay panel")
                        .readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(false).openWorldHint(false).build())
                .inputSchema(objectSchema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            final String outcome;
            try {
                outcome = net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                    try {
                        Class<?> hotkey = Class.forName(
                                "net.marcloud.mcp.core.compat.patches.DwmHotkey");
                        return String.valueOf(hotkey.getMethod("toggleScreenForTool").invoke(null));
                    } catch (ClassNotFoundException e) {
                        return "dwm-not-present";
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        return "failed:" + e.getCause();
                    } catch (Throwable t) {
                        return "failed:" + t;
                    }
                });
            } catch (Exception e) {
                return error("open_overlay failed: " + e.getMessage());
            }
            if ("dwm-not-present".equals(outcome)) {
                return error("the overlay is not on the classpath, so there is no panel. That is a "
                        + "launch condition, not a result: start with scripts\\run-mcp-overlay.bat, "
                        + "not scripts\\run-mcp.bat");
            }
            if (outcome != null && outcome.startsWith("failed:")) {
                return error("the overlay could not be opened: " + outcome);
            }
            return ok("overlay " + outcome + " (the same method the RSHIFT hotkey calls)");
        });
    }

    private SyncToolSpecification serverInfo() {
        Tool tool = Tool.builder()
                .name("server_info")
                .title("Server identity and connection state")
                .description("Who is on the other end: the server's MOTD, its declared protocol "
                        + "version, the mod brand it pushed in MC|Brand, the online player count, "
                        + "and whether the channel is open. NOTE browserPingMs is the "
                        + "server-BROWSER ping measured BEFORE this session joined -- 1.8.9's play "
                        + "session carries no live latency figure at all -- so it is a stale "
                        + "connection indicator, not current ping. Anything the client cannot "
                        + "currently read is reported as unknown or 'not sent', never defaulted: "
                        + "a brand of 'vanilla' or a version of 0 would be a claim it never made.")
                .annotations(ToolAnnotations.builder().title("Server identity and connection state")
                        .readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                return ok(net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                    var mc = net.minecraft.client.Minecraft.getMinecraft();
                    var p = mc.thePlayer;
                    var sb = new StringBuilder();
                    sb.append("connected=").append(ctx.game() != null && ctx.game().isConnected())
                            .append('\n');
                    sb.append("inWorld=").append(ctx.game() != null && ctx.game().isInWorld())
                            .append('\n');
                    net.minecraft.client.multiplayer.ServerData sd = mc.getCurrentServerData();
                    if (sd != null) {
                        sb.append("name=").append(orUnknown(sd.serverName)).append('\n');
                        sb.append("address=").append(orUnknown(sd.serverIP)).append('\n');
                        sb.append("motd=").append(orUnknown(sd.serverMOTD)).append('\n');
                        // The field's own default is 47, which is 1.8.9; the declared value is
                        // reported as-is rather than second-guessed into a human phrase here.
                        sb.append("protocolVersion=").append(sd.version).append('\n');
                        sb.append("gameVersion=").append(orUnknown(sd.gameVersion)).append('\n');
                        sb.append("browserPingMs=").append(sd.pingToServer)
                                .append(" (measured BEFORE this session joined; 1.8.9's play "
                                        + "session carries no live latency)\n");
                        // ServerData.playerList is the S2F list HEADER text (a String), not a
                        // count, so it cannot answer "how many". The live count is the net
                        // handler's player map, which arrives from S38PacketPlayerListItem.
                        sb.append("playerListHeader=").append(orUnknown(sd.playerList))
                                .append('\n');
                        sb.append("playersOnline=").append(onlinePlayers()).append('\n');
                    } else {
                        sb.append("name=unknown\naddress=unknown\nmotd=unknown\n"
                                + "protocolVersion=unknown\ngameVersion=unknown\n"
                                + "browserPingMs=unknown (no server-browser entry for this "
                                + "session; it would have been measured BEFORE this session "
                                + "joined, and 1.8.9's play session carries no live latency)\n"
                                + "playersOnline=unknown\n");
                    }
                    // MC|Brand is only ever set by setClientBrand, which only the MC|Brand
                    // payload calls -- so null means the payload never arrived. A value of
                    // "vanilla" is therefore a brand the client REPORTED, not a default leaking
                    // out, and the two must not be worded the same way.
                    sb.append("modBrand=")
                            .append(p == null || p.getClientBrand() == null
                                    ? "not received (no MC|Brand payload from the server)"
                                    : p.getClientBrand() + " (reported by the server)")
                            .append('\n');
                    return sb.toString();
                }));
            } catch (Exception e) {
                return error("server_info failed: " + e.getMessage());
            }
        });
    }

    /**
     * How many players the server currently lists, or "unknown" when it cannot be read.
     *
     * <p>From {@code NetHandlerPlayClient.getPlayerInfoMap} (the map fed by S38), not from
     * {@code ServerData.playerList} -- that field is the S2F header TEXT and reading a count out
     * of it would report the length of a string. Null rather than 0, because "nobody listed"
     * and "we could not ask" are different and only one of them is a fact.
     */
    private String onlinePlayers() {
        try {
            var nh = ctx.game() == null ? null : ctx.game().netHandler();
            if (nh == null) {
                return "unknown";
            }
            var it = nh.getPlayerInfoMap();
            return it == null ? "unknown" : String.valueOf(it.size());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** An absent string is named, never defaulted: "" would read as a real, empty MOTD. */
    private static String orUnknown(String v) {
        return v == null || v.isBlank() ? "unknown" : v;
    }

    /**
     * What the search did with the numbers the caller sent, as a parenthetical, or {@code ""} when
     * it did exactly what they asked.
     *

     * <p>The reply has to describe the region actually searched, because a caller reads "no match
     * within 200 blocks" as a statement about 200 blocks. It is derived from the clamp rather than
     * spelled out at the two message sites, so the miss and the hit list cannot drift into naming
     * different bounds for the same call.
     */
    private static String clampNote(int askedRadius, int searchedRadius, int askedLimit, int limit) {
        List<String> parts = new ArrayList<>();
        if (searchedRadius != askedRadius) {
            parts.add("radius " + askedRadius + " clamped to " + searchedRadius);
        }
        if (limit != askedLimit) {
            parts.add("limit " + askedLimit + " clamped to " + limit);
        }
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    /**
     * How to make a thing, and what you are short of -- read-only.
     *
     * <p>Registered at R2 with CAP_WORLD_READ and no L4 privilege, exactly as {@code world_view} and
     * {@code find_block} are, because it changes nothing: the recipe table is a static list built at
     * client startup and the only live read is the player's inventory. The privilege that gates
     * actually PERFORMING a craft is a separate question and belongs to whatever tool eventually
     * does it -- see the note below on why that tool does not exist yet. Putting a write privilege on
     * a read would be the mirror of the defect this repo keeps finding: a gate that describes
     * something other than what the code does.
     */
    private SyncToolSpecification craftPlan() {
        Tool tool = Tool.builder()
                .name("craft_plan")
                .title("How to craft an item, and what is missing")
                .description("[requires: in-world] HOW to make an item and whether you can right now. "
                        + "Read-only: it changes nothing, crafts nothing, and moves no items. "
                        + "'item' is a registry name, namespace optional ('stick' or "
                        + "'minecraft:stick'), so a name read out of world_view or find_block can be "
                        + "fed straight in. Returns, per recipe, the grid as (row,col) cells with "
                        + "(0,0) at the top-left of the RECIPE's own bounding box — NOT a slot index, "
                        + "because the slot for a cell is row*containerWidth+col and the width depends "
                        + "on which window is open (2 for your own inventory grid, 3 for a crafting "
                        + "table), which this tool cannot see. A 'shapeless' recipe's cells are ONE "
                        + "valid arrangement rather than a required one. When you cannot craft it, "
                        + "every candidate recipe comes back with a bill naming each missing "
                        + "ingredient with held/needed counts — go fetch what it names rather than "
                        + "retrying. Recipes are listed in VANILLA's order, which matters because the "
                        + "game resolves a filled grid by taking the FIRST match in that same order. "
                        + "'unsupported' lists recipes that exist but have no fixed grid (armour "
                        + "dyeing, map and book cloning, repair, banners, fireworks): if 'unsupported' "
                        + "is non-empty while no recipes are listed, the game CAN make the item and "
                        + "this tool cannot tell you how — a different answer from there being no "
                        + "recipe. NOTE there is no tool that performs a craft yet: the multi-tick "
                        + "controller exists and is tested, but driving it needs a live handle on the "
                        + "open container window, which is not built. This tool plans; it does not act.")
                .annotations(ToolAnnotations.builder()
                        .title("How to craft an item, and what is missing")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "item", Map.of("type", "string",
                                "description", "registry name of the OUTPUT, namespace optional")),
                        List.of("item")))
                .build();

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            String item = str(args.get("item"));
            if (item == null || item.isBlank()) {
                return error("craft_plan needs 'item', e.g. \"stick\" or \"minecraft:wooden_pickaxe\"");
            }
            try {
                // Only the inventory section: asking for the default would sample the block grid too,
                // which at explore radius is tens of thousands of characters this answer never uses.
                net.marcloud.mcp.core.drivers.world.WorldView v =
                        net.marcloud.mcp.core.GameBridge.onGameThread(() ->
                                net.marcloud.mcp.core.drivers.world.WorldViewCapture.capture(
                                        ctx.game(),
                                        net.marcloud.mcp.core.drivers.world.ObserveProfile.SPARSE,
                                        1, List.of("inventory")));
                if (!v.present()) {
                    return ok("not in world");
                }
                // An inventory that could not be READ is not an empty inventory, and the
                // difference is the whole answer. CraftInventory.from(null) yields an empty
                // pack, so an unread one planned as "craftable=false, go and gather 4 sticks"
                // -- a confident, specific, wrong instruction built on a read that never
                // happened. The tool's own description promises the bill names what is
                // missing; that promise is only true if the pack it was read from was real.
                if (v.inventory() == null) {
                    return error("the inventory could not be read, so the missing materials are "
                            + "unknown. Nothing is claimed about what you have or lack");
                }
                var inv = net.marcloud.mcp.core.drivers.craft.CraftInventory.from(v.inventory());
                var plan = net.marcloud.mcp.core.drivers.craft.Craft.plan(item, inv);
                return ok(net.marcloud.mcp.core.io.http.Json.write(craftPlanMap(item, plan)));
            } catch (Exception e) {
                return error("craft_plan failed: " + e.getMessage());
            }
        });
    }

    /** Reference-free projection: only Maps, Lists and scalars cross to the model. */
    private static Map<String, Object> craftPlanMap(
            String asked, net.marcloud.mcp.core.drivers.craft.Craft.Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", asked);
        m.put("canCraft", plan.canCraft());
        if (plan.craftable() != null) {
            m.put("craftable", recipeMap(plan.craftable()));
        }
        if (!plan.blocked().isEmpty()) {
            List<Object> blocked = new ArrayList<>();
            for (var s : plan.blocked()) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("recipe", recipeMap(s.recipe()));
                List<Object> missing = new ArrayList<>();
                for (var miss : s.missing()) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("item", miss.item());
                    if (miss.anyMeta()) {
                        mm.put("anyMeta", true);
                    } else {
                        mm.put("meta", miss.meta());
                    }
                    mm.put("held", miss.available());
                    mm.put("need", miss.need());
                    missing.add(mm);
                }
                b.put("missing", missing);
                blocked.add(b);
            }
            m.put("blocked", blocked);
        }
        if (!plan.unsupported().isEmpty()) {
            m.put("unsupported", plan.unsupported());
        }
        return m;
    }

    private static Map<String, Object> recipeMap(
            net.marcloud.mcp.core.drivers.craft.RecipeView v) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", v.index());
        r.put("output", v.output());
        r.put("outputMeta", v.outputMeta());
        r.put("outputCount", v.outputCount());
        r.put("shapeless", v.shapeless());
        r.put("width", v.width());
        r.put("height", v.height());
        List<Object> cells = new ArrayList<>();
        for (var c : v.cells()) {
            cells.add(List.of(c.row(), c.col(), c.item(), c.anyMeta() ? -1 : c.meta()));
        }
        r.put("cells", cells);
        return r;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        return List.of();
    }

    private SyncToolSpecification captureScreen() {
        Tool tool = Tool.builder()
                .name("capture_screen")
                .title("Capture screen (PNG)")
                .description("[requires: GLFW-window] VALIDATION PROFILE — a rendered PNG frame for when you "
                        + "must literally SEE the scene (visual bugs, GUI layout, a build you can't infer). "
                        + "This is NOT your primary sense: world_view (structured, reference-free, cheap) is "
                        + "how you perceive and decide; capture_screen is a secondary validation/debug channel. "
                        + "Costs image tokens — never use it as a per-tick sensor. Downscaled to ~1024px long edge.")
                .annotations(ToolAnnotations.builder()
                        .title("Capture screen (PNG)")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "maxEdge", Map.of("type", "integer",
                                "description", "max long-edge pixels, 64-1600 (default 1024)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int maxEdge = net.marcloud.mcp.core.drivers.video.ScreenCapture.DEFAULT_MAX_EDGE;
            Object v = request.arguments() == null ? null : request.arguments().get("maxEdge");
            if (v instanceof Number num) {
                maxEdge = Math.max(64, Math.min(1600, num.intValue()));
            }
            final int edge = maxEdge;
            try {
                // glReadPixels must run on the game/GL thread; block for the bytes.
                byte[] png = net.marcloud.mcp.core.GameBridge.onGameThread(
                        () -> net.marcloud.mcp.core.drivers.video.ScreenCapture.capturePng(ctx.game(), edge));
                String b64 = java.util.Base64.getEncoder().encodeToString(png);
                var img = io.modelcontextprotocol.spec.McpSchema.ImageContent.builder(b64, "image/png").build();
                return io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                        .addContent(img)
                        .addTextContent("game view (" + png.length + " bytes PNG)")
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return error("capture_screen failed: " + e.getMessage());
            }
        });
    }

    // ---- typed send_* tools (W6) -------------------------------------------

    private SyncToolSpecification sendClientStatus() {
        Tool tool = Tool.builder()
                .name("do_client_status")
                .title("Send client status")
                .description("[requires: connected-to-server] Send a C16 client-status packet. "
                        + "status: PERFORM_RESPAWN (respawn after death / leave the end), "
                        + "REQUEST_STATS, or OPEN_INVENTORY_ACHIEVEMENT. Respawn is the main use.")
                .annotations(ToolAnnotations.builder().title("Send client status")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "status", enumProp("which respawn action.",
                                "PERFORM_RESPAWN", "REQUEST_STATS", "OPEN_INVENTORY_ACHIEVEMENT")
                        ), List.of("status")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String status = argString(request.arguments(), "status");
            if (status == null) {
                return error("status is required");
            }
            net.minecraft.network.play.client.C16PacketClientStatus.EnumState state;
            try {
                state = net.minecraft.network.play.client.C16PacketClientStatus.EnumState
                        .valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return error("unknown status '" + status + "' (PERFORM_RESPAWN|REQUEST_STATS|OPEN_INVENTORY_ACHIEVEMENT)");
            }
            return sendTyped(new net.minecraft.network.play.client.C16PacketClientStatus(state),
                    "client_status " + state.name());
        });
    }

    private SyncToolSpecification sendHeldItem() {
        Tool tool = Tool.builder()
                .name("do_select_slot")
                .title("Change held hotbar slot")
                .description("[requires: connected-to-server] Send a C09 held-item-change: select "
                        + "hotbar slot 0-8 as the active held item. "
                        + "**This reply is NOT confirmation and no outcome is measured.** The server "
                        + "sends nothing back for a held-item change (NetHandlerPlayServer:765-776 "
                        + "just updates state), so 'sent' here means the packet left, not that the "
                        + "slot changed. The only way to check is to read it back: world_view with "
                        + "sections=['inventory'], and compare inventory.selectedSlot. Neither "
                        + "read_player_state nor world_view's self section carries the held slot -- "
                        + "'selectedSlot' lives in the INVENTORY section only. If you need the tool "
                        + "to tell you, use act_set interact kind=hotbar, which is the path that "
                        + "re-reads the slot rather than asserting it.")
                .annotations(ToolAnnotations.builder().title("Change held hotbar slot")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "slot", Map.of("type", "integer", "description", "hotbar slot 0-8")),
                        List.of("slot")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Integer slot = argInt(request.arguments(), "slot");
            if (slot == null) {
                return error("slot (integer 0-8) is required");
            }
            if (slot < 0 || slot > 8) {
                return error("slot must be 0-8, got " + slot);
            }
            return sendTyped(new net.minecraft.network.play.client.C09PacketHeldItemChange(slot),
                    "held_item slot=" + slot);
        });
    }

    private SyncToolSpecification sendCloseWindow() {
        Tool tool = Tool.builder()
                .name("do_close_container")
                .title("Close a container window")
                .description("[requires: connected-to-server] Close the open container window, "
                        + "by driving the CLIENT's own close path rather than sending a raw C0D. "
                        + "windowId must be the window you think is open (0 = the player's own "
                        + "inventory); the reply names the one actually open if they differ, and "
                        + "refuses rather than closing a window you did not name. Sending C0D "
                        + "directly is what this tool used to do, and it had two failures at once: "
                        + "the server has no reply for it at all (NetHandlerPlayServer drops it "
                        + "silently, so there was nothing to confirm), and the CLIENT's own screen "
                        + "stayed open -- the window you asked to close was still there on screen "
                        + "and still eating input.")
                .annotations(ToolAnnotations.builder().title("Close a container window")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "windowId", Map.of("type", "integer", "description", "window id (0 = own inventory)")),
                        List.of("windowId")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Integer windowId = argInt(request.arguments(), "windowId");
            if (windowId == null) {
                return error("windowId (integer) is required");
            }
            // Drive closeScreen(), which is the one call that both tells the server and takes
            // the window off our own screen. The raw packet did only half of that, and the half
            // it skipped is the half a user can see.
            try {
                String result = net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                    var mc = ctx.game().mc();
                    var player = ctx.game().player();
                    if (mc == null || player == null) {
                        return "not in world";
                    }
                    int open = player.openContainer != null
                            ? player.openContainer.windowId : -1;
                    if (open < 0) {
                        return "no container is open";
                    }
                    if (open != windowId) {
                        // Refusing is the point. Closing the wrong window is worse than doing
                        // nothing: a caller that believed it had shut a chest would then act on
                        // an inventory that is still live.
                        return "MISMATCH: window " + windowId + " was named but window " + open
                                + " is the one open. Nothing was closed -- close the window you "
                                + "mean, or read world_view/gui_snapshot to see which is open";
                    }
                    mc.displayGuiScreen(null);
                    return "closed";
                });
                if ("closed".equals(result)) {
                    return ok("closed window " + windowId
                            + " -- the server was told and the screen is gone from this client");
                }
                return error("did not close window " + windowId + ": " + result);
            } catch (Exception e) {
                return error("close failed on the game thread: " + e);
            }
        });
    }

    private SyncToolSpecification sendDig() {
        Tool tool = Tool.builder()
                .name("do_dig")
                .title("Send player digging")
                .description("[requires: connected-to-server, in-world] Send a C07 player-digging packet. "
                        + "status: START_DESTROY_BLOCK / STOP_DESTROY_BLOCK / ABORT_DESTROY_BLOCK (mining a "
                        + "block at pos+face), or DROP_ITEM / DROP_ALL_ITEMS / RELEASE_USE_ITEM (pos/face "
                        + "ignored). pos is x,y,z; face is UP/DOWN/NORTH/SOUTH/EAST/WEST. "
                        + "For a BLOCK action the reply CONFIRMS the outcome by re-reading the world, "
                        + "not just the send: you get either 'confirmed in the world' naming what "
                        + "replaced the block, or a failure naming the block that is still there and "
                        + "the reasons the server would have refused. Dropping S25 is not a "
                        + "confirmation route -- vanilla does not send the break animation to the "
                        + "breaker (NetHandlerPlayServer checks the id), so it cannot be used to "
                        + "watch your own dig land.")
                .annotations(ToolAnnotations.builder().title("Send player digging")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "status", enumProp("which digging action.",
                                "START_DESTROY_BLOCK", "STOP_DESTROY_BLOCK", "ABORT_DESTROY_BLOCK",
                                "DROP_ITEM", "DROP_ALL_ITEMS", "RELEASE_USE_ITEM"),
                        "x", Map.of("type", "integer", "description", "block x (default 0 for item actions)"),
                        "y", Map.of("type", "integer", "description", "block y"),
                        "z", Map.of("type", "integer", "description", "block z"),
                        "face", enumProp("which side of the block (default UP).",
                                "UP", "DOWN", "NORTH", "SOUTH", "EAST", "WEST")),
                        List.of("status")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String status = argString(args, "status");
            if (status == null) {
                return error("status is required");
            }
            net.minecraft.network.play.client.C07PacketPlayerDigging.Action action;
            try {
                action = net.minecraft.network.play.client.C07PacketPlayerDigging.Action
                        .valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return error("unknown status '" + status + "'");
            }
            Integer xa = argInt(args, "x");
            Integer ya = argInt(args, "y");
            Integer za = argInt(args, "z");
            // The three block statuses act on a specific block: never invent (0,0,0)
            // and mine at the world origin on a live server. The item statuses
            // (DROP_*/RELEASE_USE_ITEM) genuinely ignore the position on the wire.
            boolean needsPos = action == net.minecraft.network.play.client.C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
                    || action == net.minecraft.network.play.client.C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK
                    || action == net.minecraft.network.play.client.C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK;
            if (needsPos && (xa == null || ya == null || za == null)) {
                return error("x, y and z are required for " + action.name()
                        + " (a block action targets a specific block; refusing to default to 0,0,0)");
            }
            int x = xa == null ? 0 : xa;
            int y = ya == null ? 0 : ya;
            int z = za == null ? 0 : za;
            net.minecraft.util.EnumFacing face = net.minecraft.util.EnumFacing.UP;
            String faceArg = argString(args, "face");
            if (faceArg != null) {
                try {
                    face = net.minecraft.util.EnumFacing.valueOf(faceArg.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return error("unknown face '" + faceArg + "'");
                }
            }
            CallToolResult sent = sendTyped(
                    new net.minecraft.network.play.client.C07PacketPlayerDigging(
                            action, new net.minecraft.util.BlockPos(x, y, z), face),
                    "dig " + action.name());
            if (Boolean.TRUE.equals(sent.isError()) || !needsPos) {
                // Nothing was sent, or the action does not act on a block: there is no world
                // change to go and look for, and a confirmation that cannot fail is decoration.
                return sent;
            }
            if (action == net.minecraft.network.play.client.C07PacketPlayerDigging.Action
                    .START_DESTROY_BLOCK) {
                // START is not a break and must not be measured like one. A live client showed
                // why: START on the block underfoot, polled for 600ms, correctly found the
                // grass still there -- because breaking it needs START held for the block's
                // break time and then a STOP. Reporting that as a FAILURE would be a lie in
                // the other direction: the dig is going fine, it just has not finished. So
                // START says exactly that, and names the way to get an outcome.
                return ok("sent dig START_DESTROY_BLOCK on (" + x + "," + y + "," + z + "). This "
                        + "STARTS mining and breaks nothing yet: the block comes down when a "
                        + "STOP_DESTROY_BLOCK follows it, and the break takes as long as the "
                        + "block's hardness allows. Send STOP after the hold and THAT reply is "
                        + "the one that reads the world back and confirms or refuses");
            }
            return confirmDig(sent, x, y, z);
        });
    }

    // ===== W7: six more typed do_* tools =====

    /** Resolve an ItemStack from item id-or-name + count + meta; null id → null stack (empty hand). */
    private static net.minecraft.item.ItemStack resolveStack(String itemId, int count, int meta) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        net.minecraft.item.Item item = net.minecraft.item.Item.getByNameOrId(itemId.trim());
        if (item == null) {
            return null;
        }
        return new net.minecraft.item.ItemStack(item, count, meta);
    }

    private SyncToolSpecification doSetAbilities() {
        Tool tool = Tool.builder()
                .name("do_set_abilities")
                .title("Set player abilities")
                .description("[requires: connected-to-server, in-world] NOT CONFIRMABLE -- the reply "
                        + "is 'sent' and nothing more. A C13 is mostly ignored by a survival server "
                        + "(processPlayerAbilities:1206-1210 reads isFlying and drops the rest), so "
                        + "there is no reply to read back and a sent packet is not evidence it took "
                        + "effect. Check world_view's self section for the ability flags, and treat "
                        + "no change there as the answer. Send a C13 player-abilities packet "
                        + "(flying/allow-flying/invulnerable/creative + fly/walk speed). Only include the "
                        + "flags you want to set; omitted booleans keep the server's current view is NOT "
                        + "assumed — you must pass the full intended state. flying/allowFlying/invulnerable/"
                        + "creative are booleans (default false); flySpeed/walkSpeed are floats (defaults "
                        + "0.05 / 0.1). Servers commonly reject client-asserted flight; this is mainly for "
                        + "creative/allowed contexts.")
                .annotations(ToolAnnotations.builder().title("Set player abilities")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "flying", Map.of("type", "boolean", "description", "currently flying"),
                        "allowFlying", Map.of("type", "boolean", "description", "may toggle flight"),
                        "invulnerable", Map.of("type", "boolean", "description", "damage disabled"),
                        "creative", Map.of("type", "boolean", "description", "creative-mode abilities"),
                        "flySpeed", Map.of("type", "number", "description", "fly speed (default 0.05)"),
                        "walkSpeed", Map.of("type", "number", "description", "walk speed (default 0.1)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            net.minecraft.entity.player.PlayerCapabilities caps =
                    new net.minecraft.entity.player.PlayerCapabilities();
            caps.isFlying = argBool(a, "flying", false);
            caps.allowFlying = argBool(a, "allowFlying", false);
            caps.disableDamage = argBool(a, "invulnerable", false);
            caps.isCreativeMode = argBool(a, "creative", false);
            caps.setFlySpeed(argFloat(a, "flySpeed", 0.05f));
            caps.setPlayerWalkSpeed(argFloat(a, "walkSpeed", 0.1f));
            return sendTyped(new net.minecraft.network.play.client.C13PacketPlayerAbilities(caps),
                    "set_abilities flying=" + caps.isFlying);
        });
    }

    /**
     * What one re-read of the creative slot can establish.
     *
     * <p>Deliberately has no {@code SENT} value, for the reason {@link TransferVerdict} has
     * none: "the packet left" is a fact about the wire, and an enum that can express it is an
     * enum that eventually gets returned as an outcome. A caller told OK here must be able to
     * act on it, and the only thing that makes it actionable is the slot's actual contents.
     */
    enum CreativeSlotVerdict {
        /** The slot now reads exactly the stack that was sent. */
        SET,
        /** The slot still reads something else: the server did not apply the write. */
        NOT_SET,
        /** The slot could not be read, so nothing at all is claimed. */
        UNREADABLE
    }

    /**
     * Did the slot become what was sent?
     *
     * <p>Judgment is by IDENTITY with the intended stack, not by "did anything change", and that
     * inversion is the whole point. NetHandlerPlayServer.processCreativeInventoryAction:1110-1117
     * writes the packet's stack verbatim or writes nothing at all, so the only success condition
     * is that the slot ends up holding the sent stack. A "did it change" test would read a
     * DIFFERENT stack arriving as a success, which is the lie this tool exists to stop.
     *
     * <p>Pure and package-visible so it is reachable headlessly, exactly like
     * {@link #verdictFor}: with the decision buried in the polling loop, swapping "re-read and
     * compare" for "report that it was sent" ran green, because with no client attached the
     * read-back never runs and nothing exercises the branch.
     *
     * <p>A CLEAR (null stack) is a real write too, so {@code wanted} being {@link SlotView#nothing()}
     * is compared like any other value instead of being waved through.
     */
    static CreativeSlotVerdict creativeVerdictFor(SlotView wanted, SlotView now) {
        if (wanted == null || now == null) {
            return CreativeSlotVerdict.UNREADABLE;
        }
        return wanted.equals(now) ? CreativeSlotVerdict.SET : CreativeSlotVerdict.NOT_SET;
    }

    /**
     * Send the C10, then read the slot back until the server's resync has landed or the budget
     * expires, and report what is actually in the slot.
     *
     * <p>The read-back is the CLIENT's inventory, and that is worth being precise about: it is
     * the server's own copy only because vanilla resyncs it. NetHandlerPlayServer writes into
     * {@code playerEntity.inventoryContainer} (line 1116), EntityPlayerMP.onUpdate calls
     * {@code openContainer.detectAndSendChanges()} every tick, and the client's
     * handleSetSlot applies that S2F for windowId 0 slots 36-44. So a change seen here came from
     * the server, not from this call — this tool never mutates the client's slot itself. The one
     * case where that inference does not hold is a creative GUI open on the client, which
     * mutates its own container locally before/alongside the packet; the message names that.
     */
    private CallToolResult setCreativeSlotAndConfirm(int slot, net.minecraft.item.ItemStack stack) {
        SlotView wanted = SlotView.of(stack);
        // Read BEFORE the send, and REFUSE if the slot cannot be read at all. This is the honest
        // answer to "the effect cannot be observed": a write whose outcome this tool can never
        // report is not a write worth making, and sending it would produce exactly the defect
        // this replaces -- a packet on the wire and a "sent" string with nothing behind it. It
        // also keeps the refusal distinguishable from a transport failure: "I could not read the
        // slot" and "there was no channel" are different facts and must not share a reply.
        SlotView before = readInventorySlot(slot);
        if (before == null) {
            return error("refusing to send: this client's inventory slot " + slot
                    + " could not be read (not in world, no player, or the slot index does not "
                    + "exist in this container), so the effect of a write could never be confirmed"
                    + " -- and a write that cannot be confirmed is the one thing this tool exists "
                    + "to refuse. Nothing was sent. Slot numbers are the CONTAINER's: 1-4 crafting "
                    + "grid, 5-8 armour, 9-35 main inventory, 36-44 hotbar; the server only accepts "
                    + "1-44, so anything outside that range names no writable slot at all.");
        }
        CallToolResult sent = sendTyped(
                new net.minecraft.network.play.client.C10PacketCreativeInventoryAction(slot, stack),
                "set_creative_slot " + slot);
        if (Boolean.TRUE.equals(sent.isError())) {
            return sent;
        }
        // One RTT plus a couple of ticks, same budget and same reasoning as confirmDig /
        // transferAndConfirm: the server applies the write and pushes it back on a later tick,
        // so reading sooner scores a write that is still in flight as a refusal.
        long deadline = System.nanoTime() + 600_000_000L;
        SlotView last = null;
        do {
            last = readInventorySlot(slot);
            if (creativeVerdictFor(wanted, last) == CreativeSlotVerdict.SET) {
                return ok("set creative slot " + slot + ": the slot now holds " + last
                        + (before != null && before.equals(last)
                                ? " -- which is what it already held, so this write changed "
                                + "nothing measurable here"
                                : " (it was " + before + " before)")
                        + " -- read back from the client's slot, not taken from the packet having "
                        + "been sent");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming the creative slot " + slot
                        + "; the packet was sent but the slot was not re-read");
            }
        } while (System.nanoTime() < deadline);
        if (last == null) {
            // The honest answer when the read itself is impossible. It is NOT "sent, unconfirmed":
            // that phrasing is a success string with a caveat stapled on, which is the exact hole
            // TransferVerdict closed by leaving SENT out of its enum. This is an ERROR -- the
            // outcome is unknown, and an unknown outcome must never be handed back as OK.
            return error("the C10 for slot " + slot + " was sent, but this client's slot " + slot
                    + " could not be read at all (not in world, or the inventory is unreachable), "
                    + "so NO outcome was measured and none is claimed. The write may or may not "
                    + "have landed. Read it back yourself with world_view sections=['inventory'] "
                    + "before you act on it.");
        }
        return error("the creative-slot packet was sent and 600ms later slot " + slot + " reads "
                + last + " rather than " + wanted + (before == null ? "" : " (it was " + before
                        + " before) ")
                + creativeRefusalDiagnosis(slot, wanted));
    }

    /**
     * Why a C10 write does nothing, from the server's own conditions rather than a guess list.
     *
     * <p>{@code NetHandlerPlayServer.processCreativeInventoryAction:1078} gates the ENTIRE
     * handler on {@code theItemInWorldManager.isCreative()}. Outside creative mode the packet is
     * not refused, not answered and not logged — it simply cannot do anything, so "sent" is the
     * only thing this tool could honestly have reported, and the live bug was exactly that.
     * A caller deserves the reason instead.
     *
     * <p>The client's {@code capabilities.isCreativeMode} is a faithful mirror of the server's
     * gamemode: ItemInWorldManager.setGameType:52-57 applies the new gamemode's capabilities and
     * immediately pushes them with S39, which the client copies into the player verbatim
     * (NetHandlerPlayClient.handlePlayerAbilities). So this is the server's own answer arriving
     * over the wire, not a client-side guess.
     */
    private String creativeRefusalDiagnosis(int slot, SlotView wanted) {
        StringBuilder sb = new StringBuilder();
        try {
            Boolean creative = net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var p = ctx.game().player();
                return p == null || p.capabilities == null ? null : p.capabilities.isCreativeMode;
            });
            if (creative == null) {
                sb.append("Creative mode could not be read (not in world), so the usual cause "
                        + "cannot be ruled out. ");
            } else if (!creative) {
                sb.append("This client is NOT in creative mode, and that is why the write did "
                        + "nothing: processCreativeInventoryAction:1078 wraps its whole body in "
                        + "theItemInWorldManager.isCreative(), so outside creative a C10 is "
                        + "dropped silently -- no reply, no error, nothing to read back. Get into "
                        + "creative first (send_chat with /gamemode 1) and this write will land. ");
            } else {
                sb.append("Creative mode IS on, so the server processed the packet and still "
                        + "refused the slot. ");
            }
        } catch (Exception e) {
            sb.append("Creative mode could not be read (" + e + "). ");
        }
        // flag1 at :1104 — the server's own slot bound. Out of this range the packet addresses no
        // slot at all, so nothing can ever change and no amount of retrying will help.
        if (slot < 1 || slot >= 36 + 9) {
            sb.append("ALSO: slot ").append(slot).append(" is outside the range the server "
                    + "accepts. Line 1104 requires slotId >= 1 && slotId < 45, so slots 1-4 are "
                    + "the crafting grid, 5-8 the armour slots, 9-35 the main inventory and 36-44 "
                    + "the hotbar. Slot 0 is the crafting OUTPUT and is never writable, which is "
                    + "why a 'slot 0' write reports success and does nothing at all. ");
        }
        if (!wanted.empty()) {
            sb.append("Count must be 1-64 and meta >= 0 (line 1106 rejects anything else "
                    + "silently), and the item must be a real registry item. ");
        }
        sb.append("Slot numbers here are the CONTAINER's, not the inventory index world_view "
                + "prints: in your own window inventory index N is container slot N+9, and "
                + "hotbar slot 0 is container slot 36.");
        return sb.toString();
    }

    /**
     * One slot of the player's own inventory, by CONTAINER index, on the game thread.
     *
     * <p>{@code inventoryContainer}, never {@code openContainer}: a C10 addresses the player's
     * own window, and reading a container slot out of whatever window happens to be open would
     * read a different slot entirely whenever a chest is up.
     *
     * <p>Returns null when unreadable, which is deliberately distinct from an empty slot — an
     * empty slot is a measurable "the clear worked", and folding the two together would let a
     * failed read report as a successful clear.
     */
    private SlotView readInventorySlot(int containerSlot) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var p = ctx.game().player();
                if (p == null || p.inventoryContainer == null) {
                    return null;
                }
                if (containerSlot < 0 || containerSlot >= p.inventoryContainer.inventorySlots.size()) {
                    return null;
                }
                var slot = p.inventoryContainer.getSlot(containerSlot);
                return slot == null ? null : SlotView.of(slot.getStack());
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private SyncToolSpecification doSetCreativeSlot() {
        Tool tool = Tool.builder()
                .name("do_set_creative_slot")
                .title("Set creative inventory slot")
                .description("[requires: connected-to-server, creative mode] CONFIRMED AGAINST THE "
                        + "SLOT, not against the wire: the reply reports what slot 'slot' actually "
                        + "holds after the write, re-read from this client once the server's resync "
                        + "has landed. You get 'the slot now holds <item>' only when the slot really "
                        + "does; a refused write comes back as an ERROR naming the slot's real "
                        + "contents and the reason (usually: this client is not in creative mode, "
                        + "and outside creative the server drops a C10 with no reply at all -- see "
                        + "NetHandlerPlayServer.processCreativeInventoryAction:1078). 'sent' never "
                        + "appears as an outcome. "
                        + "Send a C10 creative-inventory action: place an item stack into inventory "
                        + "slot 'slot'. item is an item id or name (e.g. 'minecraft:diamond' or "
                        + "'264'); count/meta default 1/0. Omit item (or empty) to CLEAR the slot "
                        + "(null stack), which is confirmed by the slot reading empty. "
                        + "SLOT NUMBERING is the CONTAINER's, not the inventory index world_view "
                        + "prints: 1-4 the 2x2 crafting grid, 5-8 armour, 9-35 the main inventory, "
                        + "36-44 the hotbar; inventory index N is container slot N+9 and hotbar "
                        + "slot 0 is container slot 36. The server only accepts 1-44 (line 1104) -- "
                        + "slot 0 is the crafting OUTPUT and is never writable, so a 'slot 0' write "
                        + "reports a refusal rather than a fake success. "
                        + "CAVEAT: with a creative inventory GUI open on this client, vanilla's own "
                        + "container mutates locally, so a slot that already looks right may be the "
                        + "client's copy rather than the server's. Close the creative screen for an "
                        + "unambiguous read-back.")
                .annotations(ToolAnnotations.builder().title("Set creative inventory slot")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "slot", Map.of("type", "integer", "description",
                                "CONTAINER slot index 1-44 (9-35 main inventory, 36-44 hotbar); "
                                        + "slot 0 is the crafting output and is never writable"),
                        "item", stringProp("item id or name; omit/empty = clear slot"),
                        "count", Map.of("type", "integer",
                                "description", "stack size 1-64 (default 1; 0 or >64 is refused "
                                        + "silently by the server)"),
                        "meta", Map.of("type", "integer",
                                "description", "damage/meta, must be >= 0 (default 0)")),
                        List.of("slot")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            Integer slot = argInt(a, "slot");
            if (slot == null) {
                return error("slot (integer) is required");
            }
            String item = argString(a, "item");
            net.minecraft.item.ItemStack stack = resolveStack(item,
                    argIntOr(a, "count", 1), argIntOr(a, "meta", 0));
            if (item != null && !item.isBlank() && stack == null) {
                return error("unknown item '" + item + "'");
            }
            return setCreativeSlotAndConfirm(slot, stack);
        });
    }
    private SyncToolSpecification doPlaceBlock() {
        Tool tool = Tool.builder()
                .name("do_place_block")
                .title("Place block / use item on block")
                .description("[requires: connected-to-server, in-world] Send a C08 block-placement: use the "
                        + "held item against the block at pos (x,y,z) on the given face. face is UP/DOWN/"
                        + "NORTH/SOUTH/EAST/WEST. hitX/hitY/hitZ (0..1, default 0.5) are the in-face hit "
                        + "offset. Optional item id/name+count/meta describes the held stack the server "
                        + "should see; omit to send an empty stack (server uses your actual held item). "
                        + "A block action targets a specific block, so x,y,z are required.")
                .annotations(ToolAnnotations.builder().title("Place block")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "x", Map.of("type", "integer", "description", "block x"),
                        "y", Map.of("type", "integer", "description", "block y"),
                        "z", Map.of("type", "integer", "description", "block z"),
                        "face", enumProp("which side of the target block; the block goes in the cell on "
                                + "this side.", "UP", "DOWN", "NORTH", "SOUTH", "EAST", "WEST"),
                        "hitX", Map.of("type", "number", "description", "in-face x 0..1 (default 0.5)"),
                        "hitY", Map.of("type", "number", "description", "in-face y 0..1 (default 0.5)"),
                        "hitZ", Map.of("type", "number", "description", "in-face z 0..1 (default 0.5)"),
                        "item", stringProp("held item id or name (optional)"),
                        "count", Map.of("type", "integer", "description", "held count (default 1)"),
                        "meta", Map.of("type", "integer", "description", "held meta (default 0)")),
                        List.of("x", "y", "z", "face")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            Integer x = argInt(a, "x");
            Integer y = argInt(a, "y");
            Integer z = argInt(a, "z");
            if (x == null || y == null || z == null) {
                return error("x, y and z are required (a block placement targets a specific block)");
            }
            String faceArg = argString(a, "face");
            if (faceArg == null) {
                return error("face is required (UP|DOWN|NORTH|SOUTH|EAST|WEST)");
            }
            net.minecraft.util.EnumFacing face;
            try {
                face = net.minecraft.util.EnumFacing.valueOf(faceArg.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return error("unknown face '" + faceArg + "'");
            }
            String item = argString(a, "item");
            // Absent 'item' means "whatever the player is holding", which is what the property
            // has always claimed to be ("held item id or name (optional)"). It did not: the
            // stack came back null and the C08 went out EMPTY-HANDED, so a caller who meant to
            // place the dirt they were carrying placed nothing, and was told the click was
            // empty-handed by design. Measured on a live client holding Dirt x3 -- the tool
            // reported "with NO stack ... changes nothing" three times with a full hand.
            //
            // An explicit 'item' still wins, and an explicit item that resolves to nothing is
            // still an error, so this changes only the case the docs already described.
            net.minecraft.item.ItemStack stack = resolveStack(item,
                    argIntOr(a, "count", 1), argIntOr(a, "meta", 0));
            // An EXPLICIT item that resolves to nothing is an error and must be reported as
            // one -- "place a stick here" must not quietly become "place whatever is held".
            // The check therefore runs on the explicit resolution, BEFORE the held fallback.
            if (item != null && !item.isBlank() && stack == null) {
                return error("unknown item '" + item + "'");
            }
            // No held-item fallback here, and that is deliberate. NetHandlerPlayServer
            // line 587 reads `player.inventory.getCurrentItem()` and NEVER looks at the stack
            // this packet carries, so putting the held stack on the wire cannot change whether
            // the server places anything -- it only changes the wording of the confirmation.
            // A change justified by that premise is dead weight, so it was reverted.
            CallToolResult sent = sendTyped(
                    new net.minecraft.network.play.client.C08PacketPlayerBlockPlacement(
                            new net.minecraft.util.BlockPos(x, y, z), face.getIndex(), stack,
                            argFloat(a, "hitX", 0.5f), argFloat(a, "hitY", 0.5f),
                            argFloat(a, "hitZ", 0.5f)),
                    "place_block " + x + "," + y + "," + z);
            if (Boolean.TRUE.equals(sent.isError())) {
                return sent;
            }
            return confirmPlace(x, y, z, face, stack);
        });
    }

    /**
     * Did the block actually appear, or did the server simply accept the packet?
     *
     * <p>The same contract as {@link #confirmDig}, for the same reason: "sent place_block" is a
     * transport fact, and the whole point of this project is that it drives a REAL client. A
     * caller told OK must be able to act on it.
     *
     * <p>Placement has one complication digging does not: the cell is usually EMPTY before the
     * placement, so there is no "before" name to compare against. The judgment is therefore
     * inverted -- did the cell become the thing that was sent? -- which is the right question
     * here anyway, since a placement that silently became a DIFFERENT block is a failure even
     * though the cell did change.
     *
     * <p>A placement against a cell that already held the right block cannot be confirmed this
     * way and says so, rather than reporting a success nobody measured.
     */
    private CallToolResult confirmPlace(int x, int y, int z, net.minecraft.util.EnumFacing face,
                                        net.minecraft.item.ItemStack stack) {
        // The item becomes a BLOCK before the world can name it, and not every item becomes
        // one: `Block.getBlockFromItem` is null for a sword, a stick, anything that is not
        // placeable. A null here is the honest answer -- there is no cell state to expect.
        String wanted = null;
        if (stack != null && stack.getItem() != null) {
            net.minecraft.block.Block asBlock =
                    net.minecraft.block.Block.getBlockFromItem(stack.getItem());
            if (asBlock != null) {
                Object loc = net.minecraft.block.Block.blockRegistry.getNameForObject(asBlock);
                if (loc != null) {
                    wanted = loc.toString();
                }
            }
        }
        if (wanted == null) {
            // This is the branch an ordinary call reaches: 'item' omitted means the packet
            // carries no stack, so there is no expected block name to read back -- NOT that
            // nothing will happen. The server places from ITS OWN selected hotbar slot
            // (NetHandlerPlayServer.processPlayerBlockPlacement), so the honest thing is to ask
            // what the server is holding rather than declare the click empty-handed. A live
            // client had the client on slot 0 holding dirt while the server sat on an empty
            // slot; the tool said "an empty-handed click ... by design" and every placement
            // silently did nothing.
            return ok("sent place_block at (" + x + "," + y + "," + z
                    + ") with NO stack, so there is no block name to verify against -- pass "
                    + "'item' (the block you expect) and the outcome can be confirmed by reading "
                    + "the cell back. " + serverHeldDiagnosis(null));
        }
        String want = wanted.toString().replace("minecraft:", "");
        // A placement goes in the cell ADJACENT to the face, not the cell the face is on.
        int px = x + face.getFrontOffsetX();
        int py = y + face.getFrontOffsetY();
        int pz = z + face.getFrontOffsetZ();
        String before = readBlockName(px, py, pz);
        if (want.equals(before)) {
            return ok("(" + px + "," + py + "," + pz + ") already holds " + want + ", so the "
                    + "placement had nothing to change. No outcome was measured and none is claimed");
        }
        long deadline = System.nanoTime() + 600_000_000L;
        String last = before;
        while (System.nanoTime() < deadline) {
            last = readBlockName(px, py, pz);
            if (want.equals(last)) {
                return ok("sent place_block, and (" + px + "," + py + "," + pz + ") is now " + last
                        + " -- confirmed in the world, not just on the wire");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming the placement at (" + px + "," + py + ","
                        + pz + "); the packet was sent but the world was not re-read");
            }
        }
        return error("the placement packet was sent and 600ms later (" + px + "," + py + "," + pz
                + ") reads " + (last == null ? "nothing" : last) + " rather than " + want
                + (before == null ? "" : " (it was empty before)") + ". "
                + serverHeldDiagnosis(want)
                + " Re-read the cell before retrying.");
    }

    /**
     * Name the real reason a placement did not happen, instead of listing four guesses.
     *
     * <p>NetHandlerPlayServer.processPlayerBlockPlacement takes the stack from
     * {@code player.inventory.getCurrentItem()} and never from the packet, so a placement can
     * only happen if the SERVER's selected hotbar slot holds something. The client's view is not
     * evidence of that: the two desynchronise, and a live client was found with the client on
     * slot 0 holding dirt while the server sat on slot 8 holding nothing -- every placement then
     * failed with "out of reach (vanilla's 64)" in the message, which is true and useless. The
     * caller retried reach, occlusion and aim for hours.
     *
     * <p>So this reads the server's actual answer. It is the difference between a blocked reason
     * that merely happens to be nearby-true and one that names the cause.
     */
    private String serverHeldDiagnosis(String want) {
        try {
            String held = net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                // GameBridge.game() hands back a GameAccess wrapper, not the Minecraft
                // instance, so the field has to be read off the real singleton.
                var mc = net.minecraft.client.Minecraft.getMinecraft();
                // Minecraft.theIntegratedServer is private with no getter in this tree.
                var sf = net.minecraft.client.Minecraft.class
                        .getDeclaredField("theIntegratedServer");
                sf.setAccessible(true);
                var srv = (net.minecraft.server.integrated.IntegratedServer) sf.get(mc);
                if (srv == null) {
                    return "no integrated server, so the server's view is unreadable";
                }
                var field = net.minecraft.server.MinecraftServer.class
                        .getDeclaredField("serverConfigManager");
                field.setAccessible(true);
                var scm = (net.minecraft.server.management.ServerConfigurationManager)
                        field.get(srv);
                var list = scm.getPlayerList();
                if (list == null || list.isEmpty()) {
                    return "the server has no player entry";
                }
                var inv = list.get(0).inventory;
                var heldStack = inv.getCurrentItem();
                return "slot " + inv.currentItem + " holds "
                        + (heldStack == null ? "nothing" : heldStack.getDisplayName());
            });
            if (held == null) {
                return "The SERVER's held item could not be read, so the usual cause -- the "
                        + "server's selected hotbar slot being empty -- cannot be ruled out.";
            }
            if (held.endsWith("holds nothing")) {
                return "The SERVER's selected hotbar slot is empty (" + held + "), and the server "
                        + "places from THAT slot, not from the client's: this placement could not "
                        + "have happened. Select a hotbar slot with do_select_slot and re-read the "
                        + "server's held item before retrying -- chasing reach or aim is wasted "
                        + "effort while the server holds nothing.";
            }
            return "The server does hold something (" + held + "), so it can place; this call "
                    + "simply cannot be verified, because the packet named no block. Re-read the "
                    + "cell to see what actually happened.";
        } catch (Exception e) {
            return "The server's held item could not be read (" + e + ").";
        }
    }

    private SyncToolSpecification doClickSlot() {
        Tool tool = Tool.builder()
                .name("do_click_slot")
                .title("Click a container slot")
                .description("[requires: connected-to-server, container open] Send a C0E click-window: "
                        + "windowId (0 = own inventory), slotId, button (0=left,1=right), mode "
                        + "(0=pickup,1=shift,2=hotbar-swap,3=middle,4=drop,5=drag,6=double), actionNumber "
                        + "(an ECHO TAG, see below), and the clicked item (item id/name+count/meta). "
                        + "Container-protocol primitive. "
                        + "WHAT DECIDES ACCEPTANCE: only the ITEM CLAIM. The server replays your click "
                        + "against its own container and compares ITS result to the item you sent; "
                        + "equal = accepted, different = rejected. The item is therefore the "
                        + "load-bearing argument, not an afterthought, and what it must hold is the "
                        + "click's RESULT, which is NOT 'whatever is in the slot': mode 0 wants the "
                        + "slot's contents BEFORE the click (omit for an empty slot, and also for a "
                        + "throw-away click at slotId -999); mode 1 wants the contents before the "
                        + "shift, but OMIT it if nothing would actually fit in the destination; and "
                        + "for modes 2,3,4,5,6 the server's result is ALWAYS empty, so you must OMIT "
                        + "item for those or you are guaranteed a rejection. actionNumber is NOT "
                        + "validated on this packet -- the server compares it to no counter of its "
                        + "own, it only echoes it back -- so a stale or repeated number is harmless "
                        + "and the default 0 is fine. "
                        + "WHAT REJECTION COSTS: it is not a veto. The server has ALREADY applied the "
                        + "click to its container by the time it compares; it then resyncs every slot "
                        + "back to you (that resync, not your item, is the truth) and LOCKS the "
                        + "window, after which every later click on it is SILENTLY DROPPED -- no "
                        + "error, no reply, your clicks simply stop happening. The lock clears when "
                        + "the same actionNumber comes back on a C0F confirm-transaction, which the "
                        + "vanilla client sends by itself for a window it still recognises, so it "
                        + "normally heals within one round trip -- but anything you click inside that "
                        + "gap is lost, and a lock on windowId 0 cannot be cleared by reopening, "
                        + "since your own inventory container lives as long as the session. "
                        + "A SECOND SILENT DROP: the server ignores the packet outright, doing "
                        + "nothing at all, when windowId is not the window it currently has open -- "
                        + "so windowId 0 while a chest is open is a no-op, not an inventory click. "
                        + "This tool reports only that the packet was SENT; neither drop is visible "
                        + "in its result. Confirm rather than assuming a click landed: for windowId 0 "
                        + "read world_view with sections=['inventory'] and compare the slots you "
                        + "claimed; for a server container read gui_snapshot, whose per-slot "
                        + "slotNumber/count is the resync the server pushed over you, so a dropped "
                        + "click is visible as unchanged slots. The one packet-level verdict is the "
                        + "S32 confirm-transaction, which packet_view projects as accepted=true/false "
                        + "with its meaning spelled out -- but only once seam_netty_install has put "
                        + "the tap in. If clicks stop having any effect, suspect the lock.")
                .annotations(ToolAnnotations.builder().title("Click a container slot")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "windowId", Map.of("type", "integer", "description", "window id (0 = own inventory)"),
                        "slotId", Map.of("type", "integer", "description", "slot index"),
                        "button", Map.of("type", "integer", "description", "0=left, 1=right (default 0)"),
                        "mode", Map.of("type", "integer", "description", "click mode 0-6 (default 0)"),
                        "actionNumber", Map.of("type", "integer", "description", "transaction id (default 0)"),
                        "item", stringProp("clicked item id/name; omit = empty"),
                        "count", Map.of("type", "integer", "description", "count (default 1)"),
                        "meta", Map.of("type", "integer", "description", "meta (default 0)")),
                        List.of("windowId", "slotId")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            Integer windowId = argInt(a, "windowId");
            Integer slotId = argInt(a, "slotId");
            if (windowId == null || slotId == null) {
                return error("windowId and slotId (integers) are required");
            }
            String item = argString(a, "item");
            net.minecraft.item.ItemStack stack = resolveStack(item,
                    argIntOr(a, "count", 1), argIntOr(a, "meta", 0));
            if (item != null && !item.isBlank() && stack == null) {
                return error("unknown item '" + item + "'");
            }
            return sendTyped(new net.minecraft.network.play.client.C0EPacketClickWindow(
                    windowId, slotId, argIntOr(a, "button", 0), argIntOr(a, "mode", 0),
                    stack, (short) argIntOr(a, "actionNumber", 0)),
                    "click_slot win=" + windowId + " slot=" + slotId);
        });
    }
    /**
     * Resolve a live {@link net.minecraft.entity.Entity} by id on the GAME THREAD
     * (world/entity state is not thread-safe). Returns null if not in world or no
     * such entity. The Entity reference is used only to build the packet inside the
     * same game-thread call and never escapes to a worker thread.
     */
    private net.minecraft.entity.Entity resolveEntityOnGameThread(int entityId) throws Exception {
        return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
            net.minecraft.client.multiplayer.WorldClient w = ctx.game().world();
            return w == null ? null : w.getEntityByID(entityId);
        });
    }

    // ===== do_use_entity / do_entity_action: what a re-read can actually establish =====
    //
    // The honest answer is NOT uniform across these actions, and that is the whole point. Read off
    // NetHandlerPlayServer.processEntityAction:846 and processUseEntity:905:
    //
    //   START/STOP_SNEAKING  server does setSneaking -> setFlag(1) -> datawatcher index 0 bit 1,
    //                        which EntityTrackerEntry.func_151261_b sends to the tracked player
    //                        ITSELF (it is an EntityPlayerMP), and the client applies it in
    //                        handleEntityMetadata. So the flag IS the server's answer. CONFIRMABLE.
    //   START/STOP_SPRINTING the same server write, BUT EntityPlayerSP.onLivingUpdate:800-819 also
    //                        calls setSprinting() from the player's OWN keys every tick, clobbering
    //                        the bit within one tick of any reply. NOT separable. NOT CONFIRMABLE.
    //   STOP_SLEEPING        server calls wakeUpPlayer -> S0B animation type 2 -> the client clears
    //                        `sleeping` in handleAnimation, and only trySleep sets it. CONFIRMABLE.
    //   RIDING_JUMP          server acts only on a SADDLED horse; jumpPower is a plain private field,
    //                        never in a datawatcher, so nothing about it ever reaches the client.
    //   OPEN_INVENTORY       server acts only on a TAME horse, and opens THE HORSE'S chest --
    //                        NOT the caller's own inventory. Observable as a screen, and refusable.
    //   ATTACK               damages the target; health is datawatcher index 6, pushed as S1C
    //                        metadata, readable via getHealth(). CONFIRMABLE.
    //   INTERACT/INTERACT_AT server discards interactWith's boolean and never sends it back, and the
    //                        base Entity.interactAt returns false for everything but an armor
    //                        stand. There is no single intended state to compare against, so
    //                        neither can be honestly confirmed.

    /** What one re-read can establish. No SENT, and no UNCONFIRMED either, as TransferVerdict has none. */
    enum EntityActionVerdict {
        /** The state now reads exactly what the action was supposed to produce. */
        CONFIRMED,
        /** The state still reads the opposite: the server did not apply it. */
        NOT_CONFIRMED,
        /** The state could not be read, so nothing at all is claimed. */
        UNREADABLE
    }

    /** What an attack's effect can establish, judged on the target's health and nothing else. */
    enum AttackVerdict {
        /** The target's health dropped, or the target left the world -- a lethal hit. */
        HIT,
        /** Health is unchanged: the server did not apply the damage. */
        NOT_HIT,
        /** Health could not be read, so nothing is claimed. */
        UNREADABLE
    }

    /**
     * Why ATTACK would be refused, or null when it may be sent.
     *
     * <p>Pure and package-visible so it is reachable headlessly, for the reason
     * {@link #creativeVerdictFor} is: the polling loop needs a live client, and a rule that can only
     * be exercised live is a rule nobody checks. NetHandlerPlayServer.processUseEntity:932 is what
     * this encodes -- attacking an item, an XP orb, an arrow, or yourself is answered with
     * {@code kickPlayerFromServer}, and a non-living target has no health to read back, so for both
     * shapes the attack's outcome could never be reported. Refusing is the only honest reply.
     */
    enum AttackTarget {
        /** An EntityLivingBase: it has health, so the damage is observable. */
        LIVING,
        /** Item / XP orb / arrow / self -- the server KICKS you for attacking these. */
        KICKS_ON_ATTACK,
        /** Some other non-living entity: legal to hit, but there is no health to read back. */
        NOT_LIVING
    }

    static String attackRefusalFor(AttackTarget target) {
        if (target == null) {
            return "refusing to send: that entity is not in this world, so an attack on it could "
                    + "never be confirmed -- and an action whose outcome can never be reported is "
                    + "the one thing this tool exists to refuse. Nothing was sent.";
        }
        if (target == AttackTarget.KICKS_ON_ATTACK) {
            return "refusing to send: attacking an item, an XP orb, an arrow, or yourself is "
                    + "answered by processUseEntity:932 with kickPlayerFromServer, so this would "
                    + "kick you off the server rather than hit anything. Nothing was sent. If you "
                    + "meant to collect or attack a real mob, resolve its entityId from "
                    + "world_view sections=['entities'] and pass that id.";
        }
        if (target == AttackTarget.NOT_LIVING) {
            return "refusing to send: that entity has no health, so there is nothing to read back "
                    + "and the attack's outcome could never be confirmed. Nothing was sent. Only "
                    + "living entities (mobs, animals, players) are attackable through this tool.";
        }
        return null;
    }

    /**
     * Did the read state come out as the action required?
     *
     * <p>Judged by IDENTITY with the wanted value, not by "did anything change". For STOP_SNEAKING
     * on a player who was already standing, both the applied and the ignored case read false, and
     * the honest answer for the second is NOT_CONFIRMED -- a "did it change" test would score that
     * ignored packet as a success, which is the lie this exists to stop.
     */
    static EntityActionVerdict entityActionVerdictFor(Boolean observed, boolean wanted) {
        if (observed == null) {
            return EntityActionVerdict.UNREADABLE;
        }
        return observed == wanted ? EntityActionVerdict.CONFIRMED : EntityActionVerdict.NOT_CONFIRMED;
    }

    /**
     * Did the target's health take the hit?
     *
     * <p>A target that has LEFT THE WORLD counts as a hit, because for a living entity that is what
     * a lethal hit looks like from the client: the server destroys the entity and the client's copy
     * is gone. Reading it as NOT_HIT would invert the one outcome that matters most, and reading it
     * as UNREADABLE would refuse to name a kill the caller can plainly see.
     */
    static AttackVerdict attackVerdictFor(Float beforeHealth, Float afterHealth, boolean targetPresent) {
        if (beforeHealth == null) {
            return AttackVerdict.UNREADABLE;
        }
        if (!targetPresent) {
            return AttackVerdict.HIT;
        }
        if (afterHealth == null) {
            return AttackVerdict.UNREADABLE;
        }
        return afterHealth < beforeHealth ? AttackVerdict.HIT : AttackVerdict.NOT_HIT;
    }

    /** The mount this player is on, as the server's own two gates will see it. Null when unreadable. */
    record RideState(boolean riding, boolean horse, boolean saddled, boolean tame) {}

    /**
     * Why this entity action could never take effect, or null when it may be sent.
     *
     * <p>Two C0B actions are gated on the mount in {@code processEntityAction:878-889} and are not
     * refusals of taste -- with the gate closed the handler body is empty, so the packet cannot do
     * anything at all and "sent" would be the only thing this tool could honestly have reported.
     * RIDING_JUMP additionally reaches {@code EntityHorse.setJumpPower}, whose whole body is
     * {@code if (isHorseSaddled())}; OPEN_INVENTORY reaches {@code openGUI}, which requires
     * {@code isTame()}. And note WHAT OPEN_INVENTORY opens: {@code displayGUIHorse} -- the horse's
     * chest. It has never opened the caller's own inventory, which is the C16 the E key sends.
     */
    static String entityActionRefusalFor(
            net.minecraft.network.play.client.C0BPacketEntityAction.Action action, RideState s) {
        if (action != net.minecraft.network.play.client.C0BPacketEntityAction.Action.RIDING_JUMP
                && action != net.minecraft.network.play.client.C0BPacketEntityAction.Action.OPEN_INVENTORY) {
            return null;
        }
        if (s == null) {
            return "refusing to send: whether you are on a horse could not be read (not in world), "
                    + "and " + action.name() + " does nothing at all unless you are, so its outcome "
                    + "could never be confirmed. Nothing was sent.";
        }
        if (!s.riding()) {
            return "refusing to send: " + action.name() + " is a no-op unless you are RIDING "
                    + "something -- processEntityAction:878-889 checks ridingEntity instanceof "
                    + "EntityHorse and otherwise does nothing. You are not riding anything, so this "
                    + "packet could not do anything and could not be confirmed. Nothing was sent. "
                    + "Mount a horse with do_use_entity action=INTERACT first.";
        }
        if (!s.horse()) {
            return "refusing to send: " + action.name() + " only works while riding a HORSE, and "
                    + "you are riding something else, so processEntityAction would skip it entirely. "
                    + "Nothing was sent.";
        }
        if (action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.RIDING_JUMP
                && !s.saddled()) {
            return "refusing to send: RIDING_JUMP reaches EntityHorse.setJumpPower, whose whole "
                    + "body is guarded by isHorseSaddled(), so an unsaddled horse makes this packet "
                    + "do nothing. Put a saddle in slot 0 of the horse's inventory first. Nothing "
                    + "was sent.";
        }
        if (action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.OPEN_INVENTORY
                && !s.tame()) {
            return "refusing to send: OPEN_INVENTORY reaches EntityHorse.openGUI, which is guarded "
                    + "by isTame(), so an untamed horse makes this packet do nothing. Nothing was sent.";
        }
        return null;
    }

    /**
     * The reply for the two actions whose effect can never be observed.
     *
     * <p>Kept bare but SAYING SO, which is legitimate and already the shape of four other tools in
     * this file. The defect this replaces had no disclosure at all, and the disclosure has to live in
     * the REPLY as well as the description: the description is read when choosing a tool, the reply
     * is read when deciding what just happened, and a caller who trusts the reply would otherwise
     * act on a send. Pinned by {@code theUnconfirmedRepliesDiscloseThatTheyAreUnconfirmed}.
     *
     * <p>Pure so that assertion is reachable with no client attached -- the handler's bare branch
     * cannot be reached headlessly, because with no player the tool errors out long before it.
     */
    static String unconfirmedEntityActionReply(
            net.minecraft.network.play.client.C0BPacketEntityAction.Action action) {
        boolean jump = action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.RIDING_JUMP;
        return "sent entity_action " + action.name() + " -- SENT ONLY, NOT CONFIRMED. "
                + (jump
                ? "jumpPower is a server-side field that is never pushed to the client in any packet, "
                + "so there is nothing to read back and no way to know whether the horse jumped."
                : "this client recomputes the sprint flag from your own movement keys every tick, so "
                + "a re-read cannot tell the server's answer from your keyboard.");
    }

    /** The local player's OWN server-driven state, read on the game thread. Null when unreadable. */
    private Boolean readServerDrivenFlag(C0BReadKind kind) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var p = ctx.game() == null ? null : ctx.game().player();
                if (p == null) {
                    return null;
                }
                // NOT isSneaking(): EntityPlayerSP OVERRIDES it (line 684) to return
                // `movementInput.sneak && !sleeping` -- the KEY BINDING, not the server's flag.
                // Reading that would confirm the keyboard, which this call never touched. The
                // server's own answer is datawatcher index 0 bit 1, written only by
                // setSneaking -> setFlag(1) and pushed back to us by the entity tracker.
                boolean sneakingFlag = (p.getDataWatcher().getWatchableObjectByte(0) & (1 << 1)) != 0;
                return kind == C0BReadKind.SNEAKING ? Boolean.valueOf(sneakingFlag)
                        : Boolean.valueOf(p.isPlayerSleeping());
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private enum C0BReadKind { SNEAKING, SLEEPING }

    /** The mount this player is on, as the server's own gates will see it. Null when unreadable. */
    private RideState readRideState() {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var p = ctx.game() == null ? null : ctx.game().player();
                if (p == null) {
                    return null;
                }
                var v = p.ridingEntity;
                if (v == null) {
                    return new RideState(false, false, false, false);
                }
                if (v instanceof net.minecraft.entity.passive.EntityHorse h) {
                    return new RideState(true, true, h.isHorseSaddled(), h.isTame());
                }
                return new RideState(true, false, false, false);
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /** Non-null when SOME container/chest GUI is on screen, which is what OPEN_INVENTORY produces. */
    private Boolean readScreenOpen() {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var mc = ctx.game() == null ? null : ctx.game().mc();
                return mc == null ? null : Boolean.valueOf(mc.currentScreen != null);
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /** The target's health, or null when it is absent or has none. Never fabricates a number. */
    private Float readTargetHealth(int entityId) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var w = ctx.game() == null ? null : ctx.game().world();
                if (w == null) {
                    return null;
                }
                var e = w.getEntityByID(entityId);
                return e instanceof net.minecraft.entity.EntityLivingBase living
                        ? Float.valueOf(living.getHealth()) : null;
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean entityStillPresent(int entityId) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var w = ctx.game() == null ? null : ctx.game().world();
                return w != null && w.getEntityByID(entityId) != null;
            });
        } catch (Throwable t) {
            return false;
        }
    }

    private SyncToolSpecification doUseEntity() {
        Tool tool = Tool.builder()
                .name("do_use_entity")
                .title("Use or attack an entity")
                .description("[requires: connected-to-server, in-world] Send a C02 use-entity. action "
                        + "is ATTACK, INTERACT (right-click) or INTERACT_AT (right-click at a precise "
                        + "point, with hitX/hitY/hitZ as the local hit vector). The entity is resolved "
                        + "live by id on the game thread; an unknown id is an honest error, never a "
                        + "fabricated send. THE REPLY IS NOT UNIFORM: ATTACK is CONFIRMED -- it "
                        + "re-reads the target's health and reports whether it dropped, and a target "
                        + "that left the world counts as a kill; attacking an item, an XP orb, an "
                        + "arrow or yourself is REFUSED before sending, because processUseEntity:932 "
                        + "answers those with kickPlayerFromServer and a non-living target has no "
                        + "health to read back. INTERACT and INTERACT_AT are NOT CONFIRMABLE and say "
                        + "so: the server discards interactWith's return value and never sends it "
                        + "back, so 'sent' is the whole truth; interactWith dispatches to whatever "
                        + "that entity does when clicked (a chest opens, a villager opens a trade "
                        + "window, a boat mounts you), and the base Entity.interactAt returns false "
                        + "for every entity except an armor stand, so INTERACT_AT on a non-armor-stand "
                        + "is very likely a no-op. After an INTERACT, verify with gui_snapshot "
                        + "sections=['screen'] or world_view sections=['self'] -- a screen opening or "
                        + "isRiding flipping is the confirmation, not this tool's reply. Anything "
                        + "past vanilla's reach of 36 blocks (9 if a block is in the way, per "
                        + "canEntityBeSeen) is silently dropped by processUseEntity and nothing is "
                        + "read back either way.")
                .annotations(ToolAnnotations.builder().title("Use or attack an entity")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "entityId", Map.of("type", "integer", "description", "target entity id"),
                        "action", enumProp("how to act on the entity.",
                                "INTERACT", "ATTACK", "INTERACT_AT"),
                        "hitX", Map.of("type", "number", "description", "INTERACT_AT local hit x"),
                        "hitY", Map.of("type", "number", "description", "INTERACT_AT local hit y"),
                        "hitZ", Map.of("type", "number", "description", "INTERACT_AT local hit z")),
                        List.of("entityId", "action")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            Integer entityId = argInt(a, "entityId");
            if (entityId == null) {
                return error("entityId (integer) is required");
            }
            String actionArg = argString(a, "action");
            if (actionArg == null) {
                return error("action is required (INTERACT | ATTACK | INTERACT_AT)");
            }
            net.minecraft.network.play.client.C02PacketUseEntity.Action action;
            try {
                action = net.minecraft.network.play.client.C02PacketUseEntity.Action
                        .valueOf(actionArg.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return error("unknown action '" + actionArg + "' (INTERACT|ATTACK|INTERACT_AT)");
            }
            if (action == net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK) {
                // Read BEFORE sending, and REFUSE when the outcome could never be read. Same
                // reasoning as setCreativeSlotAndConfirm: a write whose effect this tool can never
                // report is not a write worth making, and firing it anyway would recreate the very
                // defect this replaces -- a packet on the wire and a "sent" string with nothing
                // behind it.
                AttackTarget kind = classifyAttackTarget(entityId);
                String refusal = attackRefusalFor(kind);
                if (refusal != null) {
                    return error(refusal);
                }
                Float before = readTargetHealth(entityId);
                if (before == null) {
                    return error("refusing to send: the health of entity " + entityId + " could not "
                            + "be read (it left the world between resolving it and now, or this "
                            + "client is not in world), so the damage could never be confirmed. "
                            + "Nothing was sent.");
                }
                CallToolResult sent;
                try {
                    net.minecraft.entity.Entity target = resolveEntityOnGameThread(entityId);
                    if (target == null) {
                        return error("no entity with id " + entityId + " (not in world, or id unknown)");
                    }
                    sent = sendTyped(new net.minecraft.network.play.client.C02PacketUseEntity(
                            target, net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK),
                            "use_entity ATTACK #" + entityId);
                } catch (Exception e) {
                    return error("use_entity failed resolving entity " + entityId + ": " + e);
                }
                if (Boolean.TRUE.equals(sent.isError())) {
                    return sent;
                }
                return confirmAttack(entityId, before);
            }
            net.minecraft.network.play.client.C02PacketUseEntity packet;
            try {
                net.minecraft.entity.Entity target = resolveEntityOnGameThread(entityId);
                if (target == null) {
                    return error("no entity with id " + entityId + " (not in world, or id unknown)");
                }
                if (action == net.minecraft.network.play.client.C02PacketUseEntity.Action.INTERACT_AT) {
                    net.minecraft.util.Vec3 hit = new net.minecraft.util.Vec3(
                            argFloat(a, "hitX", 0f), argFloat(a, "hitY", 0f), argFloat(a, "hitZ", 0f));
                    packet = new net.minecraft.network.play.client.C02PacketUseEntity(target, hit);
                } else {
                    packet = new net.minecraft.network.play.client.C02PacketUseEntity(target, action);
                }
            } catch (Exception e) {
                return error("use_entity failed resolving entity " + entityId + ": " + e);
            }
            return sendTyped(packet, "use_entity " + action.name() + " #" + entityId
                    + " -- SENT ONLY, NOT CONFIRMED: " + action.name() + " has no read-back (the "
                    + "server discards interactWith's result and never returns it), so this reply "
                    + "means the packet left and nothing more. Verify with gui_snapshot "
                    + "sections=['screen'] or world_view sections=['self'].");
        });
    }

    /** Which of {@link AttackTarget} this entity is, judged from the class the server would judge. */
    private AttackTarget classifyAttackTarget(int entityId) {
        try {
            return net.marcloud.mcp.core.GameBridge.onGameThread(() -> {
                var w = ctx.game() == null ? null : ctx.game().world();
                var p = ctx.game() == null ? null : ctx.game().player();
                if (w == null) {
                    return null;
                }
                var e = w.getEntityByID(entityId);
                if (e == null) {
                    return null;
                }
                // The exact four classes processUseEntity:932 kicks for, verbatim.
                if (e instanceof net.minecraft.entity.item.EntityItem
                        || e instanceof net.minecraft.entity.item.EntityXPOrb
                        || e instanceof net.minecraft.entity.projectile.EntityArrow
                        || e == p) {
                    return AttackTarget.KICKS_ON_ATTACK;
                }
                return e instanceof net.minecraft.entity.EntityLivingBase
                        ? AttackTarget.LIVING : AttackTarget.NOT_LIVING;
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Poll the target's health until it drops, the target dies, or the budget expires.
     *
     * <p>One RTT plus a few ticks, the same budget and reasoning as {@code confirmPlace}: the server
     * applies the damage and pushes the new health back on a later tick, so reading sooner scores an
     * attack that is still in flight as a refusal.
     */
    private CallToolResult confirmAttack(int entityId, Float before) {
        long deadline = System.nanoTime() + 600_000_000L;
        Float last = before;
        boolean present = true;
        do {
            present = entityStillPresent(entityId);
            last = present ? readTargetHealth(entityId) : null;
            AttackVerdict v = attackVerdictFor(before, last, present);
            if (v == AttackVerdict.HIT) {
                return ok("use_entity ATTACK #" + entityId + ": CONFIRMED in the world, not just on "
                        + "the wire -- " + (present
                                ? "the target's health is now " + last + " (it was " + before + ")"
                                : "the target has LEFT THE WORLD, which for a living entity is what a "
                                        + "lethal hit looks like from here"));
            }
            if (v == AttackVerdict.UNREADABLE) {
                return error("the ATTACK on entity " + entityId + " was sent, but its health could "
                        + "not be read back, so NO outcome was measured and none is claimed. The hit "
                        + "may or may not have landed. Read the target with world_view "
                        + "sections=['entities'] before you act on it.");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming the attack on entity " + entityId
                        + "; the packet was sent but the health was not re-read");
            }
        } while (System.nanoTime() < deadline);
        return error("the ATTACK packet was sent and 600ms later entity " + entityId + " still reads "
                + last + " health, the same as before. Either the server refused it -- out of reach "
                + "(vanilla's 36 blocks, or 9 when canEntityBeSeen finds a block between you), or it "
                + "was invulnerable, or the target regenerated within the window. Re-read the entity "
                + "before retrying, because retrying a refused attack is how a tool reports progress "
                + "it did not make.");
    }

    private SyncToolSpecification doEntityAction() {
        Tool tool = Tool.builder()
                .name("do_entity_action")
                .title("Player entity action")
                .description("[requires: connected-to-server, in-world] Send a C0B entity-action for the "
                        + "local player: START_SNEAKING/STOP_SNEAKING, START_SPRINTING/STOP_SPRINTING, "
                        + "STOP_SLEEPING, RIDING_JUMP (auxData = jump boost 0-100), or OPEN_INVENTORY. "
                        + "entityId defaults to the local player; auxData defaults 0 (only meaningful for "
                        + "RIDING_JUMP). The entity is resolved live by id on the game thread. THE REPLY "
                        + "IS NOT UNIFORM, because the actions are not equally observable: "
                        + "START_SNEAKING/STOP_SNEAKING and STOP_SLEEPING are CONFIRMED -- the reply "
                        + "re-reads the SERVER's own flag (sneaking is datawatcher index 0 bit 1, set by "
                        + "processEntityAction's setSneaking and pushed back to you by the entity "
                        + "tracker; sleeping is cleared by the S0B animation the server sends). "
                        + "START_SPRINTING/STOP_SPRINTING is SENT ONLY, NOT CONFIRMED: the server "
                        + "applies the same flag, but this client recomputes it from your own movement "
                        + "keys every tick (EntityPlayerSP.onLivingUpdate), so no re-read can separate "
                        + "the server's answer from your keyboard. OPEN_INVENTORY is CONFIRMED by the "
                        + "screen that opens -- and note it opens THE HORSE'S CHEST, never your own "
                        + "inventory (that is a C16, and the E key sends it); it is refused outright "
                        + "unless you are riding a TAME horse. RIDING_JUMP is SENT ONLY, NOT CONFIRMED: "
                        + "jumpPower is a plain server-side field that is never in any datawatcher, so "
                        + "nothing about it reaches the client at all; it is refused outright unless you "
                        + "are riding a SADDLED horse, which is the only case where it can do anything.")
                .annotations(ToolAnnotations.builder().title("Player entity action")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "action", enumProp("which entity action. A live client was told "
                                + "unknown action 'jump' by a schema that said only type: string and "
                                + "listed these in prose.",
                                "START_SNEAKING", "STOP_SNEAKING", "STOP_SLEEPING", "START_SPRINTING",
                                "STOP_SPRINTING", "RIDING_JUMP", "OPEN_INVENTORY"),
                        "entityId", Map.of("type", "integer", "description", "entity id (default local player)"),
                        "auxData", Map.of("type", "integer", "description", "RIDING_JUMP boost 0-100 (default 0)")),
                        List.of("action")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String actionArg = argString(a, "action");
            if (actionArg == null) {
                return error("action is required");
            }
            net.minecraft.network.play.client.C0BPacketEntityAction.Action action;
            try {
                action = net.minecraft.network.play.client.C0BPacketEntityAction.Action
                        .valueOf(actionArg.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return error("unknown action '" + actionArg + "'");
            }
            Integer entityId = argInt(a, "entityId");
            int aux = argIntOr(a, "auxData", 0);

            // The two mount-gated actions are refused BEFORE sending when the server's own gate is
            // provably closed: with it closed, processEntityAction's body for that case is empty, so
            // the packet cannot do anything and nothing could ever be read back.
            boolean mountGated = action
                    == net.minecraft.network.play.client.C0BPacketEntityAction.Action.RIDING_JUMP
                    || action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.OPEN_INVENTORY;
            if (mountGated) {
                String refusal = entityActionRefusalFor(action, readRideState());
                if (refusal != null) {
                    return error(refusal);
                }
            }

            // Read the BEFORE state only where a re-read can actually mean something, so the refusal
            // below can distinguish "the flag was already right" from "the server never moved it".
            Boolean before = null;
            C0BReadKind readKind = null;
            if (action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SNEAKING
                    || action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SNEAKING) {
                readKind = C0BReadKind.SNEAKING;
                before = readServerDrivenFlag(readKind);
            } else if (action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SLEEPING) {
                readKind = C0BReadKind.SLEEPING;
                before = readServerDrivenFlag(readKind);
            } else if (action
                    == net.minecraft.network.play.client.C0BPacketEntityAction.Action.OPEN_INVENTORY) {
                before = readScreenOpen();
            }

            net.minecraft.network.play.client.C0BPacketEntityAction packet;
            try {
                net.minecraft.entity.Entity target = (entityId == null)
                        ? net.marcloud.mcp.core.GameBridge.onGameThread(() -> ctx.game().player())
                        : resolveEntityOnGameThread(entityId);
                if (target == null) {
                    return error(entityId == null
                            ? "not in world (no local player to act as)"
                            : "no entity with id " + entityId);
                }
                packet = new net.minecraft.network.play.client.C0BPacketEntityAction(target, action, aux);
            } catch (Exception e) {
                return error("entity_action failed: " + e);
            }
            CallToolResult sent = sendTyped(packet, "entity_action " + action.name());
            if (Boolean.TRUE.equals(sent.isError())) {
                return sent;
            }
            if (readKind == null) {
                return ok(unconfirmedEntityActionReply(action));
            }
            return confirmEntityAction(action, readKind, before);
        });
    }

    /**
     * Poll the server-driven flag until it matches what the action was supposed to produce.
     *
     * <p>Same budget as {@link #confirmPlace} and {@code confirmAttack}: the server applies the flag
     * and the tracker pushes it back on a later tick, so reading sooner scores an action that is
     * still in flight as a refusal.
     */
    private CallToolResult confirmEntityAction(
            net.minecraft.network.play.client.C0BPacketEntityAction.Action action,
            C0BReadKind readKind, Boolean before) {
        boolean wanted = action == net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SNEAKING;
        String state = readKind == C0BReadKind.SNEAKING ? "sneaking" : "asleep";
        Boolean last = null;
        long deadline = System.nanoTime() + 600_000_000L;
        do {
            last = readServerDrivenFlag(readKind);
            EntityActionVerdict v = entityActionVerdictFor(last, wanted);
            if (v == EntityActionVerdict.CONFIRMED) {
                return ok("entity_action " + action.name() + ": CONFIRMED -- the server's own "
                        + state + " flag now reads " + wanted
                        + (before != null && before == wanted
                                ? " (it already read " + wanted + " before, so this changed nothing "
                                + "measurable here)"
                                : "")
                        + " -- re-read from the server's flag, not from the packet having been sent");
            }
            if (v == EntityActionVerdict.UNREADABLE) {
                return error("entity_action " + action.name() + " was sent, but the " + state
                        + " flag could not be read back (not in world), so NO outcome was measured "
                        + "and none is claimed. Read it yourself with read_player_state before you "
                        + "act on it.");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming entity_action " + action.name()
                        + "; the packet was sent but the " + state + " flag was not re-read");
            }
        } while (System.nanoTime() < deadline);
        return error("entity_action " + action.name() + " was sent and 600ms later the server's "
                + state + " flag still reads " + last + " rather than " + wanted
                + (before == null ? "" : " (it was " + before + " before) ")
                + "-- the server did not apply it. processEntityAction:846-889 applies these with no "
                + "distance, cooldown or permission check, so a refusal here means the packet was "
                + "dropped upstream (another handler, a proxy, or a disconnect) rather than "
                + "declined by vanilla. Re-read read_player_state before retrying.");
    }

}
