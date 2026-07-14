package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.compat.CompatDatabase;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * Non-vacuous tests for {@link Ki4LocalServerChannelPatch}. The core assertions FAIL on a
 * no-op transform: they require that the {@code GETSTATIC eventLoops} inside
 * {@code addLocalEndpoint} was rewritten to {@code SERVER_LOCAL_EVENTLOOP}, while the one
 * inside {@code addLanEndpoint} was left alone. An identity/no-op patch leaves both as
 * {@code eventLoops} and every rewrite assertion fails — proving the transform is
 * load-bearing.
 */
public class Ki4LocalServerChannelPatchTest {

    private static final String TARGET = "net/minecraft/network/NetworkSystem";
    private static final String FIELD_DESC = "Lnet/minecraft/util/LazyLoadBase;";

    // ---- synthetic-shape test (always runs, fully headless) -----------------

    /**
     * Builds a class whose internal name + field references mimic vanilla NetworkSystem:
     * two methods, {@code addLanEndpoint} and {@code addLocalEndpoint}, each reading the
     * static {@code eventLoops} field. Only {@code addLocalEndpoint} (matching name AND
     * descriptor) must be rewritten.
     */
    @Test
    public void rewritesOnlyAddLocalEndpointGetstatic() {
        byte[] original = synthNetworkSystem();

        // Sanity: BOTH methods read eventLoops before the patch (the shape we depend on).
        assertTrue(groupFieldsIn(original, "addLanEndpoint", "(Ljava/net/InetAddress;I)V").contains("eventLoops"));
        assertTrue(groupFieldsIn(original, "addLocalEndpoint", "()Ljava/net/SocketAddress;").contains("eventLoops"));

        byte[] patched = new Ki4LocalServerChannelPatch().transform(original);
        assertNotNull("patch must transform a class that has the wrong GETSTATIC", patched);

        // addLocalEndpoint must now read ONLY the CORRECT group; addLanEndpoint untouched.
        List<String> local = groupFieldsIn(patched, "addLocalEndpoint", "()Ljava/net/SocketAddress;");
        assertTrue("addLocalEndpoint must be rewritten to the Local group",
                local.contains("SERVER_LOCAL_EVENTLOOP"));
        assertFalse("addLocalEndpoint must no longer read the NIO group",
                local.contains("eventLoops"));
        List<String> lan = groupFieldsIn(patched, "addLanEndpoint", "(Ljava/net/InetAddress;I)V");
        assertTrue("addLanEndpoint's NIO group must be left untouched", lan.contains("eventLoops"));
        assertFalse("addLanEndpoint must NOT be pointed at the Local group",
                lan.contains("SERVER_LOCAL_EVENTLOOP"));
    }

    /** A class with the target method but NO eventLoops GETSTATIC yields no change (null). */
    @Test
    public void noOpWhenExpectedInstructionAbsent() {
        byte[] original = synthAlreadyFixed();
        assertNull("no wrong GETSTATIC to rewrite -> signal no change (null)",
                new Ki4LocalServerChannelPatch().transform(original));
    }

    /** A class without the target method is left alone (null), never throws. */
    @Test
    public void noOpWhenTargetMethodAbsent() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        cw.visitEnd();
        assertNull(new Ki4LocalServerChannelPatch().transform(cw.toByteArray()));
    }

    @Test
    public void nullAndEmptyInputAreSafe() {
        Ki4LocalServerChannelPatch p = new Ki4LocalServerChannelPatch();
        assertNull(p.transform(null));
        assertNull(p.transform(new byte[0]));
        assertNull("garbage bytes must not throw, just signal no change",
                p.transform(new byte[] {1, 2, 3, 4}));
    }

    // ---- real vanilla test (runs only when client bytes are on the classpath) --

    /**
     * If the compiled vanilla {@code NetworkSystem.class} is on the test classpath (it is
     * a {@code provided} dependency: {@code ./mvnw -pl core -am test}), transform the REAL
     * bytes and assert the same surgical result on the real method shapes.
     */
    @Test
    public void rewritesRealVanillaNetworkSystem() {
        byte[] vanilla = classBytes(TARGET);
        Assume.assumeTrue("vanilla NetworkSystem.class not on classpath "
                + "(build client too: mvnw -pl core -am test)", vanilla != null);

        assertTrue("vanilla addLocalEndpoint reads the wrong NIO group",
                groupFieldsIn(vanilla, "addLocalEndpoint", "()Ljava/net/SocketAddress;").contains("eventLoops"));

        byte[] patched = new Ki4LocalServerChannelPatch().transform(vanilla);
        assertNotNull("real NetworkSystem must be transformed", patched);

        List<String> local = groupFieldsIn(patched, "addLocalEndpoint", "()Ljava/net/SocketAddress;");
        assertTrue("addLocalEndpoint must read the Local group after patch",
                local.contains("SERVER_LOCAL_EVENTLOOP"));
        assertFalse("addLocalEndpoint must no longer read the NIO group",
                local.contains("eventLoops"));
        // addLanEndpoint reads eventLoops in the else-branch; it must remain the NIO group.
        List<String> lan = groupFieldsIn(patched, "addLanEndpoint", "(Ljava/net/InetAddress;I)V");
        assertTrue("addLanEndpoint's NIO group must be left untouched", lan.contains("eventLoops"));
        assertFalse("addLanEndpoint must NOT be pointed at the Local group",
                lan.contains("SERVER_LOCAL_EVENTLOOP"));
    }

    // ---- manifest / registration -------------------------------------------

    @Test
    public void manifestIsBoundAndRegisters() {
        Ki4LocalServerChannelPatch p = new Ki4LocalServerChannelPatch();
        PatchManifest m = p.manifest();
        assertTrue("manifest must be bound (transform hash + patchId)", m.isBound());
        assertEquals("MCP-KI0004", m.code());
        assertEquals("KI-4", m.kiRef());
        assertEquals("net.minecraft.network.NetworkSystem", m.targetClass());
        assertEquals(PatchManifest.Status.VERIFIED, m.status());
        assertNotNull(m.patchId());
        assertNotNull(m.contentHash());
        // Unsigned by construction (integrity/arming is a separate gate).
        assertNull("KI-4 is unsigned in-code; signature stays null", m.signature());

        CompatDatabase db = new CompatDatabase();
        db.register(p); // must not throw
        assertEquals(1, db.size());
        assertNotNull(db.byPatchId(m.patchId()));
    }

    @Test
    public void contentHashIsStableAndBehaviorBound() {
        // The bound content hash pins the transform's identity (v1 marker).
        assertEquals(PatchManifest.sha256Hex("ki4-localserverchannel-group-v1"),
                new Ki4LocalServerChannelPatch().manifest().contentHash());
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * All LazyLoadBase-typed static fields (event-loop group selectors) that the given
     * method reads via GETSTATIC on the NetworkSystem owner, in program order.
     */
    private static List<String> groupFieldsIn(byte[] classBytes, String method, String desc) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!method.equals(mn.name) || !desc.equals(mn.desc)) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    if (TARGET.equals(f.owner) && FIELD_DESC.equals(f.desc)) {
                        fields.add(f.name);
                    }
                }
            }
            return fields;
        }
        throw new AssertionError("method not found: " + method + desc);
    }

    /** A synthetic NetworkSystem-shaped class: both methods read the eventLoops group. */
    private static byte[] synthNetworkSystem() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "eventLoops", FIELD_DESC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "SERVER_LOCAL_EVENTLOOP", FIELD_DESC, null, null).visitEnd();

        // addLanEndpoint(InetAddress,int): reads eventLoops (NIO) — must stay untouched.
        MethodVisitor lan = cw.visitMethod(Opcodes.ACC_PUBLIC, "addLanEndpoint",
                "(Ljava/net/InetAddress;I)V", null, null);
        lan.visitCode();
        lan.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "eventLoops", FIELD_DESC);
        lan.visitInsn(Opcodes.POP);
        lan.visitInsn(Opcodes.RETURN);
        lan.visitMaxs(1, 3);
        lan.visitEnd();

        // addLocalEndpoint(): reads eventLoops (WRONG) — must be rewritten to Local group.
        MethodVisitor local = cw.visitMethod(Opcodes.ACC_PUBLIC, "addLocalEndpoint",
                "()Ljava/net/SocketAddress;", null, null);
        local.visitCode();
        local.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "eventLoops", FIELD_DESC);
        local.visitInsn(Opcodes.POP);
        local.visitInsn(Opcodes.ACONST_NULL);
        local.visitInsn(Opcodes.ARETURN);
        local.visitMaxs(1, 1);
        local.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** addLocalEndpoint present but reads SERVER_LOCAL_EVENTLOOP already (nothing to fix). */
    private static byte[] synthAlreadyFixed() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "SERVER_LOCAL_EVENTLOOP", FIELD_DESC, null, null).visitEnd();
        MethodVisitor local = cw.visitMethod(Opcodes.ACC_PUBLIC, "addLocalEndpoint",
                "()Ljava/net/SocketAddress;", null, null);
        local.visitCode();
        local.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "SERVER_LOCAL_EVENTLOOP", FIELD_DESC);
        local.visitInsn(Opcodes.POP);
        local.visitInsn(Opcodes.ACONST_NULL);
        local.visitInsn(Opcodes.ARETURN);
        local.visitMaxs(1, 1);
        local.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Load a class's raw bytes from the test classpath, or null if not present. */
    private static byte[] classBytes(String internalName) {
        String res = "/" + internalName + ".class";
        try (InputStream in = Ki4LocalServerChannelPatchTest.class.getResourceAsStream(res)) {
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
