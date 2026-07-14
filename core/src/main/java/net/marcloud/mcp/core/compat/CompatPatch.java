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

    /**
     * A fixed, self-contained "canary" classfile that this patch's {@link #transform}
     * produces a DETERMINISTIC, NON-NULL output for — the anchor for TUF L0 content
     * binding. Same transform logic + same canary input ⇒ same transformed bytes ⇒
     * same {@code contentHash}, so the signature binds the patch's actual BEHAVIOR
     * (its transform's effect on a known input), not an author-supplied label
     * (closes KI-10 for the in-code registration model).
     *
     * <p>The canary must be a minimal synthetic class shaped like the patch's target
     * enough that {@code transform(canary)} exercises the real rewrite (e.g. a class
     * with the same method name+descriptor + the instruction the patch matches) and
     * returns changed bytes — NOT null. It must be produced deterministically (e.g. a
     * fixed ASM emission), so the hash is stable across builds and machines. It is
     * self-contained: no dependency on the vanilla client jar being present at
     * runtime, so the engine can recompute the content hash at premain.
     *
     * <p><b>Honest boundary:</b> the canary covers the transform's behavior ON THAT
     * INPUT, not its entire behavior on every possible class. It is a behavior
     * FINGERPRINT, not a total spec — strong enough to detect a swapped/altered
     * transform, not a proof of full equivalence.
     *
     * <p>Default: {@code null} — "no canary". Such a patch cannot participate in L0
     * content binding; {@link ContentHash} treats a null canary as "unbound behavior"
     * and the engine can require a canary for arming under a strict posture. The
     * harmless {@code IdentityProbePatch} and any legacy patch keep compiling.
     *
     * @return deterministic canary classfile bytes, or {@code null} if this patch
     *         provides no behavior anchor
     */
    default byte[] canaryClassBytes() {
        return null;
    }

    /**
     * The EXPECTED L0 behavior hash — the value {@link ContentHash#forPatch(CompatPatch)}
     * should produce for this patch in this build. It is a compile-time constant the
     * patch author pins into the source (generated once by running {@code ContentHash}
     * over the patch), and the engine compares the runtime-recomputed behavior hash
     * against it at arm time.
     *
     * <p><b>Why a pinned constant, not the signed contentHash (design decision — the
     * "L0 form" fork):</b> the behavior hash is {@code sha256(transform(canary))}, which
     * depends on the exact bytes ASM emits and therefore can drift across ASM/compiler
     * versions. The Ed25519 signature must cover a STABLE value, so it keeps covering the
     * manifest label (unchanged). L0 is a SECOND, INDEPENDENT gate: the signature proves
     * provenance (a trusted key blessed this patch), and this canary check proves the
     * transform has not been swapped/mutated since the author pinned its fingerprint. A
     * mismatch (e.g. someone altered {@code transform} but kept the signed manifest) fails
     * arming even with a valid signature.
     *
     * <p>Default {@code null} = no pinned hash: such a patch is exempt from the L0 gate
     * (signature-only, legacy behavior), so anchor-less patches keep working.
     *
     * @return the pinned expected behavior hash, or {@code null} if this patch has none
     */
    default String expectedCanaryHash() {
        return null;
    }
}
