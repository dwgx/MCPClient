package net.marcloud.mcp.core.security;

/**
 * The result of a {@link PolicyEngine} evaluation: allow/deny plus, on deny, a
 * precise reason naming the layer that rejected (e.g. "L4 privilege
 * SE_DEBUG_CLASS not enabled"). The layer tag makes denials debuggable and
 * mirrors an NT reference-monitor audit record.
 *
 * @param allow true if every layer permitted the request
 * @param layer the layer that decided (the denying layer, or "L*" on allow)
 * @param reason human-readable explanation (empty on allow)
 */
public record AccessDecision(boolean allow, String layer, String reason) {

    private static final AccessDecision ALLOWED = new AccessDecision(true, "L1-L7", "");

    /** A uniform allow (every layer passed). */
    public static AccessDecision allowed() {
        return ALLOWED;
    }

    /** A deny attributed to {@code layer} with a human {@code reason}. */
    public static AccessDecision deny(String layer, String reason) {
        return new AccessDecision(false, layer, reason);
    }

    /** The full "denied: <layer>: <reason>" message for a tool error result. */
    public String message() {
        return allow ? "" : "permission denied [" + layer + "]: " + reason;
    }
}
