package net.marcloud.mcp.core.io.transport;

import net.marcloud.mcp.core.drivers.action.ActionManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.HookBridge;

import java.util.ArrayList;
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
 */
public final class ToolRegistry {

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
                        + "if the player isn't spawned.")
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
                        + "Touching game state directly off-thread can crash the game.")
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
                        + "nearby entities sorted by distance. Cheap and precise. NOTE: world_view "
                        + "is the richer structured successor (columnar grid, per-slot inventory, "
                        + "raytrace target, full|diff modes) — prefer it for detailed decisions; "
                        + "this stays for a compact heartbeat. (capture_screen only when you must SEE.)")
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
                .description("[requires: in-world] The richer successor to scan_surroundings: a "
                        + "structured, reference-free snapshot — self (pos/vel/look/hp/food/xp/armor/"
                        + "air/effects/gamemode/flags), a columnar local block grid, nearby entities "
                        + "(sorted, capped), per-slot inventory (registry names), the crosshair target "
                        + "(raytrace), and env (dimension/biome/time). 'profile'=sparse|explore|combat "
                        + "sets token budget; 'mode'=full|diff (diff = changes since your last "
                        + "world_view); 'radius' overrides grid size; 'sections' picks a subset. Prefer "
                        + "this over capture_screen for decisions — you can play without screenshots.")
                .annotations(ToolAnnotations.builder()
                        .title("World view (structured observation)")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .inputSchema(objectSchema(Map.of(
                        "profile", Map.of("type", "string",
                                "description", "sparse | explore | combat (default explore)"),
                        "mode", Map.of("type", "string",
                                "description", "full | diff (default full; diff = since last world_view)"),
                        "radius", Map.of("type", "integer",
                                "description", "grid half-size 1-16 (default from profile)"),
                        "sections", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "subset of self,grid,entities,inventory,target,env")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
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
                        + "is 'where is the nearest X' -- world_view is ~34k tokens at radius 16 "
                        + "and its blockCounts says a type is PRESENT without saying where, while "
                        + "scan_surroundings discards positions entirely. 'types' is "
                        + "comma-separated and namespace-optional ('iron_ore' or "
                        + "'minecraft:iron_ore'), so a name read out of a world_view can be fed "
                        + "straight back. Returns block/x/y/z/dist per hit; air is never matched.")
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
                                "description", "search half-size in blocks, 1-32 (default 16)"),
                        "limit", Map.of("type", "integer",
                                "description", "max hits, 1-64 (default 8)")),
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
                                    w, feet, types, radius, limit);
                        });
                if (hits.isEmpty()) {
                    // An explicit miss, not an empty list: "no iron_ore within 16" is a fact the
                    // caller can act on (search wider, or dig), and a bare [] reads like an error.
                    return ok("no match for \"" + types + "\" within " + radius + " blocks");
                }
                StringBuilder sb = new StringBuilder();
                for (var h : hits) {
                    sb.append(h.block()).append(' ').append(h.x()).append(',').append(h.y())
                            .append(',').append(h.z()).append("  d=").append(h.dist()).append('\n');
                }
                return ok(sb.toString().stripTrailing());
            } catch (Exception e) {
                return error("find_block failed: " + e.getMessage());
            }
        });
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
                        "status", stringProp("PERFORM_RESPAWN | REQUEST_STATS | OPEN_INVENTORY_ACHIEVEMENT")),
                        List.of("status")))
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
                        + "hotbar slot 0-8 as the active held item.")
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
                .description("[requires: connected-to-server] Send a C0D close-window packet for the "
                        + "given windowId (0 = the player's own inventory).")
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
            return sendTyped(new net.minecraft.network.play.client.C0DPacketCloseWindow(windowId),
                    "close_window win=" + windowId);
        });
    }

    private SyncToolSpecification sendDig() {
        Tool tool = Tool.builder()
                .name("do_dig")
                .title("Send player digging")
                .description("[requires: connected-to-server, in-world] Send a C07 player-digging packet. "
                        + "status: START_DESTROY_BLOCK / STOP_DESTROY_BLOCK / ABORT_DESTROY_BLOCK (mining a "
                        + "block at pos+face), or DROP_ITEM / DROP_ALL_ITEMS / RELEASE_USE_ITEM (pos/face "
                        + "ignored). pos is x,y,z; face is UP/DOWN/NORTH/SOUTH/EAST/WEST.")
                .annotations(ToolAnnotations.builder().title("Send player digging")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "status", stringProp("START_DESTROY_BLOCK|STOP_DESTROY_BLOCK|ABORT_DESTROY_BLOCK|"
                                + "DROP_ITEM|DROP_ALL_ITEMS|RELEASE_USE_ITEM"),
                        "x", Map.of("type", "integer", "description", "block x (default 0 for item actions)"),
                        "y", Map.of("type", "integer", "description", "block y"),
                        "z", Map.of("type", "integer", "description", "block z"),
                        "face", stringProp("UP|DOWN|NORTH|SOUTH|EAST|WEST (default UP)")),
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
            return sendTyped(new net.minecraft.network.play.client.C07PacketPlayerDigging(
                    action, new net.minecraft.util.BlockPos(x, y, z), face),
                    "dig " + action.name());
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
                .description("[requires: connected-to-server, in-world] Send a C13 player-abilities packet "
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

    private SyncToolSpecification doSetCreativeSlot() {
        Tool tool = Tool.builder()
                .name("do_set_creative_slot")
                .title("Set creative inventory slot")
                .description("[requires: connected-to-server, creative mode] Send a C10 creative-inventory "
                        + "action: place an item stack into inventory slot 'slot'. item is an item id or "
                        + "name (e.g. 'minecraft:diamond' or '264'); count/meta default 1/0. Omit item (or "
                        + "empty) to CLEAR the slot (null stack). Server ignores this outside creative mode.")
                .annotations(ToolAnnotations.builder().title("Set creative inventory slot")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "slot", Map.of("type", "integer", "description", "inventory slot id"),
                        "item", stringProp("item id or name; omit/empty = clear slot"),
                        "count", Map.of("type", "integer", "description", "stack size (default 1)"),
                        "meta", Map.of("type", "integer", "description", "damage/meta (default 0)")),
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
            return sendTyped(new net.minecraft.network.play.client.C10PacketCreativeInventoryAction(
                    slot, stack), "set_creative_slot " + slot);
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
                        "face", stringProp("UP|DOWN|NORTH|SOUTH|EAST|WEST"),
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
            net.minecraft.item.ItemStack stack = resolveStack(item,
                    argIntOr(a, "count", 1), argIntOr(a, "meta", 0));
            if (item != null && !item.isBlank() && stack == null) {
                return error("unknown item '" + item + "'");
            }
            return sendTyped(new net.minecraft.network.play.client.C08PacketPlayerBlockPlacement(
                    new net.minecraft.util.BlockPos(x, y, z), face.getIndex(), stack,
                    argFloat(a, "hitX", 0.5f), argFloat(a, "hitY", 0.5f), argFloat(a, "hitZ", 0.5f)),
                    "place_block " + x + "," + y + "," + z);
        });
    }

    private SyncToolSpecification doClickSlot() {
        Tool tool = Tool.builder()
                .name("do_click_slot")
                .title("Click a container slot")
                .description("[requires: connected-to-server, container open] Send a C0E click-window: "
                        + "windowId (0 = own inventory), slotId, button (0=left,1=right), mode "
                        + "(0=pickup,1=shift,2=hotbar-swap,3=middle,4=drop,5=drag,6=double), actionNumber "
                        + "(transaction id, must match the container's counter), and the clicked item "
                        + "(item id/name+count/meta; omit for an empty slot). Container-protocol primitive; "
                        + "a wrong actionNumber makes the server reject the transaction.")
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

    private SyncToolSpecification doUseEntity() {
        Tool tool = Tool.builder()
                .name("do_use_entity")
                .title("Use or attack an entity")
                .description("[requires: connected-to-server, in-world] Send a C02 use-entity: interact "
                        + "with or attack the entity with the given entityId. action is INTERACT (right-"
                        + "click), ATTACK (left-click), or INTERACT_AT (right-click at a precise point — "
                        + "then hitX/hitY/hitZ give the local hit vector). The entity is resolved live by "
                        + "id on the game thread; an unknown id is an honest error, never a fabricated send.")
                .annotations(ToolAnnotations.builder().title("Use or attack an entity")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "entityId", Map.of("type", "integer", "description", "target entity id"),
                        "action", stringProp("INTERACT | ATTACK | INTERACT_AT"),
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
            return sendTyped(packet, "use_entity " + action.name() + " #" + entityId);
        });
    }

    private SyncToolSpecification doEntityAction() {
        Tool tool = Tool.builder()
                .name("do_entity_action")
                .title("Player entity action")
                .description("[requires: connected-to-server, in-world] Send a C0B entity-action for the "
                        + "local player: START_SNEAKING/STOP_SNEAKING, START_SPRINTING/STOP_SPRINTING, "
                        + "STOP_SLEEPING, RIDING_JUMP (auxData = jump boost 0-100), or OPEN_INVENTORY. "
                        + "entityId defaults to the local player; auxData defaults 0 (only meaningful for "
                        + "RIDING_JUMP). The entity is resolved live by id on the game thread.")
                .annotations(ToolAnnotations.builder().title("Player entity action")
                        .readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(false).openWorldHint(true).build())
                .inputSchema(objectSchema(Map.of(
                        "action", stringProp("START_SNEAKING|STOP_SNEAKING|STOP_SLEEPING|START_SPRINTING|"
                                + "STOP_SPRINTING|RIDING_JUMP|OPEN_INVENTORY"),
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
            return sendTyped(packet, "entity_action " + action.name());
        });
    }
}
