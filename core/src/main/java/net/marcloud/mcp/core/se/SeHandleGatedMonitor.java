package net.marcloud.mcp.core.se;

import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.ob.ObManager;

/**
 * A LOCAL L6 object-handle gate spliced in FRONT of a remote {@link
 * SeReferenceMonitor} authority (the P-SECURE {@link SeRemoteMonitor}).
 *
 * <p><b>Why this exists (KI-8).</b> Under {@code -Dmcp.core.psecure=true} the
 * engine defers L1-L5 to the separate P-SECURE process. But an L6 handle freezes
 * an <i>in-JVM object</i> — a snapshot the remote process cannot resolve, because
 * the object lives in this address space. So {@code McpCore.buildEngine} used to
 * return the bare {@link SeRemoteMonitor}, silently DROPPING the {@link ObManager}:
 * with psecure + handles + hardened all on, the L6 strict-handle TOCTOU protection
 * became a no-op. This wrapper restores it by keeping L6 <b>local</b>, in front of
 * the remote authority, rather than trying (and failing) to pass the handle across
 * the wall.
 *
 * <p><b>Ordering (mirrors {@link SeLocalMonitor#evaluate}).</b> {@link #evaluate}
 * asks the remote authority FIRST for L1-L5 (fail-closed — a transport failure is
 * a deny there). Only if the remote authority ALLOWS does it run the local L6
 * {@link ObManager#checkRequest}. This keeps L6 innermost and preserves the remote
 * layer's own deny name (e.g. "L1 VTL", "L5 capability") on a remote deny, exactly
 * as {@code SeLocalMonitor} short-circuits on the first failing layer with that
 * layer's name and only reaches L6 last.
 *
 * <p><b>Everything else delegates straight through</b> to the remote monitor:
 * clearance / dropTo / tryRestore / restorable / currentSubject and the L4/L5 self
 * management verbs are all owned by the P-SECURE authority across the wall. This
 * wrapper adds ONLY the local L6 check; it never mutates or second-guesses the
 * remote authority's clearance or subject.
 *
 * <p><b>When L6 is off</b> ({@code objects == null}) do NOT wrap — use the bare
 * remote monitor directly. The constructor rejects a null manager so a wrapped
 * instance always has real L6 teeth.
 */
public final class SeHandleGatedMonitor implements SeReferenceMonitor {

    private final SeReferenceMonitor delegate;
    private final ObManager objects;

    /**
     * @param delegate the remote authority handling L1-L5 (never null)
     * @param objects  the LOCAL L6 object-handle manager (never null — when L6 is
     *                 off, use {@code delegate} directly instead of wrapping)
     */
    public SeHandleGatedMonitor(SeReferenceMonitor delegate, ObManager objects) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate monitor must not be null");
        }
        if (objects == null) {
            throw new IllegalArgumentException(
                    "ObManager must not be null — when L6 is off, use the delegate monitor "
                    + "directly rather than wrapping it in SeHandleGatedMonitor");
        }
        this.delegate = delegate;
        this.objects = objects;
    }

    @Override
    public SeAccessCheck evaluate(SeToken subject, IoRequestPacket request) {
        // L1-L5 at the remote authority FIRST (fail-closed: a transport failure is a
        // deny there, keeping its own layer name). A remote deny short-circuits with
        // that layer's reason — never masked by L6.
        SeAccessCheck outer = delegate.evaluate(subject, request);
        if (!outer.allow()) {
            return outer;
        }
        // Only if the remote authority allows do we apply the LOCAL L6 handle check
        // (innermost), mirroring SeLocalMonitor's L6-last ordering. Handle-less
        // non-handle tools pass through as a no-op; a strict-mode handle-op without a
        // valid handle bites here even though the remote authority saw no handle.
        return objects.checkRequest(subject, request);
    }

    // ---- everything else: the P-SECURE authority owns it across the wall --------

    @Override
    public Ring clearance() {
        return delegate.clearance();
    }

    @Override
    public Ring dropTo(Ring target) {
        return delegate.dropTo(target);
    }

    @Override
    public boolean tryRestore(Ring target, String token) {
        return delegate.tryRestore(target, token);
    }

    @Override
    public boolean restorable() {
        return delegate.restorable();
    }

    @Override
    public SeToken currentSubject() {
        return delegate.currentSubject();
    }

    @Override
    public boolean enablePrivilege(Privilege p) {
        return delegate.enablePrivilege(p);
    }

    @Override
    public boolean disablePrivilege(Privilege p) {
        return delegate.disablePrivilege(p);
    }

    @Override
    public boolean grantCapability(CapabilitySid sid) {
        return delegate.grantCapability(sid);
    }

    @Override
    public boolean revokeCapability(CapabilitySid sid) {
        return delegate.revokeCapability(sid);
    }
}
