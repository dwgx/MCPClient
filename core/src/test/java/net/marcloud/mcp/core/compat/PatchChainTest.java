package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * TUF L1 — chain structure (承前 + 不可回退). A superseding patch must point at a well-formed
 * predecessor and carry a strictly higher version than a PRESENT predecessor; a rollback,
 * cross-target link, malformed pointer, or supersedes cycle is rejected. These fail on a
 * chain-check that is absent or wrong (teeth).
 */
public class PatchChainTest {

    /** A minimal bound patch with a given version + supersedes pointer, for chain tests. */
    private static CompatPatch patch(String code, String target, String version,
                                     String seed, String supersedes) {
        PatchManifest m = new PatchManifest.Builder()
                .code(code).name(code).version(version).kiRef("KI-x")
                .targetClass(target).publisher("kernel").builtAt("t")
                .supersedes(supersedes).status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(seed), null);
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) { return null; }
        };
    }

    @Test
    public void versionComparatorOrdersDottedNumerics() {
        assertTrue(PatchChain.compareVersions("1.0.0.1", "1.0.0.0") > 0);
        assertTrue(PatchChain.compareVersions("2.0", "1.9.9.9") > 0);
        assertTrue(PatchChain.compareVersions("1.2", "1.2.0") == 0);
        assertTrue(PatchChain.compareVersions("1.0.0.0", "1.0.0.1") < 0);
    }

    @Test
    public void genesisPatchHasNoChainToCheck() {
        CompatPatch g = patch("A", "a.B", "1.0.0.0", "s1", null);
        assertNull("no supersedes -> valid genesis",
                PatchChain.rejectionReason(g.manifest(), List.of(g)));
    }

    @Test
    public void higherVersionSupersedingPresentPredecessorIsValid() {
        CompatPatch v1 = patch("A1", "a.B", "1.0.0.0", "s1", null);
        String v1id = v1.manifest().patchId();
        CompatPatch v2 = patch("A2", "a.B", "1.0.0.1", "s2", v1id);
        assertNull("v2 > v1, same target, well-formed prev -> valid link",
                PatchChain.rejectionReason(v2.manifest(), List.of(v1, v2)));
    }

    @Test
    public void rollbackOverPresentPredecessorIsRejected() {
        CompatPatch v2 = patch("A2", "a.B", "2.0.0.0", "s2", null);
        String v2id = v2.manifest().patchId();
        // A "v1" that claims to supersede the PRESENT v2 but has a LOWER version = rollback.
        CompatPatch rollback = patch("A1", "a.B", "1.0.0.0", "s1", v2id);
        String reason = PatchChain.rejectionReason(rollback.manifest(), List.of(v2, rollback));
        assertNotNull("rollback masquerading as update must be rejected", reason);
        assertTrue(reason.contains("rollback") || reason.contains("does not exceed"));
    }

    @Test
    public void crossTargetSupersedeIsRejected() {
        CompatPatch other = patch("A", "a.B", "1.0.0.0", "s1", null);
        String otherId = other.manifest().patchId();
        CompatPatch bad = patch("C", "c.D", "2.0.0.0", "s2", otherId); // different target
        String reason = PatchChain.rejectionReason(bad.manifest(), List.of(other, bad));
        assertNotNull("supersede across target classes is not a valid chain link", reason);
        assertTrue(reason.contains("different target"));
    }

    @Test
    public void malformedPrevPointerIsRejected() {
        CompatPatch bad = patch("C", "c.D", "2.0.0.0", "s2", "not-a-patchid");
        String reason = PatchChain.rejectionReason(bad.manifest(), List.of(bad));
        assertNotNull("supersedes must be a cp-… patchId", reason);
        assertTrue(reason.contains("well-formed"));
    }

    @Test
    public void absentPredecessorAllowsTipOnlyShipping() {
        // v2 supersedes a v1 that is NOT in the database (dropped once superseded).
        CompatPatch v2 = patch("A2", "a.B", "2.0.0.0", "s2", "cp-deadbeef");
        assertNull("absent predecessor -> tip arms, no rollback possible",
                PatchChain.rejectionReason(v2.manifest(), List.of(v2)));
    }

    @Test
    public void supersedesCycleIsDetected() {
        // Build A and B that point at each other (a cycle) — patchIds are content-addressed,
        // so we construct then can't easily self-reference; simulate by two patches whose
        // supersedes each name the other's id. We derive ids first with null supersedes, then
        // the cycle detector works on the supersedes map regardless of id derivation.
        CompatPatch a = patch("A", "a.B", "1.0.0.0", "sa", null);
        CompatPatch b = patch("B", "a.B", "2.0.0.0", "sb", a.manifest().patchId());
        // a now supersedes b -> cycle a->b->a
        CompatPatch a2 = patch("A", "a.B", "1.0.0.0", "sa", b.manifest().patchId());
        String cyc = PatchChain.findCycle(List.of(a2, b));
        assertNotNull("a<->b supersedes loop is a cycle", cyc);
        assertTrue(cyc.contains("cycle"));
    }
}
