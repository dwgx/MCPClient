package net.marcloud.mcp.core.security;

import java.util.Set;

/**
 * The subject of an access decision — the security principal a {@link ToolRequest}
 * runs as. Bundles the five in-JVM security dimensions the {@link PolicyEngine}
 * evaluates:
 * <ul>
 *   <li>{@code clearance} (L2 ring) — driven by drop_privilege / restore_privilege.</li>
 *   <li>{@code integrity} (L3) — the subject's mandatory integrity level.</li>
 *   <li>{@code privileges} (L4) — the two-state privilege token.</li>
 *   <li>{@code capabilities} (L5) — granted capability SIDs (null = wildcard = holds all).</li>
 *   <li>identity ({@code tokenId}) — for auditing and future per-session P-SECURE subjects.</li>
 * </ul>
 * L6 handles and L7 boundary validation are evaluated against the request/args,
 * not carried on the subject, so they are not fields here.
 *
 * <p>Immutable. The dev default is {@link #wideOpen()}: SYSTEM integrity (writes
 * everything except PROTECTED), all privileges enabled, wildcard capabilities —
 * matching today's wide-open R-1 clearance. {@link #withClearance} returns a copy
 * at a different ring so the live clearance from {@link PermissionPolicy} can be
 * folded in per call without rebuilding the token/caps.
 *
 * @param tokenId      opaque subject id (for audit; "dev" for the default subject)
 * @param clearance    L2 ring clearance
 * @param integrity    L3 mandatory integrity level
 * @param privileges   L4 privilege token
 * @param capabilities L5 granted SIDs; {@code null} means wildcard (holds every SID)
 */
public record SecurityContext(String tokenId,
                              Ring clearance,
                              IntegrityLevel integrity,
                              PrivilegeToken privileges,
                              Set<CapabilitySid> capabilities) {

    /**
     * The dev-default subject: wide open on every dimension, matching the R-1
     * clearance the project ships with. Capabilities are wildcard (null) so L5 is
     * satisfied without enumerating; strict default-deny is opt-in at wiring time.
     */
    public static SecurityContext wideOpen() {
        return new SecurityContext("dev", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                PrivilegeToken.wideOpen(), null);
    }

    /** A copy at a different ring clearance (fold in the live drop/restore state). */
    public SecurityContext withClearance(Ring newClearance) {
        if (newClearance == clearance) {
            return this;
        }
        return new SecurityContext(tokenId, newClearance, integrity, privileges, capabilities);
    }

    /** True if this subject holds {@code sid} (wildcard capabilities hold all). */
    public boolean holdsCapability(CapabilitySid sid) {
        return capabilities == null || capabilities.contains(sid);
    }

    /** True if this subject holds every SID in {@code required}. */
    public boolean holdsAll(Set<CapabilitySid> required) {
        if (capabilities == null || required == null || required.isEmpty()) {
            return true;
        }
        return capabilities.containsAll(required);
    }
}
