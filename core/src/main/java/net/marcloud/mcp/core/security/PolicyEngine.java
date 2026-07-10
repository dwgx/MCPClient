package net.marcloud.mcp.core.security;

/**
 * The single access-decision authority. {@link #evaluate} runs the 7 layers in
 * order, AND-composed, short-circuiting on the first deny with a reason that
 * names the failing layer. This is the one place {@link
 * net.marcloud.mcp.core.registry.CapabilityRegistry} consults before dispatching
 * a tool — the reference monitor.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link InProcessPolicyEngine} (default) — decides in the game JVM.</li>
 *   <li>{@code RemotePolicyEngine} (L1 P-SECURE, opt-in) — asks a separate
 *       process over a loopback socket, so a rogue in-JVM hook cannot forge a
 *       grant. Swapped in without changing any caller because both satisfy this
 *       interface.</li>
 * </ul>
 *
 * <p>The engine also owns the clearance dimension (drop/restore) so callers have
 * one authority to talk to; those delegate to the underlying {@link
 * PermissionPolicy} in-process, or cross the socket in the remote impl.
 */
public interface PolicyEngine {

    /** Evaluate a request for a subject; never returns null. */
    AccessDecision evaluate(SecurityContext subject, ToolRequest request);

    /** Current L2 clearance (highest ring currently permitted). */
    Ring clearance();

    /** Voluntarily lower clearance (self-sandbox); returns the new clearance. */
    Ring dropTo(Ring target);

    /** Raise clearance, gated by the restore token; true on success. */
    boolean tryRestore(Ring target, String token);

    /** Whether restoration is possible (a token was configured). */
    boolean restorable();

    /** The subject to evaluate a tool call as (dev default = wide open). */
    SecurityContext currentSubject();
}
