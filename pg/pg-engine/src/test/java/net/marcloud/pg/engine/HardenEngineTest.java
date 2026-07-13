package net.marcloud.pg.engine;

import net.marcloud.pg.Guarded;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Teeth-verified: proves the harden chain (scan -> pass -> verify) both PRESERVES
 * behavior and REMOVES the plaintext string. Each assertion fails on the naive /
 * pre-fix behavior:
 *  - "secret literal gone from hardened bytes" fails if the pass did nothing.
 *  - "hardened class returns the same string" fails if the injected decoder is wrong.
 *  - "not-guarded class is byte-identical" fails if the engine touches unmarked code.
 */
public class HardenEngineTest {

    private static final String SECRET = "TOP_SECRET_LICENSE_KEY_42";

    /** Generate a class whose getSecret() returns SECRET; optionally @Guarded. */
    private static byte[] genClass(String internalName, boolean guarded) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName, null, "java/lang/Object", null);
        if (guarded) {
            // @Guarded (CLASS retention -> invisible annotation), default STANDARD.
            cw.visitAnnotation("Lnet/marcloud/pg/Guarded;", false).visitEnd();
        }
        // default ctor
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        // public static String getSecret() { return "TOP_SECRET..."; }
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getSecret", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitLdcInsn(SECRET);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Loader that defines one class from bytes. */
    private static Class<?> define(String dotted, byte[] bytes) {
        return new ClassLoader() {
            Class<?> load() {
                return defineClass(dotted, bytes, 0, bytes.length);
            }
        }.load();
    }

    private static boolean containsUtf8(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    public void guardedClassLosesPlaintextButKeepsBehavior() throws Exception {
        byte[] original = genClass("pgtest/Guarded1", true);
        // sanity: the plaintext IS in the compiled class before hardening (teeth).
        assertTrue("precondition: literal present pre-harden", containsUtf8(original, SECRET));

        HardenEngine engine = HardenEngine.defaults(12345L, msg -> { });
        HardenEngine.Result r = engine.harden("pgtest.Guarded1", original);

        assertTrue("engine must report the class hardened", r.changed());
        assertEquals(Guarded.Level.STANDARD, r.level());
        assertTrue("string-constant pass must have run", r.appliedPasses().contains("string-constant"));

        // THE TEETH: plaintext literal gone from hardened bytes (fails if pass no-op).
        assertFalse("hardened bytes must NOT contain the plaintext literal",
                containsUtf8(r.bytes(), SECRET));

        // behavior preserved: loaded hardened class returns the same string.
        Class<?> hardened = define("pgtest.Guarded1", r.bytes());
        Object out = hardened.getMethod("getSecret").invoke(null);
        assertEquals("hardened class must return the original string", SECRET, out);
    }

    @Test
    public void unguardedClassIsUntouched() {
        byte[] original = genClass("pgtest/Plain1", false);
        HardenEngine engine = HardenEngine.defaults(12345L, msg -> { });
        HardenEngine.Result r = engine.harden("pgtest.Plain1", original);

        assertFalse("unmarked class must not be reported hardened", r.changed());
        assertEquals(HardenEngine.Result.Status.NOT_GUARDED, r.status());
        assertArrayEquals("unmarked class bytes must be byte-identical", original, r.bytes());
    }

    @Test
    public void hardeningIsDeterministicForSameSeed() {
        byte[] original = genClass("pgtest/Guarded2", true);
        byte[] a = HardenEngine.defaults(999L, msg -> { }).harden("pgtest.Guarded2", original).bytes();
        byte[] b = HardenEngine.defaults(999L, msg -> { }).harden("pgtest.Guarded2", original).bytes();
        assertArrayEquals("same seed must yield identical hardened bytes", a, b);
    }

    /**
     * KI-6 teeth: hardening must be IDEMPOTENT. A rebuild-without-clean feeds the
     * already-hardened bytes back through the engine. Pre-fix, apply() re-encoded
     * the (already-ciphered) LDCs and injected a SECOND pg$dec — a duplicate method
     * that loads as {@link ClassFormatError}, yet verify() reported HARDENED. This
     * test hardens once (loadable, plaintext gone) then hardens the RESULT again and
     * requires byte-identical, loadable output with no duplicate decoder.
     */
    @Test
    public void hardeningAlreadyHardenedBytesIsIdempotent() throws Exception {
        byte[] original = genClass("pgtest/Twice1", true);
        assertTrue("precondition: literal present pre-harden", containsUtf8(original, SECRET));

        // First harden: must succeed, drop the plaintext, and stay behavior-correct.
        HardenEngine.Result r1 = HardenEngine.defaults(7777L, msg -> { }).harden("pgtest.Twice1", original);
        assertTrue("first harden must report hardened", r1.changed());
        assertFalse("first harden must remove the plaintext literal", containsUtf8(r1.bytes(), SECRET));
        Class<?> once = define("pgtest.Twice1", r1.bytes());
        assertEquals("hardened-once class must still return the string",
                SECRET, once.getMethod("getSecret").invoke(null));

        // Second harden of the ALREADY-hardened bytes: pre-fix injects a duplicate
        // pg$dec -> ClassFormatError below. Post-fix the class is recognized as
        // already hardened and returned unchanged.
        HardenEngine.Result r2 = HardenEngine.defaults(7777L, msg -> { }).harden("pgtest.Twice1", r1.bytes());
        assertArrayEquals("re-hardening already-hardened bytes must be a no-op (byte-identical)",
                r1.bytes(), r2.bytes());
        assertEquals("exactly one pg$dec decoder must exist after re-harden",
                1, countDecoderMethods(r2.bytes()));

        // Must still LOAD (pre-fix threw ClassFormatError: duplicate method) and work.
        Class<?> twice = define("pgtest.Twice1", r2.bytes());
        assertEquals("re-hardened class must still return the string",
                SECRET, twice.getMethod("getSecret").invoke(null));
    }

    /**
     * KI-7 teeth: a @Guarded method with a reference-type frame merge of two types
     * the hardener's classloader cannot resolve (simulating the plugin realm being
     * unable to see the project classes being hardened). Pre-fix, COMPUTE_FRAMES
     * called Class.forName in getCommonSuperClass, threw, the pass reverted, and the
     * class shipped with its plaintext string intact. Post-fix the frame merges to
     * Object, the class hardens, LOADS, and the plaintext is gone.
     */
    @Test
    public void frameMergeOfUnresolvableTypesStillHardens() {
        byte[] original = genFrameMergeClass("pgtest/FrameMerge1", true);
        assertTrue("precondition: literal present pre-harden", containsUtf8(original, SECRET));

        HardenEngine.Result r = HardenEngine.defaults(24680L, msg -> { }).harden("pgtest.FrameMerge1", original);

        assertTrue("frame-merge class must report hardened (pre-fix reverts -> not-guarded)", r.changed());
        assertFalse("hardened frame-merge bytes must NOT contain the plaintext literal",
                containsUtf8(r.bytes(), SECRET));

        // Must define/LOAD without error (verification of the unresolvable refs is
        // lazy; defineClass succeeds on a structurally valid, frame-correct class).
        Class<?> loaded = define("pgtest.FrameMerge1", r.bytes());
        assertEquals("pgtest.FrameMerge1", loaded.getName());
    }

    /** Count synthesized pg$dec(String)String decoder methods in a class. */
    private static int countDecoderMethods(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(cn, 0);
        int n = 0;
        for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
            if ("pg$dec".equals(mn.name) && "(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Generate a @Guarded class with getSecret() plus pick(boolean, Object) whose
     * body merges two references of types NOT on the classpath (pgtest/invisible/*)
     * at a control-flow join — forcing ASM's COMPUTE_FRAMES to call
     * getCommonSuperClass on types the writer's classloader cannot resolve.
     */
    private static byte[] genFrameMergeClass(String internalName, boolean guarded) {
        String refA = "pgtest/invisible/RealmA";
        String refB = "pgtest/invisible/RealmB";
        // Generate with a LOCAL frame-safe writer so the GENERATOR does not itself
        // choke on the unresolvable RealmA/RealmB merge. This is deliberately a
        // private nested subclass (not the production FrameSafeClassWriter) so the
        // teeth stay honest: the test exercises whether the HARDENER + verify()
        // survive the merge, without depending on the class under fix to compile.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName, null, "java/lang/Object", null);
        if (guarded) {
            cw.visitAnnotation("Lnet/marcloud/pg/Guarded;", false).visitEnd();
        }
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        // public static String getSecret() { return SECRET; }  -- LDC to be hardened.
        var g = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getSecret", "()Ljava/lang/String;", null, null);
        g.visitCode();
        g.visitLdcInsn(SECRET);
        g.visitInsn(Opcodes.ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();
        // public static Object pick(boolean cond, Object o) {
        //   Object x; if (cond) x = (RealmA) o; else x = (RealmB) o; return x;
        // }  -- the RealmA/RealmB frame merge at the join is the KI-7 trigger.
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pick", "(ZLjava/lang/Object;)Ljava/lang/Object;", null, null);
        m.visitCode();
        Label elseL = new Label();
        Label joinL = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 0);
        m.visitJumpInsn(Opcodes.IFEQ, elseL);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitTypeInsn(Opcodes.CHECKCAST, refA);
        m.visitVarInsn(Opcodes.ASTORE, 2);
        m.visitJumpInsn(Opcodes.GOTO, joinL);
        m.visitLabel(elseL);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitTypeInsn(Opcodes.CHECKCAST, refB);
        m.visitVarInsn(Opcodes.ASTORE, 2);
        m.visitLabel(joinL);
        m.visitVarInsn(Opcodes.ALOAD, 2); // merge point: local 2 is RealmA | RealmB
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
