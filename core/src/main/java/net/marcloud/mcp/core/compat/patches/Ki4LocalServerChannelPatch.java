package net.marcloud.mcp.core.compat.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * KI-4 — the first real compat patch. Fixes the singleplayer world-entry crash where
 * {@code net.minecraft.network.NetworkSystem.addLocalEndpoint()} binds a
 * {@link io.netty.channel.local.LocalServerChannel} on the WRONG event-loop group.
 *
 * <p><b>The bug (verbatim vanilla, {@code NetworkSystem.java}):</b> {@code addLocalEndpoint}
 * builds its {@code ServerBootstrap} with
 * {@code .group((EventLoopGroup) eventLoops.getValue())}. {@code eventLoops} is a
 * {@code LazyLoadBase<NioEventLoopGroup>} — an NIO (socket) group backed by
 * {@code NioIoHandler}. Under Netty 4.2's {@code IoHandler} abstraction a
 * {@code LocalServerChannel} can only run on a {@code LocalIoHandler}-backed group, so
 * registering it on the NIO group throws
 * <i>"IoHandle of type LocalServerChannel$LocalServerUnsafe not supported"</i> and the
 * singleplayer integrated server never binds. (Under Netty 4.1 the two group types were
 * interchangeable enough that the vanilla code happened to work; 4.2 tightened it.)
 *
 * <p><b>The fix:</b> the class ALREADY declares the correct group as a static field —
 * {@code SERVER_LOCAL_EVENTLOOP}, a {@code LazyLoadBase<DefaultEventLoopGroup>}
 * ({@code LocalIoHandler}-backed). The vanilla {@code addLocalEndpoint} simply reads the
 * wrong field. This patch rewrites, at class-load time and ONLY inside
 * {@code addLocalEndpoint}, the {@code GETSTATIC NetworkSystem.eventLoops} that feeds the
 * {@code .group(...)} call into {@code GETSTATIC NetworkSystem.SERVER_LOCAL_EVENTLOOP}.
 * Both fields erase to {@code LazyLoadBase} and {@code getValue()} returns {@code Object}
 * then {@code checkcast EventLoopGroup}, so the surrounding bytecode/stack is unchanged —
 * a pure field-selector swap.
 *
 * <p><b>Surgical scope.</b> {@code eventLoops} is read via {@code GETSTATIC} in TWO
 * methods ({@code addLanEndpoint} and {@code addLocalEndpoint}); {@code addLanEndpoint}'s
 * NIO group is CORRECT for a public socket listener and must NOT be touched. This patch
 * therefore only ever rewrites instructions inside the method whose name+descriptor is
 * exactly {@code addLocalEndpoint ()Ljava/net/SocketAddress;}.
 *
 * <p><b>Fail-safe.</b> If the target method or the expected {@code GETSTATIC eventLoops}
 * instruction is not found (e.g. a remapped/obfuscated or already-fixed class), the
 * transform returns {@code null} ("no change") and never throws — it never emits altered
 * bytes for a class it does not fully recognize.
 *
 * <p><b>Trust / arming.</b> This ships as an in-code, classpath {@link CompatPatch} object,
 * but in-code registration confers NO trust: the {@code CompatEngine} has exactly one
 * arming rule — a valid Ed25519 signature verified against {@link net.marcloud.mcp.core.compat.TrustAnchors}.
 * So this patch SHIPS SIGNED: its manifest carries {@link #KERNEL_SIGNATURE}, an
 * {@code ed25519:v1:} signature produced offline by the kernel signing key
 * ({@code PatchSignerCli}) over this patch's canonical signing input, verified at premain
 * against the baked-in kernel public key ({@code KernelTrustAnchor}, keyId
 * {@code mcp-kernel-ed25519-v1}). It ACTUALLY ARMS (headless-provable: the engine reports
 * it armed) because that signature verifies — NOT because it is in-code registered. With
 * empty anchors it would not arm (fail-safe intact).
 *
 * <p><b>KI-10 honesty preserved.</b> The signature binds the manifest LABEL (targetClass,
 * contentHash, keyId, status, kiRef, publisher, version), NOT the executed transform bytes —
 * {@link net.marcloud.mcp.core.compat.PatchCanonicalizer}'s honest-boundary javadoc still
 * holds. {@code contentHash} remains author-supplied; nothing here recomputes it from the
 * emitted transform bytes. This class does not close, or widen, that gap.
 */
public final class Ki4LocalServerChannelPatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/network/NetworkSystem";
    /** The method that must be fixed — the LOCAL (memory) endpoint, not the LAN one. */
    static final String METHOD_NAME = "addLocalEndpoint";
    static final String METHOD_DESC = "()Ljava/net/SocketAddress;";
    /** The wrong field (NIO group) the vanilla code reads. */
    static final String WRONG_FIELD = "eventLoops";
    /** The correct field (Local/Default group) already present on the class. */
    static final String CORRECT_FIELD = "SERVER_LOCAL_EVENTLOOP";
    /** Both fields erase to this type, so the swap is stack-compatible. */
    static final String FIELD_DESC = "Lnet/minecraft/util/LazyLoadBase;";

    /** Kill switch (operator kit): {@code -Dmcp.compat.ki4=false} disables applicability.
     *  Defaults enabled because the bug is real and always present under this build's
     *  Netty 4.2.x. It can only make the patch apply LESS — it is NOT a signer bypass. */
    private static final String FLAG = "mcp.compat.ki4";

    /** Stable hash of this patch's transform logic (author-supplied; the content-address
     *  seed). Bound into the manifest via {@code withTransform} and covered by the
     *  signature below. */
    static final String TRANSFORM_SEED = "ki4-localserverchannel-group-v1";

    /**
     * The kernel's Ed25519 signature over this patch's canonical signing input
     * (targetClass=net.minecraft.network.NetworkSystem, contentHash=sha256({@link #TRANSFORM_SEED}),
     * keyId=mcp-kernel-ed25519-v1, status=VERIFIED, kiRef=KI-4, publisher=kernel,
     * version=1.0.0.0), produced OFFLINE by {@code PatchSignerCli} with the kernel private
     * key (which never enters the repo/jar). It verifies at premain against the baked-in
     * kernel PUBLIC key ({@code KernelTrustAnchor}). This signature — not in-code
     * registration — is what arms KI-4. Ed25519 is deterministic, so this string is stable.
     *
     * <p>HONESTY (KI-10): this binds the manifest LABEL, NOT the transform bytes; see the
     * class javadoc and {@code PatchCanonicalizer}.
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:"
            + "eDNAOlg6byiiNK9IL0s-HCmzrSL9H-zVX4fxHf1G1RPCTq7vo8Tm_Fcm-t_T9JrGH9CpnBz-lRmukbSbFkXTAg";

    private final PatchManifest manifest;

    public Ki4LocalServerChannelPatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-KI0004")
                .name("LocalServerChannel bound on wrong Netty event-loop group")
                .version("1.0.0.0")
                .kiRef("KI-4")
                .targetClass("net.minecraft.network.NetworkSystem")
                .platformCondition("netty>=4.2")
                .publisher("kernel")
                .builtAt("2026-07-14T00:00:00Z")
                .evidence("NetworkSystem.addLocalEndpoint() binds LocalServerChannel on "
                        + "eventLoops (NioEventLoopGroup / NioIoHandler); under Netty 4.2 this "
                        + "throws \"IoHandle of type LocalServerChannel$LocalServerUnsafe not "
                        + "supported\" and singleplayer world entry fails. Fix: use the "
                        + "already-present SERVER_LOCAL_EVENTLOOP (DefaultEventLoopGroup / "
                        + "LocalIoHandler) inside addLocalEndpoint only; addLanEndpoint's NIO "
                        + "group is left untouched.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                // Ship SIGNED: bind the transform hash + the kernel Ed25519 signature, so
                // the engine arms KI-4 through the normal verify path (not in-code trust).
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    /**
     * Applies under Netty &gt;= 4.2 (where the {@code IoHandler} abstraction makes the
     * wrong group fatal), unless disabled by {@code -Dmcp.compat.ki4=false}. Netty 4.2 is
     * detected by the presence of {@code io.netty.channel.IoHandler}, which does not exist
     * in 4.1.
     */
    @Override
    public boolean appliesToRuntime() {
        if (!Boolean.parseBoolean(System.getProperty(FLAG, "true"))) {
            return false;
        }
        return isNetty42OrLater();
    }

    private static boolean isNetty42OrLater() {
        try {
            Class.forName("io.netty.channel.IoHandler", false,
                    Ki4LocalServerChannelPatch.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            // Class not present (Netty 4.1) or classloader oddity -> do not claim to apply.
            return false;
        }
    }

    /**
     * Rewrite the wrong {@code GETSTATIC eventLoops} inside {@code addLocalEndpoint} to
     * {@code SERVER_LOCAL_EVENTLOOP}. Returns the patched bytes, or {@code null} if the
     * target method / instruction is absent (fail-safe: no throw, no altered bytes for an
     * unrecognized class).
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

            int rewritten = 0;
            for (AbstractInsnNode insn = target.instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.GETSTATIC) {
                    continue;
                }
                FieldInsnNode f = (FieldInsnNode) insn;
                if (TARGET_INTERNAL.equals(f.owner)
                        && WRONG_FIELD.equals(f.name)
                        && FIELD_DESC.equals(f.desc)) {
                    // Swap ONLY the field selector; owner + descriptor stay identical, so
                    // the stack shape (a LazyLoadBase ref) is unchanged.
                    f.name = CORRECT_FIELD;
                    rewritten++;
                }
            }
            if (rewritten == 0) {
                return null; // expected instruction not found -> no change
            }

            // No stack/frame layout change (pure field-selector swap), so preserve the
            // original frames: ClassWriter(0) writes the node's stored frames as-is,
            // avoiding any getCommonSuperClass class-loading during COMPUTE_FRAMES.
            ClassWriter writer = new ClassWriter(0);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // Fail-safe: never break class loading for a class we could not fully parse.
            return null;
        }
    }
}
