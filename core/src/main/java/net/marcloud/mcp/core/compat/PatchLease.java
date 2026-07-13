package net.marcloud.mcp.core.compat;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A LIVE, refreshable authorization lease over the set of patchIds the remote
 * authority currently blesses — the fix for the "short-TTL ticket is decorative"
 * defect (crypto-s-redteam BLUE-1). Before this, authorization was a frozen
 * premain snapshot: a ~120s ticket armed a patch for the whole JVM lifetime and
 * de-list never reached a running client.
 *
 * <p>Now {@link CompatEngine#apply} consults a lease at the moment of use:
 * <ul>
 *   <li>a patchId not in the current lease set is NOT applied (de-list disarms it);</li>
 *   <li>an expired lease authorizes NOTHING until refreshed (fail-closed);</li>
 *   <li>a heartbeat re-fetches the authorized set + pushes a new expiry via
 *       {@link #renew}; the monotonic {@code epoch} rejects stale/rolled-back
 *       renewals.</li>
 * </ul>
 *
 * <p><b>Time source is monotonic</b> ({@link System#nanoTime}), not wall-clock, so
 * a compromised client clock cannot extend a lease (BLUE-1 companion finding:
 * clock rollback must not defeat expiry).
 *
 * <p><b>Fail-closed default:</b> a freshly constructed lease is EMPTY and EXPIRED —
 * it authorizes nothing until the first {@link #renew}. When compat runs
 * offline-only (no authority), the engine simply does not attach a lease and keeps
 * its static snapshot behavior; a lease is attached ONLY when online authorization
 * is in force, and then it is authoritative.
 */
public final class PatchLease {

    /** Immutable snapshot of the current authorization, swapped atomically on renew. */
    private record State(Set<String> authorized, long epoch, long expiresAtNanos) {}

    private final AtomicReference<State> state =
            new AtomicReference<>(new State(Set.of(), Long.MIN_VALUE, Long.MIN_VALUE));

    /** Monotonic clock; overridable in tests. Package-visible seam. */
    interface Clock {
        long nanoTime();
    }

    private final Clock clock;

    public PatchLease() {
        this(System::nanoTime);
    }

    PatchLease(Clock clock) {
        this.clock = clock;
    }

    /**
     * Install a fresh authorization: the set the authority blesses now, a monotonic
     * {@code epoch} (must strictly increase — a renewal with epoch &lt;= the current
     * one is rejected as stale/rollback), and a lease TTL in milliseconds from now.
     * Returns true if applied, false if rejected as stale.
     */
    public boolean renew(Set<String> authorizedIds, long epoch, long ttlMillis) {
        Set<String> frozen = authorizedIds == null ? Set.of() : Set.copyOf(authorizedIds);
        long expires = clock.nanoTime() + Math.max(0L, ttlMillis) * 1_000_000L;
        while (true) {
            State cur = state.get();
            if (epoch <= cur.epoch) {
                // Stale or replayed renewal — reject (monotonic epoch guard).
                return false;
            }
            if (state.compareAndSet(cur, new State(frozen, epoch, expires))) {
                return true;
            }
        }
    }

    /** Immediately expire the lease so nothing is authorized until the next renew. */
    public void expireNow() {
        while (true) {
            State cur = state.get();
            State next = new State(cur.authorized, cur.epoch, Long.MIN_VALUE);
            if (state.compareAndSet(cur, next)) {
                return;
            }
        }
    }

    /** True if {@code patchId} is authorized right now (in-set AND lease not expired). */
    public boolean isAuthorized(String patchId) {
        State s = state.get();
        if (clock.nanoTime() >= s.expiresAtNanos) {
            return false; // expired -> authorize nothing (fail-closed)
        }
        return s.authorized.contains(patchId);
    }

    /** True if the lease is currently valid (not expired). */
    public boolean isValid() {
        return clock.nanoTime() < state.get().expiresAtNanos;
    }

    /** Current monotonic epoch (last accepted renewal). */
    public long epoch() {
        return state.get().epoch;
    }
}
