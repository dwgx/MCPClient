package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * Pure unit tests for the online authorizedIds filter in {@link CompatEngine#build}.
 * No socket — proves intersection of offline gauntlet with ticket allowlist.
 */
public class CompatEngineOnlineFilterTest {

    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override
        public boolean verify(PatchManifest m) {
            return m != null && m.isBound();
        }

        @Override
        public PatchManifest sign(PatchManifest m, String h) {
            return m.withTransform(h, "sig");
        }
    };

    private static CompatPatch patch(String targetClass, byte[] replacement) {
        PatchManifest m = new PatchManifest.Builder()
                .code("MCP-KI9999").name("test").version("1.0.0.0").kiRef("KI-test")
                .targetClass(targetClass).platformCondition("").publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z").status(PatchManifest.Status.VERIFIED).build()
                .withTransform(
                        PatchManifest.sha256Hex(
                                "t:" + targetClass + ":" + java.util.Arrays.toString(replacement)),
                        "sig");
        return new CompatPatch() {
            @Override
            public PatchManifest manifest() {
                return m;
            }

            @Override
            public byte[] transform(byte[] original) {
                return replacement;
            }
        };
    }

    @Test
    public void nullAuthorizedIdsMeansOfflineOnly() {
        CompatDatabase db = new CompatDatabase();
        CompatPatch p = patch("com.example.Foo", new byte[]{1});
        db.register(p);
        CompatEngine e = CompatEngine.build(db, TRUSTING, null);
        assertTrue(e.armedPatchIds().contains(p.manifest().patchId()));
    }

    @Test
    public void emptyAuthorizedIdsArmsNothing() {
        CompatDatabase db = new CompatDatabase();
        CompatPatch p = patch("com.example.Foo", new byte[]{1});
        db.register(p);
        CompatEngine e = CompatEngine.build(db, TRUSTING, Set.of());
        assertTrue(e.armedPatchIds().isEmpty());
    }

    @Test
    public void onlyListedIdsArm() {
        CompatDatabase db = new CompatDatabase();
        CompatPatch a = patch("com.example.A", new byte[]{1});
        CompatPatch b = patch("com.example.B", new byte[]{2});
        db.register(a);
        db.register(b);
        CompatEngine e = CompatEngine.build(db, TRUSTING, Set.of(a.manifest().patchId()));
        assertEquals(Set.of(a.manifest().patchId()), e.armedPatchIds());
        assertFalse(e.armedPatchIds().contains(b.manifest().patchId()));
    }
}
