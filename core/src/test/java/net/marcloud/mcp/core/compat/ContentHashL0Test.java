package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.core.compat.patches.Ki1MipmapZeroFillPatch;
import net.marcloud.mcp.core.compat.patches.Ki4LocalServerChannelPatch;

/**
 * TUF L0 — content binding. The behavior hash {@code sha256(transform(canary))} must match
 * each shipped patch's author-pinned {@code expectedCanaryHash}, and a MUTATED transform
 * must fail the check even though it targets the same class (the teeth: this fails on a
 * swapped transform, proving the gate is load-bearing).
 */
public class ContentHashL0Test {

    @Test
    public void ki4BehaviorHashMatchesPinnedExpected() {
        Ki4LocalServerChannelPatch p = new Ki4LocalServerChannelPatch();
        assertNotNull("KI-4 provides a canary", p.canaryClassBytes());
        assertNotNull("KI-4 recomputes a behavior hash", ContentHash.forPatch(p));
        assertTrue("KI-4 recomputed behavior hash == pinned expected",
                ContentHash.matchesExpected(p));
    }

    @Test
    public void ki1BehaviorHashMatchesPinnedExpected() {
        Ki1MipmapZeroFillPatch p = new Ki1MipmapZeroFillPatch();
        assertNotNull("KI-1 provides a canary", p.canaryClassBytes());
        assertNotNull("KI-1 recomputes a behavior hash", ContentHash.forPatch(p));
        assertTrue("KI-1 recomputed behavior hash == pinned expected",
                ContentHash.matchesExpected(p));
    }

    /**
     * TEETH: a patch whose transform was swapped (does something different on the same
     * canary) produces a DIFFERENT behavior hash, so it fails the pinned-hash check even
     * while claiming the real patch's expected hash. This is exactly the "someone altered
     * the transform but kept the signed manifest" attack L0 exists to stop.
     */
    @Test
    public void mutatedTransformFailsL0() {
        final Ki4LocalServerChannelPatch real = new Ki4LocalServerChannelPatch();
        // A malicious/altered variant: same canary + same claimed expected hash, but its
        // transform is the IDENTITY (returns input unchanged) — a different behavior.
        CompatPatch mutated = new CompatPatch() {
            @Override public PatchManifest manifest() { return real.manifest(); }
            @Override public byte[] canaryClassBytes() { return real.canaryClassBytes(); }
            @Override public String expectedCanaryHash() { return real.expectedCanaryHash(); }
            @Override public byte[] transform(byte[] original) { return original; } // altered!
        };
        // Identity transform on the canary -> forPatch returns null (no behavior change),
        // so it cannot match the pinned hash. Arming would reject it.
        assertNull("identity transform anchors no behavior", ContentHash.forPatch(mutated));
        assertFalse("mutated transform fails the L0 pinned-hash gate",
                ContentHash.matchesExpected(mutated));
    }

    /**
     * TEETH #2: a transform that DOES change the canary but differently than the real patch
     * yields a different hash than the pinned one — also rejected.
     */
    @Test
    public void differentButNonNullTransformFailsL0() {
        final Ki4LocalServerChannelPatch real = new Ki4LocalServerChannelPatch();
        CompatPatch altered = new CompatPatch() {
            @Override public PatchManifest manifest() { return real.manifest(); }
            @Override public byte[] canaryClassBytes() { return real.canaryClassBytes(); }
            @Override public String expectedCanaryHash() { return real.expectedCanaryHash(); }
            @Override public byte[] transform(byte[] original) {
                // Append a byte -> changed, non-null, but NOT what the real transform does.
                byte[] out = new byte[original.length + 1];
                System.arraycopy(original, 0, out, 0, original.length);
                return out;
            }
        };
        String h = ContentHash.forPatch(altered);
        assertNotNull("altered transform does change the canary", h);
        assertFalse("but its hash differs from the pinned expected -> L0 rejects",
                ContentHash.matchesExpected(altered));
    }

    /** A patch with no canary is exempt from L0 (signature-only): forPatch + matchesExpected are false/null. */
    @Test
    public void canarylessPatchIsExemptNotCrashing() {
        CompatPatch noCanary = new CompatPatch() {
            @Override public PatchManifest manifest() {
                return new PatchManifest.Builder().code("X").name("x").kiRef("none")
                        .targetClass("a.B").publisher("kernel").builtAt("t")
                        .status(PatchManifest.Status.VERIFIED).build()
                        .withTransform(PatchManifest.sha256Hex("x"), null);
            }
            @Override public byte[] transform(byte[] original) { return null; }
            // canaryClassBytes / expectedCanaryHash default to null
        };
        assertNull("no canary -> no behavior hash", ContentHash.forPatch(noCanary));
        assertFalse("no pinned hash -> matchesExpected false (caller exempts it)",
                ContentHash.matchesExpected(noCanary));
    }
}
