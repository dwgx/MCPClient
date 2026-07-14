package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Teeth tests for the compat patch engine. Each asserts a load-bearing safety
 * property and FAILS on the wrong/absent behavior:
 * <ul>
 *   <li>the fail-safe {@link UnsignedPatchSigner} arms NOTHING (un-authenticated
 *       patches never auto-apply);</li>
 *   <li>a trusted signer arms a patch and dispatches its transform ONLY for the
 *       target class;</li>
 *   <li>a patch targeting a protected Core class is rejected regardless of
 *       signature;</li>
 *   <li>a throwing patch preserves the original bytes (no class corruption);</li>
 *   <li>content-addressing binds identity to the transform hash;</li>
 *   <li>the database rejects unbound and duplicate patches.</li>
 * </ul>
 */
public final class CompatEngineTest {

    /** A signer that trusts every bound manifest — stand-in for the real HMAC signer. */
    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override public boolean verify(PatchManifest m) { return m != null && m.isBound(); }
        @Override public PatchManifest sign(PatchManifest m, String h) { return m.withTransform(h, "sig"); }
    };

    private static PatchManifest manifest(String targetClass, PatchManifest.Status status) {
        return manifest(targetClass, status, "t:" + targetClass);
    }

    private static PatchManifest manifest(String targetClass, PatchManifest.Status status, String seed) {
        return new PatchManifest.Builder()
                .code("MCP-KI9999").name("test").version("1.0.0.0").kiRef("KI-test")
                .targetClass(targetClass).platformCondition("").publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z").status(status).build()
                .withTransform(PatchManifest.sha256Hex(seed), null);
    }

    /**
     * A patch whose transform replaces the byte array (so "changed" is observable).
     * The transform hash is derived from the replacement bytes, so two distinct
     * patches on the same target get distinct content-addressed ids (real patches
     * with different logic differ in transform hash).
     */
    private static CompatPatch patch(String targetClass, PatchManifest.Status status,
                                     byte[] replacement) {
        PatchManifest m = manifest(targetClass, status,
                "t:" + targetClass + ":" + java.util.Arrays.toString(replacement) + ":" + status);
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) { return replacement; }
        };
    }

    @Test
    public void unsignedSignerArmsNothing() {
        CompatDatabase db = new CompatDatabase();
        db.register(patch("net.minecraft.client.Foo", PatchManifest.Status.VERIFIED, new byte[]{9}));
        CompatEngine engine = CompatEngine.build(db, new UnsignedPatchSigner());
        // The patch is VERIFIED + applicable + in-code registered, so ONLY the signature
        // gate can stop it. In-code registration confers NO trust: with no kernel key,
        // nothing is trusted -> nothing armed. This is the one arming rule (no bypass).
        assertTrue("fail-safe signer must arm zero patches", engine.armedInternalNames().isEmpty());
        assertNull("unarmed target must not transform",
                engine.apply("net/minecraft/client/Foo", new byte[]{1}));
    }

    @Test
    public void trustedPatchIsArmedAndDispatchedOnlyForTarget() {
        CompatDatabase db = new CompatDatabase();
        byte[] patched = {7, 7, 7};
        db.register(patch("net.minecraft.client.Foo", PatchManifest.Status.VERIFIED, patched));
        CompatEngine engine = CompatEngine.build(db, TRUSTING);

        assertTrue("target must be armed under a trusting signer",
                engine.armedInternalNames().contains("net/minecraft/client/Foo"));
        // Dispatched for the target: returns the patched bytes.
        assertArrayEquals(patched, engine.apply("net/minecraft/client/Foo", new byte[]{1}));
        // NOT dispatched for any other class: null (no change).
        assertNull("non-target class must be untouched",
                engine.apply("net/minecraft/client/Bar", new byte[]{1}));
    }

    @Test
    public void protectedClassTargetIsRejectedEvenWhenTrusted() {
        CompatDatabase db = new CompatDatabase();
        // se.* is whole-package protected (SeProtectedObjects). A compat patch must
        // never be able to rewrite the guard itself.
        db.register(patch("net.marcloud.mcp.core.se.Ring", PatchManifest.Status.VERIFIED, new byte[]{9}));
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        assertTrue("a patch targeting a protected Core class must not arm",
                engine.armedInternalNames().isEmpty());
    }

    @Test
    public void throwingPatchPreservesOriginalBytes() {
        CompatDatabase db = new CompatDatabase();
        PatchManifest m = manifest("net.minecraft.client.Foo", PatchManifest.Status.VERIFIED);
        db.register(new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) {
                throw new RuntimeException("buggy patch");
            }
        });
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        // A throwing patch must be caught and treated as "no change" — never corrupt
        // the class or propagate out of the transformer.
        assertNull("a throwing patch must yield no change",
                engine.apply("net/minecraft/client/Foo", new byte[]{1, 2, 3}));
    }

    @Test
    public void nonVerifiedStatusIsNotArmed() {
        CompatDatabase db = new CompatDatabase();
        db.register(patch("net.minecraft.client.Foo", PatchManifest.Status.DISABLED, new byte[]{9}));
        db.register(patch("net.minecraft.client.Bar", PatchManifest.Status.SUPERSEDED, new byte[]{9}));
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        assertTrue("DISABLED/SUPERSEDED patches must not arm", engine.armedInternalNames().isEmpty());
    }

    @Test
    public void slashFormTargetCannotBypassProtectedGuard() {
        // The guard (SeProtectedObjects) matches dotted names; the transformer keys on
        // JVM internal (slash) names. A patch naming its target in slash form must NOT
        // slip past the protected-class check and then match at dispatch. This fails
        // on pre-fix code (which gated on the raw, un-canonicalized target).
        CompatDatabase db = new CompatDatabase();
        db.register(patch("net/marcloud/mcp/core/se/Ring", PatchManifest.Status.VERIFIED, new byte[]{9}));
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        assertTrue("a slash-form protected target must not arm", engine.armedInternalNames().isEmpty());
        assertNull("slash-form protected target must not dispatch",
                engine.apply("net/marcloud/mcp/core/se/Ring", new byte[]{1}));
    }

    @Test
    public void armedIsPerPatchNotPerTarget() {
        // Two patches on the SAME target: one VERIFIED (armed), one DISABLED (skipped).
        // The skipped patch must NOT be reported as armed just because it shares a
        // target with the armed one.
        CompatDatabase db = new CompatDatabase();
        CompatPatch verified = patch("net.minecraft.client.Foo", PatchManifest.Status.VERIFIED, new byte[]{7});
        CompatPatch disabled = patch("net.minecraft.client.Foo", PatchManifest.Status.DISABLED, new byte[]{8});
        db.register(verified);
        db.register(disabled);
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        assertTrue("verified patch is armed",
                engine.armedPatchIds().contains(verified.manifest().patchId()));
        assertFalse("disabled patch sharing the target must NOT be armed",
                engine.armedPatchIds().contains(disabled.manifest().patchId()));
        assertEquals(1, engine.armedPatchIds().size());
    }

    @Test
    public void multiplePatchesOnSameTargetChain() {
        // Two armed patches on one target: the second sees the first's output, and the
        // final bytes are the last transform's result (chaining, not overwrite-from-original).
        CompatDatabase db = new CompatDatabase();
        db.register(patch("net.minecraft.client.Foo", PatchManifest.Status.VERIFIED, new byte[]{1}));
        // Second patch appends a marker to whatever it receives, proving it saw the chain.
        PatchManifest m2 = manifest("net.minecraft.client.Foo", PatchManifest.Status.VERIFIED)
                .withTransform(PatchManifest.sha256Hex("second"), null);
        db.register(new CompatPatch() {
            @Override public PatchManifest manifest() { return m2; }
            @Override public byte[] transform(byte[] original) {
                byte[] out = new byte[original.length + 1];
                System.arraycopy(original, 0, out, 0, original.length);
                out[original.length] = 2;
                return out;
            }
        });
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        // patch1 -> {1}; patch2 appends 2 -> {1, 2}.
        assertArrayEquals(new byte[]{1, 2}, engine.apply("net/minecraft/client/Foo", new byte[]{0, 0, 0}));
    }

    @Test
    public void identityProbePatchExercisesPipelineEndToEnd() {
        // The shipped IdentityProbePatch runs the whole collect->verify->filter->dispatch
        // path against a trusting signer and performs a genuine no-op (returns null).
        net.marcloud.mcp.core.compat.patches.IdentityProbePatch probe =
                new net.marcloud.mcp.core.compat.patches.IdentityProbePatch();
        CompatDatabase db = new CompatDatabase();
        db.register(probe);
        CompatEngine engine = CompatEngine.build(db, TRUSTING);
        String internal = net.marcloud.mcp.core.compat.patches.IdentityProbePatch.TARGET.replace('.', '/');
        assertTrue("probe arms under a trusting signer", engine.armedInternalNames().contains(internal));
        assertNull("identity probe performs no transform", engine.apply(internal, new byte[]{1, 2, 3}));
    }

    @Test
    public void f2_installFromRejectsOnlineAuthorizedIds() {
        // Red-team F2: installFrom(...) with a non-null online set would register the
        // transformer with NO lease (BLUE-1/F3 regression). It must refuse, forcing
        // callers onto the explicit build -> setLease -> install path.
        CompatDatabase db = new CompatDatabase();
        try {
            CompatEngine.installFrom(null, db, TRUSTING, java.util.Set.of("cp-anything"));
            fail("installFrom must reject a non-null authorizedIds (F2)");
        } catch (IllegalArgumentException expected) {
            // correct: online path must attach a lease before installing
        }
    }
}
