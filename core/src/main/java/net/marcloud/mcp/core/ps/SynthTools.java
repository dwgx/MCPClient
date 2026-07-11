package net.marcloud.mcp.core.ps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;

/**
 * C7 MCP tool: eval_ephemeral. Thin wrapper over {@link PsSynthesizer},
 * handling arg parsing and result formatting. R-1 HYPERVISOR only (arbitrary
 * in-JVM code execution).
 */
public final class SynthTools {

    private final PsSynthesizer synth;

    public SynthTools(PsSynthesizer synth) {
        this.synth = synth;
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(evalEphemeral());
        return t;
    }

    public void registerAll(IoManager registry) {
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

    private static Map<String, Object> obj(String desc) {
        return Map.of("type", "object", "description", desc);
    }

    private SyncToolSpecification evalEphemeral() {
        Tool tool = Tool.builder()
                .name("eval_ephemeral")
                .description("HYPERVISOR (R-1): compile AI Java and execute it as a throwaway "
                        + "HIDDEN class (GC-able, not registered, invisible to list_capabilities, "
                        + "cannot be redefined). The source must declare "
                        + "'package net.marcloud.mcp.core.ps;' and have "
                        + "'public String handle(java.util.Map<String,Object> args)'. On success "
                        + "the class is compiled, defined as hidden (initialize=true), handle() "
                        + "is invoked with 'args', and the result is returned. After this call "
                        + "completes all references are dropped so the class is unloaded by GC. "
                        + "Use this for one-shot computations that don't need persistence. "
                        + "CONTAINMENT ≠ SANDBOX: handle() runs with full JVM power (can call "
                        + "Unsafe, crash the game, redefine classes). R-1 gating is the only control.")
                .inputSchema(schema(Map.of(
                        "className", str("fully-qualified class name (must be net.marcloud.mcp.core.ps.* "
                                + "— e.g. net.marcloud.mcp.core.ps.Compute)"),
                        "source", str("full Java source with 'package net.marcloud.mcp.core.ps;' "
                                + "and 'public String handle(Map<String,Object> args)'"),
                        "args", obj("arguments passed to handle() (optional, defaults to empty map)")),
                        List.of("className", "source")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String className = arg(a, "className");
            String source = arg(a, "source");
            Object argsRaw = a.get("args");

            if (className == null || source == null) {
                return err("className and source are required");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> args = (argsRaw instanceof Map<?, ?> m)
                    ? (Map<String, Object>) m
                    : Map.of();

            PsSynthesizer.EvalResult result = synth.eval(className, source, args);
            if (!result.success()) {
                return err(result.message());
            }

            return ok(result.output());
        });
    }
}
