package net.marcloud.mcp.core.security;

import java.util.Set;

/**
 * The canonical set of load-bearing Core classes that must never be redefined,
 * retransformed, or hot-swapped — the "self-lobotomy" guard.
 *
 * <p>Without this, the {@code redefine_class} hypervisor tool could rewrite the
 * very classes that enforce the privilege model ({@link PermissionPolicy},
 * {@link Ring}, {@code CapabilityRegistry}, {@code SafeToolExecutor}), or the
 * {@code CoreAgent} that owns {@link java.lang.instrument.Instrumentation} — and
 * so disable the guard from inside. Every code path that can change a loaded
 * class's bytecode (the {@code redefine_class} tool, {@link
 * net.marcloud.mcp.core.hotload.Redefiner}, and the Byte Buddy {@code .type()}
 * matchers in the hook installers) consults this set and refuses a protected
 * target.
 *
 * <p><b>Honest boundary.</b> This blocks the <i>named, schema-driven</i> redefine
 * path and the retransform installers. It is <i>not</i> a wall against arbitrary
 * R-1 code: {@code eval_java} runs unrestricted Java in-process and could reach
 * {@code Instrumentation} by other means (e.g. self-attach, enabled by the launch
 * flags). The real cross-address-space wall is the separate P-SECURE process.
 * This set is defense-in-depth: it removes the trivial, tool-mediated way to
 * neutralize the guard.
 *
 * <p>Protection is by exact fully-qualified name plus a prefix rule for the whole
 * {@code net.marcloud.mcp.core.security} package, so kernel classes added later
 * (integrity/privilege/capability/handle types) are covered automatically.
 * Redefining {@code net.minecraft.*} game classes — the legitimate use case —
 * is never affected.
 */
public final class ProtectedClasses {

    private ProtectedClasses() {
    }

    /** Whole-package prefix: everything in the security kernel is protected. */
    private static final String SECURITY_PACKAGE = "net.marcloud.mcp.core.security.";

    /**
     * Load-bearing classes outside the security package: the supervised-gate
     * machinery, the agent that holds Instrumentation, and the redefine/hook
     * plumbing itself. Redefining any of these could disable the guard.
     */
    private static final Set<String> PROTECTED = Set.of(
            // agent / Instrumentation ownership
            "net.marcloud.mcp.core.agent.CoreAgent",
            "net.marcloud.mcp.core.agent.AgentAccess",
            // the supervised registry + circuit breaker + tool records
            "net.marcloud.mcp.core.registry.CapabilityRegistry",
            "net.marcloud.mcp.core.registry.SafeToolExecutor",
            "net.marcloud.mcp.core.registry.ToolStats",
            "net.marcloud.mcp.core.registry.Capability",
            "net.marcloud.mcp.core.registry.MetaTools",
            "net.marcloud.mcp.core.registry.DynamicToolFactory",
            // the redefine / hot-load path that would perform the rewrite
            "net.marcloud.mcp.core.hotload.Redefiner",
            "net.marcloud.mcp.core.hotload.HotLoadEngine",
            // the hook installers (retransform machinery)
            "net.marcloud.mcp.core.hook.HookManager",
            "net.marcloud.mcp.core.hook.HookBridge");

    /**
     * True if {@code className} must never be redefined/retransformed. Null or
     * blank is treated as not protected (callers validate names separately).
     */
    public static boolean isProtected(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        return className.startsWith(SECURITY_PACKAGE) || PROTECTED.contains(className);
    }

    /** The exact-name protected set (for introspection/describe_class flags). */
    public static Set<String> names() {
        return PROTECTED;
    }
}
