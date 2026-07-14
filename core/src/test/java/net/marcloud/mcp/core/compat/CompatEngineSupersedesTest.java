package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

import org.junit.Test;

/**
 * Teeth for IN-CODE {@code supersedes} (patches are updatable): when a newer registered patch B
 * declares {@code manifest.supersedes() == A.patchId}, the engine arms B and skips A — the older
 * version is reported superseded, not armed.
 *
 * <p><b>Scope.</b> This is supersedes among patches ALREADY registered in the database, now
 * enforced as a TUF L1 chain ({@link PatchChain}): a superseding patch must target the SAME
 * class and carry a strictly HIGHER version than the predecessor it replaces (承前 + 不可回退).
 * The signed, delivered chain-metadata of the data-delivery endgame (L2 root/targets, L3
 * snapshot/timestamp) sign OVER this in-code chain later.
 *
 * <p>Both A and B are signed by a throwaway kernel-stand-in key the test's verifier trusts, so
 * arming still goes through the real signature gate (supersedes never bypasses it). The
 * load-bearing test {@link #newerSupersedesOlder_onlyBArms} FAILS if supersedes is ignored (then
 * BOTH would arm).
 */
public final class CompatEngineSupersedesTest {

    private static final KeyPair KP = CompatCrypto.generateEd25519();
    private static final String KEY_ID = "test-kernel-key";

    private static Ed25519PatchSigner signingTool() {
        return new Ed25519PatchSigner(TrustAnchors.empty(), KP.getPrivate(), KEY_ID);
    }

    private static Ed25519PatchSigner verifier() {
        return new Ed25519PatchSigner(TrustAnchors.of(Map.of(KEY_ID, KP.getPublic())));
    }

    /** A signed patch at the default v1 (no version bump) — genesis / unrelated patches. */
    private static CompatPatch signedPatch(String code, String kiRef, String target,
                                           String supersedes, String transformSeed) {
        return signedPatch(code, kiRef, target, supersedes, transformSeed, "1.0.0.0");
    }

    /**
     * A signed patch at an explicit version: manifest built + signed by the test key,
     * transform returns a marker. Under TUF L1 a superseding patch must carry a strictly
     * HIGHER version than the (present) predecessor it replaces, so a successor passes its
     * new version here.
     */
    private static CompatPatch signedPatch(String code, String kiRef, String target,
                                           String supersedes, String transformSeed, String version) {
        PatchManifest.Builder b = new PatchManifest.Builder()
                .code(code).name(code).version(version).kiRef(kiRef)
                .targetClass(target).platformCondition("")
                .publisher("kernel").builtAt("2026-07-14T00:00:00Z")
                .status(PatchManifest.Status.VERIFIED);
        if (supersedes != null) {
            b.supersedes(supersedes);
        }
        PatchManifest signed = signingTool().sign(b.build(), PatchManifest.sha256Hex(transformSeed));
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return signed; }
            @Override public byte[] transform(byte[] original) { return new byte[]{1}; }
        };
    }

    @Test
    public void newerSupersedesOlder_onlyBArms() {
        String target = "net.minecraft.client.Foo";
        // A is v1 (no supersedes). Build it first so we can learn its patchId.
        CompatPatch a = signedPatch("MCP-A", "KI-9", target, null, "foo-v1");
        String aId = a.manifest().patchId();
        // B is v2 targeting the same class, superseding A by patchId — with a strictly
        // higher version (TUF L1 monotonicity: a successor must advance the version).
        CompatPatch b = signedPatch("MCP-B", "KI-9", target, aId, "foo-v2", "1.0.0.1");
        String bId = b.manifest().patchId();

        CompatDatabase db = new CompatDatabase();
        db.register(a);
        db.register(b);

        CompatEngine engine = CompatEngine.build(db, verifier(), null);

        assertTrue("newer patch B must arm", engine.armedPatchIds().contains(bId));
        assertFalse("older patch A must be superseded (not armed)", engine.armedPatchIds().contains(aId));
        // The class still dispatches (B is armed for it), but only through B.
        assertTrue(engine.armedInternalNames().contains("net/minecraft/client/Foo"));
    }

    @Test
    public void supersedesAcrossDifferentTargetsIsRejectedByChainRule() {
        // TUF L1 (承前): a chain link connects VERSIONS OF THE SAME TARGET, not unrelated
        // classes. A patch that supersedes a patch for a DIFFERENT target is not a valid
        // chain link — the engine rejects the superseding patch (it does not arm). This
        // tightened the old "supersede-by-patchId across any target" behavior, which was
        // an unanchored skip, not a version chain. Teeth: fails if the same-target rule is
        // dropped (then B would arm across targets again).
        CompatPatch a = signedPatch("MCP-A2", "KI-8", "net.minecraft.client.Bar", null, "bar-v1");
        String aId = a.manifest().patchId();
        CompatPatch b = signedPatch("MCP-B2", "KI-8", "net.minecraft.client.Baz", aId, "baz-v2", "1.0.0.1");

        CompatDatabase db = new CompatDatabase();
        db.register(a);
        db.register(b);
        CompatEngine engine = CompatEngine.build(db, verifier(), null);

        assertFalse("cross-target supersede is not a valid chain link -> B does not arm",
                engine.armedPatchIds().contains(b.manifest().patchId()));
        // A did not supersede anything and B's (invalid) supersede does not count, so A still
        // arms on its own target — it was never actually replaced by a valid successor.
        assertTrue("A arms (it targets Bar and was not validly superseded)",
                engine.armedPatchIds().contains(aId));
    }

    @Test
    public void unrelatedPatchesBothArmWhenNoSupersedes() {
        // Guard against a bug that skips patches even without a supersedes relationship.
        CompatPatch a = signedPatch("MCP-C", "KI-7", "net.minecraft.client.Foo", null, "c-v1");
        CompatPatch b = signedPatch("MCP-D", "KI-6", "net.minecraft.client.Bar", null, "d-v1");
        CompatDatabase db = new CompatDatabase();
        db.register(a);
        db.register(b);
        CompatEngine engine = CompatEngine.build(db, verifier(), null);
        assertTrue(engine.armedPatchIds().contains(a.manifest().patchId()));
        assertTrue(engine.armedPatchIds().contains(b.manifest().patchId()));
    }

    @Test
    public void supersededPatchSkippedEvenWhenRegisteredAfterSuccessor() {
        // Registration order must not matter: B (superseding) registered BEFORE A.
        String target = "net.minecraft.client.Foo";
        CompatPatch a = signedPatch("MCP-A3", "KI-5", target, null, "foo3-v1");
        String aId = a.manifest().patchId();
        CompatPatch b = signedPatch("MCP-B3", "KI-5", target, aId, "foo3-v2", "1.0.0.1");

        CompatDatabase db = new CompatDatabase();
        db.register(b); // successor first
        db.register(a); // older second
        CompatEngine engine = CompatEngine.build(db, verifier(), null);

        assertTrue("B arms regardless of registration order", engine.armedPatchIds().contains(b.manifest().patchId()));
        assertFalse("A superseded regardless of registration order", engine.armedPatchIds().contains(aId));
    }
}
