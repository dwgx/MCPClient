package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Teeth tests for {@link PatchManifest} identity + {@link CompatDatabase}
 * registration invariants.
 */
public final class PatchManifestTest {

    private static PatchManifest.Builder base() {
        return new PatchManifest.Builder()
                .code("MCP-KI0001").name("mipmap zero-fill").version("1.0.0.0").kiRef("KI-1")
                .targetClass("net.minecraft.client.renderer.texture.TextureUtil")
                .platformCondition("LWJGL3").publisher("kernel").builtAt("2026-07-13T00:00:00Z");
    }

    @Test
    public void contentAddressingBindsIdToTransform() {
        PatchManifest a = base().build().withTransform(PatchManifest.sha256Hex("transformA"), null);
        PatchManifest b = base().build().withTransform(PatchManifest.sha256Hex("transformB"), null);
        // Same everything except the transform hash -> different patchId. Identity is
        // bound to behavior: a patch cannot mutate its logic under a fixed id.
        assertNotEquals("different transform must yield a different patchId", a.patchId(), b.patchId());
        assertTrue(a.patchId().startsWith("cp-"));

        // Deterministic: same inputs -> same id.
        PatchManifest a2 = base().build().withTransform(PatchManifest.sha256Hex("transformA"), null);
        assertEquals("content-addressed id must be deterministic", a.patchId(), a2.patchId());
    }

    @Test
    public void unboundManifestHasNoId() {
        PatchManifest m = base().build();
        assertFalse("a manifest is unbound until withTransform", m.isBound());
        assertNull(m.patchId());
        assertNull(m.contentHash());
    }

    @Test
    public void requiredFieldsAreValidated() {
        try {
            new PatchManifest.Builder().name("x").build(); // missing code, kiRef, target, ...
            fail("expected IllegalArgumentException for missing required fields");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void signingUnboundManifestFails() {
        try {
            base().build().withSignature("sig");
            fail("expected IllegalStateException signing an unbound manifest");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void databaseRejectsUnboundPatch() {
        CompatDatabase db = new CompatDatabase();
        PatchManifest unbound = base().build();
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return unbound; }
            @Override public byte[] transform(byte[] original) { return null; }
        };
        try {
            db.register(p);
            fail("expected IllegalArgumentException registering an unbound patch");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void databaseRejectsDuplicatePatchId() {
        CompatDatabase db = new CompatDatabase();
        PatchManifest m = base().build().withTransform(PatchManifest.sha256Hex("t"), null);
        CompatPatch p = new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) { return null; }
        };
        db.register(p);
        try {
            db.register(p); // same patchId
            fail("expected IllegalArgumentException on duplicate patchId");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(1, db.size());
    }

    @Test
    public void unsignedSignerNeverSignsOrTrusts() {
        PatchSigner signer = new UnsignedPatchSigner();
        PatchManifest m = base().build().withTransform(PatchManifest.sha256Hex("t"), null);
        assertFalse("fail-safe signer must trust nothing", signer.verify(m));
        try {
            signer.sign(m, PatchManifest.sha256Hex("t"));
            fail("expected UnsupportedOperationException — no kernel key");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
