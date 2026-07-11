package net.marcloud.mcp.core.ob;

import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeToken;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The L6 object-handle registry: mints frozen {@link ObHandle}s
 * (open → freeze-mask), enforces the per-operation subset check
 * ({@link #require}), owner-scopes handles per subject, caps handles per
 * subject, and reaps idle handles.
 *
 * <p><b>Additive and orthogonal.</b> {@link #checkRequest} is the single gate
 * seam the {@link SeReferenceMonitor} splices in: it is a pure no-op unless the
 * request literally carries a {@code "handle"} arg, so evaluate() with an empty
 * arg map (isAllowed / the pre-handler gate) is unaffected, and every tool that
 * uses no handles passes through untouched.
 *
 * <p>The 4-arg ctor injects a fake clock so the idle reaper is deterministic in
 * tests with no sleeps; production uses {@code System::nanoTime}.
 */
public final class ObManager {

    /** Resolves a {@link ObRef} to the live object a handle freezes over. */
    @FunctionalInterface
    public interface TargetResolver {
        Object resolve(ObRef ref) throws Exception;
    }

    private final ConcurrentHashMap<Long, ObHandle> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> perSubject = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);
    private final TargetResolver resolver;
    private final int perSubjectCap;
    private final long idleTtlNanos;
    private final LongSupplier clock;   // System::nanoTime in prod; fake clock in tests

    /**
     * Strict-handle posture (default false). When true, a tool listed in
     * {@link #HANDLE_OPS} invoked WITHOUT a {@code "handle"} arg is DENIED instead
     * of passing through — so L6's frozen-handle TOCTOU protection cannot be
     * bypassed simply by omitting the handle and falling back to name-based
     * resolution. Wired from {@code -Dmcp.core.hardened=true}. Off ⇒ the historical
     * "voluntary" behavior (handle-less handle-op tools pass to their own name path).
     */
    private final boolean strictHandles;

    /**
     * Per-tool right needed for a handle op. Only handle-using tools are listed;
     * a tool not present defaults to {@link ObAccessMask#READ}. The {@code "handle"}
     * arg key is reserved — non-handle tools must not declare it.
     */
    private static final Map<String, Integer> HANDLE_OPS = Map.ofEntries(
            Map.entry("debug_read_local",    ObAccessMask.READ.bit()),
            Map.entry("debug_write_local",   ObAccessMask.WRITE.bit()),
            Map.entry("debug_force_return",  ObAccessMask.WRITE.bit()),
            Map.entry("debug_suspend_thread", ObAccessMask.EXECUTE.bit()),
            Map.entry("debug_pop_frame",     ObAccessMask.EXECUTE.bit()),
            Map.entry("debug_single_step",   ObAccessMask.EXECUTE.bit()));

    public ObManager(TargetResolver r, int cap, long idleTtlMillis) {
        this(r, cap, idleTtlMillis, System::nanoTime, false);
    }

    /** As the 3-arg ctor but with the strict-handle posture (see {@link #strictHandles}). */
    public ObManager(TargetResolver r, int cap, long idleTtlMillis, boolean strictHandles) {
        this(r, cap, idleTtlMillis, System::nanoTime, strictHandles);
    }

    ObManager(TargetResolver r, int cap, long idleTtlMillis, LongSupplier clock) {
        this(r, cap, idleTtlMillis, clock, false);
    }

    ObManager(TargetResolver r, int cap, long idleTtlMillis, LongSupplier clock, boolean strictHandles) {
        this.resolver = r;
        this.perSubjectCap = cap;
        this.idleTtlNanos = idleTtlMillis * 1_000_000L;
        this.clock = clock;
        this.strictHandles = strictHandles;
    }

    /**
     * Open a handle: reject a mask above the scheme ceiling, enforce the
     * per-subject cap, resolve the target once, and mint a frozen handle.
     */
    public synchronized ObHandle open(SeToken s, ObRef ref, int desiredMask) {
        return open(s, ref, desiredMask, resolver);
    }

    /**
     * As {@link #open(SeToken, ObRef, int)} but resolves the target
     * with the supplied {@code with} resolver instead of the instance one. Lets a
     * caller freeze a resource it has ALREADY resolved (avoiding a second lookup
     * that could observe a different object), while the mask ceiling, per-subject
     * cap, and freeze semantics are enforced identically.
     */
    public synchronized ObHandle open(SeToken s, ObRef ref, int desiredMask,
                                          TargetResolver with) {
        reapIdle();
        if (!ObAccessMask.subset(ref.allowableRights(), desiredMask)) {
            throw new IllegalArgumentException("L6 open: mask " + ObAccessMask.render(desiredMask)
                    + " exceeds " + ref.prefix() + " allowable " + ObAccessMask.render(ref.allowableRights()));
        }
        String who = s.tokenId();
        if (perSubject.getOrDefault(who, 0) >= perSubjectCap) {
            throw new IllegalStateException("L6 open: subject '" + who + "' at handle cap " + perSubjectCap);
        }
        Object t;
        try {
            t = with.resolve(ref);
        } catch (Exception e) {
            throw new RuntimeException("L6 open: cannot resolve " + ref, e);
        }
        long id = seq.getAndIncrement();
        long now = clock.getAsLong();
        ObHandle h = new ObHandle(id, who, ref, desiredMask, t, now, this::deregister);
        handles.put(id, h);
        perSubject.merge(who, 1, Integer::sum);
        return h;
    }

    private synchronized void deregister(ObHandle h) {
        handles.remove(h.id());
        perSubject.computeIfPresent(h.owner(), (k, v) -> v <= 1 ? null : v - 1);
    }

    /**
     * The resolved-once frozen target of an open handle owned by {@code s}, or
     * null (unknown / closed / not owned). Handlers call this to operate on the
     * snapshot the handle froze at open() rather than re-resolving the resource by
     * name — the actual point of L6: it closes the jthread/name-reuse TOCTOU that a
     * per-call findThread(name) leaves open. The {@link #checkRequest} gate has
     * already validated the mask for this op before the handler runs.
     */
    public Object frozenTarget(long id, SeToken s) {
        ObHandle h = handles.get(id);
        if (h == null || h.isClosed() || !h.owner().equals(s.tokenId())) {
            return null;
        }
        return h.target();
    }

    /** Close a handle by id, but only if the caller owns it. */
    public void close(long id, SeToken s) {
        ObHandle h = handles.get(id);
        if (h != null && h.owner().equals(s.tokenId())) {
            h.close();
        }
    }

    /** The subset check — the L6 verdict for one handle op. */
    public SeAccessCheck require(long id, SeToken s, int needed) {
        ObHandle h = handles.get(id);
        if (h == null || h.isClosed()) {
            return SeAccessCheck.deny("L6 handle", "no open handle #" + id + " (unknown, closed, or reaped)");
        }
        if (!h.owner().equals(s.tokenId())) {
            return SeAccessCheck.deny("L6 handle",
                    "handle #" + id + " owned by '" + h.owner() + "', not '" + s.tokenId() + "'");
        }
        if (!h.permits(needed)) {
            return SeAccessCheck.deny("L6 handle", "handle #" + id + " frozen mask "
                    + ObAccessMask.render(h.mask()) + " does not grant " + ObAccessMask.render(needed));
        }
        h.touch(clock.getAsLong());
        return SeAccessCheck.allowed();
    }

    /**
     * The gate seam. No {@code "handle"} arg ⇒ allowed() (pure no-op for every
     * non-handle tool) — EXCEPT under {@link #strictHandles}, where a tool listed
     * in {@link #HANDLE_OPS} invoked without a handle is DENIED: those tools have a
     * frozen-handle path precisely to close the name-reuse TOCTOU, so in a hardened
     * posture we refuse the handle-less name-based fallback rather than letting it
     * silently bypass L6.
     */
    public SeAccessCheck checkRequest(SeToken s, IoRequestPacket req) {
        Object hv = req.arguments().get("handle");
        if (hv == null) {
            if (strictHandles && HANDLE_OPS.containsKey(req.toolName())) {
                return SeAccessCheck.deny("L6 handle",
                        "tool '" + req.toolName() + "' is a handle-op and strict-handle posture "
                        + "(-Dmcp.core.hardened=true) requires an explicit 'handle' arg — open one "
                        + "with debug_open_thread first (refusing the name-based TOCTOU fallback).");
            }
            return SeAccessCheck.allowed();
        }
        long id;
        try {
            id = Long.parseLong(hv.toString().trim());
        } catch (NumberFormatException e) {
            return SeAccessCheck.deny("L6 handle", "malformed handle id '" + hv + "'");
        }
        return require(id, s, HANDLE_OPS.getOrDefault(req.toolName(), ObAccessMask.READ.bit()));
    }

    /** Close every handle idle longer than the TTL; returns the count reaped. */
    public synchronized int reapIdle() {
        long now = clock.getAsLong();
        int n = 0;
        for (ObHandle h : new ArrayList<>(handles.values())) {
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
