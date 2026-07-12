package net.marcloud.pg.engine.pass;

import net.marcloud.pg.Guarded;
import net.marcloud.pg.engine.HardenContext;
import net.marcloud.pg.engine.HardenPass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * STANDARD-level pass: remove plaintext string constants from the constant pool.
 *
 * <p>Each {@code LDC "literal"} of a String is replaced by {@code LDC <cipher>}
 * followed by a call to a synthesized static decoder {@code pg$dec(String)String}
 * injected into the class. The cipher is a per-class-keyed XOR (key derived from
 * {@link HardenContext#classSeed()}), so {@code strings} on the jar and a plain
 * decompile no longer reveal the literals; the value only materializes at runtime.
 *
 * <p>Deterministic: same (seed, class) yields the same cipher. Fail-safe by the
 * engine's contract — if anything here is off, the engine reverts this pass.
 *
 * <p>Honest boundary: this defeats static string extraction and casual reading,
 * not a runtime memory dump (the decoded string exists in memory when used). It is
 * a decompiler/static-analysis speed bump, the STANDARD-tier baseline.
 */
public final class StringConstantPass implements HardenPass {

    private static final String DECODE_NAME = "pg$dec";
    private static final String DECODE_DESC = "(Ljava/lang/String;)Ljava/lang/String;";

    @Override
    public String id() {
        return "string-constant";
    }

    @Override
    public Guarded.Level minLevel() {
        return Guarded.Level.STANDARD;
    }

    @Override
    public byte[] apply(byte[] classBytes, HardenContext ctx) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);

        // Skip interfaces/annotations: no code to carry a decoder, and injecting a
        // static method into an interface changes its shape. Only classes.
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ANNOTATION) != 0) {
            return classBytes;
        }

        int key = deriveKey(ctx.classSeed());
        boolean anyEncoded = false;

        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) {
                continue;
            }
            // Do not touch the decoder itself if a prior run added it (idempotence).
            if (DECODE_NAME.equals(mn.name)) {
                continue;
            }
            List<LdcInsnNode> targets = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode) {
                    LdcInsnNode ldc = (LdcInsnNode) insn;
                    if (ldc.cst instanceof String) {
                        targets.add(ldc);
                    }
                }
            }
            for (LdcInsnNode ldc : targets) {
                String literal = (String) ldc.cst;
                ldc.cst = xor(literal, key);
                InsnList call = new InsnList();
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, DECODE_NAME, DECODE_DESC, false));
                mn.instructions.insert(ldc, call);
                anyEncoded = true;
            }
        }

        if (!anyEncoded) {
            return classBytes; // nothing to do — pass is a no-op for this class
        }

        injectDecoder(cn, key);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** A non-zero XOR key from the per-class seed (16-bit so it fits char XOR cleanly). */
    private static int deriveKey(long classSeed) {
        int k = (int) (classSeed ^ (classSeed >>> 32)) & 0xFFFF;
        return (k == 0) ? 0x5A5A : k;
    }

    private static String xor(String s, int key) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] = (char) (c[i] ^ (key + i));
        }
        return new String(c);
    }

    /**
     * Inject {@code private static String pg$dec(String)} that reverses {@link
     * #xor}. Position-dependent XOR ({@code key + i}) so identical substrings do
     * not encode identically. Written as ASM so it needs no runtime pg dependency.
     */
    private void injectDecoder(ClassNode cn, int key) {
        MethodNode dec = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                DECODE_NAME, DECODE_DESC, null, null);
        InsnList in = dec.instructions;
        // char[] c = arg.toCharArray();
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        in.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ASTORE, 1));
        // int i = 0;
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ISTORE, 2));
        org.objectweb.asm.tree.LabelNode loop = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
        in.add(loop);
        // if (i >= c.length) goto end;
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARRAYLENGTH));
        in.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ICMPGE, end));
        // c[i] = (char)(c[i] ^ (key + i));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.CALOAD));
        in.add(new org.objectweb.asm.tree.LdcInsnNode(key));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IADD));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IXOR));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.I2C));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.CASTORE));
        // i++
        in.add(new org.objectweb.asm.tree.IincInsnNode(2, 1));
        in.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, loop));
        in.add(end);
        // return new String(c);
        in.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
        in.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        in.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
        in.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        dec.maxStack = 6;
        dec.maxLocals = 3;
        cn.methods.add(dec);
    }

    /** Unused Type import guard (keeps the ASM Type on the classpath for future passes). */
    @SuppressWarnings("unused")
    private static Type keep() {
        return Type.INT_TYPE;
    }
}
