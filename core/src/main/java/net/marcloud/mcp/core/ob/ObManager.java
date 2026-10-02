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
    static final Map<String, Integer> HANDLE_OPS = Map.ofEntries(
            Map.entry("debug_read_local",    ObAccessMask.READ.bit()),
            Map.entry("debug_write_local",   ObAccessMask.WRITE.bit()),
            Map.entry("debug_force_return",  ObAccessMask.WRITE.bit()),
            Map.entry("debug_suspend_thread", ObAccessMask.EXECUTE.bit()),
            Map.entry("debug_pop_frame",     ObAccessMask.EXECUTE.bit()),
            Map.entry("debug_single_step",   ObAccessMask.EXECUTE.bit()));

    /**
     * The tools whose separate operations were folded into one name by ADR-0004, so a call names
     * the concrete operation in an {@code action} argument. This is the ONLY condition under which
     * {@code action} means "which handle-op is this" -- see {@link #checkRequest} for why asking
     * "was an argument called action passed" instead denied two unrelated actuation tools on every
     * call.
     *
     * <p>Mirrors {@code DebugTools.FOLDED_TOOL_NAMES} rather than importing it: {@code ob} must
     * not depend on {@code kd}. A test keeps the two lists equal, because a rule that names half
     * of a pair drifts silently and then denies the wrong half.
     */
    static final java.util.Set<String> FOLDED_DEBUG_TOOLS =
            java.util.Set.of("debug_manage", "debug_handle");

    /**
     * The two operations that CREATE and DESTROY handles, and therefore cannot require one.
     *
     * <p>{@link #HANDLE_OPS} is the table of operations that consume an existing handle. The handle
     * LIFECYCLE is not in it and cannot be: {@code debug_open_thread} is what mints the handle every
     * other entry needs. Without this exemption the rule "an unrecognised action is refused" refuses
     * those two as well, and the gate becomes unpassable -- no handle can ever be minted through the
     * tool surface, so the whole L6 frozen-target protection is inert in the only configuration that
     * enables it, and under strict handles every debugger op is unusable.
     *
     * <p>The refusal message made this visible out loud: it told the caller to "open one with
     * debug_handle action=debug_open_thread first" -- naming, as the remedy, the exact call being
     * refused.
     *
     * <p>Mirrors {@code DebugTools.HANDLE_ACTIONS} rather than importing it, for the same reason as
     * {@link #FOLDED_DEBUG_TOOLS}: {@code ob} must not depend on {@code kd}. The drift guard is in
     * ANonDebuggerActionIsNotAHandleOpTest.
     */
    static final java.util.Set<String> HANDLE_LIFECYCLE_ACTIONS =
            java.util.Set.of("debug_open_thread", "debug_close_handle");

    /** Whether this operation consumes an existing handle, as opposed to creating one. */
    private static boolean needsHandle(String op) {
        return HANDLE_OPS.containsKey(op);
    }

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
        // Which OPERATION this is. ADR-0004 folded eleven debug tools into two manifest
        // entries, so toolName() is now "debug_manage" or "debug_handle" and the name that
        // actually selects the access mask lives in the "action" argument. Reading toolName()
        // alone SILENTLY DISABLED this check: the folded names are absent from HANDLE_OPS, so
        // strictHandles stopped denying handle-less calls, and a handle-bearing call fell
        // through to the READ default -- which would have let a READ-only handle suspend a
        // thread. L6DebugGateThroughRegistryTest caught it; nothing else would have.
        //
        // The concrete operation wins whenever the caller named one, and the tool name remains
        // the fallback for every unfolded caller. An action the table does not know is NOT
        // downgraded to READ: it is denied, because an unrecognised operation reaching a
        // debugger must never be the weak case.
        //
        // `folded` means "this tool's operations were folded into one name", and the ONLY
        // tools in that state are the two below. It used to mean "the caller passed an argument
        // called action", which is a different question with the same word: do_use_entity and
        // do_entity_action both take an `action`, so every call to them landed here with
        // folded=true and an op the handle table has never heard of, and was refused with
        // "action 'ATTACK' on tool 'do_use_entity' is not a recognised handle-op" -- two shipped
        // R1 actuation tools, unusable on every call, naming a concept the caller never used.
        // The gate is fail-closed and that is why it shipped: it denied correctly, just far too
        // much. The rule has to ask who the tool is, not what the caller typed.
        //
        // The names are mirrored from DebugTools.FOLDED_TOOL_NAMES rather than imported:
        // ob must not depend on kd, and ANameListMatchesTheFoldedDebugTools keeps the two in
        // step, because a rule that names half of a pair drifts silently.
        boolean folded = FOLDED_DEBUG_TOOLS.contains(req.toolName());
        String op = req.toolName();
        Object action = folded ? req.arguments().get("action") : null;
        if (action != null) {
            op = String.valueOf(action);
        }
        Object hv = req.arguments().get("handle");
        // A LIFECYCLE action is not a handle-op that failed the table; it is not a handle-op at
        // all. `debug_open_thread` is how a handle comes into existence, so routing it through a
        // table of handle-consuming operations makes the gate unpassable: nothing can ever be
        // minted, and the refusal message names this very call as the remedy.
        if (HANDLE_LIFECYCLE_ACTIONS.contains(op)) {
            return hv == null
                    ? SeAccessCheck.allowed()
                    : SeAccessCheck.deny("L6 handle",
                            "action '" + op + "' creates or destroys the handle itself and takes "
                                    + "no 'handle' argument, but one was supplied (id " + hv + ")");
        }
        Object hv2 = hv;
        if (hv2 == null) {
            // An action the table does not know is refused outright, handle or no handle. Only
            // an action that IS a known handle-op is allowed to fall through to the name-based
            // path, and then only when the posture is not strict.
            if (folded && !needsHandle(op)) {
                return denyUnknownAction(op, req.toolName(), "no 'handle' was supplied");
            }
            if (strictHandles && needsHandle(op)) {
                return SeAccessCheck.deny("L6 handle",
                        "tool '" + req.toolName() + "' (action '" + op + "') is a handle-op and "
                        + "strict-handle posture (-Dmcp.core.hardened=true) requires an explicit "
                        + "'handle' arg -- open one with debug_handle action=debug_open_thread "
                        + "first (refusing the name-based TOCTOU fallback).");
            }
            return SeAccessCheck.allowed();
        }
        long id;
        try {
            id = Long.parseLong(hv.toString().trim());
        } catch (NumberFormatException e) {
            return SeAccessCheck.deny("L6 handle", "malformed handle id '" + hv + "'");
        }
        if (folded && !needsHandle(op)) {
            return denyUnknownAction(op, req.toolName(), "handle id " + hv + " was supplied");
        }
        return require(id, s, HANDLE_OPS.getOrDefault(op, ObAccessMask.READ.bit()));
    }

    private static SeAccessCheck denyUnknownAction(String op, String tool, String what) {
        return SeAccessCheck.deny("L6 handle",
                "action '" + op + "' on tool '" + tool + "' is not a recognised handle-op ("
                        + what + "); refusing rather than falling back to the least-privileged "
                        + "mask, which would let an unknown debugger operation through on a "
                        + "read-only handle");
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
