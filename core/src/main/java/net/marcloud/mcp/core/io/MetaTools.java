package net.marcloud.mcp.core.io;

import net.marcloud.mcp.core.ldr.LdrRedefiner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * The self-referential tools: they let the AI inspect and extend the Kernel itself.
 * This is what makes the system self-describing and self-modifying —
 * "喂食开放接口代码让他自己可以更改".
 *
 * <ul>
 *   <li>{@code list_capabilities} — enumerate every tool: name, description,
 *       version, built-in?, and circuit/health stats (introspection manifest).
 *       MODEL-FACING by ruling: it is the one truthful "what can I do" verb, and it
 *       enumerates the model-facing registry rather than the audited one.</li>
 *   <li>{@code get_tool_source} — read a tool's Java source (AI reads before it
 *       modifies).</li>
 *   <li>{@code create_tool} — compile AI-authored Java into a NEW live tool that
 *       auto-registers and is announced via tools/list_changed (grow a neuron).</li>
 *   <li>{@code rollback_tool} — revert a tool to its previous version (safety
 *       net for self-modification gone wrong).</li>
 * </ul>
 *
 * <h2>Two registries, and why this class holds both</h2>
 *
 * <p>{@code surface} and {@code audited} are different {@link IoManager} instances over the same
 * executor and the same reference monitor, and conflating them was a live defect:
 *
 * <ul>
 *   <li>{@code surface} is where {@code create_tool} installs what it builds, and what
 *       {@code list_capabilities} enumerates. Handing it the audited registry instead would put
 *       every AI-authored tool where the model can never call it, and would make
 *       {@code create_tool}'s own success text ("It is now callable") false.</li>
 *   <li>{@code audited} is the complete built-in set, and it is the ONLY thing
 *       {@link #isReserved} may consult. The reservation check used to read the surface, where
 *       kernel-layered names are <em>by definition</em> absent — so it answered "not reserved"
 *       for every hidden tool, and {@code create_tool{toolName:"eval_java"}} was accepted.</li>
 * </ul>
 *
 * <p>Neither is a superset of the other: the surface holds 51 model-facing names and no kernel
 * ones; the audited registry holds all 84 registered built-ins. The constructor takes both as
 * separate arguments precisely so the next reader cannot collapse them again.
 */
public final class MetaTools {

    /**
     * The MODEL-FACING registry: {@code create_tool}'s install target, and the registry
     * {@code list_capabilities} enumerates. Kernel-layered names are absent from it by design.
     */
    private final IoManager surface;

    /**
     * The AUDITED registry: every registered built-in, kernel-layered ones included. Held for
     * the reserved-name check only — see {@link #isReserved} and the class javadoc.
     */
    private final IoManager audited;

    private final DynamicToolFactory factory;
    private final net.marcloud.mcp.core.ldr.LdrEngine hotLoad;

    /**
     * @param surface  the model-facing registry {@code create_tool} installs into and
     *                 {@code list_capabilities} enumerates
     * @param audited  the complete built-in registry, consulted by {@link #isReserved} for the
     *                 reserved-name check. <b>Must not be {@code surface}</b>: a kernel-layered
     *                 name is absent from the surface by construction, so passing the surface
     *                 here silently disables the check for every hidden tool.
     */
    public MetaTools(IoManager surface, IoManager audited, DynamicToolFactory factory,
                     net.marcloud.mcp.core.ldr.LdrEngine hotLoad) {
        this.surface = surface;
        this.audited = audited;
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
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    net.marcloud.mcp.core.se.Ring.forBuiltin(tool.name(),
                            net.marcloud.mcp.core.se.Ring.R3));
        }
    }

    /**
     * Register ONLY the model-facing tools of this provider into the model-facing registry.
     *
     * <p><b>Why this provider needs its own filtered path.</b> {@code McpCore.wireProvider}
     * decides with an all-or-nothing rule: it calls this method if ANY name the provider
     * contributed is model-facing, and then the provider decides for itself what lands. Passed
     * {@link #registerAll} as that second argument, the ruling that moved
     * {@code list_capabilities} to the model-facing side would have carried
     * {@code create_tool} — the verb that compiles arbitrary Java into the game JVM — onto the
     * surface with it. {@link net.marcloud.mcp.core.io.transport.ToolRegistry} already carries
     * this method for the same reason; the two are now the only two straddling providers, and
     * each reads the same single layer table rather than a private list.
     *
     * <p>It is not a copy of {@code ToolRegistry.registerModelFacing}'s logic — it is the same
     * one-line call to the same table, in the same shape, because the table is the authority.
     */
    public void registerModelFacing(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            if (net.marcloud.mcp.core.io.transport.ToolRegistry.isKernelLayered(tool.name())) {
                continue;
            }
            registry.register(tool.name(), spec, null, tool.description(), true,
                    net.marcloud.mcp.core.se.Ring.forBuiltin(tool.name(),
                            net.marcloud.mcp.core.se.Ring.R3));
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
                .title("List capabilities")
                .description("List every capability (tool) the system currently has: name, "
                        + "description, version, whether built-in, and health (circuit state, "
                        + "call/failure counts). The system describing itself to you.")
                .inputSchema(schema(Map.of(), List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("List capabilities")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            StringBuilder sb = new StringBuilder();
            for (Capability c : surface.capabilities()) {
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
                .title("Get tool source")
                .description("Return the Java source of an AI-authored tool (null for built-ins). "
                        + "Read this before modifying a tool with create_tool.")
                .inputSchema(schema(Map.of("name", str("tool name")), List.of("name")))
                .annotations(ToolAnnotations.builder()
                        .title("Get tool source")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String name = arg(request.arguments(), "name");
            if (name == null) {
                return err("name is required");
            }
            Capability c = surface.get(name);
            if (c == null) {
                return err("no such tool: " + name);
            }
            return ok(c.source() == null ? "(built-in tool, no source available)" : c.source());
        });
    }

    private SyncToolSpecification createTool() {
        Tool tool = Tool.builder()
                .name("create_tool")
                .title("Create tool (compile live)")
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
                .annotations(ToolAnnotations.builder()
                        .title("Create tool (compile live)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
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
            // needs to keep operating (self-lobotomy invariant, à la DGM). Derived
            // from the AUDITED registry — ANY built-in is reserved — so it can
            // never drift out of sync with the actual built-in set (CRITICAL#2).
            // This check is the enforcement, not a courtesy: see isReserved for why
            // IoManager.register is not a backstop for a hidden name.
            if (isReserved(toolName)) {
                return err("'" + toolName + "' is a reserved core tool and cannot be replaced");
            }
            DynamicToolFactory.BuildResult built = factory.build(toolName, className, description, source);
            if (!built.success()) {
                return err(built.message());
            }
            try {
                // AI-authored tools default to R-1 (HYPERVISOR): generated Java runs
                // in-process and can reach any R-1 capability (reflection, Instrumentation,
                // Unsafe), so it is exactly as dangerous as eval_java and sits at the same
                // ring. A lowered clearance then genuinely locks it out. See
                // Ring.DEFAULT_GENERATED (= R_MINUS_1) and SeToolRequirement's generated-tool gate.
                surface.register(toolName, built.spec(), source, description, false,
                        net.marcloud.mcp.core.se.Ring.DEFAULT_GENERATED);
                return ok("created and registered tool '" + toolName + "'. It is now callable.");
            } catch (RuntimeException e) {
                return err("registration failed: " + e);
            }
        });
    }

    private SyncToolSpecification rollbackTool() {
        Tool tool = Tool.builder()
                .name("rollback_tool")
                .title("Rollback tool version")
                .description("Revert a tool to its previous version (undo the last create_tool "
                        + "on that name). Safety net for a self-modification that made things worse.")
                .inputSchema(schema(Map.of("name", str("tool name")), List.of("name")))
                .annotations(ToolAnnotations.builder()
                        .title("Rollback tool version")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String name = arg(request.arguments(), "name");
            if (name == null) {
                return err("name is required");
            }
            boolean done = surface.rollback(name);
            return done ? ok("rolled back '" + name + "' to previous version")
                        : err("no previous version to roll back to for '" + name + "'");
        });
    }

    private SyncToolSpecification redefineClass() {
        Tool tool = Tool.builder()
                .name("redefine_class")
                .title("Redefine loaded class (hot-swap)")
                .description("[requires: -javaagent] HYPERVISOR (R-1): replace the bytecode of an ALREADY-LOADED class "
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
                .annotations(ToolAnnotations.builder()
                        .title("Redefine loaded class (hot-swap)")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String className = arg(request.arguments(), "className");
            String source = arg(request.arguments(), "source");
            if (className == null || source == null) {
                return err("className and source are required");
            }
            // Refuse the guard's own machinery up front (clear message, no
            // force-resolve). LdrRedefiner enforces this again as the choke point.
            if (net.marcloud.mcp.core.se.SeProtectedObjects.isProtected(className)) {
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

    /**
     * Whether {@code name} is reserved — i.e. whether {@code create_tool} must refuse to install
     * an AI-authored handler under it.
     *
     * <p><b>The reserved set is every DECLARED name in the tool-layering table.</b> Two terms,
     * both read live, neither a hand-maintained switch:
     * <ol>
     *   <li>{@link IoManager#isBuiltin} on the <b>audited</b> registry — every registered
     *       built-in, model-facing or kernel-layered (84 names, 85 with L6 wired).</li>
     *   <li>{@code ToolRegistry.isDeclaredKernelLayered} — every DECLARED kernel-layered name,
     *       including the 12 ADR-0004 {@code debug_*} folds that are declared but never
     *       registered.</li>
     * </ol>
     *
     * <p><b>Why the second term exists, and why the twelve folds belong in it.</b> The first
     * term alone would leave {@code debug_suspend_thread} and its ten siblings squattable,
     * because ADR-0004 folded them behind {@code debug_manage} and nothing registers them. That
     * is the wrong answer for two reasons. A name-shaped probe can then install its own handler
     * under a name an operator reads in {@code list_capabilities} as the JVMTI verb — the exact
     * impersonation this guard exists to prevent, one ADR away from being live. And the layer
     * table's own javadoc already states the rule this implements: those twelve are declared
     * precisely so that "the reservation authority covers the whole kernel vocabulary rather than
     * only the handful that happens to be live". Reserving an unregistered name costs nothing —
     * {@code create_tool} is the only writer, so the cost of a false positive is one refused
     * tool name and the cost of a false negative is a forged hypervisor verb.
     *
     * <p><b>Why {@code isDeclaredKernelLayered} and not {@code isKernelLayered}.</b> The latter
     * denies by default, so an undeclared name is kernel — using it here would refuse EVERY name
     * the model could ever invent and turn {@code create_tool} into a no-op that fails with a
     * gate error instead of doing its job. "Undeclared" has to mean "a genuinely new tool".
     *
     * <p><b>This is the enforcement, not a courtesy.</b> {@code IoManager.register}'s check is
     * {@code !builtIn && previous != null && previous.builtIn()} ({@code IoManager.java:161}),
     * and {@code previous} is read from the same surface this class installs into — where a
     * kernel-layered name does not exist, so {@code previous} is null and the guard is skipped.
     * <b>An earlier version of this comment asserted that backstop as a hard guarantee. It is
     * not one, and it does not fire for any hidden name.</b> The reachability is real rather than
     * latent: promotion is a supported feature ({@code -Dmcp.core.promote}, {@code
     * McpCore.promote}), so one property puts {@code create_tool} in the model's hands and the
     * model can then install its own handler under a name an operator reads as the
     * hypervisor's.
     *
     * <p>Both terms are pinned from the outside by
     * {@code ReservedToolNamesAreCheckedAgainstTheAuditedRegistryTest}, in both directions.
     */
    private boolean isReserved(String name) {
        return audited.isBuiltin(name)
                || net.marcloud.mcp.core.io.transport.ToolRegistry.isDeclaredKernelLayered(name);
    }
}
