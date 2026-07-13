package net.marcloud.mcp.core.compat;

/**
 * One startup-time compatibility patch for a single vanilla class — the NT
 * AppCompat "shim" analogue. A patch is a self-contained, signed unit that
 * transforms one target class's bytecode <b>as it is first loaded</b>, before any
 * game code runs. It never edits {@code client/} source and is not a runtime
 * hot-swap ({@code ldr}) or an observation hook ({@code flt}).
 *
 * <p>Lifecycle, per {@code 07-COMPAT-SHIM.md}: {@link CompatEngine} pulls patches
 * from the {@link CompatDatabase}, verifies each via {@link PatchSigner}, filters
 * by {@link #appliesToRuntime()}, and registers a single {@code
 * ClassFileTransformer} that dispatches to {@link #transform(byte[])} by
 * {@link PatchManifest#targetClass()}.
 *
 * <p><b>Contract for {@link #transform}:</b> given the original class bytes, return
 * the patched bytes, or the input unchanged if this patch decides not to act.
 * Returning {@code null} is also allowed and means "no change" (mirrors the JDK
 * {@code ClassFileTransformer} convention). A patch must be deterministic and must
 * never throw for a class it does not intend to modify.
 */
public interface CompatPatch {

    /** This patch's identity + provenance (never null). */
    PatchManifest manifest();

    /**
     * Whether this patch's {@link PatchManifest#platformCondition() platform
     * condition} matches the current runtime (e.g. "only under LWJGL3", "only under
     * Netty >= 4.2"). The engine skips patches that do not apply, so a patch for a
     * bug that only manifests on one platform never touches an unaffected one.
     * Default: always applicable.
     */
    default boolean appliesToRuntime() {
        return true;
    }

    /**
     * Transform the target class's bytes. Called only for the class named by
     * {@link PatchManifest#targetClass()}. Return the patched bytes, or {@code null}
     * / the original array to signal "no change".
     *
     * @param originalClassfileBytes the vanilla class bytes as loaded by the JVM
     * @return patched bytes, or null for no change
     */
    byte[] transform(byte[] originalClassfileBytes);
}
