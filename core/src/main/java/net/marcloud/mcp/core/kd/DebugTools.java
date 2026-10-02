package net.marcloud.mcp.core.kd;

import net.marcloud.mcp.core.flt.seam.SeamTools;
import net.marcloud.mcp.core.io.MetaTools;
import net.marcloud.mcp.core.ke.event.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
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

    /**
     * The folded manifest names: two entries, not eleven. {@code debug_handle} exists only
     * when the L6 object-handle layer is wired, so a headless build registers
     * {@code debug_manage} alone. See {@link #all()} and ADR-0004.
     */
    public static final List<String> FOLDED_TOOL_NAMES = List.of("debug_manage", "debug_handle");

    /** The nine R-1 JVMTI operations behind {@code debug_manage}. */
    public static final List<String> MANAGE_ACTIONS = TOOL_NAMES;

    /** The two R-0 handle-lifecycle operations behind {@code debug_handle}. */
    public static final List<String> HANDLE_ACTIONS =
            List.of("debug_open_thread", "debug_close_handle");

    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R3));
        }
    }

    /**
     * The folded surface: two tools, not eleven.
     *
     * <p>Folded BY RING, and only because the split is forced. {@code SeLocalMonitor.evaluate}
     * never passes {@code arguments} to the gate, so a folded tool cannot carry several
     * privilege requirements and resolve them per branch. Nine of these tools sit at R-1 and
     * two ({@code debug_open_thread} / {@code debug_close_handle}) sit at R0: same
     * IntegrityLevel, same {@code SE_DEBUG_CONTROL}, same {@code CAP_DEBUG_CONTROL}, different
     * ring — the handles do not themselves generate thread control, so they are kernel self-
     * management rather than hypervisor work. One tool for all eleven would have to declare
     * either R-1 (promoting the two handles past their gate) or R0 (demoting the nine execution
     * tools until they stop working). Both directions are privilege changes, so the cluster
     * boundary is drawn exactly at the ring boundary and nowhere else.
     *
     * <p>Each cluster dispatches to the ORIGINAL spec via {@link SyncToolSpecification#callHandler}
     * with {@code action} stripped from the arguments. The handler bodies are untouched, so
     * behaviour per action is byte-identical to the pre-fold surface; only the manifest shrinks.
     * See ADR-0004.
     */
    private List<SyncToolSpecification> all() {
        Map<String, SyncToolSpecification> byName = new LinkedHashMap<>();
        byName.put("debug_suspend_thread", suspendThread());
        byName.put("debug_pop_frame", popFrame());
        byName.put("debug_force_return", forceReturn());
        byName.put("debug_set_breakpoint", setBreakpoint());
        byName.put("debug_clear_breakpoint", clearBreakpoint());
        byName.put("debug_single_step", singleStep());
        byName.put("debug_read_local", readLocal());
        byName.put("debug_write_local", writeLocal());
        byName.put("debug_watch_field", watchField());
        Map<String, SyncToolSpecification> handles = new LinkedHashMap<>();
        if (objects != null) {
            // L6 handle lifecycle — only when the object-handle layer is wired.
            handles.put("debug_open_thread", openThread());
            handles.put("debug_close_handle", closeHandle());
        }
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(fold("debug_manage", "JVMTI thread execution, held at R-1 hypervisor",
                new ArrayList<>(byName.keySet()), byName));
        if (!handles.isEmpty()) {
            t.add(fold("debug_handle", "JVMTI object-handle lifecycle, held at R0 kernel",
                    new ArrayList<>(handles.keySet()), handles));
        }
        return t;
    }

    /**
     * One manifest entry that dispatches to the per-action specs.
     *
     * <p>The {@code action} value is stripped before delegating so the original handlers see
     * exactly the argument map they saw before the fold. An unknown action is an error that
     * names the valid ones — folding eleven precise errors into one vague one is the cost
     * ADR-0004 records, and it is paid back here by listing the actions in the message.
     */
    private static SyncToolSpecification fold(String name, String blurb, List<String> actions,
                                              Map<String, SyncToolSpecification> byName) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of(
                "type", "string",
                "enum", actions,
                "description", "which operation to perform: " + String.join(", ", actions)
                        + ". Required."));
        // The per-action arguments travel at the TOP level, not nested: SeLocalMonitor never
        // sees `arguments`, so the gate reads only the tool NAME, and nesting them would make
        // the schema describe a shape the handler does not read.
        // Per-action signatures belong in the description, because folding otherwise deletes
        // them from the manifest. With eleven separate tools the agent could read
        // debug_read_local's schema and see it accepts an optional `handle`; folded behind a
        // single `action` enum it can see only the list of action NAMES. That is a real loss of
        // schema discovery -- found by L6DebugGateThroughRegistryTest, which asserts the handle
        // property is advertised and stopped being able to -- so it is paid back here in prose.
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<String, SyncToolSpecification> e : byName.entrySet()) {
            Object req = e.getValue().tool().inputSchema() == null ? null
                    : e.getValue().tool().inputSchema().get("required");
            sig.append("\n  - ").append(e.getKey()).append(": requires ")
                    .append(req instanceof List<?> l && !l.isEmpty()
                            ? String.join(", ", l.stream().map(String::valueOf).toList())
                            : "(no required arguments)");
        }
        Tool tool = Tool.builder()
                .name(name)
                .description(blurb + ". Choose one operation with 'action' and pass that "
                        + "operation's own arguments alongside it, at the top level."
                        + "\nEvery action also accepts an optional 'handle' in place of"
                        + " 'threadName' when the object-handle layer is wired: mint one with"
                        + " debug_handle action=debug_open_thread. A handle is subject to a"
                        + " frozen READ|WRITE|EXECUTE mask, and under -Dmcp.core.hardened=true"
                        + " a handle-op called without one is refused rather than falling back"
                        + " to the name-based path."
                        + "\nActions and their required arguments:" + sig)
                .inputSchema(schema(props, List.of("action")))
                .annotations(ToolAnnotations.builder()
                        .title(name)
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            Map<String, Object> raw = req.arguments() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(req.arguments());
            Object a = raw.remove("action");
            if (a == null) {
                return err("'action' is required. One of: " + String.join(", ", actions) + ".");
            }
            SyncToolSpecification target = byName.get(String.valueOf(a));
            if (target == null) {
                return err("unknown action '" + a + "' for " + name + ". One of: "
                        + String.join(", ", actions) + ".");
            }
            // Delegate under the ORIGINAL tool name with 'action' stripped, so the handler
            // sees exactly the argument map and tool identity it saw before the fold. The gate
            // has already run against the FOLDED name by this point, which is why the two
            // clusters must be gate-homogeneous -- see the note on all().
            return target.callHandler().apply(ex,
                    new CallToolRequest(target.tool().name(), raw));
        });
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
                .annotations(ToolAnnotations.builder()
                        .title("Suspend/resume a thread (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
        Map<String, Object> props;
        List<String> required;
        if (objects != null) {
            props = Map.of(
                    "threadName", str("suspended thread name (or use handle)"),
                    "handle", handleProp());
            required = List.of();
        } else {
            props = Map.of("threadName", str("suspended thread name"));
            required = List.of("threadName");
        }
        Tool tool = Tool.builder()
                .name("debug_pop_frame")
                .description("HYPERVISOR (R-1): pop the top stack frame of a SUSPENDED thread "
                        + "(re-executes the call on resume). The thread must be suspended first "
                        + "(debug_suspend_thread). Requires the native agent.")
                .inputSchema(schema(props, required))
                .annotations(ToolAnnotations.builder()
                        .title("Pop a stack frame (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
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
            try {
                KdBridge.popFrame(t);
                return ok("popped top frame of " + threadDesc(req.arguments()));
            } catch (DebuggerException | DebuggerUnavailableException e) {
                return err(e.getMessage());
            }
        });
    }

    private SyncToolSpecification forceReturn() {
        Map<String, Object> props;
        List<String> required;
        if (objects != null) {
            props = Map.of(
                    "threadName", str("suspended thread name (or use handle)"),
                    "handle", handleProp(),
                    "kind", str("void | int | object (default void)"),
                    "intValue", intp("return value when kind=int (default 0)"));
            required = List.of();
        } else {
            props = Map.of(
                    "threadName", str("suspended thread name"),
                    "kind", str("void | int | object (default void)"),
                    "intValue", intp("return value when kind=int (default 0)"));
            required = List.of("threadName");
        }
        Tool tool = Tool.builder()
                .name("debug_force_return")
                .description("HYPERVISOR (R-1): force the current method of a SUSPENDED thread to "
                        + "return early. kind=void|int|object (object forces null — object values "
                        + "can't be marshaled from JSON). Requires the native agent.")
                .inputSchema(schema(props, required))
                .annotations(ToolAnnotations.builder()
                        .title("Force early return (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
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
                return ok("forced early return (" + kind + ") on " + threadDesc(req.arguments()));
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
                .annotations(ToolAnnotations.builder()
                        .title("Set a breakpoint (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
                .annotations(ToolAnnotations.builder()
                        .title("Clear a breakpoint (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
        Map<String, Object> props;
        List<String> required;
        if (objects != null) {
            props = Map.of(
                    "threadName", str("thread name (or use handle)"),
                    "handle", handleProp(),
                    "enabled", boolp("true to enable stepping, false to disable"));
            required = List.of("enabled");
        } else {
            props = Map.of(
                    "threadName", str("thread name"),
                    "enabled", boolp("true to enable stepping, false to disable"));
            required = List.of("threadName", "enabled");
        }
        Tool tool = Tool.builder()
                .name("debug_single_step")
                .description("HYPERVISOR (R-1): enable/disable JVMTI single-step events on a "
                        + "thread (fires a DebugEvent per bytecode step — extremely high volume, "
                        + "use briefly). Requires the native agent.")
                .inputSchema(schema(props, required))
                .annotations(ToolAnnotations.builder()
                        .title("Toggle single-step (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
            boolean enabled = boolArg(req.arguments(), "enabled", false);
            try {
                KdBridge.setSingleStep(t, enabled);
                return ok("single-step " + (enabled ? "enabled" : "disabled") + " on "
                        + threadDesc(req.arguments()));
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
                .annotations(ToolAnnotations.builder()
                        .title("Read a local variable (JVMTI)")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
        Map<String, Object> props;
        List<String> required;
        if (objects != null) {
            props = Map.of(
                    "threadName", str("suspended thread name (or use handle)"),
                    "handle", handleProp(),
                    "depth", intp("frame depth, 0 = top (default 0)"),
                    "slot", intp("local variable slot index"),
                    "intValue", intp("the int value to write"));
            required = List.of("slot", "intValue");
        } else {
            props = Map.of(
                    "threadName", str("suspended thread name"),
                    "depth", intp("frame depth, 0 = top (default 0)"),
                    "slot", intp("local variable slot index"),
                    "intValue", intp("the int value to write"));
            required = List.of("threadName", "slot", "intValue");
        }
        Tool tool = Tool.builder()
                .name("debug_write_local")
                .description("HYPERVISOR (R-1): write an INT local variable of a SUSPENDED "
                        + "thread's frame (int slots only — the verified JVMTI SetLocalInt "
                        + "surface). Requires the native agent.")
                .inputSchema(schema(props, required))
                .annotations(ToolAnnotations.builder()
                        .title("Write a local variable (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
                        + "the op (TOCTOU-safe). Close it with debug_close_handle. Requires "
                        + "SE_DEBUG_CONTROL and CAP_DEBUG_CONTROL, so disable_privilege or "
                        + "revoke_capability closes this tool, and requires the native agent "
                        + "(see the other debug_* tools).")
                .inputSchema(schema(Map.of("threadName", str("exact live thread name")),
                        List.of("threadName")))
                .annotations(ToolAnnotations.builder()
                        .title("Watch field modification (JVMTI)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .annotations(ToolAnnotations.builder()
                        .title("Open a frozen thread handle (L6)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            // The shared preamble the other nine debug_* tools run: this handler was the one
            // that skipped it, so an L4/L5-in-handler check (the defense-in-depth layer under
            // the side tables) never ran for handle minting (audit H9).
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
                        + "debug_open_thread. Idempotent; only the owner can close it. Requires "
                        + "SE_DEBUG_CONTROL and CAP_DEBUG_CONTROL.")
                .inputSchema(schema(Map.of("handle", str("the handle id to close")),
                        List.of("handle")))
                .annotations(ToolAnnotations.builder()
                        .title("Close an object handle (L6)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (ex, req) -> {
            // Same shared preamble as every other debug_* handler (audit H9): closing a
            // frozen READ|WRITE|EXECUTE handle is part of the same controlled surface, and the
            // side-table rows for it are only a defense-in-depth layer away from this check.
            CallToolResult g = guard();
            if (g != null) {
                return g;
            }
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
