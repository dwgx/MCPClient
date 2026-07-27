package net.marcloud.mcp.core.compat.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * KI-11 — no way to open the DWM screen. Gives the UI subsystem a key.
 *
 * <p><b>The gap.</b> {@code dwm} ships a real {@code GuiScreen} and nothing anywhere constructs it:
 * {@code DwmEntry} has no callers. The client baseline is frozen, so a {@code KeyBinding} cannot be
 * added to it, which is exactly the situation the compat layer exists for — the NT AppCompat
 * analogue, intercepting a call in code we do not own rather than editing it.
 *
 * <p><b>Why this target.</b> {@code Minecraft.dispatchKeypresses()} is called once per keyboard
 * event from inside {@code runTick}'s {@code while (Keyboard.next())} loop, so a hook there sees
 * the authoritative event stream. The alternative considered first — polling
 * {@code Keyboard.isKeyDown} on the 20 Hz tick — samples a level signal and therefore drops any
 * press that starts and ends between two ticks. It is also {@code public void ()V}, so the injected
 * call needs no operands.
 *
 * <p><b>Injection shape.</b> A single no-argument {@code INVOKESTATIC} to
 * {@link DwmHotkey#onKeyEvent()} at the method's first real instruction:
 * <pre>
 *   INVOKESTATIC net/marcloud/mcp/core/compat/patches/DwmHotkey.onKeyEvent ()V
 * </pre>
 * Nothing is pushed or popped, no local is added, and no branch target is created, so the method's
 * existing stack map frames stay valid and {@code ClassWriter(0)} can write them through unchanged.
 * That matters more than it looks: {@code COMPUTE_FRAMES} would make ASM call
 * {@code getCommonSuperClass}, which loads classes from inside a {@code ClassFileTransformer} and
 * risks circularity during boot. KI-1 and KI-4 hold the same line.
 *
 * <p>Injecting at ENTRY rather than exit is deliberate: vanilla's own body can call
 * {@code displayGuiScreen} (the stream-toggle confirmation dialog), and running after that would
 * make the hotkey's decision about the current screen depend on what vanilla just did to it.
 *
 * <p><b>Fail-safe.</b> A missing target method, or bytes that will not parse, returns {@code null}
 * ("no change") and never throws — it never emits altered bytes for a class it does not recognise.
 * Idempotent by inspection: if the injected call is already present the transform declines, so a
 * double application cannot fire the hotkey twice per event.
 *
 * <p><b>Inert until asked for.</b> The injected call is unconditional but
 * {@link DwmHotkey#onKeyEvent()} returns immediately unless a scancode is bound
 * ({@code -Dmcp.dwm.hotkey}, or {@code -Dmcp.core.overlay=true} for the default). So arming KI-11
 * changes no observable behaviour on its own, and dwm being absent costs one no-op call per
 * keystroke.
 *
 * <p><b>Trust / arming.</b> Like KI-1 and KI-4 this must ship SIGNED to arm: the engine's one
 * arming rule is a valid Ed25519 signature over the canonical signing input, verified against the
 * baked-in kernel public key. In-code registration confers no trust.
 * <b>{@link #KERNEL_SIGNATURE} is currently a placeholder</b>, so this patch does NOT arm — the
 * kernel private key is deliberately not on the development machine. Signing is a separate
 * ceremony: run {@code PatchSignerCli} with the key, paste the result over the placeholder. Until
 * then the fail-safe holds in the correct direction (an unsigned patch simply does not run), and
 * {@link net.marcloud.mcp.core.compat.patches.Ki11SigningContractTest} proves both halves — that
 * it does not arm unsigned, and that it WOULD arm under a valid signature.
 *
 * <p><b>KI-10 honesty preserved.</b> The signature binds the manifest LABEL, not the executed
 * transform bytes; {@code contentHash} stays author-supplied. This class neither closes nor widens
 * that gap.
 */
public final class Ki11DwmHotkeyPatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/client/Minecraft";
    /** Vanilla's per-event keyboard dispatch — called once per event from runTick's loop. */
    static final String METHOD_NAME = "dispatchKeypresses";
    static final String METHOD_DESC = "()V";

    /** The Core helper the injected call targets. */
    static final String HELPER_OWNER = "net/marcloud/mcp/core/compat/patches/DwmHotkey";
    static final String HELPER_METHOD = "onKeyEvent";
    static final String HELPER_DESC = "()V";

    /** Kill switch (operator kit): {@code -Dmcp.compat.ki11=false} disables applicability. It can
     *  only make the patch apply LESS — it is NOT a signer bypass. */
    private static final String FLAG = "mcp.compat.ki11";

    /** Stable hash seed of this patch's transform logic (author-supplied content-address). */
    static final String TRANSFORM_SEED = "ki11-dwm-hotkey-v1";

    /**
     * PLACEHOLDER — not a valid signature, so KI-11 does not arm.
     *
     * <p>The kernel private key lives outside the repo and outside the development machine, so it
     * cannot be produced here. This is deliberately left obviously invalid rather than absent:
     * absent would read as "signing was forgotten", whereas this records that the ceremony is a
     * known outstanding step. Replace with the output of:
     *
     * <pre>
     *   java -cp core.jar net.marcloud.mcp.core.compat.tools.PatchSignerCli \
     *       --privkey &lt;kernel key&gt; --target net.minecraft.client.Minecraft \
     *       --kiref KI-11 --publisher kernel --version 1.0.0.0 --status VERIFIED \
     *       --platform lwjgl3 \
     *       --transform-hash cb3c3fc80be1b47130ab42c70a283b63bccda269d5e1bfb2333894e059fb6029
     * </pre>
     *
     * <p>Every argument above is covered by the canonical signing input, so all of them must match
     * this manifest exactly or the result will not verify. The transform hash is
     * {@code sha256(TRANSFORM_SEED)}, precomputed here so the ceremony needs no build step. The
     * resulting patchId is {@code cp-41597d554e8d618ed8927160068aabe553f337d4606035e292d6de8432d6dd34}.
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:UNSIGNED-PLACEHOLDER-AWAITING-KEY-CEREMONY";

    private final PatchManifest manifest;

    public Ki11DwmHotkeyPatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-KI0011")
                .name("No way to open the DWM screen")
                .version("1.0.0.0")
                .kiRef("KI-11")
                .targetClass("net.minecraft.client.Minecraft")
                .platformCondition("lwjgl3")
                .publisher("kernel")
                .builtAt("2026-07-27T00:00:00Z")
                .evidence("dwm ships a real GuiScreen (QmlGuiScreen) and nothing constructs it: "
                        + "DwmEntry has no callers, so the UI cannot be opened at all. The client "
                        + "baseline is frozen, so no KeyBinding can be added to it. Fix: inject a "
                        + "no-argument INVOKESTATIC to DwmHotkey.onKeyEvent() at the entry of "
                        + "Minecraft.dispatchKeypresses(), which vanilla calls once per keyboard "
                        + "event from runTick's while(Keyboard.next()) loop — the authoritative "
                        + "event stream, unlike tick-rate polling of isKeyDown, which drops a "
                        + "press that starts and ends within one tick. The call is inert unless a "
                        + "scancode is bound via -Dmcp.dwm.hotkey / -Dmcp.core.overlay=true.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    /**
     * Applies under LWJGL3 unless disabled by {@code -Dmcp.compat.ki11=false}.
     *
     * <p>LWJGL3 is detected by a class that exists only there. The condition is about the shim
     * keyboard the helper reads through, not about the injection itself.
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
            Class.forName("org.lwjgl.glfw.GLFW", false,
                    Ki11DwmHotkeyPatch.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Insert {@code INVOKESTATIC DwmHotkey.onKeyEvent()} at the entry of
     * {@code dispatchKeypresses}. Returns the patched bytes, or {@code null} when the target method
     * is absent or the call is already there (fail-safe: no throw, no altered bytes for a class we
     * do not recognise).
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
            if (alreadyInjected(target)) {
                // Idempotent: a second application would fire the hotkey twice per event.
                return null;
            }

            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HELPER_OWNER, HELPER_METHOD, HELPER_DESC, false));
            // At the very start, before vanilla's own body: it can call displayGuiScreen itself
            // (the stream-toggle dialog), and running after that would make our view of the
            // current screen depend on what vanilla just changed it to.
            target.instructions.insert(hook);

            // Stack-neutral with no new locals or branch targets, so the stored frames remain
            // valid: ClassWriter(0) writes them through without a getCommonSuperClass pass, which
            // would load classes from inside a transformer during boot.
            ClassWriter writer = new ClassWriter(0);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // Fail-safe: never break class loading for a class we could not fully parse/patch.
            return null;
        }
    }

    /** True when the helper call is already present, so the transform declines to add a second. */
    private static boolean alreadyInjected(MethodNode target) {
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (HELPER_OWNER.equals(call.owner) && HELPER_METHOD.equals(call.name)
                    && HELPER_DESC.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    // ---- TUF L0 content binding (behavior anchor) --------------------------

    /**
     * The author-pinned expected L0 behavior hash.
     *
     * <p>Regenerate with {@code ContentHash.forPatch(new Ki11DwmHotkeyPatch())} if the transform
     * changes; {@code Ki11ContentBindingTest} fails loudly when this drifts, which is the point.
     */
    static final String EXPECTED_CANARY_HASH =
            "6a1563395f7e1018cc6628215879a56504a861e4ed0143161450217d00825ef4";

    /**
     * A deterministic canary shaped like the vanilla target: a class with
     * {@code dispatchKeypresses ()V} plus a second method the transform must leave alone, so
     * {@code sha256(transform(canary))} fingerprints the injection AND its scope.
     */
    @Override
    public byte[] canaryClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET_INTERNAL, null, "java/lang/Object", null);

        // The method that must be hooked.
        org.objectweb.asm.MethodVisitor target = cw.visitMethod(Opcodes.ACC_PUBLIC,
                METHOD_NAME, METHOD_DESC, null, null);
        target.visitCode();
        target.visitInsn(Opcodes.RETURN);
        target.visitMaxs(0, 1);
        target.visitEnd();

        // A decoy with the same descriptor: the transform must NOT touch it, and the hash proves
        // it, since an over-broad rewrite would change these bytes too.
        org.objectweb.asm.MethodVisitor other = cw.visitMethod(Opcodes.ACC_PUBLIC,
                "runTick", METHOD_DESC, null, null);
        other.visitCode();
        other.visitInsn(Opcodes.RETURN);
        other.visitMaxs(0, 1);
        other.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The pinned L0 behavior fingerprint; the engine recomputes and compares at arm time. */
    @Override
    public String expectedCanaryHash() {
        return EXPECTED_CANARY_HASH;
    }
}
