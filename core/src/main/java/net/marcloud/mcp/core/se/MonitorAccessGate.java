package net.marcloud.mcp.core.se;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The enforcing {@link AccessGate}: the L4/L5 half of the AND that
 * {@code HookTools}, {@code DebugTools} and {@code MmAccess} document as
 * "defense-in-depth", backed by the live {@link SeReferenceMonitor} instead of
 * by a no-op.
 *
 * <p><b>Why this exists.</b> {@link AccessGate} is consulted at the
 * <i>implementation</i> boundary — {@code MmAccess.setField}, {@code
 * FltDynamicManager} install/uninstall via {@code HookTools}, the {@code
 * DebugTools} guard — which is a different seam from the by-name tool table
 * {@link SeToolRequirement#forTool} reads at the {@code IoManager} dispatch
 * boundary. The two are AND-composed: a tool name must clear the table
 * <i>and</i> the operation must clear this gate. Before this class existed the
 * second term was {@link AllowAllGate}, an empty method body, so the documented
 * second term of the AND was not there. The in-JVM path is exactly where the
 * table cannot help: a caller that reaches {@code MmAccess.setField} without
 * going through a registered tool name crosses no L4 and no L5 at all. This gate
 * is what closes that.
 *
 * <p><b>What it checks, and what it deliberately does not.</b> Only L4 and L5,
 * because only those are what {@link AccessGate#require} is given: a capability
 * SID (a resource class) and a set of privileges (dangerous verbs). The L2 ring
 * is not re-checked here — the call site has no ring to check against, and the
 * ring term of the AND is the registry's. A subject whose capabilities are
 * wildcard ({@code null}) and whose privileges are all enabled is allowed,
 * which is the shipped wide-open posture.
 *
 * <p><b>Live, not snapshotted.</b> The subject is re-read from the monitor on
 * every call, so an in-session {@code disable_privilege} /
 * {@code revoke_capability} takes effect at the implementation boundary
 * immediately, exactly as it does at the dispatch boundary. Caching the subject
 * in the constructor would reintroduce the defect in a new costume: a gate
 * built from a snapshot is a gate that stops biting the moment the operator
 * tightens the posture.
 *
 * <p><b>Known limit, stated rather than papered over.</b> Under
 * {@code -Dmcp.core.psecure=true} the engine is a {@link SeRemoteMonitor} (or a
 * {@link SeHandleGatedMonitor} in front of it), and
 * {@link SeRemoteMonitor#currentSubject} deliberately returns a wide-open
 * display token: the authoritative subject lives in the other process and
 * {@code evaluate} is the only thing that consults it. So under P-SECURE this
 * gate adds no local teeth — the authority's L4/L5 verdict still arrives at the
 * dispatch boundary, and the implementation boundary is covered by that same
 * verdict rather than by a second local decision. Making the gate fail closed
 * here instead would deny every {@code MmAccess} operation in a P-SECURE
 * deployment while adding no security, because the authority has already ruled
 * by the time the call is made.
 *
 * <p>Implementations deny by throwing {@link SecurityException}, with the
 * message formatted by {@link SeAccessCheck#message()} so the text an operator
 * sees is byte-identical whether the deny came from the dispatch table or from
 * here.
 */
public final class MonitorAccessGate implements AccessGate {

    private final Supplier<SeToken> subject;

    /**
     * @param engine the live reference monitor; its {@link
     *               SeReferenceMonitor#currentSubject()} is read per call, never
     *               cached
     */
    public MonitorAccessGate(SeReferenceMonitor engine) {
        this(Objects.requireNonNull(engine, "engine")::currentSubject);
    }

    /**
     * A gate over an arbitrary subject supplier, for the cases where the
     * subject is not reachable through a monitor. Deliberately a factory and not
     * a second PUBLIC constructor: two public single-argument constructors make
     * a method reference like {@code SeToken::wideOpen} ambiguous at the call
     * site, and an ambiguous overload in a security class is a trap rather than
     * a convenience. This one is private, so it cannot collide for callers.
     *
     * @param subject supplies the CURRENT subject; consulted on every
     *                {@link #require} call, never cached
     */
    public static MonitorAccessGate overSubject(Supplier<SeToken> subject) {
        return new MonitorAccessGate(Objects.requireNonNull(subject, "subject"));
    }

    private MonitorAccessGate(Supplier<SeToken> subject) {
        this.subject = subject;
    }

    @Override
    public void require(CapabilitySid cap, Privilege... privs) {
        // A null requirement is a programming error at the call site, not a
        // permission to proceed. Failing open on a bug here would be the same
        // shape of defect this class exists to remove, one level down.
        if (cap == null) {
            throw new IllegalArgumentException("capability SID is required");
        }
        SeToken s = subject.get();
        if (s == null) {
            throw new SecurityException(SeAccessCheck.deny("L4/L5",
                    "no current subject to evaluate the operation against — failing closed")
                    .message());
        }

        // L4 — each named privilege must be granted AND enabled.
        if (privs != null) {
            for (Privilege p : privs) {
                if (p == null) {
                    throw new IllegalArgumentException("privilege entries must not be null");
                }
                if (!s.privileges().isEnabled(p)) {
                    throw new SecurityException(SeAccessCheck.deny("L4 privilege",
                            "this operation requires privilege " + p.name() + " to be enabled"
                            + (s.privileges().isGranted(p)
                                ? " (granted but disabled — enable it in-session with "
                                  + "enable_privilege(" + p.name() + "), or relaunch with it "
                                  + "enabled)."
                                : " (not granted to this subject).")).message());
                }
            }
        }

        // L5 — the resource class must be held (wildcard capabilities hold all).
        if (!s.holdsCapability(cap)) {
            throw new SecurityException(SeAccessCheck.deny("L5 capability",
                    "this operation requires capability " + cap.tag()
                    + " which the current subject does not hold"
                    + " (grant it in-session with grant_capability(" + cap.name() + "), "
                    + "or relaunch with it granted).").message());
        }
    }
}
