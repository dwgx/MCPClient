package net.marcloud.mcp.core.flt.seam;

import net.marcloud.mcp.core.flt.seam.events.SeamKeyEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamMouseEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;

/**
 * MCP tool registration façade for the seam layer. Exposes 6 tools for
 * installing/uninstalling the three runtime seams: Netty tap, GLFW input
 * hooks, and tick injector.
 *
 * <p>All seam tools are gated at {@link Ring#R_MINUS_1} (HYPERVISOR): they
 * retransform or MITM the running game. In phase 4/5, they will also require
 * {@code SE_SEAM_INJECT} privilege and {@code CAP_SEAM_INJECT} capability SID.
 */
public final class SeamTools {

    private final SeamController controller;

    public SeamTools(SeamController controller) {
        this.controller = controller;
    }

    /** Register all seam tools into the supervised capability registry. */
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            // LOW#18: use the per-tool ring from the Ring table — uninstall/disable
            // seams are R0, not R-1. Runtime enforcement already recomputes by name,
            // but registering the true ring keeps list_permissions from displaying a
            // seam's ring as R-1 when it is actually R0.
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R_MINUS_1));
        }
    }

    private List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(seamNettyInstall());
        t.add(seamNettyUninstall());
        t.add(seamGlfwKeyHook());
        t.add(seamGlfwMouseHook());
        t.add(seamTickEnable());
        t.add(seamTickDisable());
        return t;
    }

    private static CallToolResult ok(String s) {
        return CallToolResult.builder().addTextContent(s).isError(false).build();
    }

    private static CallToolResult err(String s) {
        return CallToolResult.builder().addTextContent(s).isError(true).build();
    }

    private static String arg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return v == null ? null : v.toString();
    }

    private static Boolean boolArg(Map<String, Object> a, String k, boolean fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private static Map<String, Object> bool(String desc) {
        return Map.of("type", "boolean", "description", desc);
    }

    private SyncToolSpecification seamNettyInstall() {
        Tool tool = Tool.builder()
                .name("seam_netty_install")
                .description("Install the built-in Netty packet observer on the live game channel. "
                        + "Publishes SeamPacketInboundEvent and SeamPacketOutboundEvent to the "
                        + "EventBus. Wire bytes are frozen: the handler observes but never mutates. "
                        + "Can only be installed when connected to a server. Idempotent.")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            if (!controller.canInstall()) {
                return err("Instrumentation unavailable. Start with -javaagent:core-agent.jar");
            }
            boolean done = controller.installNettyTap();
            return done ? ok("Netty packet tap installed")
                        : err("failed to install (not connected or already installed)");
        });
    }

    private SyncToolSpecification seamNettyUninstall() {
        Tool tool = Tool.builder()
                .name("seam_netty_uninstall")
                .description("Remove the built-in Netty packet observer from the channel pipeline.")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            boolean done = controller.uninstallNettyTap();
            return done ? ok("Netty packet tap removed")
                        : err("not installed");
        });
    }

    private SyncToolSpecification seamGlfwKeyHook() {
        Tool tool = Tool.builder()
                .name("seam_glfw_key_hook")
                .description("Install or uninstall the GLFW key callback observer. When enabled, "
                        + "publishes SeamKeyEvent (key, scancode, action, mods) to the EventBus. "
                        + "Chains before the game's original callback so input still works. "
                        + "Requires a live GLFW window (not available in headless tests).")
                .inputSchema(schema(Map.of(
                        "enabled", bool("true to install, false to uninstall")),
                        List.of("enabled")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            boolean enable = boolArg(request.arguments(), "enabled", true);
            if (enable) {
                boolean done = controller.installKeyHook();
                return done ? ok("GLFW key hook installed")
                            : err("failed to install (GLFW unavailable or already installed)");
            } else {
                boolean done = controller.uninstallKeyHook();
                return done ? ok("GLFW key hook removed")
                            : err("not installed");
            }
        });
    }

    private SyncToolSpecification seamGlfwMouseHook() {
        Tool tool = Tool.builder()
                .name("seam_glfw_mouse_hook")
                .description("Install or uninstall the GLFW mouse button callback observer. When "
                        + "enabled, publishes SeamMouseEvent (button, action, mods) to the EventBus. "
                        + "Chains before the game's original callback so input still works. "
                        + "Requires a live GLFW window (not available in headless tests).")
                .inputSchema(schema(Map.of(
                        "enabled", bool("true to install, false to uninstall")),
                        List.of("enabled")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            boolean enable = boolArg(request.arguments(), "enabled", true);
            if (enable) {
                boolean done = controller.installMouseHook();
                return done ? ok("GLFW mouse hook installed")
                            : err("failed to install (GLFW unavailable or already installed)");
            } else {
                boolean done = controller.uninstallMouseHook();
                return done ? ok("GLFW mouse hook removed")
                            : err("not installed");
            }
        });
    }

    private SyncToolSpecification seamTickEnable() {
        Tool tool = Tool.builder()
                .name("seam_tick_enable")
                .description("Install the tick event injector. Retransforms Minecraft.runTick to "
                        + "publish TickEvent on every game tick (20 ticks/sec). Requires "
                        + "Instrumentation. TickEvent is already declared but unwired; this seam "
                        + "fires it. EventBus subscribers must be cheap (offload heavy work to "
                        + "executor). Idempotent.")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            if (!controller.canInstall()) {
                return err("Instrumentation unavailable. Start with -javaagent:core-agent.jar");
            }
            try {
                boolean done = controller.installTickInjector();
                return done ? ok("Tick injector installed (TickEvent now fires every tick)")
                            : ok("Tick injector already installed");
            } catch (IllegalStateException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification seamTickDisable() {
        Tool tool = Tool.builder()
                .name("seam_tick_disable")
                .description("Disable the tick event injector: resets the ByteBuddy retransform "
                        + "so TickEvent stops firing and Minecraft.runTick reverts to its original "
                        + "bytecode. Genuinely reversible — no restart needed.")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            boolean reverted = controller.uninstallTickInjector();
            return reverted
                    ? ok("tick injector uninstalled (runTick reverted; TickEvent no longer fires)")
                    : err("tick injector was not installed (nothing to disable)");
        });
    }
}
