package net.marcloud.mcp.core.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * The default {@link PolicyEngine}: decides in the game JVM. Composes the layers
 * that are meaningful in-process (L1 VTL is a no-op here — the separate-process
 * wall only exists in the remote engine):
 *
 * <pre>
 *   L2 ring       clearance.level &lt;= requiredRing.level
 *   L3 integrity  subject.integrity.canWriteTo(tool.writesResourceAt)
 *   L4 privilege  subject.privileges.isEnabled(tool.requiredPrivilege)
 *   L5 capability subject holds all tool.requiredCaps
 *   L6 handle     (extension seam — filled by the object-handle layer)
 *   L7 boundary   (arg deep-copy + schema validation happens in supervise())
 * </pre>
 *
 * <p>The clearance dimension delegates to {@link PermissionPolicy} (unchanged),
 * so drop_privilege / restore_privilege keep driving L2 exactly as before. The
 * subject is built once ({@code baseSubject}) and re-stamped with the live
 * clearance per evaluation, so a drop is reflected immediately.
 *
 * <p><b>Dev default</b>: {@code baseSubject} is {@link SecurityContext#wideOpen()}
 * (SYSTEM integrity, all privileges enabled, wildcard caps) so every existing
 * tool passes L3-L5 untouched. Strict per-resource-class default-deny is opt-in:
 * construct with an explicit granted capability set instead of wildcard.
 */
public final class InProcessPolicyEngine implements PolicyEngine {

    private final PermissionPolicy policy;
    private final SecurityContext baseSubject;

    /** Wide-open dev default: wraps the policy, subject holds everything. */
    public InProcessPolicyEngine(PermissionPolicy policy) {
        this(policy, SecurityContext.wideOpen());
    }

    /**
     * @param policy      the clearance authority (drop/restore)
     * @param baseSubject the subject template; its clearance is overwritten per
     *                    call with the live {@code policy.clearance()}
     */
    public InProcessPolicyEngine(PermissionPolicy policy, SecurityContext baseSubject) {
        this.policy = policy;
        this.baseSubject = baseSubject;
    }

    /**
     * Strict-mode subject: SYSTEM integrity + all privileges granted/enabled, but
     * only the given capability SIDs (default-deny for the rest). Use for
     * {@code -Dmcp.core.caps=strict}.
     */
    public static SecurityContext strictSubject(Set<CapabilitySid> grantedCaps) {
        return new SecurityContext("dev-strict", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                PrivilegeToken.wideOpen(),
                grantedCaps == null ? EnumSet.noneOf(CapabilitySid.class) : Set.copyOf(grantedCaps));
    }

    @Override
    public AccessDecision evaluate(SecurityContext subject, ToolRequest request) {
        ToolPolicy tp = ToolPolicy.forTool(request.toolName(), request.builtIn());

        // L2 — ring clearance (identical to the old policy.allows(ring) check).
        if (subject.clearance().level() > tp.requiredRing().level()) {
            return AccessDecision.deny("L2 ring",
                    "tool '" + request.toolName() + "' requires " + tp.requiredRing().tag()
                    + " but clearance is " + subject.clearance().tag()
                    + (policy.restorable()
                        ? " — raise it with restore_privilege(token)."
                        : " — privilege was dropped and cannot be restored this session."));
        }

        // L3 — mandatory integrity (no-write-up) for mutating tools.
        if (tp.writesResourceAt() != null && !subject.integrity().canWriteTo(tp.writesResourceAt())) {
            return AccessDecision.deny("L3 integrity",
                    "tool '" + request.toolName() + "' writes a " + tp.writesResourceAt().label()
                    + "-integrity resource but the subject is only " + subject.integrity().label());
        }

        // L4 — privilege must be granted AND enabled.
        if (tp.requiredPrivilege() != null && !subject.privileges().isEnabled(tp.requiredPrivilege())) {
            return AccessDecision.deny("L4 privilege",
                    "tool '" + request.toolName() + "' requires privilege "
                    + tp.requiredPrivilege().name() + " to be enabled"
                    + (subject.privileges().isGranted(tp.requiredPrivilege())
                        ? " (granted but disabled — no in-session tool can re-enable it;"
                          + " relaunch with the subject configured to enable this privilege)."
                        : " (not granted to this subject)."));
        }

        // L5 — capability SIDs, default-deny per resource class.
        if (!subject.holdsAll(tp.requiredCaps())) {
            return AccessDecision.deny("L5 capability",
                    "tool '" + request.toolName() + "' requires capabilities " + tp.requiredCaps()
                    + " which the subject does not fully hold");
        }

        // L6/L7 are enforced elsewhere (object handles / supervise() boundary guard).
        return AccessDecision.allowed();
    }

    @Override
    public Ring clearance() {
        return policy.clearance();
    }

    @Override
    public Ring dropTo(Ring target) {
        return policy.dropTo(target);
    }

    @Override
    public boolean tryRestore(Ring target, String token) {
        return policy.tryRestore(target, token);
    }

    @Override
    public boolean restorable() {
        return policy.restorable();
    }

    @Override
    public SecurityContext currentSubject() {
        return baseSubject.withClearance(policy.clearance());
    }

    /** The underlying clearance policy (for PermissionTools compatibility). */
    public PermissionPolicy policy() {
        return policy;
    }
}
