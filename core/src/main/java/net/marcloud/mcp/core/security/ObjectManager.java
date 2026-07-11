package net.marcloud.mcp.core.security;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The L6 object-handle registry: mints frozen {@link ObjectHandle}s
 * (open → freeze-mask), enforces the per-operation subset check
 * ({@link #require}), owner-scopes handles per subject, caps handles per
 * subject, and reaps idle handles.
 *
 * <p><b>Additive and orthogonal.</b> {@link #checkRequest} is the single gate
 * seam the {@link PolicyEngine} splices in: it is a pure no-op unless the
 * request literally carries a {@code "handle"} arg, so evaluate() with an empty
 * arg map (isAllowed / the pre-handler gate) is unaffected, and every tool that
 * uses no handles passes through untouched.
 *
 * <p>The 4-arg ctor injects a fake clock so the idle reaper is deterministic in
 * tests with no sleeps; production uses {@code System::nanoTime}.
 */
public final class ObjectManager {

    /** Resolves a {@link ResourceRef} to the live object a handle freezes over. */
    @FunctionalInterface
    public interface TargetResolver {
        Object resolve(ResourceRef ref) throws Exception;
    }

    private final ConcurrentHashMap<Long, ObjectHandle> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> perSubject = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);
    private final TargetResolver resolver;
    private final int perSubjectCap;
    private final long idleTtlNanos;
    private final LongSupplier clock;   // System::nanoTime in prod; fake clock in tests

    /**
     * Per-tool right needed for a handle op. Only handle-using tools are listed;
     * a tool not present defaults to {@link AccessRight#READ}. The {@code "handle"}
     * arg key is reserved — non-handle tools must not declare it.
     */
    private static final Map<String, Integer> HANDLE_OPS = Map.ofEntries(
            Map.entry("dbg_read_locals",  AccessRight.READ.bit()),
            Map.entry("dbg_set_local",    AccessRight.WRITE.bit()),
            Map.entry("dbg_force_return", AccessRight.WRITE.bit()),
            Map.entry("dbg_suspend",      AccessRight.EXECUTE.bit()),
            Map.entry("dbg_resume",       AccessRight.EXECUTE.bit()),
            Map.entry("dbg_pop_frame",    AccessRight.EXECUTE.bit()),
            Map.entry("dbg_step",         AccessRight.EXECUTE.bit()));

    public ObjectManager(TargetResolver r, int cap, long idleTtlMillis) {
        this(r, cap, idleTtlMillis, System::nanoTime);
    }

    ObjectManager(TargetResolver r, int cap, long idleTtlMillis, LongSupplier clock) {
        this.resolver = r;
        this.perSubjectCap = cap;
        this.idleTtlNanos = idleTtlMillis * 1_000_000L;
        this.clock = clock;
    }

    /**
     * Open a handle: reject a mask above the scheme ceiling, enforce the
     * per-subject cap, resolve the target once, and mint a frozen handle.
     */
    public synchronized ObjectHandle open(SecurityContext s, ResourceRef ref, int desiredMask) {
        reapIdle();
        if (!AccessRight.subset(ref.allowableRights(), desiredMask)) {
            throw new IllegalArgumentException("L6 open: mask " + AccessRight.render(desiredMask)
                    + " exceeds " + ref.prefix() + " allowable " + AccessRight.render(ref.allowableRights()));
        }
        String who = s.tokenId();
        if (perSubject.getOrDefault(who, 0) >= perSubjectCap) {
            throw new IllegalStateException("L6 open: subject '" + who + "' at handle cap " + perSubjectCap);
        }
        Object t;
        try {
            t = resolver.resolve(ref);
        } catch (Exception e) {
            throw new RuntimeException("L6 open: cannot resolve " + ref, e);
        }
        long id = seq.getAndIncrement();
        long now = clock.getAsLong();
        ObjectHandle h = new ObjectHandle(id, who, ref, desiredMask, t, now, this::deregister);
        handles.put(id, h);
        perSubject.merge(who, 1, Integer::sum);
        return h;
    }

    private synchronized void deregister(ObjectHandle h) {
        handles.remove(h.id());
        perSubject.computeIfPresent(h.owner(), (k, v) -> v <= 1 ? null : v - 1);
    }

    /** Close a handle by id, but only if the caller owns it. */
    public void close(long id, SecurityContext s) {
        ObjectHandle h = handles.get(id);
        if (h != null && h.owner().equals(s.tokenId())) {
            h.close();
        }
    }

    /** The subset check — the L6 verdict for one handle op. */
    public AccessDecision require(long id, SecurityContext s, int needed) {
        ObjectHandle h = handles.get(id);
        if (h == null || h.isClosed()) {
            return AccessDecision.deny("L6 handle", "no open handle #" + id + " (unknown, closed, or reaped)");
        }
        if (!h.owner().equals(s.tokenId())) {
            return AccessDecision.deny("L6 handle",
                    "handle #" + id + " owned by '" + h.owner() + "', not '" + s.tokenId() + "'");
        }
        if (!h.permits(needed)) {
            return AccessDecision.deny("L6 handle", "handle #" + id + " frozen mask "
                    + AccessRight.render(h.mask()) + " does not grant " + AccessRight.render(needed));
        }
        h.touch(clock.getAsLong());
        return AccessDecision.allowed();
    }

    /** The gate seam: no {@code "handle"} arg ⇒ allowed() (pure no-op for every non-handle tool). */
    public AccessDecision checkRequest(SecurityContext s, ToolRequest req) {
        Object hv = req.arguments().get("handle");
        if (hv == null) {
            return AccessDecision.allowed();
        }
        long id;
        try {
            id = Long.parseLong(hv.toString().trim());
        } catch (NumberFormatException e) {
            return AccessDecision.deny("L6 handle", "malformed handle id '" + hv + "'");
        }
        return require(id, s, HANDLE_OPS.getOrDefault(req.toolName(), AccessRight.READ.bit()));
    }

    /** Close every handle idle longer than the TTL; returns the count reaped. */
    public synchronized int reapIdle() {
        long now = clock.getAsLong();
        int n = 0;
        for (ObjectHandle h : new ArrayList<>(handles.values())) {
            if (now - h.lastUsedNanos() > idleTtlNanos) {
                h.close();
                n++;
            }
        }
        return n;
    }

    /** Open-handle count for a subject. */
    public int openCount(String tokenId) {
        return perSubject.getOrDefault(tokenId, 0);
    }

    /** Total live handles across all subjects. */
    public int total() {
        return handles.size();
    }
}
