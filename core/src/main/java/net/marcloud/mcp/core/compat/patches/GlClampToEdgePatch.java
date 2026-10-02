package net.marcloud.mcp.core.compat.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * Restores vanilla's texture-clamp behaviour, which modern GL no longer accepts.
 *
 * <p><b>Why this exists as a patch rather than as an edit to {@code client/src}.</b> That tree is
 * vendored Mojang code and is contractually read-only. An earlier port edited
 * {@code TextureUtil.setTextureClamped} in place to pass {@code GL_CLAMP_TO_EDGE}; this patch is
 * where that change belongs, and {@code client/src} is now byte-for-byte vanilla again. The
 * practical difference is reversibility: a vendor edit can only be undone by finding whoever
 * changed it, while a patch is one manifest entry with a status and an evidence string.
 *
 * <p><b>The behaviour.</b> Vanilla passes {@code GL_CLAMP} (0x2900), a fixed-function enum.
 * {@code glTexParameteri} rejects it on a core-ish profile with {@code GL_INVALID_ENUM} and
 * leaves the wrap mode at its default, so a texture asking to clamp wraps instead. Swapping the
 * constant to {@code GL_CLAMP_TO_EDGE} (0x812F) makes the request do what it says.
 *
 * <p><b>Reach, stated precisely so nobody over-estimates it.</b> {@code setTextureClamped(true)}
 * is reached from {@code uploadTextureSub} only via {@code uploadTextureMipmap}, and every
 * mipmap call site passes {@code false, false}, so the mip path never sees it. The live path is
 * {@code uploadTextureImageSubImpl <- uploadTextureImageAllocate <- SimpleTexture.loadTexture},
 * gated on {@code TextureMetadataSection.getTextureClamp()} — that is, only textures whose
 * {@code .txt} metadata declares {@code clamp: true}.
 *
 * <p><b>What is deliberately NOT patched.</b> The same earlier port also widened the transparent
 * texel pre-scan in {@code generateMipmapData} from {@code p_147949_2_.length} (the MIP LEVEL
 * count) to {@code p_147949_2_[0].length} (the base-level pixel count). That one is a broad
 * change to shipped mip generation — it moves every cutout sprite onto the alpha-weighted
 * averaging branch — and the commit that made it concedes it does not fix KI-1. It is a
 * behaviour change with no demonstrated need, so {@code client/src} is simply reverted and
 * nothing replaces it. Reinstating it would be a decision to change vanilla's rendering, and it
 * does not belong in a port.
 *
 * <p><b>Shape keying.</b> The transform keys on the method by name and descriptor, then on the
 * specific {@code INVOKESTATIC} call to {@code GL11.glTexParameteri} whose third argument was
 * loaded as the literal {@code 0x2900}. An unrecognised shape returns {@code null} — "no change"
 * — so an unexpected Minecraft version degrades to vanilla behaviour rather than to a wrong
 * rewrite. That is deliberate: on a texture bug, doing nothing is recoverable and doing the
 * wrong thing is not.
 */
public final class GlClampToEdgePatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/client/renderer/texture/TextureUtil";

    static final String METHOD_NAME = "setTextureClamped";
    static final String METHOD_DESC = "(Z)V";

    /** GL11.glTexParameteri(int, int, int). */
    private static final String GL_TEX_PARAMETERI = "glTexParameteri";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL_TEX_PARAMETERI_DESC = "(III)V";

    /** GL_CLAMP. Vanilla's constant, and the thing this patch replaces. */
    private static final int GL_CLAMP = 0x2900;
    /** GL_CLAMP_TO_EDGE. */
    private static final int GL_CLAMP_TO_EDGE = 0x812F;

    /** Stable hash seed of this patch's transform logic (author-supplied content-address). */
    static final String TRANSFORM_SEED = "gl-clamp-to-edge-v1";

    /**
     * Ed25519 signature over the canonical signing input, produced by
     * {@code PatchSignerCli --privkey <kernel key> --platform lwjgl3}. It is not decoration:
     * {@code RootTrust} recomputes it and refuses the patch when it does not match.
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:TbYnk1JVFNPCdII8t1eb0C1TUo1GVG4k7HNZa6GQgEkpj-Nl4WVruxGpr1Y0X2k5NmKy9cg2IuNaz-Zy6IMKBw";

    private final PatchManifest manifest;

    public GlClampToEdgePatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-GL0001")
                .name("Vanilla GL_CLAMP is rejected by modern GL, leaving textures wrapped")
                .version("1.0.0.0")
                .kiRef("KI-12")
                .targetClass("net.minecraft.client.renderer.texture.TextureUtil")
                .platformCondition("lwjgl3")
                .publisher("kernel")
                .builtAt("2026-09-30T00:00:00Z")
                .evidence("Vanilla setTextureClamped(Z)V passes GL_CLAMP (0x2900) to "
                        + "glTexParameteri. 0x2900 is a fixed-function enum; a core-ish profile "
                        + "rejects it with GL_INVALID_ENUM and leaves the wrap mode at its "
                        + "default, so a texture that asked to clamp wraps instead. Rewriting the "
                        + "literal to GL_CLAMP_TO_EDGE (0x812F) makes the request take effect. "
                        + "Live path is uploadTextureImageSubImpl <- uploadTextureImageAllocate "
                        + "<- SimpleTexture.loadTexture, gated on TextureMetadataSection."
                        + "getTextureClamp(); the mipmap path passes false and is unaffected. "
                        + "An earlier port made this edit inside client/src; that tree is "
                        + "contractually read-only, so the change moved here and the vendored "
                        + "source is vanilla again.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

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
                return null;
            }

            int rewritten = 0;
            AbstractInsnNode insn = target.instructions.getFirst();
            while (insn != null) {
                if (insn.getOpcode() == Opcodes.INVOKESTATIC
                        && insn instanceof MethodInsnNode min
                        && GL11.equals(min.owner)
                        && GL_TEX_PARAMETERI.equals(min.name)
                        && GL_TEX_PARAMETERI_DESC.equals(min.desc)) {
                    // The third argument is the push IMMEDIATELY before the call: arguments are
                    // pushed in order, so for a static (III)V call the sequence is
                    // [target, pname, value, INVOKESTATIC].
                    //
                    // WHICH INSTRUCTION that push is depends on the value's width, and this patch
                    // originally matched only LDC. GL_CLAMP is 0x2900 = 10496, which fits a signed
                    // 16-bit immediate, so javac emits SIPUSH -- verified against the compiled
                    // vanilla class with javap: `sipush 10496`, twice. The patch therefore never
                    // matched, returned null, was reported armed because its signature verifies,
                    // and did nothing -- while the client/src vendor edit it was written to replace
                    // had already been reverted. The fix would have vanished on the day the pair
                    // landed, with one "N patch(es) armed" line as the only symptom.
                    //
                    // Both encodings of the SOURCE are accepted, and both are rewritten to LDC --
                    // because the two constants do not have the same width. GL_CLAMP = 0x2900 =
                    // 10496 fits a signed 16-bit immediate, so javac emits SIPUSH; GL_CLAMP_TO_EDGE
                    // = 0x812F = 33071 does NOT (the maximum is 32767), so the replacement has to
                    // be a different instruction rather than a different operand. Writing 33071
                    // into the SIPUSH operand produces a class that does not verify -- which the
                    // real-bytecode test caught, and which a hand-built LDC canary never would.
                    AbstractInsnNode back = insn.getPrevious();
                    if ((back instanceof IntInsnNode push
                            && push.getOpcode() == Opcodes.SIPUSH
                            && push.operand == GL_CLAMP)
                            || (back instanceof LdcInsnNode ldcIn
                            && ldcIn.cst instanceof Integer vIn
                            && vIn == GL_CLAMP)) {
                        target.instructions.set(back, new LdcInsnNode(GL_CLAMP_TO_EDGE));
                        rewritten++;
                    }
                }
                insn = insn.getNext();
            }
            if (rewritten == 0) {
                // Already fixed, or a shape we do not recognise. Either way: no change.
                return null;
            }

            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static AbstractInsnNode walkBack(AbstractInsnNode from, int steps) {
        AbstractInsnNode n = from;
        for (int i = 0; i < steps && n != null; i++) {
            n = n.getPrevious();
        }
        return n;
    }

    /**
     * A tiny stand-in with the same shape, so {@link #transform} has something real to key on.
     * Kept beside the patch rather than in a test so the canary and the transform cannot drift
     * apart: if the keying ever stops matching this, the test fails instead of the patch
     * silently doing nothing in production.
     */
    public byte[] canaryClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET_INTERNAL, null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                METHOD_NAME, METHOD_DESC, null, null);
        m.visitCode();
        m.visitLdcInsn(0x2901);                                   // GL_TEXTURE_2D
        m.visitLdcInsn(0x2802);                                   // GL_TEXTURE_WRAP_S
        m.visitLdcInsn(GL_CLAMP);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, GL_TEX_PARAMETERI, GL_TEX_PARAMETERI_DESC,
                false);
        m.visitLdcInsn(0x2901);
        m.visitLdcInsn(0x2803);                                   // GL_TEXTURE_WRAP_T
        m.visitLdcInsn(GL_CLAMP);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, GL_TEX_PARAMETERI, GL_TEX_PARAMETERI_DESC,
                false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(3, 1);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
