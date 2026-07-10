package net.marcloud.mcp.core.deepaccess;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.GameBridge;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.security.Ring;

/**
 * C5 MCP tools: read_field / write_field / invoke_method / open_module. Thin
 * wrappers over {@link DeepAccess}, handling arg parsing, game-thread marshalling,
 * and protected-value redaction. All mutating tools (write/invoke/open) run at
 * R-1 HYPERVISOR; read_field at R0 KERNEL.
 */
public final class MutateStateTools {

    private final DeepAccess deepAccess;
    private final RootResolver roots;

    public MutateStateTools(DeepAccess deepAccess, RootResolver roots) {
        this.deepAccess = deepAccess;
        this.roots = roots;
        // Wire RootResolver back to DeepAccess for path resolution
        roots.setDeepAccess(deepAccess);
    }

    /**
     * Convenience constructor: builds the (package-private) {@link RootResolver}
     * internally so callers outside this package (e.g. McpCore wiring) don't need
     * to touch it. Equivalent to {@code new MutateStateTools(deep, new
     * RootResolver(game))}.
     */
    public MutateStateTools(DeepAccess deepAccess, net.marcloud.mcp.core.GameAccess game) {
        this(deepAccess, new RootResolver(game));
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(readField());
        t.add(writeField());
        t.add(invokeMethod());
        t.add(openModule());
        return t;
    }

    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R3));
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

    private static Map<String, Object> bool(String desc) {
        return Map.of("type", "boolean", "description", desc);
    }

    private static Map<String, Object> array(String desc) {
        return Map.of("type", "array", "description", desc);
    }

    // ===== read_field (R0) =====

    private SyncToolSpecification readField() {
        Tool tool = Tool.builder()
                .name("read_field")
                .description("KERNEL (R0): read a field (including private) from a live object or "
                        + "static class. Provide either 'path' (dotted instance path like "
                        + "\"player.capabilities\") OR 'className' (fully-qualified class for static "
                        + "field). Protected values (Instrumentation, PermissionPolicy internals) are "
                        + "redacted to \"<protected>\".")
                .inputSchema(schema(Map.of(
                        "path", str("dotted instance path (mc, player, world, netHandler, networkManager)"),
                        "className", str("fully-qualified class name for static field"),
                        "field", str("field name (required)")),
                        List.of("field")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String path = arg(a, "path");
            String className = arg(a, "className");
            String field = arg(a, "field");

            if (field == null) {
                return err("field is required");
            }
            if (path == null && className == null) {
                return err("provide either path or className");
            }

            try {
                Object value;
                if (className != null) {
                    // static field
                    Class<?> owner = Class.forName(className, false, getClass().getClassLoader());
                    value = deepAccess.getStaticField(owner, field);
                } else {
                    // instance field: resolve via game thread
                    value = GameBridge.onGameThread(() -> {
                        Object target = roots.resolveReceiver(path);
                        return deepAccess.getField(target, field);
                    });
                }

                // Redact protected values
                if (isProtectedValue(value)) {
                    return ok("<protected>");
                }

                return ok(ValueCodec.render(value));
            } catch (Exception e) {
                return err("read_field failed: " + e.getMessage());
            }
        });
    }

    // ===== write_field (R-1) =====

    private SyncToolSpecification writeField() {
        Tool tool = Tool.builder()
                .name("write_field")
                .description("HYPERVISOR (R-1): write a field (including private/final) on a live "
                        + "object or static class. Provide either 'path' (instance) OR 'className' "
                        + "(static). value can be JSON scalar, or {\"$path\":\"...\"} to pass a live "
                        + "object. Refuses protected classes (PermissionPolicy, Ring, "
                        + "CapabilityRegistry). HONEST LIMIT: final writes have no JMM visibility "
                        + "guarantee; compile-time-constant static finals may remain inlined.")
                .inputSchema(schema(Map.of(
                        "path", str("dotted instance path"),
                        "className", str("fully-qualified class name for static"),
                        "field", str("field name (required)"),
                        "value", str("JSON value to write (required)"),
                        "static", bool("true for static field (optional)")),
                        List.of("field", "value")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String path = arg(a, "path");
            String className = arg(a, "className");
            String field = arg(a, "field");
            Object value = a.get("value");

            if (field == null || value == null) {
                return err("field and value are required");
            }
            if (path == null && className == null) {
                return err("provide either path or className");
            }

            try {
                if (className != null) {
                    // static field
                    Class<?> owner = Class.forName(className, false, getClass().getClassLoader());
                    deepAccess.setStaticField(owner, field, value, roots);
                } else {
                    // instance field: must run on game thread
                    GameBridge.onGameThread(() -> {
                        Object target = roots.resolveReceiver(path);
                        deepAccess.setField(target, field, value, roots);
                        return null;
                    });
                }
                return ok("wrote " + field);
            } catch (Exception e) {
                return err("write_field failed: " + e.getMessage());
            }
        });
    }

    // ===== invoke_method (R-1) =====

    private SyncToolSpecification invokeMethod() {
        Tool tool = Tool.builder()
                .name("invoke_method")
                .description("HYPERVISOR (R-1): invoke a method (including private) on a live object "
                        + "or static class. paramTypes is an array of type names (\"int\", "
                        + "\"java.lang.String\", etc.); omit to match by name+arity. args is an "
                        + "array of JSON values; use {\"$path\":\"...\"} for object args. Refuses "
                        + "protected classes.")
                .inputSchema(schema(Map.of(
                        "path", str("dotted instance path"),
                        "className", str("fully-qualified class name for static"),
                        "method", str("method name (required)"),
                        "paramTypes", array("array of type names (optional, matches arity if omitted)"),
                        "args", array("array of argument values (optional)")),
                        List.of("method")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String path = arg(a, "path");
            String className = arg(a, "className");
            String method = arg(a, "method");
            Object paramTypesRaw = a.get("paramTypes");
            Object argsRaw = a.get("args");

            if (method == null) {
                return err("method is required");
            }
            if (path == null && className == null) {
                return err("provide either path or className");
            }

            try {
                Class<?>[] paramTypes = null;
                if (paramTypesRaw instanceof List<?> list) {
                    paramTypes = new Class<?>[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        paramTypes[i] = ValueCodec.classForTypeName(list.get(i).toString(),
                                getClass().getClassLoader());
                    }
                }

                Object[] args = null;
                if (argsRaw instanceof List<?> list) {
                    args = list.toArray();
                }

                // Make effectively final for lambda capture
                final Class<?>[] finalParamTypes = paramTypes;
                final Object[] finalArgs = args;

                Object result;
                if (className != null) {
                    // static method
                    Class<?> owner = Class.forName(className, false, getClass().getClassLoader());
                    result = deepAccess.invokeStatic(owner, method, paramTypes, args, roots);
                } else {
                    // instance method: game thread
                    result = GameBridge.onGameThread(() -> {
                        try {
                            Object target = roots.resolveReceiver(path);
                            return deepAccess.invoke(target, method, finalParamTypes, finalArgs, roots);
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                    });
                }

                if (isProtectedValue(result)) {
                    return ok("<protected>");
                }

                return ok(ValueCodec.render(result));
            } catch (Throwable e) {
                return err("invoke_method failed: " + e.getMessage());
            }
        });
    }

    // ===== open_module (R-1) =====

    private SyncToolSpecification openModule() {
        Tool tool = Tool.builder()
                .name("open_module")
                .description("HYPERVISOR (R-1): open a package of a named platform module to Core, "
                        + "via Instrumentation.redefineModule. Enables privateLookupIn / reflection "
                        + "into java.base internals. Provide module name (\"java.base\") and package "
                        + "(\"jdk.internal.misc\").")
                .inputSchema(schema(Map.of(
                        "module", str("module name (e.g. \"java.base\")"),
                        "package", str("package to open (e.g. \"jdk.internal.misc\")")),
                        List.of("module", "package")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String moduleName = arg(a, "module");
            String pkg = arg(a, "package");

            if (moduleName == null || pkg == null) {
                return err("module and package are required");
            }

            try {
                Module target = ModuleLayer.boot().findModule(moduleName).orElse(null);
                if (target == null) {
                    return err("module " + moduleName + " not found in boot layer");
                }
                deepAccess.openModule(target, pkg);
                return ok("opened " + moduleName + "/" + pkg + " to Core");
            } catch (Exception e) {
                return err("open_module failed: " + e.getMessage());
            }
        });
    }

    /**
     * Redact values whose type is Instrumentation or whose class is protected.
     * Prevents leaking the Instrumentation handle or PermissionPolicy internals.
     */
    private boolean isProtectedValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Instrumentation) {
            return true;
        }
        return net.marcloud.mcp.core.security.ProtectedClasses.isProtected(value.getClass().getName());
    }
}
