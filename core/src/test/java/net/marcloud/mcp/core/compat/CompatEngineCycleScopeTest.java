package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

import org.junit.Test;

/**
 * S3 F5 teeth: a supersedes CYCLE must be scoped to ITS OWN chain — it must NOT globally poison
 * every superseding patch in the engine.
 *
 * <p>The bug: {@code CompatEngine.build} computed a single global {@code cycle != null} flag from
 * {@link PatchChain#findCycle}. When set it (a) emptied {@code supersededIds} and (b) skipped
 * EVERY patch carrying a {@code supersedes} value — so one injected 2-cycle on one target disabled
 * ALL superseding patches engine-wide. The fix computes the exact SET of cycle members
 * ({@link PatchChain#findCycleMembers}) and only refuses/skips patches in that set; patches on
 * other independent chains still arm and still supersede.
 *
 * <p>The load-bearing test {@link #cycleOnXDoesNotDisarmSupersedingChainOnY} builds a cycle A&lt;-&gt;B
 * on target X PLUS a valid signed superseding chain on target Y (Y1 v2 supersedes Y0 v1). Pre-fix,
 * the X-cycle poisons everything: the tip Y1 is skipped (has a supersedes) and Y0 arms instead
 * (supersededIds emptied). Post-fix, Y1 (the tip) arms and Y0 is superseded, while X is refused.
 * So the test FAILS on the current global-poison code and passes after scoping.
 *
 * <p>Both patches are signed by a throwaway kernel-stand-in key the verifier trusts, so arming
 * still goes through the real signature gate (the cycle handling never bypasses it).
 */
public final class CompatEngineCycleScopeTest {

    private static final KeyPair KP = CompatCrypto.generateEd25519();
    private static final String KEY_ID = "test-kernel-key";

    private static Ed25519PatchSigner signingTool() {
        return new Ed25519PatchSigner(TrustAnchors.empty(), KP.getPrivate(), KEY_ID);
    }

    private static Ed25519PatchSigner verifier() {
        return new Ed25519PatchSigner(TrustAnchors.of(Map.of(KEY_ID, KP.getPublic())));
    }

    /**
     * A signed patch. patchId is content-addressed over target|transformHash|kiRef|publisher and
     * is INDEPENDENT of {@code supersedes} and {@code version}, so two patches sharing those four
     * fields share a patchId regardless of what they supersede — exactly how the cycle below is
     * constructed (rebuild A pointing back at B without changing A's id).
     */
    private static CompatPatch signed(String code, String kiRef, String target,
                                      String supersedes, String seed, String version) {
        PatchManifest.Builder b = new PatchManifest.Builder()
                .code(code).name(code).version(version).kiRef(kiRef)
                .targetClass(target).platformCondition("")
                .publisher("kernel").builtAt("2026-07-15T00:00:00Z")
                .status(PatchManifest.Status.VERIFIED);
        if (supersedes != null) {
            b.supersedes(supersedes);
        }
        PatchManifest m = signingTool().sign(b.build(), PatchManifest.sha256Hex(seed));
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) { return new byte[]{1}; }
        };
    }

    @Test
    public void cycleOnXDoesNotDisarmSupersedingChainOnY() {
        // --- Cycle on target X: A <-> B (each supersedes the other) ---
        String targetX = "net.minecraft.client.Xclass";
        // A0 (no supersedes) only to learn A's patchId (id is supersedes-independent).
        CompatPatch a0 = signed("MCP-A", "KI-1", targetX, null, "x-a", "1.0.0.0");
        String aId = a0.manifest().patchId();
        // B supersedes A.
        CompatPatch b = signed("MCP-B", "KI-1", targetX, aId, "x-b", "2.0.0.0");
        String bId = b.manifest().patchId();
        // A' has the SAME id as A0 (same target/seed/kiRef/publisher) but now supersedes B ->
        // completing the cycle A <-> B. Register A' (not A0) + B.
        CompatPatch aPrime = signed("MCP-A", "KI-1", targetX, bId, "x-a", "1.0.0.0");
        assertTrue("A' keeps A's content-addressed id", aId.equals(aPrime.manifest().patchId()));

        // --- Independent valid superseding chain on target Y: Y1 v2 supersedes Y0 v1 ---
        String targetY = "net.minecraft.client.Yclass";
        CompatPatch y0 = signed("MCP-Y0", "KI-2", targetY, null, "y-0", "1.0.0.0");
        String y0Id = y0.manifest().patchId();
        CompatPatch y1 = signed("MCP-Y1", "KI-2", targetY, y0Id, "y-1", "1.0.0.1");
        String y1Id = y1.manifest().patchId();

        CompatDatabase db = new CompatDatabase();
        db.register(aPrime);
        db.register(b);
        db.register(y0);
        db.register(y1);

        CompatEngine engine = CompatEngine.build(db, verifier(), null);

        // TEETH: the Y chain must arm its TIP despite the unrelated X cycle. Pre-fix, the global
        // poison skipped Y1 (it carries a supersedes) and armed Y0 instead — both assertions fail.
        assertTrue("Y tip (Y1) must ARM — an unrelated cycle on X must not disarm it",
                engine.armedPatchIds().contains(y1Id));
        assertFalse("Y0 must be superseded by Y1 (not armed)",
                engine.armedPatchIds().contains(y0Id));
        assertTrue("Y's target class is armed (via the tip Y1)",
                engine.armedInternalNames().contains("net/minecraft/client/Yclass"));

        // X (the cycle) is refused entirely: no member arms, its target class is not armed.
        assertFalse("cycle member A must not arm", engine.armedPatchIds().contains(aId));
        assertFalse("cycle member B must not arm", engine.armedPatchIds().contains(bId));
        assertFalse("X's target class must not be armed (whole cycle refused)",
                engine.armedInternalNames().contains("net/minecraft/client/Xclass"));
    }

    @Test
    public void cycleMembersSetIsExactlyTheLoopingPatches() {
        // A focused check on the scoping primitive: findCycleMembers returns ONLY the looping
        // patchIds, leaving an independent chain's ids out. Fails if the whole graph is flagged.
        String targetX = "net.minecraft.client.Xclass";
        CompatPatch a0 = signed("MCP-A", "KI-1", targetX, null, "x-a", "1.0.0.0");
        String aId = a0.manifest().patchId();
        CompatPatch b = signed("MCP-B", "KI-1", targetX, aId, "x-b", "2.0.0.0");
        String bId = b.manifest().patchId();
        CompatPatch aPrime = signed("MCP-A", "KI-1", targetX, bId, "x-a", "1.0.0.0");

        String targetY = "net.minecraft.client.Yclass";
        CompatPatch y0 = signed("MCP-Y0", "KI-2", targetY, null, "y-0", "1.0.0.0");
        String y0Id = y0.manifest().patchId();
        CompatPatch y1 = signed("MCP-Y1", "KI-2", targetY, y0Id, "y-1", "1.0.0.1");
        String y1Id = y1.manifest().patchId();

        java.util.Set<String> members = PatchChain.findCycleMembers(
                java.util.List.of(aPrime, b, y0, y1));

        assertTrue("A is a cycle member", members.contains(aId));
        assertTrue("B is a cycle member", members.contains(bId));
        assertFalse("Y0 (acyclic chain) is NOT a cycle member", members.contains(y0Id));
        assertFalse("Y1 (acyclic chain) is NOT a cycle member", members.contains(y1Id));
    }
}
