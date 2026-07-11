package net.marcloud.mcp.core.registry;

import java.lang.reflect.Method;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.hotload.HotLoadEngine;

/**
 * Turns AI-authored Java source into a live MCP tool (the Kernel "grows a new
 * neuron"). Implements the Voyager/CREATOR pattern: the AI provides the tool as
 * code; we compile it, validate the contract, wrap it as a {@link
 * SyncToolSpecification}, and hand it to the {@link CapabilityRegistry}.
 *
 * <p>Contract for AI-authored tools: a public class named as given, with
 * <pre>public String handle(java.util.Map&lt;String,Object&gt; args)</pre>
 * returning the tool's text output. Keeping the contract this narrow makes
 * generated tools easy to validate and impossible to mis-wire.
 */
public final class DynamicToolFactory {

    private final HotLoadEngine hotLoad;

    public DynamicToolFactory(HotLoadEngine hotLoad) {
        this.hotLoad = hotLoad;
    }

    /** Result of trying to build a tool from source. */
    public record BuildResult(boolean success, String message, SyncToolSpecification spec) {
    }

    /**
     * Compile {@code source} and build a tool spec named {@code toolName}.
     * Validates the handle(Map) contract before wiring.
     */
    public BuildResult build(String toolName, String className, String description, String source) {
        HotLoadEngine.LoadOutcome outcome = hotLoad.loadNew(className, source);
        if (!outcome.success()) {
            return new BuildResult(false, "compile/load failed:\n" + outcome.message(), null);
        }
        Class<?> clazz = outcome.loadedClass();
        final Method handle;
        try {
            handle = clazz.getMethod("handle", Map.class);
            if (!String.class.equals(handle.getReturnType())) {
                return new BuildResult(false,
                        "handle(Map) must return String, got " + handle.getReturnType(), null);
            }
        } catch (NoSuchMethodException e) {
            return new BuildResult(false,
                    "class " + className + " must declare 'public String handle(java.util.Map<String,Object> args)'",
                    null);
        }
        final Object instance;
        try {
            instance = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return new BuildResult(false, "cannot instantiate " + className + ": " + e, null);
        }

        // Free-form object schema: generated tools accept an arbitrary arg map.
        Tool tool = Tool.builder()
                .name(toolName)
                .description(description == null ? "AI-authored tool " + toolName : description)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        SyncToolSpecification spec = new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                Object result = handle.invoke(instance, args == null ? Map.of() : args);
                return CallToolResult.builder()
                        .addTextContent(String.valueOf(result))
                        .isError(false)
                        .build();
            } catch (Throwable t) {
                // Let a genuine tool fault propagate so SafeToolExecutor's boundary
                // records it as a FAILURE (a swallowed isError result counts as
                // success and would never trip the breaker — the self-heal for
                // AI-authored tools depends on this throwing).
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(cause);
            }
        });
        return new BuildResult(true, "built " + toolName, spec);
    }
}
