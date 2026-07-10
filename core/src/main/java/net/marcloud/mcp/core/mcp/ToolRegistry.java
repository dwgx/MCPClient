package net.marcloud.mcp.core.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.state.PlayerState;
import net.marcloud.mcp.core.state.Surroundings;
import net.marcloud.mcp.core.state.WorldScanner;
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
 *   <li>{@code eval_java} — compile + load + run arbitrary Java (the神器 REPL).</li>
 * </ul>
 * Arbitrary raw-packet send is exposed via {@code send_chat}'s sibling once
 * packet construction helpers land; the ActionManager already supports it.
 */
public final class ToolRegistry {

    private final ToolContext ctx;

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
        tools.add(captureScreen());
        return tools;
    }

    /** Register all built-in game tools into the supervised capability registry. */
    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : all()) {
            Tool t = spec.tool();
            registry.register(t.name(), spec, null, t.description(), true,
                    net.marcloud.mcp.core.security.Ring.forBuiltin(t.name(),
                            net.marcloud.mcp.core.security.Ring.R3));
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

    // ---- tools -------------------------------------------------------------

    private SyncToolSpecification readPlayerState() {
        Tool tool = Tool.builder()
                .name("read_player_state")
                .description("Read the local player's live state: name, position "
                        + "(x,y,z), yaw/pitch, health, onGround. Returns 'not in world' "
                        + "if the player isn't spawned.")
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
                .description("List recently observed packets (inbound <- / outbound ->), "
                        + "oldest first. Useful to see what happened right before a "
                        + "disconnect/kick.")
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
                .description("Send a chat message as the player. If it starts with '/', "
                        + "it runs as a command. Requires being in a world.")
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
                return error("send failed: " + e.getMessage());
            }
        });
    }

    private SyncToolSpecification evalJava() {
        Tool tool = Tool.builder()
                .name("eval_java")
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
                .description("Send an ARBITRARY protocol packet down the current connection. "
                        + "Provide Java source for a class with a 'public Object run()' method "
                        + "that constructs and RETURNS a net.minecraft.network.Packet (e.g. "
                        + "'return new net.minecraft.network.play.client.C03PacketPlayer(true);'). "
                        + "The packet is compiled, then dispatched on the game thread. Raw "
                        + "protocol experiment primitive — no filtering. Requires being connected.")
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
                return error("send_raw_packet failed: " + e);
            }
        });
    }

    private SyncToolSpecification disconnectReport() {
        Tool tool = Tool.builder()
                .name("disconnect_report")
                .description("Explain the last disconnect/kick: the reason text plus the "
                        + "packets observed right before it. Answers 'why was I kicked?'.")
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
            return ok(ctx.disconnects().report(n));
        });
    }

    private SyncToolSpecification scanSurroundings() {
        Tool tool = Tool.builder()
                .name("scan_surroundings")
                .description("The primary observation: a symbolic snapshot of the player's "
                        + "situation — position, health/hunger, biome/dimension/time, inventory, "
                        + "the block column (below/legs/head), dedup'd nearby block types with "
                        + "counts, and nearby entities sorted by distance. Cheap and precise; "
                        + "use this as your main sense of the world (use capture_screen only when "
                        + "you specifically need to SEE the scene).")
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

    private SyncToolSpecification captureScreen() {
        Tool tool = Tool.builder()
                .name("capture_screen")
                .description("SEE the game: capture the current rendered frame as a PNG image "
                        + "and return it for you to look at. Use this when you need visual "
                        + "understanding the symbolic scan can't give (scenery, builds, GUI, "
                        + "terrain, rendering issues). Costs image tokens — prefer "
                        + "scan_surroundings for routine sensing. Downscaled to ~1024px long edge.")
                .inputSchema(objectSchema(Map.of(
                        "maxEdge", Map.of("type", "integer",
                                "description", "max long-edge pixels, 64-1600 (default 1024)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int maxEdge = net.marcloud.mcp.core.vision.ScreenCapture.DEFAULT_MAX_EDGE;
            Object v = request.arguments() == null ? null : request.arguments().get("maxEdge");
            if (v instanceof Number num) {
                maxEdge = Math.max(64, Math.min(1600, num.intValue()));
            }
            final int edge = maxEdge;
            try {
                // glReadPixels must run on the game/GL thread; block for the bytes.
                byte[] png = net.marcloud.mcp.core.GameBridge.onGameThread(
                        () -> net.marcloud.mcp.core.vision.ScreenCapture.capturePng(ctx.game(), edge));
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
}
