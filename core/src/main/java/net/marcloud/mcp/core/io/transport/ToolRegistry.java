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
        tools.add(captureScreen());
        // Typed send_* tools (packet-exposure W6): build a specific C-packet and
        // dispatch it via the same veto-guarded ActionManager.sendRawPacket path.
        tools.add(sendClientStatus());
        tools.add(sendHeldItem());
        tools.add(sendCloseWindow());
        tools.add(sendDig());
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
                .name("send_client_status")
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
                .name("send_held_item")
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
                .name("send_close_window")
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
                .name("send_dig")
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
            int x = argInt(args, "x") == null ? 0 : argInt(args, "x");
            int y = argInt(args, "y") == null ? 0 : argInt(args, "y");
            int z = argInt(args, "z") == null ? 0 : argInt(args, "z");
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
}
