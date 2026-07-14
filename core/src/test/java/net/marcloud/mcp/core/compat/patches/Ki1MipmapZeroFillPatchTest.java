package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.compat.CompatDatabase;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * Non-vacuous tests for {@link Ki1MipmapZeroFillPatch}. The core assertions FAIL on a no-op
 * transform: they require that an {@code INVOKESTATIC MipFill.zero(III)V} was injected
 * immediately after the {@code glTexImage2D(...IntBuffer)} allocation inside
 * {@code allocateTextureImpl} — absent before the patch, present after. An identity/no-op
 * transform leaves zero such calls and every assertion fails, proving the transform is
 * load-bearing.
 */
public class Ki1MipmapZeroFillPatchTest {

    private static final String TARGET = "net/minecraft/client/renderer/texture/TextureUtil";
    private static final String GL_OWNER = "org/lwjgl/opengl/GL11";
    private static final String HELPER_OWNER = "net/marcloud/mcp/core/compat/patches/MipFill";

    // ---- synthetic-shape test (always runs, fully headless) -----------------

    /**
     * Build a class shaped like {@code TextureUtil.allocateTextureImpl (IIII)V}: a per-level
     * loop containing {@code GL11.glTexImage2D(...,(IntBuffer)null)} followed by the loop
     * {@code IINC}. The patch must splice one {@code MipFill.zero(level,width,height)} call
     * after that allocation.
     */
    @Test
    public void injectsZeroFillAfterGlTexImage2D() {
        byte[] original = synthTextureUtil();

        assertEquals("baseline: exactly one glTexImage2D alloc call before patch",
                1, countCalls(original, "allocateTextureImpl", "(IIII)V", GL_OWNER, "glTexImage2D"));
        assertEquals("baseline: NO MipFill.zero call before patch",
                0, countCalls(original, "allocateTextureImpl", "(IIII)V", HELPER_OWNER, "zero"));

        byte[] patched = new Ki1MipmapZeroFillPatch().transform(original);
        assertNotNull("patch must transform a class with the null-pointer alloc", patched);

        assertEquals("patch must inject exactly one MipFill.zero call",
                1, countCalls(patched, "allocateTextureImpl", "(IIII)V", HELPER_OWNER, "zero"));
        assertEquals("the original glTexImage2D alloc must remain",
                1, countCalls(patched, "allocateTextureImpl", "(IIII)V", GL_OWNER, "glTexImage2D"));
        // The injected zero-fill call must come immediately AFTER the alloc, not before it.
        assertTrue("MipFill.zero must be injected after glTexImage2D, in that order",
                zeroFillFollowsAlloc(patched));
    }

    /** A class whose target method has NO glTexImage2D IntBuffer alloc yields no change (null). */
    @Test
    public void noOpWhenAllocCallAbsent() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "allocateTextureImpl", "(IIII)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 4);
        mv.visitEnd();
        cw.visitEnd();
        assertNull("no glTexImage2D alloc to anchor on -> no change (null)",
                new Ki1MipmapZeroFillPatch().transform(cw.toByteArray()));
    }

    /** A class without the target method is left alone (null), never throws. */
    @Test
    public void noOpWhenTargetMethodAbsent() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        cw.visitEnd();
        assertNull(new Ki1MipmapZeroFillPatch().transform(cw.toByteArray()));
    }

    @Test
    public void nullAndEmptyAndGarbageInputAreSafe() {
        Ki1MipmapZeroFillPatch p = new Ki1MipmapZeroFillPatch();
        assertNull(p.transform(null));
        assertNull(p.transform(new byte[0]));
        assertNull("garbage bytes must not throw, just signal no change",
                p.transform(new byte[]{1, 2, 3, 4}));
    }

    // ---- real vanilla test (runs only when client bytes are on the classpath) --

    /**
     * If the compiled vanilla {@code TextureUtil.class} is on the test classpath (a
     * {@code provided} dependency: {@code ./mvnw -pl core -am test}), transform the REAL bytes
     * and assert the injection landed on the real method shape.
     */
    @Test
    public void injectsIntoRealVanillaTextureUtil() {
        byte[] vanilla = classBytes(TARGET);
        Assume.assumeTrue("vanilla TextureUtil.class not on classpath "
                + "(build client too: mvnw -pl core -am test)", vanilla != null);

        assertEquals("vanilla allocateTextureImpl has exactly one null-pointer glTexImage2D alloc",
                1, countCalls(vanilla, "allocateTextureImpl", "(IIII)V", GL_OWNER, "glTexImage2D"));
        assertEquals("vanilla has no MipFill.zero yet",
                0, countCalls(vanilla, "allocateTextureImpl", "(IIII)V", HELPER_OWNER, "zero"));

        byte[] patched = new Ki1MipmapZeroFillPatch().transform(vanilla);
        assertNotNull("real TextureUtil must be transformed", patched);

        assertEquals("patch must inject exactly one MipFill.zero into real allocateTextureImpl",
                1, countCalls(patched, "allocateTextureImpl", "(IIII)V", HELPER_OWNER, "zero"));
        assertTrue("MipFill.zero must follow the real glTexImage2D alloc",
                zeroFillFollowsAlloc(patched));

        // Re-parsing the patched bytes must not throw (well-formed class).
        new ClassReader(patched).accept(new ClassNode(), 0);
    }

    @Test
    public void manifestIsBoundAndRegisters() {
        Ki1MipmapZeroFillPatch p = new Ki1MipmapZeroFillPatch();
        PatchManifest m = p.manifest();
        assertTrue("manifest must be bound (transform hash + patchId)", m.isBound());
        assertEquals("MCP-KI0001", m.code());
        assertEquals("KI-1", m.kiRef());
        assertEquals("net.minecraft.client.renderer.texture.TextureUtil", m.targetClass());
        assertEquals("lwjgl3", m.platformCondition());
        assertEquals(PatchManifest.Status.VERIFIED, m.status());
        assertNotNull(m.patchId());
        assertNotNull(m.contentHash());
        assertNotNull("KI-1 must ship signed (arming is signature-gated)", m.signature());
        assertTrue("KI-1 signature must be in ed25519:v1: wire form",
                m.signature().startsWith("ed25519:v1:"));

        CompatDatabase db = new CompatDatabase();
        db.register(p); // must not throw
        assertEquals(1, db.size());
        assertNotNull(db.byPatchId(m.patchId()));
    }

    @Test
    public void contentHashIsStableAndBehaviorBound() {
        assertEquals(PatchManifest.sha256Hex("ki1-mipmap-zerofill-v1"),
                new Ki1MipmapZeroFillPatch().manifest().contentHash());
    }

    // ---- helpers ------------------------------------------------------------

    /** Count INVOKESTATIC calls to owner.name in the given method. */
    private static int countCalls(byte[] classBytes, String method, String desc,
                                  String owner, String name) {
        MethodNode mn = methodOf(classBytes, method, desc);
        int n = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode c = (MethodInsnNode) insn;
                if (owner.equals(c.owner) && name.equals(c.name)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** True iff a MipFill.zero call appears AFTER a glTexImage2D call in allocateTextureImpl. */
    private static boolean zeroFillFollowsAlloc(byte[] classBytes) {
        MethodNode mn = methodOf(classBytes, "allocateTextureImpl", "(IIII)V");
        boolean sawAlloc = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            MethodInsnNode c = (MethodInsnNode) insn;
            if (GL_OWNER.equals(c.owner) && "glTexImage2D".equals(c.name)) {
                sawAlloc = true;
            } else if (HELPER_OWNER.equals(c.owner) && "zero".equals(c.name)) {
                return sawAlloc; // a zero() before any alloc would be wrong
            }
        }
        return false;
    }

    private static MethodNode methodOf(byte[] classBytes, String method, String desc) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (method.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        throw new AssertionError("method not found: " + method + desc);
    }

    /**
     * A synthetic TextureUtil-shaped class: static {@code allocateTextureImpl(IIII)V} with a
     * per-level loop reading params, calling {@code GL11.glTexImage2D(...,(IntBuffer)null)},
     * then {@code IINC}-ing the loop variable — the exact shape the patch keys on.
     */
    private static byte[] synthTextureUtil() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "allocateTextureImpl", "(IIII)V", null, null);
        mv.visitCode();
        org.objectweb.asm.Label loopHead = new org.objectweb.asm.Label();
        org.objectweb.asm.Label loopEnd = new org.objectweb.asm.Label();
        // int i = 0  (slot 4)
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(loopHead);
        // if (i > mipLevels) goto end   (mipLevels = param slot 1)
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, loopEnd);
        // GL11.glTexImage2D(3553, i, 6408, width>>i, height>>i, 0, 32993, 33639, (IntBuffer)null)
        mv.visitIntInsn(Opcodes.SIPUSH, 3553);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitIntInsn(Opcodes.SIPUSH, 6408);
        mv.visitVarInsn(Opcodes.ILOAD, 2);           // width
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ISHR);
        mv.visitVarInsn(Opcodes.ILOAD, 3);           // height
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ISHR);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(32993);
        mv.visitLdcInsn(33639);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/nio/IntBuffer");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL_OWNER, "glTexImage2D",
                "(IIIIIIIILjava/nio/IntBuffer;)V", false);
        // i++
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);
        mv.visitLabel(loopEnd);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classBytes(String internalName) {
        String res = "/" + internalName + ".class";
        try (InputStream in = Ki1MipmapZeroFillPatchTest.class.getResourceAsStream(res)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
