package net.marcloud.mcp.core.cm;

import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.flt.HookInfo;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;

/**
 * MCP tools for introspection: list_classes, describe_class, find_method,
 * list_hooks. All read-only (Ring R3, declared capability
 * CAP_INTROSPECT_READ), wrapping {@link CmQuery}.
 */
public final class IntrospectionTools {

    private final CmQuery svc;

    public IntrospectionTools(CmQuery svc) {
        this.svc = svc;
    }

    /** Register all introspection tools into the capability registry. */
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : List.of(listClasses(), describeClass(),
                findMethod(), listHooks())) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R3));
        }
    }

    private SyncToolSpecification listClasses() {
        Tool tool = Tool.builder()
                .name("list_classes")
                .title("List loaded classes")
                .description("List loaded classes, optionally filtered by package prefix and "
                        + "name substring. Returns total, matched count, and a capped list "
                        + "(default 200, hard cap 2000). Shows whether each class is "
                        + "JVM-modifiable and whether Core marks it protected.")
                .inputSchema(schema(Map.of(
                        "package", str("FQN prefix filter, e.g. net.minecraft.client (optional)"),
                        "name", str("case-insensitive substring of the class name (optional)"),
                        "limit", Map.of("type", "integer", "description",
                                "max classes to return, default 200, hard cap 2000")),
                        List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("List loaded classes")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String pkg = arg(a, "package");
            String name = arg(a, "name");
            int limit = intArg(a, "limit", 200);

            ClassListing listing = svc.listClasses(pkg, name, limit);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d loaded, %d matched, showing %d (source: %s)\n",
                    listing.total(), listing.matched(), listing.classes().size(), listing.source()));
            for (ClassInfo c : listing.classes()) {
                sb.append(String.format("  %s\n    [%s] [%s] mod=%s protected=%s\n",
                        c.name(), c.classLoader(), c.module() != null ? c.module() : "<unnamed>",
                        c.jvmModifiable() ? "yes" : "no",
                        c.protectedClass() ? "yes" : "no"));
            }
            return ok(sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification describeClass() {
        Tool tool = Tool.builder()
                .name("describe_class")
                .title("Describe class structure")
                .description("Describe a class's structure via reflection: kind (class/interface/"
                        + "enum/annotation/abstract-class), modifiers, superclass, interfaces, "
                        + "fields, methods, constructors, and flags (loaded, jvmModifiable, "
                        + "protected). If the class is not already loaded, attempts to resolve it "
                        + "without initialization (side-effect: it becomes loaded).")
                .inputSchema(schema(Map.of(
                        "className", str("fully-qualified class name, e.g. net.minecraft.client.Minecraft"),
                        "members", str("all|fields|methods|summary (default all)")),
                        List.of("className")))
                .annotations(ToolAnnotations.builder()
                        .title("Describe class structure")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String className = arg(a, "className");
            String members = arg(a, "members");
            if (members == null) members = "all";

            if (className == null || className.isBlank()) {
                return err("className is required");
            }

            ClassDetail d = svc.describeClass(className);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s %s %s\n", d.kind(), d.modifiers(), d.name()));
            if (d.superclass() != null) {
                sb.append("  extends ").append(d.superclass()).append("\n");
            }
            if (!d.interfaces().isEmpty()) {
                sb.append("  implements ").append(String.join(", ", d.interfaces())).append("\n");
            }
            sb.append(String.format("  loaded: %s\n", d.loaded() ? "yes" : "no"));
            sb.append(String.format("  jvmModifiable: %s\n", d.jvmModifiable() ? "yes" : "no"));
            sb.append(String.format("  protected: %s\n", d.protectedClass() ? "yes" : "no"));
            if (d.note() != null) {
                sb.append("  note: ").append(d.note()).append("\n");
            }

            if (!members.equals("summary")) {
                if (members.equals("all") || members.equals("fields")) {
                    sb.append("\nFields:\n");
                    for (FieldInfo f : d.fields()) {
                        sb.append(String.format("  %s %s %s\n", f.modifiers(), f.type(), f.name()));
                    }
                }
                if (members.equals("all") || members.equals("methods")) {
                    sb.append("\nMethods:\n");
                    for (MethodInfo m : d.methods()) {
                        sb.append(String.format("  %s %s %s(%s) [%s]\n",
                                m.modifiers(), m.returnType(), m.name(),
                                String.join(", ", m.paramTypes()), m.descriptor()));
                    }
                    sb.append("\nConstructors:\n");
                    for (MethodInfo c : d.constructors()) {
                        sb.append(String.format("  %s %s(%s) [%s]\n",
                                c.modifiers(), c.name(),
                                String.join(", ", c.paramTypes()), c.descriptor()));
                    }
                }
            }

            return ok(sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification findMethod() {
        Tool tool = Tool.builder()
                .name("find_method")
                .title("Find methods by name")
                .description("[requires: -javaagent] Find methods across loaded classes by name (case-insensitive "
                        + "substring), optionally filtering by owner class name and parameter "
                        + "signature (simple types like 'int,int' or JVM descriptor like '(II)'). "
                        + "Returns up to limit results (default 100, cap 1000). Without -javaagent "
                        + "Instrumentation only a tiny seed class set is searchable, so an empty "
                        + "result is not authoritative and the tool reports an error rather than "
                        + "'(no matches)'.")
                .inputSchema(schema(Map.of(
                        "method", str("case-insensitive substring of the method name"),
                        "owner", str("restrict to classes whose FQN contains this (optional)"),
                        "params", str("match 'int,int' simple names OR JVM descriptor like (II) (optional)"),
                        "limit", Map.of("type", "integer", "description",
                                "max results, default 100, cap 1000")),
                        List.of("method")))
                .annotations(ToolAnnotations.builder()
                        .title("Find methods by name")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String method = arg(a, "method");
            String owner = arg(a, "owner");
            String params = arg(a, "params");
            int limit = intArg(a, "limit", 100);

            if (method == null || method.isBlank()) {
                return err("method is required");
            }

            List<MethodInfo> results = svc.findMethod(method, owner, params, limit);
            if (results.isEmpty()) {
                return ok("(no matches)");
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Found %d method(s):\n", results.size()));
            for (MethodInfo m : results) {
                sb.append(String.format("  %s#%s%s  mod=%s\n",
                        m.owner(), m.name(), m.descriptor(), m.modifiers()));
            }
            return ok(sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification listHooks() {
        Tool tool = Tool.builder()
                .name("list_hooks")
                .title("List runtime hooks")
                .description("List all runtime hooks: the target class/method, the advice class, "
                        + "the hook kind (e.g. bytebuddy-advice-retransform), and whether "
                        + "installed. Aggregates from all hook sources (FltManager, and future "
                        + "FltDynamicManager).")
                .inputSchema(schema(Map.of(), List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("List runtime hooks")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            List<HookInfo> hooks = svc.listHooks();
            if (hooks.isEmpty()) {
                return ok("(no hooks)");
            }

            StringBuilder sb = new StringBuilder();
            for (HookInfo h : hooks) {
                sb.append(String.format("[%s] %s#%s via %s (%s)\n",
                        h.installed() ? "installed" : "not installed",
                        h.targetClass(), h.targetMethod(), h.adviceClass(), h.kind()));
            }
            return ok(sb.toString().stripTrailing());
        });
    }

    // --- helpers ---

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

    private static int intArg(Map<String, Object> a, String k, int def) {
        Object v = (a == null) ? null : a.get(k);
        return (v instanceof Number num) ? num.intValue() : def;
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }
}
