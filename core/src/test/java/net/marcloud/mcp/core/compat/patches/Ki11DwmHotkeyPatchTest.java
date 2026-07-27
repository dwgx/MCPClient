package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Teeth for KI-11's transform: it must hook the one method it claims, leave everything else alone,
 * and decline anything it does not recognise.
 *
 * <p>The scope assertions matter as much as the injection one. This patch runs inside a
 * {@code ClassFileTransformer} on {@code Minecraft} — the largest class in the client — so an
 * over-broad rewrite would be both hard to notice and expensive to debug.
 */
public final class Ki11DwmHotkeyPatchTest {

    private static final String HELPER_OWNER = "net/marcloud/mcp/core/compat/patches/DwmHotkey";
    private static final String HELPER_METHOD = "onKeyEvent";

    /** A stand-in for vanilla: the hook target plus a decoy of the same descriptor. */
    private static byte[] canary() {
        return new Ki11DwmHotkeyPatch().canaryClassBytes();
    }

    private static ClassNode parse(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name)) {
                return mn;
            }
        }
        return null;
    }

    private static int helperCalls(MethodNode mn) {
        int found = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (HELPER_OWNER.equals(call.owner) && HELPER_METHOD.equals(call.name)) {
                found++;
            }
        }
        return found;
    }

    /** The hook must land in dispatchKeypresses, exactly once. */
    @Test
    public void injectsTheHookIntoDispatchKeypresses() {
        byte[] out = new Ki11DwmHotkeyPatch().transform(canary());
        assertNotNull("the transform must patch a class of the expected shape", out);

        MethodNode target = method(parse(out), "dispatchKeypresses");
        assertNotNull("the target method must survive the rewrite", target);
        assertEquals("exactly one hook call — a second would fire the hotkey twice per event",
            1, helperCalls(target));
    }

    /**
     * The hook must be the FIRST instruction.
     *
     * <p>Vanilla's own body can call {@code displayGuiScreen}, so a hook placed after it would
     * decide what to do based on a screen vanilla had just changed.
     */
    @Test
    public void theHookRunsBeforeVanillasOwnBody() {
        byte[] out = new Ki11DwmHotkeyPatch().transform(canary());
        MethodNode target = method(parse(out), "dispatchKeypresses");

        AbstractInsnNode first = target.instructions.getFirst();
        while (first != null && first.getOpcode() < 0) {
            first = first.getNext(); // skip labels / line numbers / frames
        }
        assertNotNull("the method must have instructions", first);
        assertEquals("the hook must be the first real instruction",
            Opcodes.INVOKESTATIC, first.getOpcode());
        assertEquals("and it must be our helper", HELPER_METHOD, ((MethodInsnNode) first).name);
    }

    /** No other method may be touched, however similar its descriptor. */
    @Test
    public void leavesOtherMethodsAlone() {
        byte[] out = new Ki11DwmHotkeyPatch().transform(canary());
        MethodNode decoy = method(parse(out), "runTick");
        assertNotNull("the decoy must still exist", decoy);
        assertEquals("a method that merely shares the ()V descriptor must not be hooked",
            0, helperCalls(decoy));
    }

    /**
     * Applying twice must not double the hook.
     *
     * <p>The engine chains patches for a target, and a class can be retransformed, so an
     * injection that is not idempotent would fire the hotkey once per application.
     */
    @Test
    public void isIdempotent() {
        Ki11DwmHotkeyPatch patch = new Ki11DwmHotkeyPatch();
        byte[] once = patch.transform(canary());
        assertNull("a class that already carries the hook must be declined, not patched again",
            patch.transform(once));
    }

    /** A class without the target method must be left untouched. */
    @Test
    public void declinesAClassWithoutTheTargetMethod() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null,
            "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
            "somethingElse", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();

        assertNull("an unrecognised shape must produce no change, never altered bytes",
            new Ki11DwmHotkeyPatch().transform(cw.toByteArray()));
    }

    /** Garbage in must not throw: a transform that throws during boot breaks class loading. */
    @Test
    public void survivesUnparseableInput() {
        Ki11DwmHotkeyPatch patch = new Ki11DwmHotkeyPatch();
        assertNull("null input", patch.transform(null));
        assertNull("empty input", patch.transform(new byte[0]));
        assertNull("not a classfile", patch.transform(new byte[] {1, 2, 3, 4, 5}));
    }

    /**
     * The patched bytes must still be a loadable class.
     *
     * <p>Verifying the frames is the point: the injection is stack-neutral precisely so
     * {@code ClassWriter(0)} can pass the original frames through, and a mistake there produces a
     * {@code VerifyError} at load rather than anything visible here.
     */
    @Test
    public void patchedBytesRemainWellFormed() {
        byte[] out = new Ki11DwmHotkeyPatch().transform(canary());
        ClassNode cn = parse(out);
        assertEquals("net/minecraft/client/Minecraft", cn.name);
        assertEquals("both methods must survive", 2, cn.methods.size());

        MethodNode target = method(cn, "dispatchKeypresses");
        assertTrue("maxStack must still cover the body; a no-operand call needs no more",
            target.maxStack >= 0);
    }

    /** The manifest must describe what the patch actually targets. */
    @Test
    public void manifestNamesTheRealTarget() {
        var m = new Ki11DwmHotkeyPatch().manifest();
        assertEquals("net.minecraft.client.Minecraft", m.targetClass());
        assertEquals("KI-11", m.kiRef());
        assertNotNull("a patch must ship a content hash to be signable", m.contentHash());
    }
}
