package net.marcloud.mcp.core.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.security.AccessGate;
import net.marcloud.mcp.core.security.CapabilitySid;
import net.marcloud.mcp.core.security.Privilege;
import net.marcloud.mcp.core.security.ProtectedClasses;
import net.marcloud.mcp.core.security.Ring;

/**
 * C6 CONTROL-EXEC MCP tools: the native JVMTI debugger surface (suspend, pop
 * frame, force-return, breakpoint, single-step, read/write locals, watch field).
 * All 9 are R-1 HYPERVISOR and require {@code SE_DEBUG_CONTROL} enabled +
 * {@code CAP_DEBUG_CONTROL} — gated by the supervised registry, with a
 * defense-in-depth {@link AccessGate} check inside each handler.
 *
 * <p><b>No dead tools:</b> every handler first checks {@link
 * DebuggerBridge#isAvailable()} and, when the native agent is absent (the default
 * until {@code core-jvmti.dll} is built + launched via {@code -agentpath}),
 * returns an honest {@code isError} explaining the missing flag — the tools are
 * always registered and callable, never silent no-ops.
 */
public final class DebugTools {

    private final AccessGate gate;

    public DebugTools(AccessGate gate) {
        this.gate = gate;
    }

    /** All C6 debug tool names — the reserved/gated set (kept in one place). */
    public static final List<String> TOOL_NAMES = List.of(
            "debug_suspend_thread", "debug_pop_frame", "debug_force_return",
            "debug_set_breakpoint", "debug_clear_breakpoint", "debug_single_step",
            "debug_read_local", "debug_write_local", "debug_watch_field");

    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R3));
        }
    }

    private List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(suspendThread());
        t.add(popFrame());
        t.add(forceReturn());
        t.add(setBreakpoint());
        t.add(clearBreakpoint());
        t.add(singleStep());
        t.add(readLocal());
        t.add(writeLocal());
        t.add(watchField());
        return t;
    }

    // ---- helpers (mirror SeamTools/MetaTools idiom) ----

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
        return (v instanceof Number n) ? n.intValue() : def;
    }

    private static boolean boolArg(Map<String, Object> a, String k, boolean def) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Boolean b) {
            return b;
        }
        return (v != null) ? Boolean.parseBoolean(v.toString()) : def;
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private static Map<String, Object> intp(String desc) {
        return Map.of("type", "integer", "description", desc);
    }

    private static Map<String, Object> boolp(String desc) {
        return Map.of("type", "boolean", "description", desc);
    }

    private static Thread findThread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }

    /** Resolve an already-loaded class without forcing initialization. */
    private static Class<?> resolveClass(String name) {
        try {
            return Class.forName(name, false, DebugTools.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** The shared preamble: reject when native absent + enforce L4/L5 defense-in-depth. */
    private CallToolResult guard() {
        if (!DebuggerBridge.isAvailable()) {
            return err(DebuggerBridge.unavailableReason());
        }
        try {
            gate.require(CapabilitySid.CAP_DEBUG_CONTROL, Privilege.SE_DEBUG_CONTROL);
        } catch (SecurityException e) {
            return err(e.getMessage());
        }
        return null; // proceed
    }

    private SyncToolSpecification suspendThread() {
        Tool tool = Tool.builder()
                .name("debug_suspend_thread")
                .description("HYPERVISOR (R-1): suspend (or resume) a live JVM thread by name via "
                        + "native JVMTI. Suspending the game/render thread FREEZES the client — "
                        + "must precede debug_pop_frame / debug_force_return. Requires "
                        + "-agentpath:core-jvmti.dll.")
                .inputSchema(schema(Map.of(
                        "threadName", str("exact live thread name"),
                        "resume", boolp("true to resume instead of suspend (default false)")),
                        List.of("threadName")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            try {
                if (boolArg(req.arguments(), "resume", false)) {
                    DebuggerBridge.resumeThread(t);
                    return ok("resumed thread '" + name + "'");
                }
                DebuggerBridge.suspendThread(t);
                return ok("suspended thread '" + name + "'");
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification popFrame() {
        Tool tool = Tool.builder()
                .name("debug_pop_frame")
                .description("HYPERVISOR (R-1): pop the top stack frame of a SUSPENDED thread "
                        + "(re-executes the call on resume). The thread must be suspended first "
                        + "(debug_suspend_thread). Requires the native agent.")
                .inputSchema(schema(Map.of("threadName", str("suspended thread name")),
                        List.of("threadName")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            try {
                DebuggerBridge.popFrame(t);
                return ok("popped top frame of '" + name + "'");
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification forceReturn() {
        Tool tool = Tool.builder()
                .name("debug_force_return")
                .description("HYPERVISOR (R-1): force the current method of a SUSPENDED thread to "
                        + "return early. kind=void|int|object (object forces null — object values "
                        + "can't be marshaled from JSON). Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "threadName", str("suspended thread name"),
                        "kind", str("void | int | object (default void)"),
                        "intValue", intp("return value when kind=int (default 0)")),
                        List.of("threadName")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            String kind = arg(req.arguments(), "kind");
            if (kind == null) {
                kind = "void";
            }
            try {
                switch (kind.toLowerCase(java.util.Locale.ROOT)) {
                    case "int" -> DebuggerBridge.forceReturnInt(t, intArg(req.arguments(), "intValue", 0));
                    case "object" -> DebuggerBridge.forceReturnObject(t, null);
                    default -> DebuggerBridge.forceReturnVoid(t);
                }
                return ok("forced early return (" + kind + ") on '" + name + "'");
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification setBreakpoint() {
        Tool tool = Tool.builder()
                .name("debug_set_breakpoint")
                .description("HYPERVISOR (R-1): set a JVMTI breakpoint at a method + bytecode "
                        + "location. Fires a DebugEvent (also on the EventBus) when hit. A "
                        + "breakpoint on the render/game thread can freeze the client. Protected "
                        + "Core classes are refused. Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "className", str("fully-qualified class, e.g. net.minecraft.client.Minecraft"),
                        "method", str("method name"),
                        "signature", str("JVM method descriptor, e.g. ()V"),
                        "location", intp("bytecode index (default 0)")),
                        List.of("className", "method", "signature")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            return breakpoint(req.arguments(), true);
        });
    }

    private SyncToolSpecification clearBreakpoint() {
        Tool tool = Tool.builder()
                .name("debug_clear_breakpoint")
                .description("HYPERVISOR (R-1): clear a JVMTI breakpoint previously set at a "
                        + "method + location. Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "className", str("fully-qualified class"),
                        "method", str("method name"),
                        "signature", str("JVM method descriptor"),
                        "location", intp("bytecode index (default 0)")),
                        List.of("className", "method", "signature")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            return breakpoint(req.arguments(), false);
        });
    }

    private CallToolResult breakpoint(Map<String, Object> a, boolean set) {
        String className = arg(a, "className");
        String method = arg(a, "method");
        String sig = arg(a, "signature");
        if (className == null || method == null || sig == null) {
            return err("className, method and signature are required");
        }
        if (ProtectedClasses.isProtected(className)) {
            return err("refusing to instrument protected Core class " + className);
        }
        Class<?> c = resolveClass(className);
        if (c == null) {
            return err("class not loaded / not found: " + className);
        }
        long loc = intArg(a, "location", 0);
        try {
            if (set) {
                DebuggerBridge.setBreakpoint(c, method, sig, loc);
                return ok("breakpoint set at " + className + "." + method + sig + "@" + loc);
            }
            DebuggerBridge.clearBreakpoint(c, method, sig, loc);
            return ok("breakpoint cleared at " + className + "." + method + sig + "@" + loc);
        } catch (DebuggerException | DebuggerUnavailableException e) {
            return err(e.getMessage());
        }
    }

    private SyncToolSpecification singleStep() {
        Tool tool = Tool.builder()
                .name("debug_single_step")
                .description("HYPERVISOR (R-1): enable/disable JVMTI single-step events on a "
                        + "thread (fires a DebugEvent per bytecode step — extremely high volume, "
                        + "use briefly). Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "threadName", str("thread name"),
                        "enabled", boolp("true to enable stepping, false to disable")),
                        List.of("threadName", "enabled")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            boolean enabled = boolArg(req.arguments(), "enabled", false);
            try {
                DebuggerBridge.setSingleStep(t, enabled);
                return ok("single-step " + (enabled ? "enabled" : "disabled") + " on '" + name + "'");
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification readLocal() {
        Tool tool = Tool.builder()
                .name("debug_read_local")
                .description("HYPERVISOR (R-1): read a local variable of a SUSPENDED thread's "
                        + "frame. type=int|object. Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "threadName", str("suspended thread name"),
                        "depth", intp("frame depth, 0 = top (default 0)"),
                        "slot", intp("local variable slot index"),
                        "type", str("int | object (default int)")),
                        List.of("threadName", "slot")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            int depth = intArg(req.arguments(), "depth", 0);
            int slot = intArg(req.arguments(), "slot", -1);
            if (slot < 0) {
                return err("slot is required (>= 0)");
            }
            String type = arg(req.arguments(), "type");
            try {
                if ("object".equalsIgnoreCase(type)) {
                    Object v = DebuggerBridge.readLocalObject(t, depth, slot);
                    return ok("local[" + depth + ":" + slot + "] = " + v);
                }
                int v = DebuggerBridge.readLocalInt(t, depth, slot);
                return ok("local[" + depth + ":" + slot + "] = " + v);
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification writeLocal() {
        Tool tool = Tool.builder()
                .name("debug_write_local")
                .description("HYPERVISOR (R-1): write an INT local variable of a SUSPENDED "
                        + "thread's frame (int slots only — the verified JVMTI SetLocalInt "
                        + "surface). Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "threadName", str("suspended thread name"),
                        "depth", intp("frame depth, 0 = top (default 0)"),
                        "slot", intp("local variable slot index"),
                        "intValue", intp("the int value to write")),
                        List.of("threadName", "slot", "intValue")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            int depth = intArg(req.arguments(), "depth", 0);
            int slot = intArg(req.arguments(), "slot", -1);
            if (slot < 0) {
                return err("slot is required (>= 0)");
            }
            int value = intArg(req.arguments(), "intValue", 0);
            try {
                DebuggerBridge.writeLocalInt(t, depth, slot, value);
                return ok("wrote local[" + depth + ":" + slot + "] = " + value);
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification watchField() {
        Tool tool = Tool.builder()
                .name("debug_watch_field")
                .description("HYPERVISOR (R-1): enable/disable a JVMTI field-MODIFICATION watch "
                        + "(fires a DebugEvent when the field is written; read-watch is not in "
                        + "the verified JVMTI surface). Protected Core classes are refused. "
                        + "Requires the native agent.")
                .inputSchema(schema(Map.of(
                        "className", str("fully-qualified class owning the field"),
                        "field", str("field name"),
                        "signature", str("JVM field descriptor, e.g. I or Ljava/lang/String;"),
                        "enabled", boolp("true to watch, false to unwatch")),
                        List.of("className", "field", "signature", "enabled")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String className = arg(req.arguments(), "className");
            String field = arg(req.arguments(), "field");
            String sig = arg(req.arguments(), "signature");
            if (className == null || field == null || sig == null) {
                return err("className, field and signature are required");
            }
            if (ProtectedClasses.isProtected(className)) {
                return err("refusing to instrument protected Core class " + className);
            }
            Class<?> c = resolveClass(className);
            if (c == null) {
                return err("class not loaded / not found: " + className);
            }
            boolean enabled = boolArg(req.arguments(), "enabled", false);
            try {
                if (enabled) {
                    DebuggerBridge.watchFieldModification(c, field, sig);
                    return ok("watching field modification: " + className + "#" + field);
                }
                DebuggerBridge.unwatchFieldModification(c, field, sig);
                return ok("unwatched field modification: " + className + "#" + field);
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }
}
