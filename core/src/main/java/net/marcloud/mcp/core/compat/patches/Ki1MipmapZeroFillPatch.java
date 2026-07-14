package net.marcloud.mcp.core.compat.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * KI-1 — the uninitialized-mipmap speckle bug. Fixes the blue specks on grass, white
 * flecks/ripples on water, and colored seams on distant blocks that appear under LWJGL3 on
 * modern NVIDIA drivers.
 *
 * <p><b>The bug (verbatim vanilla, {@code TextureUtil.allocateTextureImpl}):</b> each mip
 * level is allocated with a NULL pixel pointer —
 * {@code GL11.glTexImage2D(GL_TEXTURE_2D, i, GL_RGBA, w>>i, h>>i, 0, GL_BGRA,
 * GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null)}. LWJGL2 / older drivers implicitly zeroed
 * that storage; modern NVIDIA drivers under LWJGL3 do NOT, so any atlas region never written
 * at level {@code i} samples as garbage. One root cause, three visual surfaces (see
 * known-issues.md KI-1). Live-proven twice on an RTX 5070 Ti (nonzero readback 0/64).
 *
 * <p><b>The fix:</b> after each level is allocated, zero-fill it with transparent-black
 * (BGRA 0) via {@code glTexSubImage2D}, exactly the reverted client fix's row-batch pattern
 * (level 0 is harmlessly overwritten by the real sprite upload afterward). This patch emits
 * that as a single {@code INVOKESTATIC} to {@link MipFill#zero(int, int, int)} inserted
 * immediately after the {@code glTexImage2D} allocation call, inside the per-level loop — far
 * more robust than emitting the whole row-batch loop as raw ASM. {@link MipFill} lives in Core
 * (on the game classpath at load time) and does the actual GL work.
 *
 * <p><b>Injection point + how it locates it.</b> The target method is the static
 * {@code allocateTextureImpl (IIII)V} (params: id, mipLevels, width, height). Inside it the
 * single {@code INVOKESTATIC GL11.glTexImage2D (IIIIIIIILjava/nio/IntBuffer;)V} is the level
 * allocation; the loop induction variable (the mip level) is read from the {@code IINC} that
 * immediately follows that call (the loop increment). The patch inserts, right after the call:
 * <pre>
 *   ILOAD  &lt;levelSlot&gt;   // the loop variable = mip level i
 *   ILOAD  2              // width  param (slot 2, fixed by the (IIII)V descriptor)
 *   ILOAD  3              // height param (slot 3)
 *   INVOKESTATIC net/marcloud/mcp/core/compat/patches/MipFill.zero (III)V
 * </pre>
 * The sequence pushes 3 ints and {@code zero} pops all 3 and returns void, so it is
 * stack-neutral, adds no local, and creates no new branch target — the existing stack-map
 * frames stay valid and {@code ClassWriter(0)} preserves them (no class-loading
 * {@code getCommonSuperClass} pass, like KI-4).
 *
 * <p><b>Fail-safe.</b> If the target method is absent, the {@code glTexImage2D(...IntBuffer)}
 * allocation call is not found, or it is not immediately followed by the loop {@code IINC}
 * (an unrecognized / already-fixed / remapped shape), the transform returns {@code null} ("no
 * change") and never throws — it never emits altered bytes for a class it does not fully
 * recognize.
 *
 * <p><b>Trust / arming.</b> Ships SIGNED like KI-4: its manifest carries a real kernel
 * Ed25519 signature ({@link #KERNEL_SIGNATURE}) over its canonical signing input, verified at
 * premain against the baked-in kernel public key ({@code KernelTrustAnchor}). It arms through
 * the one signature-verify path, not in-code registration; with empty anchors it does not arm.
 *
 * <p><b>KI-10 honesty preserved.</b> The signature binds the manifest LABEL (targetClass,
 * contentHash, keyId, status, kiRef, publisher, version), NOT the executed transform bytes.
 * {@code contentHash} is author-supplied; nothing here recomputes it from the emitted bytes.
 * This class does not close or widen that gap.
 */
public final class Ki1MipmapZeroFillPatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/client/renderer/texture/TextureUtil";
    /** The per-level allocator we splice the zero-fill call into. */
    static final String METHOD_NAME = "allocateTextureImpl";
    static final String METHOD_DESC = "(IIII)V";

    /** The null-pointer level allocation call whose return is our injection anchor. */
    static final String GL_OWNER = "org/lwjgl/opengl/GL11";
    static final String GL_METHOD = "glTexImage2D";
    static final String GL_DESC = "(IIIIIIIILjava/nio/IntBuffer;)V";

    /** The Core helper the injected call targets. */
    static final String HELPER_OWNER = "net/marcloud/mcp/core/compat/patches/MipFill";
    static final String HELPER_METHOD = "zero";
    static final String HELPER_DESC = "(III)V";

    /** width / height are params 3 and 4 of the static (IIII)V method → local slots 2 and 3. */
    static final int WIDTH_SLOT = 2;
    static final int HEIGHT_SLOT = 3;

    /** Kill switch (operator kit): {@code -Dmcp.compat.ki1=false} disables applicability. It
     *  can only make the patch apply LESS — it is NOT a signer bypass. */
    private static final String FLAG = "mcp.compat.ki1";

    /** Stable hash seed of this patch's transform logic (author-supplied content-address). */
    static final String TRANSFORM_SEED = "ki1-mipmap-zerofill-v1";

    /**
     * The kernel's Ed25519 signature over this patch's canonical signing input
     * (targetClass=net.minecraft.client.renderer.texture.TextureUtil,
     * contentHash=sha256({@link #TRANSFORM_SEED}), keyId=mcp-kernel-ed25519-v1, status=VERIFIED,
     * kiRef=KI-1, publisher=kernel, version=1.0.0.0), produced OFFLINE by {@code PatchSignerCli}
     * with the kernel private key (never in the repo/jar). Verifies at premain against the
     * baked-in kernel PUBLIC key ({@code KernelTrustAnchor}). Ed25519 is deterministic, so this
     * string is stable.
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:"
            + "57Z3PjtHe7e4KCunyp32hHhuuRhgsGtDZVFX9erMZgEcEhS9J-VfAOqhIwhK0Hmz92XeoXO4tONUruxdYbMyBQ";

    private final PatchManifest manifest;

    public Ki1MipmapZeroFillPatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-KI0001")
                .name("Uninitialized mipmap levels sample as garbage (blue/white specks)")
                .version("1.0.0.0")
                .kiRef("KI-1")
                .targetClass("net.minecraft.client.renderer.texture.TextureUtil")
                .platformCondition("lwjgl3")
                .publisher("kernel")
                .builtAt("2026-07-14T00:00:00Z")
                .evidence("TextureUtil.allocateTextureImpl allocates each mip level with a NULL "
                        + "pixel pointer (glTexImage2D(..., (IntBuffer) null)). Under LWJGL2 that "
                        + "storage was implicitly zeroed; under LWJGL3 on modern NVIDIA drivers it "
                        + "is NOT, so atlas regions never written at level i sample as garbage — "
                        + "blue specks on grass, white flecks/ripples on water, colored seams on "
                        + "distant blocks (one root cause, three surfaces). Fix: zero-fill each "
                        + "level (transparent-black BGRA 0, row-batched glTexSubImage2D) right "
                        + "after allocation; level 0 is overwritten by the real sprite upload. "
                        + "Live-proven twice on an RTX 5070 Ti.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    /**
     * Applies under LWJGL3 (where the driver no longer implicitly zeroes texture storage),
     * unless disabled by {@code -Dmcp.compat.ki1=false}. LWJGL3 is detected by the presence of
     * {@code org.lwjgl.system.MemoryUtil}, which does not exist in LWJGL2.
     */
    @Override
    public boolean appliesToRuntime() {
        if (!Boolean.parseBoolean(System.getProperty(FLAG, "true"))) {
            return false;
        }
        return isLwjgl3();
    }

    private static boolean isLwjgl3() {
        try {
            Class.forName("org.lwjgl.system.MemoryUtil", false,
                    Ki1MipmapZeroFillPatch.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Insert {@code INVOKESTATIC MipFill.zero(level, width, height)} after the per-level
     * {@code glTexImage2D(...null)} allocation inside {@code allocateTextureImpl}. Returns the
     * patched bytes, or {@code null} if the target method / call site / loop shape is not the
     * expected one (fail-safe: no throw, no altered bytes for an unrecognized class).
     */
    @Override
    public byte[] transform(byte[] originalClassfileBytes) {
        if (originalClassfileBytes == null || originalClassfileBytes.length == 0) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(originalClassfileBytes);
            ClassNode cn = new ClassNode();
            reader.accept(cn, 0);

            MethodNode target = null;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (METHOD_NAME.equals(mn.name) && METHOD_DESC.equals(mn.desc)) {
                        target = mn;
                        break;
                    }
                }
            }
            if (target == null || target.instructions == null) {
                return null; // unrecognized shape -> no change
            }

            int injected = 0;
            for (AbstractInsnNode insn = target.instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!GL_OWNER.equals(call.owner) || !GL_METHOD.equals(call.name)
                        || !GL_DESC.equals(call.desc)) {
                    continue;
                }
                // The mip level is the loop induction variable, incremented by the IINC that
                // immediately follows this call (the for-loop increment). Read its slot from
                // there; if the next real instruction is not that IINC, the shape is not the
                // vanilla per-level loop -> fail-safe (no change).
                AbstractInsnNode next = nextReal(call.getNext());
                if (!(next instanceof IincInsnNode iinc)) {
                    return null;
                }
                int levelSlot = iinc.var;

                InsnList fill = new InsnList();
                fill.add(new VarInsnNode(Opcodes.ILOAD, levelSlot));   // mip level i
                fill.add(new VarInsnNode(Opcodes.ILOAD, WIDTH_SLOT));  // width  param
                fill.add(new VarInsnNode(Opcodes.ILOAD, HEIGHT_SLOT)); // height param
                fill.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        HELPER_OWNER, HELPER_METHOD, HELPER_DESC, false));
                // Insert AFTER the glTexImage2D call, BEFORE the loop IINC. Stack-neutral, no
                // new local, no new branch target.
                target.instructions.insert(call, fill);
                injected++;
            }
            if (injected == 0) {
                return null; // expected call site not found -> no change
            }

            // Stack-neutral, frame-structure-unchanged edit: ClassWriter(0) preserves the
            // node's stored frames as-is (no getCommonSuperClass class-loading). The original
            // maxStack already accommodates the 9-operand glTexImage2D call, so the 3-operand
            // injected sequence needs no bump.
            ClassWriter writer = new ClassWriter(0);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // Fail-safe: never break class loading for a class we could not fully parse/patch.
            return null;
        }
    }

    /** The next instruction skipping labels / line numbers / frames (pseudo-nodes). */
    private static AbstractInsnNode nextReal(AbstractInsnNode from) {
        AbstractInsnNode n = from;
        while (n != null && n.getOpcode() < 0) {
            n = n.getNext();
        }
        return n;
    }

    // ---- TUF L0 content binding (behavior anchor) --------------------------

    /** The author-pinned expected L0 behavior hash — see {@link #expectedCanaryHash()}. */
    static final String EXPECTED_CANARY_HASH =
            "85d062ee09c71af0a45ba524755fd5e2b1b944011f550657cf974188151d698e";

    /**
     * A deterministic, self-contained canary shaped exactly like the vanilla
     * {@code allocateTextureImpl(IIII)V}: a per-level loop that calls
     * {@code GL11.glTexImage2D(...,(IntBuffer)null)} then {@code IINC}s the loop var — the
     * precise shape {@link #transform} keys on. Same emission every build (ASM is
     * deterministic), so {@code sha256(transform(canary))} is a stable behavior fingerprint.
     * Self-contained: no dependency on the vanilla client jar at runtime.
     */
    @Override
    public byte[] canaryClassBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET_INTERNAL, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, METHOD_NAME, METHOD_DESC, null, null);
        mv.visitCode();
        org.objectweb.asm.Label loopHead = new org.objectweb.asm.Label();
        org.objectweb.asm.Label loopEnd = new org.objectweb.asm.Label();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, loopEnd);
        mv.visitIntInsn(Opcodes.SIPUSH, 3553);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitIntInsn(Opcodes.SIPUSH, 6408);
        mv.visitVarInsn(Opcodes.ILOAD, WIDTH_SLOT);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ISHR);
        mv.visitVarInsn(Opcodes.ILOAD, HEIGHT_SLOT);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ISHR);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(32993);
        mv.visitLdcInsn(33639);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/nio/IntBuffer");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL_OWNER, GL_METHOD, GL_DESC, false);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);
        mv.visitLabel(loopEnd);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The pinned L0 behavior fingerprint; the engine recomputes and compares at arm time. */
    @Override
    public String expectedCanaryHash() {
        return EXPECTED_CANARY_HASH;
    }
}
