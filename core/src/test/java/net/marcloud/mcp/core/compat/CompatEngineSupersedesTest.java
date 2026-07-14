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
 * <p><b>Scope (honest).</b> This is supersedes among patches ALREADY registered in the database.
 * It is deliberately NOT a TUF version-chain / snapshot / rollback-protection scheme (no
 * monotonic-version enforcement, no signed "which version is current" metadata) — that belongs
 * to the future data-delivery channel + KI-10 and would be vacuous with no data channel today.
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

    /** A signed patch: manifest built + signed by the test key, transform returns a marker. */
    private static CompatPatch signedPatch(String code, String kiRef, String target,
                                           String supersedes, String transformSeed) {
        PatchManifest.Builder b = new PatchManifest.Builder()
                .code(code).name(code).version("1.0.0.0").kiRef(kiRef)
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
        // B is v2 targeting the same class, superseding A by patchId.
        CompatPatch b = signedPatch("MCP-B", "KI-9", target, aId, "foo-v2");
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
    public void supersedesAcrossDifferentTargetsStillSkipsByPatchId() {
        // The wiring keys on patchId, so B supersedes A even if A targets a different class.
        // (In practice a superseding patch targets the same class; this proves the id-set logic.)
        CompatPatch a = signedPatch("MCP-A2", "KI-8", "net.minecraft.client.Bar", null, "bar-v1");
        String aId = a.manifest().patchId();
        CompatPatch b = signedPatch("MCP-B2", "KI-8", "net.minecraft.client.Baz", aId, "baz-v2");

        CompatDatabase db = new CompatDatabase();
        db.register(a);
        db.register(b);
        CompatEngine engine = CompatEngine.build(db, verifier(), null);

        assertTrue("B arms", engine.armedPatchIds().contains(b.manifest().patchId()));
        assertFalse("A superseded", engine.armedPatchIds().contains(aId));
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
        CompatPatch b = signedPatch("MCP-B3", "KI-5", target, aId, "foo3-v2");

        CompatDatabase db = new CompatDatabase();
        db.register(b); // successor first
        db.register(a); // older second
        CompatEngine engine = CompatEngine.build(db, verifier(), null);

        assertTrue("B arms regardless of registration order", engine.armedPatchIds().contains(b.manifest().patchId()));
        assertFalse("A superseded regardless of registration order", engine.armedPatchIds().contains(aId));
    }
}
