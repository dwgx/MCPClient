package net.marcloud.mcp.core.se;

import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.ob.ObManager;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The default {@link SeReferenceMonitor}: decides in the game JVM. Composes the layers
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
 * <p>The clearance dimension delegates to {@link SeClearancePolicy} (unchanged),
 * so drop_privilege / restore_privilege keep driving L2 exactly as before. The
 * subject is built once ({@code baseSubject}) and re-stamped with the live
 * clearance per evaluation, so a drop is reflected immediately.
 *
 * <p><b>Dev default</b>: {@code baseSubject} is {@link SeToken#wideOpen()}
 * (SYSTEM integrity, all privileges enabled, wildcard caps) so every existing
 * tool passes L3-L5 untouched. Strict per-resource-class default-deny is opt-in:
 * construct with an explicit granted capability set instead of wildcard.
 */
public final class SeLocalMonitor implements SeReferenceMonitor {

    private final SeClearancePolicy policy;

    /**
     * The ONE stable, mutable base subject for this engine's lifetime. Its {@link
     * PrivilegeToken} is mutable (enable/disable flip {@code AtomicBoolean}s in
     * place), and its capability set is swapped by reassigning this field via
     * {@link SeToken#withCapabilities} — which preserves the SAME token
     * instance, so an L4 change is never lost when an L5 change happens. Every
     * {@link #currentSubject()} re-stamps THIS instance with the live clearance, so
     * a disable/revoke persists across calls instead of resetting to wide open.
     */
    private volatile SeToken baseSubject;

    /** L6 object-handle manager, or null (the default — L6 is a pure no-op then). */
    private final ObManager objects;

    /** Wide-open dev default: wraps the policy, subject holds everything. */
    public SeLocalMonitor(SeClearancePolicy policy) {
        this(policy, SeToken.wideOpen(), null);
    }

    /**
     * @param policy      the clearance authority (drop/restore)
     * @param baseSubject the subject template; its clearance is overwritten per
     *                    call with the live {@code policy.clearance()}
     */
    public SeLocalMonitor(SeClearancePolicy policy, SeToken baseSubject) {
        this(policy, baseSubject, null);
    }

    /**
     * @param policy      the clearance authority (drop/restore)
     * @param baseSubject the subject template
     * @param objects     L6 object-handle manager, or null (L6 becomes a no-op)
     */
    public SeLocalMonitor(SeClearancePolicy policy, SeToken baseSubject,
                                 ObManager objects) {
        this.policy = policy;
        this.baseSubject = baseSubject;
        this.objects = objects;
    }

    /**
     * Strict-mode subject: SYSTEM integrity + all privileges granted/enabled, but
     * only the given capability SIDs (default-deny for the rest). Use for
     * {@code -Dmcp.core.caps=strict}.
     */
    public static SeToken strictSubject(Set<CapabilitySid> grantedCaps) {
        return new SeToken("dev-strict", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                PrivilegeToken.wideOpen(),
                grantedCaps == null ? EnumSet.noneOf(CapabilitySid.class) : Set.copyOf(grantedCaps));
    }

    /**
     * Hardened opt-in subject for {@code -Dmcp.core.hardened=true}. Unlike {@link
     * #wideOpen()} (which passes every layer) and {@link #strictSubject} (which
     * only tightens L5), this subject is built so denials surface at the
     * less-tested L4 and L5 layers rather than L3:
     * <ul>
     *   <li><b>integrity = SYSTEM</b> — so L3 (no-write-up) passes for every tool
     *       exactly as it does for the wide-open default. A lower integrity would
     *       deny HIGH/SYSTEM-writing tools at L3 first and never reach L4/L5.</li>
     *   <li><b>privileges = every {@link Privilege} GRANTED but DISABLED</b> — built
     *       from an {@link EnumMap} mapping all privileges to {@code false}. L4 then
     *       denies every dangerous verb (its privilege is not enabled), yet the
     *       existing R0 {@code enable_privilege} tool remains a live in-session lever
     *       because the privilege is granted (a disabled-but-granted privilege can
     *       be enabled; an ungranted one cannot).</li>
     *   <li><b>capabilities = empty ({@link EnumSet#noneOf})</b> — L5 default-deny for
     *       every gated resource class, re-openable via the existing R0
     *       {@code grant_capability} tool.</li>
     * </ul>
     * The subject keeps R_MINUS_1 clearance so L2 clears every ring and the deny is
     * attributable to L4/L5, not L2.
     */
    public static SeToken hardenedSubject() {
        EnumMap<Privilege, Boolean> grantedDisabled = new EnumMap<>(Privilege.class);
        for (Privilege p : Privilege.values()) {
            grantedDisabled.put(p, false); // granted (key present) but disabled (value false)
        }
        return new SeToken("dev-hardened", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                new PrivilegeToken(grantedDisabled),
                EnumSet.noneOf(CapabilitySid.class));
    }

    @Override
    public SeAccessCheck evaluate(SeToken subject, IoRequestPacket request) {
        SeToolRequirement tp = SeToolRequirement.forTool(request.toolName(), request.builtIn());

        // L2 — ring clearance (identical to the old policy.allows(ring) check).
        if (subject.clearance().level() > tp.requiredRing().level()) {
            return SeAccessCheck.deny("L2 ring",
                    "tool '" + request.toolName() + "' requires " + tp.requiredRing().tag()
                    + " but clearance is " + subject.clearance().tag()
                    + (policy.restorable()
                        ? " — raise it with restore_privilege(token)."
                        : " — privilege was dropped and cannot be restored this session."));
        }

        // L3 — mandatory integrity (no-write-up) for mutating tools.
        if (tp.writesResourceAt() != null && !subject.integrity().canWriteTo(tp.writesResourceAt())) {
            return SeAccessCheck.deny("L3 integrity",
                    "tool '" + request.toolName() + "' writes a " + tp.writesResourceAt().label()
                    + "-integrity resource but the subject is only " + subject.integrity().label());
        }

        // L4 — privilege must be granted AND enabled.
        if (tp.requiredPrivilege() != null && !subject.privileges().isEnabled(tp.requiredPrivilege())) {
            return SeAccessCheck.deny("L4 privilege",
                    "tool '" + request.toolName() + "' requires privilege "
                    + tp.requiredPrivilege().name() + " to be enabled"
                    + (subject.privileges().isGranted(tp.requiredPrivilege())
                        ? " (granted but disabled — enable it in-session with enable_privilege("
                          + tp.requiredPrivilege().name() + "), or relaunch with it enabled)."
                        : " (not granted to this subject)."));
        }

        // L5 — capability SIDs, default-deny per resource class.
        if (!subject.holdsAll(tp.requiredCaps())) {
            return SeAccessCheck.deny("L5 capability",
                    "tool '" + request.toolName() + "' requires capabilities " + tp.requiredCaps()
                    + " which the subject does not fully hold");
        }

        // L6 — object-handle subset check. No-op unless an ObManager is wired
        // AND the request carries a "handle" arg; handle-less tools are unaffected.
        if (objects != null) {
            SeAccessCheck h6 = objects.checkRequest(subject, request);
            if (!h6.allow()) {
                return h6;
            }
        }

        // L7 is enforced elsewhere (supervise() boundary guard).
        return SeAccessCheck.allowed();
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
    public SeToken currentSubject() {
        return baseSubject.withClearance(policy.clearance());
    }

    // ---- L4 / L5 self management on the live base subject ------------------
    // These mutate the ONE stable base subject so a change persists across every
    // subsequent evaluate(). The privilege token is mutated in place (its
    // AtomicBooleans); the capability set is swapped by reassigning baseSubject to
    // a copy that keeps the SAME token instance (withCapabilities), so L4 and L5
    // changes never clobber each other.

    @Override
    public synchronized boolean enablePrivilege(Privilege p) {
        return p != null && baseSubject.privileges().enable(p);
    }

    @Override
    public synchronized boolean disablePrivilege(Privilege p) {
        return p != null && baseSubject.privileges().disable(p);
    }

    @Override
    public synchronized boolean grantCapability(CapabilitySid sid) {
        if (sid == null) {
            return false;
        }
        Set<CapabilitySid> caps = baseSubject.capabilities();
        if (caps == null) {
            // Already wildcard — holds everything, including this SID. Nothing to do.
            return true;
        }
        Set<CapabilitySid> next = new LinkedHashSet<>(caps);
        next.add(sid);
        baseSubject = baseSubject.withCapabilities(next);
        return true;
    }

    @Override
    public synchronized boolean revokeCapability(CapabilitySid sid) {
        if (sid == null) {
            return false;
        }
        Set<CapabilitySid> caps = baseSubject.capabilities();
        Set<CapabilitySid> next;
        if (caps == null) {
            // Wildcard: materialize the full set MINUS the revoked SID so the
            // revoke actually bites (otherwise holdsAll would keep passing).
            next = EnumSet.allOf(CapabilitySid.class);
            next.remove(sid);
        } else {
            next = new LinkedHashSet<>(caps);
            next.remove(sid);
        }
        baseSubject = baseSubject.withCapabilities(next);
        return true;
    }

    /** The underlying clearance policy (for PermissionTools compatibility). */
    public SeClearancePolicy policy() {
        return policy;
    }
}
