package net.marcloud.mcp.core.kd;

import net.marcloud.mcp.core.flt.seam.SeamTools;
import net.marcloud.mcp.core.io.MetaTools;
import net.marcloud.mcp.core.ke.event.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.AccessGate;
import net.marcloud.mcp.core.ob.ObAccessMask;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.ob.ObHandle;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.SeProtectedObjects;
import net.marcloud.mcp.core.ob.ObRef;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToken;

/**
 * C6 CONTROL-EXEC MCP tools: the native JVMTI debugger surface (suspend, pop
 * frame, force-return, breakpoint, single-step, read/write locals, watch field).
 * All 9 are R-1 HYPERVISOR and require {@code SE_DEBUG_CONTROL} enabled +
 * {@code CAP_DEBUG_CONTROL} — gated by the supervised registry, with a
 * defense-in-depth {@link AccessGate} check inside each handler.
 *
 * <p><b>No dead tools:</b> every handler first checks {@link
 * KdBridge#isAvailable()} and, when the native agent is absent (the default
 * until {@code core-jvmti.dll} is built + launched via {@code -agentpath}),
 * returns an honest {@code isError} explaining the missing flag — the tools are
 * always registered and callable, never silent no-ops.
 */
public final class DebugTools {

    private final AccessGate gate;
    /** L6 handle layer, or null (the default — no handle tools, no gating change). */
    private final ObManager objects;
    /** Supplies the current subject so a minted handle is owned by the gate's principal. */
    private final Supplier<SeToken> subject;

    /** Legacy/default: no L6 handles (debug_open_thread / debug_close_handle are not registered). */
    public DebugTools(AccessGate gate) {
        this(gate, null, null);
    }

    /**
     * @param objects when non-null, L6 is active: {@code debug_open_thread} /
     *     {@code debug_close_handle} are registered, and the handle-carrying debug
     *     ops resolve the SUSPENDED thread from the frozen handle target instead of
     *     re-running {@link #findThread} by name (closing the jthread name-reuse
     *     TOCTOU). Null keeps the exact pre-L6 surface.
     * @param subject supplies the current principal so a minted handle is owned by
     *     the same subject the reference monitor gates against.
     */
    public DebugTools(AccessGate gate, ObManager objects, Supplier<SeToken> subject) {
        this.gate = gate;
        this.objects = objects;
        this.subject = subject;
    }

    /** All C6 debug tool names — the reserved/gated set (kept in one place). */
    public static final List<String> TOOL_NAMES = List.of(
            "debug_suspend_thread", "debug_pop_frame", "debug_force_return",
            "debug_set_breakpoint", "debug_clear_breakpoint", "debug_single_step",
            "debug_read_local", "debug_write_local", "debug_watch_field");

    public void registerAll(IoManager registry) {
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
        if (objects != null) {
            // L6 handle lifecycle — only when the object-handle layer is wired.
            t.add(openThread());
            t.add(closeHandle());
        }
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

    /**
     * Resolve the thread a debug op targets. When a {@code handle} arg is present
     * and L6 is wired, return the frozen target the handle resolved ONCE at open()
     * — never re-resolving by name — so a jthread/name-reuse swap cannot redirect a
     * live handle (the TOCTOU L6 exists to close). The L6 mask check for this op
     * already ran in {@link ObManager#checkRequest} before this handler.
     * Otherwise fall back to the classic name lookup. Returns null when unresolved.
     */
    private Thread resolveThread(Map<String, Object> a) {
        String handle = arg(a, "handle");
        if (handle != null && objects != null && subject != null) {
            try {
                Object t = objects.frozenTarget(Long.parseLong(handle.trim()), subject.get());
                return (t instanceof Thread th) ? th : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String name = arg(a, "threadName");
        return (name == null) ? null : findThread(name);
    }

    /** A human description of how the thread was addressed, for error messages. */
    private static String threadDesc(Map<String, Object> a) {
        String handle = arg(a, "handle");
        if (handle != null) {
            return "handle #" + handle;
        }
        return "name '" + arg(a, "threadName") + "'";
    }

    /** Optional L6 handle property, added to a tool schema only when L6 is wired. */
    private static Map<String, Object> handleProp() {
        return str("optional L6 object-handle id (from debug_open_thread); when given, "
                + "the frozen suspended thread is used and threadName is ignored");
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
        if (!KdBridge.isAvailable()) {
            return err(KdBridge.unavailableReason());
        }
        try {
            gate.require(CapabilitySid.CAP_DEBUG_CONTROL, Privilege.SE_DEBUG_CONTROL);
        } catch (SecurityException e) {
            return err(e.getMessage());
        }
        return null; // proceed
    }

    private SyncToolSpecification suspendThread() {
        // Default surface is byte-identical; only when L6 is wired do we accept an
        // optional handle and relax threadName to optional (handle can substitute).
        Map<String, Object> props;
        List<String> required;
        if (objects != null) {
            props = Map.of(
                    "threadName", str("exact live thread name (or use handle)"),
                    "handle", handleProp(),
                    "resume", boolp("true to resume instead of suspend (default false)"));
            required = List.of();
        } else {
            props = Map.of(
                    "threadName", str("exact live thread name"),
                    "resume", boolp("true to resume instead of suspend (default false)"));
            required = List.of("threadName");
        }
        Tool tool = Tool.builder()
                .name("debug_suspend_thread")
                .description("HYPERVISOR (R-1): suspend (or resume) a live JVM thread by name via "
                        + "native JVMTI. Suspending the game/render thread FREEZES the client — "
                        + "must precede debug_pop_frame / debug_force_return. Requires "
                        + "-agentpath:core-jvmti.dll.")
                .inputSchema(schema(props, required))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            String name = arg(req.arguments(), "threadName");
            Thread t = resolveThread(req.arguments());
            if (t == null) {
                return err("no live thread for " + threadDesc(req.arguments()));
            }
            try {
                if (boolArg(req.arguments(), "resume", false)) {
                    KdBridge.resumeThread(t);
                    return ok("resumed thread '" + name + "'");
                }
                KdBridge.suspendThread(t);
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
                KdBridge.popFrame(t);
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
                    case "int" -> KdBridge.forceReturnInt(t, intArg(req.arguments(), "intValue", 0));
                    case "object" -> KdBridge.forceReturnObject(t, null);
                    default -> KdBridge.forceReturnVoid(t);
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
        if (SeProtectedObjects.isProtected(className)) {
            return err("refusing to instrument protected Core class " + className);
        }
        Class<?> c = resolveClass(className);
        if (c == null) {
            return err("class not loaded / not found: " + className);
        }
        long loc = intArg(a, "location", 0);
        try {
            if (set) {
                KdBridge.setBreakpoint(c, method, sig, loc);
                return ok("breakpoint set at " + className + "." + method + sig + "@" + loc);
            }
            KdBridge.clearBreakpoint(c, method, sig, loc);
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
                KdBridge.setSingleStep(t, enabled);
                return ok("single-step " + (enabled ? "enabled" : "disabled") + " on '" + name + "'");
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification readLocal() {
        Map<String, Object> props;
        List<String> required;
        if (objects != null) {
            props = Map.of(
                    "threadName", str("suspended thread name (or use handle)"),
                    "handle", handleProp(),
                    "depth", intp("frame depth, 0 = top (default 0)"),
                    "slot", intp("local variable slot index"),
                    "type", str("int | object (default int)"));
            required = List.of("slot");
        } else {
            props = Map.of(
                    "threadName", str("suspended thread name"),
                    "depth", intp("frame depth, 0 = top (default 0)"),
                    "slot", intp("local variable slot index"),
                    "type", str("int | object (default int)"));
            required = List.of("threadName", "slot");
        }
        Tool tool = Tool.builder()
                .name("debug_read_local")
                .description("HYPERVISOR (R-1): read a local variable of a SUSPENDED thread's "
                        + "frame. type=int|object. Requires the native agent.")
                .inputSchema(schema(props, required))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
            Thread t = resolveThread(req.arguments());
            if (t == null) {
                return err("no live thread for " + threadDesc(req.arguments()));
            }
            int depth = intArg(req.arguments(), "depth", 0);
            int slot = intArg(req.arguments(), "slot", -1);
            if (slot < 0) {
                return err("slot is required (>= 0)");
            }
            String type = arg(req.arguments(), "type");
            try {
                if ("object".equalsIgnoreCase(type)) {
                    Object v = KdBridge.readLocalObject(t, depth, slot);
                    return ok("local[" + depth + ":" + slot + "] = " + v);
                }
                int v = KdBridge.readLocalInt(t, depth, slot);
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
                KdBridge.writeLocalInt(t, depth, slot, value);
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
            if (SeProtectedObjects.isProtected(className)) {
                return err("refusing to instrument protected Core class " + className);
            }
            Class<?> c = resolveClass(className);
            if (c == null) {
                return err("class not loaded / not found: " + className);
            }
            boolean enabled = boolArg(req.arguments(), "enabled", false);
            try {
                if (enabled) {
                    KdBridge.watchFieldModification(c, field, sig);
                    return ok("watching field modification: " + className + "#" + field);
                }
                KdBridge.unwatchFieldModification(c, field, sig);
                return ok("unwatched field modification: " + className + "#" + field);
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    // ---- L6 handle lifecycle (registered only when the ObManager is wired) ----

    private SyncToolSpecification openThread() {
        Tool tool = Tool.builder()
                .name("debug_open_thread")
                .description("KERNEL (R0): open an L6 object-handle over a live thread, frozen to "
                        + "READ|WRITE|EXECUTE. Returns a handle id to pass as 'handle' to "
                        + "debug_suspend_thread / debug_read_local etc. — the handle freezes the "
                        + "exact thread object once, so later jthread/name reuse cannot redirect "
                        + "the op (TOCTOU-safe). Close it with debug_close_handle.")
                .inputSchema(schema(Map.of("threadName", str("exact live thread name")),
                        List.of("threadName")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            String name = arg(req.arguments(), "threadName");
            Thread t = (name == null) ? null : findThread(name);
            if (t == null) {
                return err("no live thread named '" + name + "'");
            }
            try {
                SeToken s = subject.get();
                int rwe = ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.WRITE, ObAccessMask.EXECUTE);
                // Resolve to the exact thread we already found (resolved-once, TOCTOU-safe).
                ObHandle h = objects.open(s, ObRef.parse("thread:" + name), rwe, ref -> t);
                return ok("opened handle #" + h.id() + " over thread '" + name + "' (mask "
                        + ObAccessMask.render(h.mask()) + ")");
            } catch (RuntimeException e) {
                return err("L6 open failed: " + e.getMessage());
            }
        });
    }

    private SyncToolSpecification closeHandle() {
        Tool tool = Tool.builder()
                .name("debug_close_handle")
                .description("KERNEL (R0): close an L6 object-handle previously opened with "
                        + "debug_open_thread. Idempotent; only the owner can close it.")
                .inputSchema(schema(Map.of("handle", str("the handle id to close")),
                        List.of("handle")))
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            String handle = arg(req.arguments(), "handle");
            long id;
            try {
                id = Long.parseLong(handle == null ? "" : handle.trim());
            } catch (NumberFormatException e) {
                return err("malformed handle id '" + handle + "'");
            }
            objects.close(id, subject.get());
            return ok("closed handle #" + id);
        });
    }
}
