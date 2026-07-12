package net.marcloud.pg.engine;

import net.marcloud.pg.Guarded;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
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
}
