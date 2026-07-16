package net.marcloud.mcp.core.se;

import java.util.Set;

/**
 * The canonical set of load-bearing Core classes that must never be redefined,
 * retransformed, or hot-swapped — the "self-lobotomy" guard.
 *
 * <p>Without this, the {@code redefine_class} hypervisor tool could rewrite the
 * very classes that enforce the privilege model ({@link SeClearancePolicy},
 * {@link Ring}, {@code IoManager}, {@code IoSupervisor}), or the
 * {@code CoreAgent} that owns {@link java.lang.instrument.Instrumentation} — and
 * so disable the guard from inside. Every code path that can change a loaded
 * class's bytecode (the {@code redefine_class} tool, {@link
 * net.marcloud.mcp.core.ldr.LdrRedefiner}, and the Byte Buddy {@code .type()}
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
 * Security Reference Monitor ({@code se}), Object Manager ({@code ob}), and compat
 * trust-core ({@code compat}) packages, so kernel classes added later
 * (integrity/privilege/capability/handle/trust types) are covered automatically.
 * Redefining {@code net.minecraft.*} game classes — the legitimate use case —
 * is never affected.
 */
public final class SeProtectedObjects {

    private SeProtectedObjects() {
    }

    /** Whole-package prefix: everything in the Security Reference Monitor (Se) is protected. */
    private static final String SECURITY_PACKAGE = "net.marcloud.mcp.core.se.";

    /**
     * Object Manager (Ob) prefix — also whole-package protected. The Ob layer
     * (handle registry, frozen targets, access masks) was carved out of the old
     * {@code security} package in the NT-Executive rename; it holds live L6
     * handles, so redefining it could defeat the object-handle gate.
     */
    private static final String OBJECT_PACKAGE = "net.marcloud.mcp.core.ob.";

    /**
     * Compat trust-core (compat) prefix — also whole-package protected. The compat
     * layer is the entire signature/trust spine of the AppCompat shim engine
     * (CompatEngine, TufTrust, RootTrust, KernelTrustAnchor, Ed25519PatchSigner,
     * PatchChain, SnapshotVerifier, TrustAnchors, CompatDatabase, patches/*). Without
     * this prefix, {@code redefine_class} / Byte Buddy could hot-swap
     * {@code Ed25519PatchSigner.verify} to always-true and disarm signature checking
     * from inside — a self-lobotomy of the patch-trust guard. Covering the whole
     * package means trust classes added later are protected automatically, exactly
     * like the Se and Ob layers.
     */
    private static final String COMPAT_PACKAGE = "net.marcloud.mcp.core.compat.";

    /**
     * ALPC (P-SECURE transport + crypto) prefix — also whole-package protected.
     * The {@code compat} trust spine is a thin wrapper: every Ed25519 <i>verdict</i>
     * ({@code Ed25519PatchSigner.verify}, {@code TufTrust.isRootSignedToBakedTrust},
     * {@code SnapshotVerifier}, {@code CompatAuthorityClient}) funnels through the
     * single primitive {@code alpc.CompatCrypto.ed25519Verify}. Protecting only the
     * {@code compat} callers while leaving the {@code alpc} delegate exposed was a
     * refactor-induced coverage gap: {@code redefine_class} (a tool-mediated path
     * this guard governs) could hot-swap {@code ed25519Verify} to always-true and
     * disarm the entire signature/trust framework — the exact self-lobotomy the
     * COMPAT_PACKAGE prefix documents as blocked. Covering the whole {@code alpc}
     * package (CompatCrypto, CompatAuthority, TicketCompatAuthority, AlpcServer)
     * closes it and protects transport/crypto classes added later automatically.
     */
    private static final String ALPC_PACKAGE = "net.marcloud.mcp.core.alpc.";

    /**
     * Load-bearing classes outside the security package: the supervised-gate
     * machinery, the agent that holds Instrumentation, and the redefine/hook
     * plumbing itself. Redefining any of these could disable the guard.
     */
    private static final Set<String> PROTECTED = Set.of(
            // agent / Instrumentation ownership
            "net.marcloud.mcp.core.boot.CoreAgent",
            "net.marcloud.mcp.core.boot.AgentAccess",
            // the supervised registry + circuit breaker + tool records
            "net.marcloud.mcp.core.io.IoManager",
            "net.marcloud.mcp.core.io.IoSupervisor",
            "net.marcloud.mcp.core.io.ToolStats",
            // L7 boundary: deep-freeze (TOCTOU guard) + schema validation. Redefining
            // it would neutralize the L7 layer the supervised gate runs after every
            // decision (IoManager.supervise -> IoProbe.validate/freezeArgs).
            "net.marcloud.mcp.core.io.IoProbe",
            "net.marcloud.mcp.core.io.Capability",
            "net.marcloud.mcp.core.io.MetaTools",
            "net.marcloud.mcp.core.io.DynamicToolFactory",
            // the redefine / hot-load path that would perform the rewrite
            "net.marcloud.mcp.core.ldr.LdrRedefiner",
            "net.marcloud.mcp.core.ldr.LdrEngine",
            // the hook installers (retransform machinery)
            "net.marcloud.mcp.core.flt.FltManager",
            "net.marcloud.mcp.core.flt.HookBridge",
            "net.marcloud.mcp.core.flt.FltDynamicManager",
            "net.marcloud.mcp.core.flt.HookTools",
            "net.marcloud.mcp.core.flt.GenericEntryAdvice",
            // deep-access + seam machinery (hold Instrumentation / live channel)
            "net.marcloud.mcp.core.mm.MmAccess",
            "net.marcloud.mcp.core.flt.seam.SeamController",
            "net.marcloud.mcp.core.flt.seam.NettyTap",
            "net.marcloud.mcp.core.flt.seam.TickInjector",
            // C6 native debugger bridge (holds the JVMTI native binding)
            "net.marcloud.mcp.core.kd.KdBridge",
            // Auth-decision + tool-layer gate wrappers that live OUTSIDE the protected
            // prefixes. The underlying KdBridge/MmAccess are protected, but the tool
            // wrappers that gate them are one layer up in unprotected packages — a
            // redefine of the wrapper's gate method (e.g. DebugTools.guard → no-op,
            // HttpFacade.authorized → true) neutralizes the check without touching the
            // protected core. Cover the wrappers too.
            "net.marcloud.mcp.core.io.http.HttpFacade",
            "net.marcloud.mcp.core.kd.DebugTools",
            "net.marcloud.mcp.core.mm.MutateStateTools");

    /**
     * True if {@code className} must never be redefined/retransformed. Null or
     * blank is treated as not protected (callers validate names separately).
     */
    public static boolean isProtected(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        String n = normalize(className);
        return n.startsWith(SECURITY_PACKAGE) || n.startsWith(OBJECT_PACKAGE)
                || n.startsWith(COMPAT_PACKAGE) || n.startsWith(ALPC_PACKAGE)
                || PROTECTED.contains(n);
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
