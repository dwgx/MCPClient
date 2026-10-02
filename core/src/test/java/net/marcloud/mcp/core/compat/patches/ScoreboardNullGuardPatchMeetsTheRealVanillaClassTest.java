package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.marcloud.mcp.core.compat.CompatDatabase;
import net.marcloud.mcp.core.compat.CompatEngine;
import net.marcloud.mcp.core.compat.PatchManifest;
import net.marcloud.mcp.core.compat.PatchSigner;

/**
 * SEC-3 run against the class it is actually going to meet.
 *
 * <p>Same two lessons as {@code ServerResourcePackDirGuardPatchMeetsTheRealVanillaClassTest}, both
 * of which this file is built around: a hand-built fixture is a mirror and reflects nothing (the
 * {@code GlClampToEdgePatch} {@code SIPUSH} failure), and "the bytes differ" is a much weaker claim
 * than "the class still loads" (the {@code VerifyError} that cost SEC-2 its first injection
 * shape). So this reads the compiled {@code Scoreboard.class}, drives the patch through the real
 * {@link CompatEngine}, and runs the real JVM verifier over the result.
 */
public class ScoreboardNullGuardPatchMeetsTheRealVanillaClassTest {

    private static final String INTERNAL = "net/minecraft/scoreboard/Scoreboard";
    private static final String BINARY = "net.minecraft.scoreboard.Scoreboard";

    /** A signer that trusts every bound manifest (same stand-in the other engine tests use). */
    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override public boolean verify(PatchManifest m) { return m != null && m.isBound(); }
        @Override public PatchManifest sign(PatchManifest m, String h) { return m.withTransform(h, "sig"); }
    };

    private static final Method ENGINE_APPLY = engineApply();

    private static Method engineApply() {
        try {
            Method m = CompatEngine.class.getDeclaredMethod("apply", String.class, byte[].class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("CompatEngine.apply(String, byte[]) is gone; the engine's "
                    + "measurement path has moved and this test must be updated, not deleted", e);
        }
    }

    private static byte[] engineApply(CompatEngine engine, String internalName, byte[] bytes)
            throws ReflectiveOperationException {
        Object out = ENGINE_APPLY.invoke(engine, internalName, bytes);
        return out == null ? null : (byte[]) out;
    }

    private static byte[] realVanillaClass() throws IOException {
        byte[] bytes = locate(INTERNAL);
        assumeTrue("no compiled Scoreboard (build the client module, or produce MCP-1.8.9.jar)",
                bytes != null);
        return bytes;
    }

    private static byte[] locate(String internalName) throws IOException {
        String entry = internalName + ".class";
        for (Path root : new Path[] {Path.of("client/target/classes"), Path.of("../client/target/classes")}) {
            Path p = root.resolve(entry);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        }
        for (Path jar : new Path[] {Path.of("client/target/MCP-1.8.9.jar"), Path.of("../client/target/MCP-1.8.9.jar")}) {
            if (Files.isRegularFile(jar)) {
                try (JarFile jf = new JarFile(jar.toFile())) {
                    var e = jf.getJarEntry(entry);
                    if (e != null) {
                        try (InputStream in = jf.getInputStream(e)) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static byte[] patchOf(byte[] vanilla) {
        byte[] out = new ScoreboardNullGuardPatch().transform(vanilla);
        assertNotNull("the transform must return patched bytes for the REAL vanilla class; null "
                + "here is the 'armed and did nothing' failure this test exists to catch", out);
        return out;
    }

    private static MethodNode methodOf(byte[] classBytes, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name)) {
                return mn;
            }
        }
        throw new AssertionError("method " + name + " not present");
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                out.add(n);
            }
        }
        return out;
    }

    /** THE ENGINE TEST: the engine's own counters must show it changed bytes. */
    @Test
    public void thePatchChangesTheRealCompiledVanillaClassAndTheEngineCountsIt() throws Exception {
        byte[] vanilla = realVanillaClass();

        ScoreboardNullGuardPatch patch = new ScoreboardNullGuardPatch();
        CompatDatabase db = new CompatDatabase();
        db.register(patch);
        CompatEngine engine = CompatEngine.build(db, TRUSTING, null);
        String id = patch.manifest().patchId();
        assertTrue("premise: the patch must arm under a trusting signer",
                engine.armedPatchIds().contains(id));

        byte[] out = engineApply(engine, INTERNAL, vanilla);

        assertNotNull("the engine must hand back patched bytes for the REAL vanilla class", out);
        CompatEngine.ApplyRecord r = engine.applyRecord(id);
        assertEquals("the transform ran once, for this class", 1, r.transformRuns());
        assertEquals("THE POINT: the engine handed the JVM different bytes, and must say so", 1,
                r.targetsChanged());
        assertEquals("and it did not throw to get there", 0, r.applyFailures());
        assertFalse("and the bytes really must differ from vanilla's", Arrays.equals(vanilla, out));
    }

    /** THE LOADABILITY TEST: run the real JVM bytecode verifier over the patched class. */
    @Test
    public void thePatchedClassPassesTheJvmBytecodeVerifier() throws Exception {
        byte[] vanilla = realVanillaClass();
        final byte[] patched = patchOf(vanilla);
        final Path vanillaRoot = compiledVanillaRoot();

        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String n) throws ClassNotFoundException {
                try {
                    if (n.equals(BINARY)) {
                        return defineClass(n, patched, 0, patched.length);
                    }
                    Path p = vanillaRoot.resolve(n.replace('.', '/') + ".class");
                    if (Files.isRegularFile(p)) {
                        byte[] b = Files.readAllBytes(p);
                        return defineClass(n, b, 0, b.length);
                    }
                    throw new ClassNotFoundException(n);
                } catch (IOException e) {
                    throw new ClassNotFoundException(n, e);
                }
            }
        };

        // initialize=false: verification runs at link time, which is the point; <clinit> would drag
        // in the client's logger, which is not on core's test classpath.
        Class<?> loaded = Class.forName(BINARY, false, cl);
        assertNotNull("the patched class must define", loaded);
        Class<?> team = Class.forName("net.minecraft.scoreboard.ScorePlayerTeam", false, cl);
        Class<?> objective = Class.forName("net.minecraft.scoreboard.ScoreObjective", false, cl);
        loaded.getDeclaredMethod("removeTeam", team);
        loaded.getDeclaredMethod("removeObjective", objective);
    }

    private static Path compiledVanillaRoot() {
        for (Path root : new Path[] {Path.of("client/target/classes"), Path.of("../client/target/classes")}) {
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        assumeTrue("no compiled client classes to link the patched class against", false);
        return Path.of(".");
    }

    /**
     * BOTH methods must be guarded, each with the null test on the parameter and the map field it
     * removes from. A patch that guarded only one would still change bytes, which is exactly the
     * half-measure this assertion exists to reject.
     */
    @Test
    public void bothGuardedMethodsGetANullTestOnTheirParameter() throws Exception {
        byte[] vanilla = realVanillaClass();
        MethodNode patched;

        patched = methodOf(patchOf(vanilla), "removeObjective");
        assertGuardPrologue(patched, "scoreObjectives", "removeObjective");

        patched = methodOf(patchOf(vanilla), "removeTeam");
        assertGuardPrologue(patched, "teams", "removeTeam");
    }

    private static void assertGuardPrologue(MethodNode m, String mapField, String label) {
        List<AbstractInsnNode> insns = realInstructions(m);
        assertTrue(label + ": preamble too short", insns.size() >= 4);
        assertTrue(label + ": the guard must load the parameter",
                insns.get(0) instanceof VarInsnNode v
                        && v.getOpcode() == Opcodes.ALOAD
                        && v.var == 1);
        assertTrue(label + ": the guard must branch on null",
                insns.get(1) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFNULL);
        assertTrue(label + ": then vanilla's own body follows, loading this",
                insns.get(2) instanceof VarInsnNode v2
                        && v2.getOpcode() == Opcodes.ALOAD
                        && v2.var == 0);
        assertTrue(label + ": reading the map it removes from",
                insns.get(3) instanceof FieldInsnNode f
                        && f.getOpcode() == Opcodes.GETFIELD
                        && mapField.equals(f.name)
                        && "Ljava/util/Map;".equals(f.desc));
    }

    /** The refusal block declares its own frame naming exactly the two live locals. */
    @Test
    public void eachRefusalBlockDeclaresItsOwnFrame() throws Exception {
        byte[] patched = patchOf(realVanillaClass());
        for (String method : new String[] {"removeObjective", "removeTeam"}) {
            MethodNode m = methodOf(patched, method);
            FrameNode refusal = null;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof FrameNode f && f.type == Opcodes.F_FULL && f.local.size() == 2
                        && f.stack.isEmpty()) {
                    refusal = f;
                }
            }
            assertNotNull(method + ": the refusal block must declare its own F_FULL frame", refusal);
            assertEquals(method + ": locals are this and the parameter", 2, refusal.local.size());
            assertEquals(INTERNAL, String.valueOf(refusal.local.get(0)));
        }
    }

    /** Not one vanilla instruction may be dropped, reordered or rewritten, in either method. */
    @Test
    public void everyVanillaInstructionSurvivesInOrder() throws Exception {
        byte[] vanilla = realVanillaClass();
        byte[] patched = patchOf(vanilla);
        for (String method : new String[] {"removeObjective", "removeTeam"}) {
            List<String> b = shapes(realInstructions(methodOf(vanilla, method)));
            List<String> a = shapes(realInstructions(methodOf(patched, method)));
            int idx = 0;
            for (String s : a) {
                if (idx < b.size() && b.get(idx).equals(s)) {
                    idx++;
                }
            }
            assertEquals(method + ": every vanilla instruction must survive in order", b.size(), idx);
        }
    }

    private static List<String> shapes(List<AbstractInsnNode> insns) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode n : insns) {
            out.add(n.getClass().getSimpleName() + ":" + n.getOpcode());
        }
        return out;
    }

    /** Re-applying to already-patched bytes must decline, so neither guard can be stacked. */
    @Test
    public void theGuardsAreNotStackedTwice() throws Exception {
        byte[] vanilla = realVanillaClass();
        ScoreboardNullGuardPatch patch = new ScoreboardNullGuardPatch();
        assertNull("re-applying must yield null (unchanged)", patch.transform(patchOf(vanilla)));
    }

    /** The transform must decline a class it does not recognise. */
    @Test
    public void theTransformDeclinesBytesItDoesNotRecognise() {
        ScoreboardNullGuardPatch patch = new ScoreboardNullGuardPatch();
        assertNull("null input", patch.transform(null));
        assertNull("empty input", patch.transform(new byte[0]));
        assertNull("garbage that is not a classfile", patch.transform(new byte[] {1, 2, 3, 4}));
    }

    /**
     * The accessor calls the guards are keyed on must really be the ones the vanilla methods are
     * defined by. If either vanished, the patch would decline &mdash; and this says so in words
     * rather than leaving a silent skip.
     */
    @Test
    public void theAccessorsThePatchKeysOnAreTheOnesVanillaCalls() throws Exception {
        byte[] vanilla = realVanillaClass();
        assertTrue("premise: removeObjective is defined by ScoreObjective.getName()",
                calls(methodOf(vanilla, "removeObjective"), "net/minecraft/scoreboard/ScoreObjective",
                        "getName"));
        assertTrue("premise: removeTeam is defined by ScorePlayerTeam.getRegisteredName()",
                calls(methodOf(vanilla, "removeTeam"), "net/minecraft/scoreboard/ScorePlayerTeam",
                        "getRegisteredName"));
    }

    private static boolean calls(MethodNode m, String owner, String name) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)) {
                return true;
            }
        }
        return false;
    }
}