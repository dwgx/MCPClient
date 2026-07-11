package net.marcloud.mcp.core.se;

import net.marcloud.mcp.core.io.IoRequestPacket;

/**
 * The single access-decision authority. {@link #evaluate} runs the 7 layers in
 * order, AND-composed, short-circuiting on the first deny with a reason that
 * names the failing layer. This is the one place {@link
 * net.marcloud.mcp.core.io.IoManager} consults before dispatching
 * a tool — the reference monitor.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link SeLocalMonitor} (default) — decides in the game JVM.</li>
 *   <li>{@code SeRemoteMonitor} (L1 P-SECURE, opt-in) — asks a separate
 *       process over a loopback socket, so a rogue in-JVM hook cannot forge a
 *       grant. Swapped in without changing any caller because both satisfy this
 *       interface.</li>
 * </ul>
 *
 * <p>The engine also owns the clearance dimension (drop/restore) so callers have
 * one authority to talk to; those delegate to the underlying {@link
 * SeClearancePolicy} in-process, or cross the socket in the remote impl.
 */
public interface SeReferenceMonitor {

    /** Evaluate a request for a subject; never returns null. */
    SeAccessCheck evaluate(SeToken subject, IoRequestPacket request);

    /** Current L2 clearance (highest ring currently permitted). */
    Ring clearance();

    /** Voluntarily lower clearance (self-sandbox); returns the new clearance. */
    Ring dropTo(Ring target);

    /** Raise clearance, gated by the restore token; true on success. */
    boolean tryRestore(Ring target, String token);

    /** Whether restoration is possible (a token was configured). */
    boolean restorable();

    /** The subject to evaluate a tool call as (dev default = wide open). */
    SeToken currentSubject();

    /**
     * L4 self privilege management: enable a granted privilege on the live subject
     * so a tool requiring it is again permitted. Returns false if the privilege was
     * never granted (no self-escalation) or the engine does not own the subject
     * locally (e.g. the P-SECURE authority holds it — see the remote engine).
     *
     * <p>Default: no-op returning {@code false}. Only {@link SeLocalMonitor}
     * owns a mutable local subject; {@code SeRemoteMonitor} keeps its authority's
     * subject across the wall and does not mutate it from in-process.
     */
    default boolean enablePrivilege(Privilege p) {
        return false;
    }

    /**
     * L4 self privilege management: disable a granted privilege on the live subject
     * (least privilege in time). A tool requiring it is then denied at L4 until
     * re-enabled. Returns false if never granted or the engine does not own the
     * subject locally. Default: no-op returning {@code false}.
     */
    default boolean disablePrivilege(Privilege p) {
        return false;
    }

    /**
     * L5 self capability management: grant a capability SID on the live subject so a
     * tool requiring it is permitted. Returns false if the engine does not own the
     * subject locally. Default: no-op returning {@code false}.
     */
    default boolean grantCapability(CapabilitySid sid) {
        return false;
    }

    /**
     * L5 self capability management: revoke a capability SID from the live subject
     * so a tool requiring it is denied at L5. When the subject holds the wildcard
     * set, this materializes the full set minus the revoked SID so the revoke
     * actually bites. Returns false if the engine does not own the subject locally.
     * Default: no-op returning {@code false}.
     */
    default boolean revokeCapability(CapabilitySid sid) {
        return false;
    }
}
