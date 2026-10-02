package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The clamp patch, run against the class it is actually going to meet.
 *
 * <p><b>Why this test exists at all.</b> The original test built a canary class by hand and emitted
 * the constant with {@code visitLdcInsn} -- which always produces {@code LDC}. The patch matched
 * {@code LDC}. The test therefore passed, permanently, against a fixture the compiler can never
 * produce for this constant.
 *
 * <p>The real answer: {@code GL_CLAMP} is {@code 0x2900} = 10496, which fits a signed 16-bit
 * immediate, so {@code javac} emits {@code SIPUSH}. The patch never matched the real class, returned
 * {@code null}, was reported as armed because its signature verifies over its own source, and did
 * nothing. Nobody could see it, because the only test used a fixture that agreed with the bug.
 *
 * <p><b>What this test does instead.</b> It reads the actual compiled
 * {@code client/target/classes/net/minecraft/client/renderer/texture/TextureUtil.class} and asserts
 * the patch rewrites it. A hand-built canary is still used below, but only to pin the LDC encoding
 * as one of the two the patch accepts -- never as the sole proof.
 *
 * <p>This is the general lesson the repository already has in its own vocabulary: a test whose
 * expected value comes from the same construction as the code under test is a mirror, and a mirror
 * reflects nothing.
 */
public class GlClampToEdgePatchMeetsTheRealVanillaClassTest {

    private static final int GL_CLAMP = 0x2900;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final String CLASS_PATH =
            "net/minecraft/client/renderer/texture/TextureUtil.class";

    /**
     * Locate the compiled vanilla class. Skipped, never failed, when the client module has not been
     * built -- a test that fails because someone skipped a build step teaches people to skip builds.
     */
    private static byte[] realVanillaClass() {
        for (Path root : new Path[] {Path.of("client/target/classes"), Path.of("../client/target/classes")}) {
            Path p = root.resolve(CLASS_PATH);
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    throw new AssertionError("read " + p, e);
                }
            }
        }
        return null;
    }

    /** Every int constant pushed in {@code setTextureClamped}, by value. */
    private static List<Integer> pushedConstants(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        List<Integer> out = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if (!"setTextureClamped".equals(mn.name)) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof IntInsnNode i && i.getOpcode() == org.objectweb.asm.Opcodes.SIPUSH) {
                    out.add(i.operand);
                } else if (insn instanceof LdcInsnNode l && l.cst instanceof Integer v) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    @Test
    public void thePatchRewritesTheRealCompiledVanillaClass() {
        byte[] vanilla = realVanillaClass();
        assumeTrue("client/target/classes not built; run ./mvnw -pl client compile", vanilla != null);

        // State the premise, so a future javac that changes the encoding fails HERE with a reason
        // rather than leaving the patch silently inert again.
        List<Integer> before = pushedConstants(vanilla);
        assertTrue("setTextureClamped should push GL_CLAMP, found " + before,
                before.contains(GL_CLAMP));

        byte[] patched = new GlClampToEdgePatch().transform(vanilla);
        assertNotNull("THE POINT OF THIS TEST: the patch returned null on the real vanilla class, "
                + "so it armed and did nothing. If this goes null again, the encoding assumption "
                + "in the comment is wrong.", patched);

        List<Integer> after = pushedConstants(patched);
        assertTrue("GL_CLAMP must be gone, found " + after, !after.contains(GL_CLAMP));
        assertTrue("GL_CLAMP_TO_EDGE must be present, found " + after,
                after.contains(GL_CLAMP_TO_EDGE));
        assertEquals("and nothing else may change: same number of int constants pushed",
                before.size(), after.size());
    }

    @Test
    public void thePatchIsIdempotentAndSafeOnASecondPass() {
        byte[] vanilla = realVanillaClass();
        assumeTrue("client/target/classes not built", vanilla != null);

        byte[] once = new GlClampToEdgePatch().transform(vanilla);
        assertNotNull(once);
        assertNull("a second pass has nothing left to rewrite, and saying so is the contract",
                new GlClampToEdgePatch().transform(once));
    }
}
