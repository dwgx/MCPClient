package net.marcloud.mcp.core.compat.patches;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * A harmless identity patch used purely to exercise the {@link
 * net.marcloud.mcp.core.compat.CompatEngine} end-to-end: it targets a synthetic,
 * non-existent class name and returns the input bytes unchanged. It transforms no
 * real game class.
 *
 * <p>This is <b>not</b> a real bug fix. The concrete KI-1 (mipmap) / KI-4
 * (LocalServerChannel) patches — which carry actual bytecode transforms — land
 * later as their own classes here, each with a confirmed KI + evidence + kernel
 * signature. This class exists so the engine's collect → verify → filter → dispatch
 * pipeline can be tested without a real transform and without touching a vanilla
 * class.
 */
public final class IdentityProbePatch implements CompatPatch {

    /** A deliberately synthetic target that no real class uses. */
    public static final String TARGET = "net.marcloud.mcp.core.compat.__probe.Sentinel";

    private final PatchManifest manifest;

    public IdentityProbePatch() {
        // Bind to a fixed transform hash so the manifest has a stable content id.
        // Unsigned by construction: the shipped UnsignedPatchSigner will refuse to
        // trust it, so this probe is never actually armed in production.
        this.manifest = new PatchManifest.Builder()
                .code("MCP-PROBE0000")
                .name("Identity probe (engine self-test)")
                .version("1.0.0.0")
                .kiRef("none")
                .targetClass(TARGET)
                .platformCondition("test-only")
                .publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z")
                .evidence("engine end-to-end self-test; performs no transform")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex("identity-no-op"), null);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    @Override
    public byte[] transform(byte[] originalClassfileBytes) {
        // Identity: signal "no change".
        return null;
    }
}
