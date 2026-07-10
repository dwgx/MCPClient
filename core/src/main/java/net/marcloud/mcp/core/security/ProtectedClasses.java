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
            "net.marcloud.mcp.core.hook.HookBridge",
            "net.marcloud.mcp.core.hook.DynamicHookManager",
            "net.marcloud.mcp.core.hook.HookTools",
            "net.marcloud.mcp.core.hook.GenericEntryAdvice",
            // deep-access + seam machinery (hold Instrumentation / live channel)
            "net.marcloud.mcp.core.deepaccess.DeepAccess",
            "net.marcloud.mcp.core.seam.SeamController",
            "net.marcloud.mcp.core.seam.NettyTap",
            "net.marcloud.mcp.core.seam.TickInjector",
            // C6 native debugger bridge (holds the JVMTI native binding)
            "net.marcloud.mcp.core.debug.DebuggerBridge");

    /**
     * True if {@code className} must never be redefined/retransformed. Null or
     * blank is treated as not protected (callers validate names separately).
     */
    public static boolean isProtected(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        String n = normalize(className);
        return n.startsWith(SECURITY_PACKAGE) || PROTECTED.contains(n);
    }

    /**
     * Reduce a raw class name to the bare component FQCN the guard compares
     * against. Strips JVM array descriptor wrappers (e.g.
     * {@code [Lnet.marcloud...Ring;} → {@code net.marcloud...Ring}) so a caller
     * cannot slip a protected class past the guard by naming its array type, and
     * strips any inner-class suffix ({@code Foo$Bar} → {@code Foo}) so an inner
     * class of a protected type is covered too.
     */
    private static String normalize(String className) {
        String n = className.trim();
        // Array descriptor: any number of leading '[' then 'L' ... ';'.
        int firstL = n.indexOf('L');
        if (n.startsWith("[") && firstL >= 0 && n.endsWith(";")) {
            n = n.substring(firstL + 1, n.length() - 1);
        }
        // Binary array form using the component name is already handled above;
        // also treat a trailing "[]" source form defensively.
        while (n.endsWith("[]")) {
            n = n.substring(0, n.length() - 2);
        }
        int dollar = n.indexOf('$');
        if (dollar >= 0) {
            n = n.substring(0, dollar);
        }
        return n;
    }

    /** The exact-name protected set (for introspection/describe_class flags). */
    public static Set<String> names() {
        return PROTECTED;
    }
}
