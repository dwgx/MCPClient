package net.marcloud.mcp.core.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.security.AccessGate;
import net.marcloud.mcp.core.security.CapabilitySid;
import net.marcloud.mcp.core.security.Ring;

/**
 * MCP tools for the C3 INTERCEPT capability: install/uninstall runtime ByteBuddy
 * retransformation hooks. Gated by BOTH the ring (install at R-1, uninstall at
 * R0 — enforced by the supervised gate) AND an {@link AccessGate} defense-in-depth
 * check (CAP_CLASS_RETRANSFORM), following the L1-L7 AND-composition rule.
 *
 * <p>Listing is handled by the aggregate {@code list_hooks} tool in
 * {@code IntrospectionTools}, which sees both the fixed network hooks and these
 * dynamic ones via the {@link HookSource} SPI ({@link DynamicHookManager}
 * implements it) — so HookTools does not register its own list tool.
 *
 * <p>The tools wrap {@link DynamicHookManager} and translate its
 * exceptions/results into MCP {@link CallToolResult} ok/err responses, matching
 * the idiom in {@link net.marcloud.mcp.core.registry.MetaTools}.
 */
public final class HookTools {

    private final DynamicHookManager mgr;
    private final AccessGate gate;

    /**
     * @param mgr  the DynamicHookManager to wrap
     * @param gate the L4/L5 defense-in-depth gate (require CAP_CLASS_RETRANSFORM)
     */
    public HookTools(DynamicHookManager mgr, AccessGate gate) {
        this.mgr = mgr;
        this.gate = gate;
    }

    /**
     * Register all hook tools into the supervised capability registry. Follows
     * the same pattern as {@code MetaTools.registerAll}.
     *
     * @param registry the CapabilityRegistry to register into
     */
    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R3));
        }
    }

    /** All hook tools (internal helper for registerAll). list_hooks lives in IntrospectionTools. */
    private List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(installHook());
        t.add(uninstallHook());
        return t;
    }

    // Helper methods matching MetaTools idiom
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

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    /** install_hook tool (R-1 + CAP_CLASS_RETRANSFORM). */
    private SyncToolSpecification installHook() {
        Tool tool = Tool.builder()
                .name("install_hook")
                .description("Install a runtime bytecode hook on a method (any loaded class, any "
                        + "method). Fires a HookFiredEvent on every invocation. Returns a hookId "
                        + "for uninstall_hook. Requires -javaagent:core-agent.jar and "
                        + "CAP_CLASS_RETRANSFORM. Protected kernel classes (PermissionPolicy, "
                        + "Ring, CoreAgent, etc.) are refused (defense-in-depth). The hook "
                        + "persists until uninstall_hook (survives reconnects, applies to all "
                        + "instances). Example: targetClass=\"net.minecraft.network.NetworkManager\", "
                        + "method=\"channelRead0\".")
                .inputSchema(schema(Map.of(
                        "targetClass", str("fully-qualified target class name (e.g. \"net.minecraft.network.NetworkManager\")"),
                        "method", str("target method name (e.g. \"channelRead0\")")),
                        List.of("targetClass", "method")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String targetClass = arg(a, "targetClass");
            String method = arg(a, "method");
            if (targetClass == null || method == null) {
                return err("targetClass and method are required");
            }

            // L5 defense-in-depth gate (AND-composes with the ring + capability
            // gate the supervised registry already enforces).
            try {
                gate.require(CapabilitySid.CAP_CLASS_RETRANSFORM);
            } catch (SecurityException e) {
                return err(e.getMessage());
            }

            // Install the hook
            try {
                String hookId = mgr.install(targetClass, method);
                return ok("installed hook " + hookId);
            } catch (SecurityException e) {
                // Protected class denied
                return err(e.getMessage());
            } catch (RuntimeException e) {
                // Instrumentation unavailable, target not loaded, etc.
                return err("install failed: " + e.getMessage());
            }
        });
    }

    /** uninstall_hook tool (R0 + CAP_CLASS_RETRANSFORM). */
    private SyncToolSpecification uninstallHook() {
        Tool tool = Tool.builder()
                .name("uninstall_hook")
                .description("Uninstall a hook by id (returned by install_hook). Reverts the target "
                        + "class's bytecode via ResettableClassFileTransformer.reset with "
                        + "RETRANSFORMATION, so other hooks on the same class are preserved "
                        + "(surgical uninstall). Requires CAP_CLASS_RETRANSFORM. Returns ok if "
                        + "the hook existed and was reverted; err if the hookId was unknown or the "
                        + "revert failed (class no longer modifiable).")
                .inputSchema(schema(Map.of(
                        "hookId", str("the hookId returned by install_hook (format: \"className#method@routeKey\")")),
                        List.of("hookId")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String hookId = arg(request.arguments(), "hookId");
            if (hookId == null) {
                return err("hookId is required");
            }

            // L5 defense-in-depth gate
            try {
                gate.require(CapabilitySid.CAP_CLASS_RETRANSFORM);
            } catch (SecurityException e) {
                return err(e.getMessage());
            }

            // Uninstall the hook
            boolean removed = mgr.uninstall(hookId);
            if (removed) {
                return ok("uninstalled " + hookId);
            } else {
                return err("no such hook or revert failed: " + hookId);
            }
        });
    }

}
