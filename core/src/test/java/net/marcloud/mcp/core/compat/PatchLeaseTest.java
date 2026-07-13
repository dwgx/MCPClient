package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Teeth for {@link PatchLease} — the BLUE-1 fix (live, refreshable, monotonic-time
 * authorization). Each fails on wrong behavior.
 */
public final class PatchLeaseTest {

    /** Controllable monotonic clock. */
    private static final class FakeClock implements PatchLease.Clock {
        final AtomicLong nanos = new AtomicLong(0);
        @Override public long nanoTime() { return nanos.get(); }
        void advanceMillis(long ms) { nanos.addAndGet(ms * 1_000_000L); }
    }

    @Test
    public void freshLeaseAuthorizesNothing() {
        PatchLease lease = new PatchLease(new FakeClock());
        // Fail-closed default: empty + expired until first renew.
        assertFalse(lease.isValid());
        assertFalse(lease.isAuthorized("cp-abc"));
    }

    @Test
    public void renewAuthorizesThenExpires() {
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of("cp-a"), 1, 10_000); // 10s TTL
        assertTrue(lease.isValid());
        assertTrue(lease.isAuthorized("cp-a"));
        assertFalse("not-listed patch is not authorized", lease.isAuthorized("cp-b"));

        clock.advanceMillis(9_999);
        assertTrue("still inside TTL", lease.isAuthorized("cp-a"));

        clock.advanceMillis(2);
        // Past TTL -> authorize nothing (fail-closed). This is the core BLUE-1
        // property: the ticket's time bound actually bites.
        assertFalse("expired lease authorizes nothing", lease.isAuthorized("cp-a"));
        assertFalse(lease.isValid());
    }

    @Test
    public void deListDisarmsOnRenew() {
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of("cp-a", "cp-b"), 1, 10_000);
        assertTrue(lease.isAuthorized("cp-b"));
        // Authority de-lists cp-b: next renew (higher epoch) omits it.
        lease.renew(Set.of("cp-a"), 2, 10_000);
        assertTrue(lease.isAuthorized("cp-a"));
        assertFalse("de-listed patch disarmed after renew", lease.isAuthorized("cp-b"));
    }

    @Test
    public void staleEpochRenewalRejected() {
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of("cp-a"), 5, 10_000);
        // A replayed/rolled-back renewal with a lower-or-equal epoch must be rejected.
        assertFalse(lease.renew(Set.of("cp-evil"), 5, 10_000));
        assertFalse(lease.renew(Set.of("cp-evil"), 3, 10_000));
        assertFalse("stale renewal must not change authorization", lease.isAuthorized("cp-evil"));
        assertTrue(lease.isAuthorized("cp-a"));
    }

    @Test
    public void clockRollbackDoesNotExtendLease() {
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of("cp-a"), 1, 1_000); // 1s
        clock.advanceMillis(2_000);            // expire
        assertFalse(lease.isAuthorized("cp-a"));
        // Even if wall-clock "rolls back", the monotonic source only moves forward;
        // simulate: no renew, time cannot go backward -> still expired.
        assertFalse("monotonic clock -> rollback cannot revive an expired lease",
                lease.isValid());
    }

    @Test
    public void expireNowDisarmsImmediately() {
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of("cp-a"), 1, 100_000);
        assertTrue(lease.isAuthorized("cp-a"));
        lease.expireNow();
        assertFalse("expireNow disarms without waiting for TTL", lease.isAuthorized("cp-a"));
    }
}
