package net.marcloud.mcp.core.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * The self-referential tools: they let the AI inspect and extend the神器 itself.
 * This is what makes the system self-describing and self-modifying —
 * "喂食开放接口代码让他自己可以更改".
 *
 * <ul>
 *   <li>{@code list_capabilities} — enumerate every tool: name, description,
 *       version, built-in?, and circuit/health stats (introspection manifest).</li>
 *   <li>{@code get_tool_source} — read a tool's Java source (AI reads before it
 *       modifies).</li>
 *   <li>{@code create_tool} — compile AI-authored Java into a NEW live tool that
 *       auto-registers and is announced via tools/list_changed (grow a neuron).</li>
 *   <li>{@code rollback_tool} — revert a tool to its previous version (safety
 *       net for self-modification gone wrong).</li>
 * </ul>
 */
public final class MetaTools {

    private final CapabilityRegistry registry;
    private final DynamicToolFactory factory;
    private final net.marcloud.mcp.core.hotload.HotLoadEngine hotLoad;

    public MetaTools(CapabilityRegistry registry, DynamicToolFactory factory,
                     net.marcloud.mcp.core.hotload.HotLoadEngine hotLoad) {
        this.registry = registry;
        this.factory = factory;
        this.hotLoad = hotLoad;
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(listCapabilities());
        t.add(getToolSource());
        t.add(createTool());
        t.add(rollbackTool());
        t.add(redefineClass());
        return t;
    }

    /** Register all meta-tools into the supervised capability registry. */
    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    net.marcloud.mcp.core.security.Ring.forBuiltin(tool.name(),
                            net.marcloud.mcp.core.security.Ring.R3));
        }
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

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private SyncToolSpecification listCapabilities() {
        Tool tool = Tool.builder()
                .name("list_capabilities")
                .description("List every capability (tool) the system currently has: name, "
                        + "description, version, whether built-in, and health (circuit state, "
                        + "call/failure counts). The system describing itself to you.")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            StringBuilder sb = new StringBuilder();
            for (Capability c : registry.capabilities()) {
                sb.append(String.format("- %s (v%d%s): %s\n    health: %s\n",
                        c.name(), c.version(), c.builtIn() ? ", built-in" : ", ai-authored",
                        c.description(), c.stats().summary()));
            }
            return ok(sb.length() == 0 ? "(no capabilities)" : sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification getToolSource() {
        Tool tool = Tool.builder()
                .name("get_tool_source")
                .description("Return the Java source of an AI-authored tool (null for built-ins). "
                        + "Read this before modifying a tool with create_tool.")
                .inputSchema(schema(Map.of("name", str("tool name")), List.of("name")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String name = arg(request.arguments(), "name");
            if (name == null) {
                return err("name is required");
            }
            Capability c = registry.get(name);
            if (c == null) {
                return err("no such tool: " + name);
            }
            return ok(c.source() == null ? "(built-in tool, no source available)" : c.source());
        });
    }

    private SyncToolSpecification createTool() {
        Tool tool = Tool.builder()
                .name("create_tool")
                .description("Create (or replace) a live MCP tool from Java source. The source "
                        + "must declare 'public class <className>' with a method "
                        + "'public String handle(java.util.Map<String,Object> args)'. On success "
                        + "the tool is compiled, registered, and announced immediately — you can "
                        + "call it right after. Replacing an existing tool archives the old "
                        + "version (use rollback_tool to revert). The handle() method runs on a "
                        + "WORKER thread: to touch live world/player/entity state, marshal via "
                        + "net.marcloud.mcp.core.GameBridge.onGameThread(() -> ...); direct "
                        + "off-thread game access can crash the game.")
                .inputSchema(schema(Map.of(
                        "toolName", str("the MCP tool name to register"),
                        "className", str("fully-qualified Java class name, e.g. gen.MyTool"),
                        "description", str("what the tool does (shown to the model)"),
                        "source", str("full Java source with a 'public String handle(Map<String,Object>)' method")),
                        List.of("toolName", "className", "source")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String toolName = arg(a, "toolName");
            String className = arg(a, "className");
            String description = arg(a, "description");
            String source = arg(a, "source");
            if (toolName == null || className == null || source == null) {
                return err("toolName, className and source are required");
            }
            // Reserved-name guard: don't let the AI overwrite the meta-tools it
            // needs to keep operating (self-lobotomy invariant, à la DGM).
            if (isReserved(toolName)) {
                return err("'" + toolName + "' is a reserved core tool and cannot be replaced");
            }
            DynamicToolFactory.BuildResult built = factory.build(toolName, className, description, source);
            if (!built.success()) {
                return err(built.message());
            }
            try {
                // AI-authored tools default to R2 (OBSERVE): they can reach game
                // state via GameBridge but sit below the system/kernel/hypervisor
                // rings, so a lowered clearance still contains them.
                registry.register(toolName, built.spec(), source, description, false,
                        net.marcloud.mcp.core.security.Ring.DEFAULT_GENERATED);
                return ok("created and registered tool '" + toolName + "'. It is now callable.");
            } catch (RuntimeException e) {
                return err("registration failed: " + e);
            }
        });
    }

    private SyncToolSpecification rollbackTool() {
        Tool tool = Tool.builder()
                .name("rollback_tool")
                .description("Revert a tool to its previous version (undo the last create_tool "
                        + "on that name). Safety net for a self-modification that made things worse.")
                .inputSchema(schema(Map.of("name", str("tool name")), List.of("name")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String name = arg(request.arguments(), "name");
            if (name == null) {
                return err("name is required");
            }
            boolean done = registry.rollback(name);
            return done ? ok("rolled back '" + name + "' to previous version")
                        : err("no previous version to roll back to for '" + name + "'");
        });
    }

    private SyncToolSpecification redefineClass() {
        Tool tool = Tool.builder()
                .name("redefine_class")
                .description("HYPERVISOR (R-1): replace the bytecode of an ALREADY-LOADED class "
                        + "in the running game — including net.minecraft.* game classes — without "
                        + "a restart. Provide the fully-qualified class name and the FULL new Java "
                        + "source for that same class; it is compiled and hot-swapped in place. On "
                        + "standard JVM only method bodies may change; on JBR+DCEVM you may also "
                        + "add/remove fields and methods. Takes effect on the NEXT call to a changed "
                        + "method; existing instances and static state are preserved (no re-init). "
                        + "Cannot change a class's superclass. The class must already be loaded.")
                .inputSchema(schema(Map.of(
                        "className", str("fully-qualified name of the loaded class, e.g. "
                                + "net.minecraft.client.Minecraft"),
                        "source", str("full Java source of the SAME class (same package + name), "
                                + "with the modified method bodies / members")),
                        List.of("className", "source")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String className = arg(request.arguments(), "className");
            String source = arg(request.arguments(), "source");
            if (className == null || source == null) {
                return err("className and source are required");
            }
            // Refuse the guard's own machinery up front (clear message, no
            // force-resolve). Redefiner enforces this again as the choke point.
            if (net.marcloud.mcp.core.security.ProtectedClasses.isProtected(className)) {
                return err("refusing to redefine protected Core class " + className
                        + " (the privilege model cannot be modified from inside)");
            }
            final Class<?> target;
            try {
                // Only redefine an ALREADY-LOADED class (don't force-load arbitrary
                // classes as a side effect). Use the game/loader-visible resolution.
                target = Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return err("class not loaded / not found: " + className + " (" + e + ")");
            }
            var outcome = hotLoad.redefineExisting(target, source);
            return outcome.success() ? ok(outcome.message()) : err(outcome.message());
        });
    }

    /** Core tools that must never be overwritten by a generated tool. */
    private static boolean isReserved(String name) {
        return switch (name) {
            case "create_tool", "rollback_tool", "list_capabilities", "get_tool_source",
                 "eval_java", "redefine_class",
                 "drop_privilege", "restore_privilege", "list_permissions" -> true;
            default -> false;
        };
    }
}
