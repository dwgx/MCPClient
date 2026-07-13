package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Teeth for the BLUE-1 fix at the engine level: with a live {@link PatchLease}
 * attached, {@link CompatEngine#apply} evaluates authorization at the MOMENT OF USE.
 * A patch that passed the build-time gauntlet is still disarmed if the lease expires
 * or de-lists it. Fails on pre-fix code (which froze authorization at build).
 */
public final class CompatEngineLeaseTest {

    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override public boolean verify(PatchManifest m) { return m != null && m.isBound(); }
        @Override public PatchManifest sign(PatchManifest m, String h) { return m.withTransform(h, "sig"); }
    };

    private static final class FakeClock implements PatchLease.Clock {
        final AtomicLong nanos = new AtomicLong(0);
        @Override public long nanoTime() { return nanos.get(); }
        void advanceMillis(long ms) { nanos.addAndGet(ms * 1_000_000L); }
    }

    private static CompatPatch patch(String targetClass, byte[] replacement) {
        PatchManifest m = new PatchManifest.Builder()
                .code("MCP-KI9999").name("t").version("1.0.0.0").kiRef("KI-test")
                .targetClass(targetClass).platformCondition("").publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z").status(PatchManifest.Status.VERIFIED).build()
                .withTransform(PatchManifest.sha256Hex("t:" + targetClass), "sig");
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) { return replacement; }
        };
    }

    @Test
    public void leaseAuthorizedPatchApplies() {
        CompatDatabase db = new CompatDatabase();
        byte[] patched = {7, 7};
        CompatPatch p = patch("com.example.Foo", patched);
        db.register(p);
        CompatEngine e = CompatEngine.build(db, TRUSTING); // built (offline snapshot armed)
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of(p.manifest().patchId()), 1, 10_000);
        e.setLease(lease);
        // Authorized right now -> applies.
        assertArrayEquals(patched, e.apply("com/example/Foo", new byte[]{0}));
    }

    @Test
    public void leaseExpiryDisarmsAtApplyTime() {
        CompatDatabase db = new CompatDatabase();
        CompatPatch p = patch("com.example.Foo", new byte[]{7});
        db.register(p);
        CompatEngine e = CompatEngine.build(db, TRUSTING);
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of(p.manifest().patchId()), 1, 1_000); // 1s TTL
        e.setLease(lease);
        // Inside TTL -> applies.
        assertArrayEquals(new byte[]{7}, e.apply("com/example/Foo", new byte[]{0}));
        // Lease expires -> the SAME already-built engine now refuses to apply.
        // This is exactly the BLUE-1 property the frozen snapshot did NOT have.
        clock.advanceMillis(1_500);
        assertNull("expired lease disarms the patch at apply time", e.apply("com/example/Foo", new byte[]{0}));
    }

    @Test
    public void deListDisarmsAlreadyBuiltEngine() {
        CompatDatabase db = new CompatDatabase();
        CompatPatch p = patch("com.example.Foo", new byte[]{7});
        db.register(p);
        CompatEngine e = CompatEngine.build(db, TRUSTING);
        FakeClock clock = new FakeClock();
        PatchLease lease = new PatchLease(clock);
        lease.renew(Set.of(p.manifest().patchId()), 1, 100_000);
        e.setLease(lease);
        assertArrayEquals(new byte[]{7}, e.apply("com/example/Foo", new byte[]{0}));
        // Authority de-lists the patch on the next heartbeat (higher epoch, omits it).
        lease.renew(Set.of(), 2, 100_000);
        assertNull("de-listed patch disarmed on running engine", e.apply("com/example/Foo", new byte[]{0}));
    }

    @Test
    public void noLeaseKeepsStaticBehavior() {
        CompatDatabase db = new CompatDatabase();
        byte[] patched = {9};
        CompatPatch p = patch("com.example.Foo", patched);
        db.register(p);
        CompatEngine e = CompatEngine.build(db, TRUSTING);
        // No lease attached -> offline-only static behavior, applies as before.
        assertArrayEquals(patched, e.apply("com/example/Foo", new byte[]{0}));
    }
}
