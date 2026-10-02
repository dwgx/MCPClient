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
 * SEC-2 run against the class it is actually going to meet.
 *
 * <p><b>Why this file exists at all.</b> {@code GlClampToEdgePatch} matched {@code LDC} where javac
 * emits {@code SIPUSH}: its unit test used a hand-built canary that emitted {@code LDC}, so the
 * test passed, the patch armed, and the transform returned {@code null} on every real load. A
 * fixture built from the same construction as the code under test is a mirror, and a mirror
 * reflects nothing. So this file reads the compiled {@code ResourcePackRepository.class} and drives
 * the patch through the real {@link CompatEngine}, asserting through the engine's applied
 * observable ({@code transformRuns} / {@code targetsChanged} / {@code applyFailures}) that the
 * bytes handed to the JVM actually changed.
 *
 * <p><b>The second reason it exists: verification.</b> "The bytes differ" is a much weaker claim
 * than "the class still loads". ASM will happily emit a class that the JVM refuses with
 * {@code VerifyError}, and the first version of this patch did exactly that &mdash; it branched
 * from the method head to vanilla's own terminal {@code RETURN}, whose recorded frame declares
 * {@code locals[1] = List, locals[2] = int}, which is not an extension of the frame the branch
 * arrives with. The bytes differed; the class was unloadable. {@link #thePatchedClassPassesTheJvmBytecodeVerifier()}
 * is the assertion that caught it and is the reason it cannot come back.
 *
 * <p><b>On the signer.</b> This file measures the TRANSFORM, so it uses the same
 * trust-everything stand-in the other engine tests use. The production arming verdict is not
 * asserted here; it lives in {@code ServerResourcePackDirGuardPatchArmingTest}, which builds the
 * real engine from {@code Compat#defaultDatabase()} with a real
 * {@link net.marcloud.mcp.core.compat.Ed25519PatchSigner}.
 */
public class ServerResourcePackDirGuardPatchMeetsTheRealVanillaClassTest {

    /** JVM internal name (slashes) &mdash; what the engine's dispatch index and the class file use. */
    private static final String INTERNAL = "net/minecraft/client/resources/ResourcePackRepository";

    /** The same class in binary form (dots) &mdash; what {@link Class#forName} wants. */
    private static final String BINARY = "net.minecraft.client.resources.ResourcePackRepository";

    /** A signer that trusts every bound manifest (same stand-in the other engine tests use). */
    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override public boolean verify(PatchManifest m) { return m != null && m.isBound(); }
        @Override public PatchManifest sign(PatchManifest m, String h) { return m.withTransform(h, "sig"); }
    };

    /** {@code CompatEngine.apply} is package-private; this test lives in the sub-package. */
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

    private static byte[] realVanillaClass() throws IOException {
        byte[] bytes = locate(INTERNAL);
        assumeTrue("no compiled ResourcePackRepository (build the client module, or produce "
                + "MCP-1.8.9.jar)", bytes != null);
        return bytes;
    }

    private static byte[] patchOf(byte[] vanilla) {
        byte[] out = new ServerResourcePackDirGuardPatch().transform(vanilla);
        assertNotNull("the transform must return patched bytes for the REAL vanilla class; null "
                + "here is the 'armed and did nothing' failure this test exists to catch", out);
        return out;
    }

    private static MethodNode methodOf(byte[] classBytes, String name, String desc) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        throw new AssertionError("method " + name + desc + " not present");
    }

    /** Real instructions only: the compiled vanilla class interleaves LABEL / LINE / FRAME nodes. */
    private static List<AbstractInsnNode> realInstructions(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * THE ENGINE TEST: the patch goes through {@link CompatEngine} against the real class and the
     * engine's own counters must show it changed bytes.
     */
    @Test
    public void thePatchChangesTheRealCompiledVanillaClassAndTheEngineCountsIt() throws Exception {
        byte[] vanilla = realVanillaClass();

        ServerResourcePackDirGuardPatch patch = new ServerResourcePackDirGuardPatch();
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
        assertEquals("", r.lastApplyError());
        assertFalse("and the bytes really must differ from vanilla's", Arrays.equals(vanilla, out));
    }

    /**
     * THE LOADABILITY TEST. Runs the real JVM bytecode verifier over the patched class.
     *
     * <p>{@code defineClass} links the class, which is what actually runs verification. This is
     * the assertion that would have caught the first version of this patch, which produced a class
     * whose bytes differed and which the JVM rejected outright.
     */
    @Test
    public void thePatchedClassPassesTheJvmBytecodeVerifier() throws Exception {
        byte[] vanilla = realVanillaClass();
        final byte[] patched = patchOf(vanilla);

        // The verifier resolves types mentioned in the method bodies it checks, so linking this
        // class needs its neighbours (IResourcePack, IProgressUpdate, FileUtils, ...). They are
        // served from the same compiled vanilla output the class itself came from, so this runs
        // the real linkage against the real client, not against a stripped-down fixture.
        Path vanillaRoot = compiledVanillaRoot();
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

        // initialize=false on purpose: bytecode VERIFICATION runs at link time, which is what this
        // test is about, whereas <clinit> would drag in the client's log4j setup, which is not on
        // core's test classpath and has nothing to do with whether the patched method verifies.
        Class<?> loaded = Class.forName(BINARY, false, cl);
        assertNotNull("the patched class must define", loaded);
        loaded.getDeclaredMethod(ServerResourcePackDirGuardPatch.METHOD_NAME);
    }

    /**
     * The directory holding the compiled vanilla classes, or the test is skipped.
     */
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
     * The guard lands at the very head of the method, on the field vanilla never creates, and
     * refuses to the patch's OWN block &mdash; not to some mid-method instruction.
     */
    @Test
    public void theGuardSitsAtTheMethodHeadOnTheFieldVanillaNeverCreates() throws Exception {
        byte[] vanilla = realVanillaClass();
        List<AbstractInsnNode> after =
                realInstructions(methodOf(patchOf(vanilla), ServerResourcePackDirGuardPatch.METHOD_NAME,
                        ServerResourcePackDirGuardPatch.METHOD_DESC));

        assertTrue("premise: the guard prologue must be at least four instructions", after.size() >= 4);
        assertTrue("the guard must load this",
                after.get(0) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD);
        assertTrue("then read the field nothing ever creates",
                after.get(1) instanceof FieldInsnNode f
                        && f.getOpcode() == Opcodes.GETFIELD
                        && ServerResourcePackDirGuardPatch.DIR_FIELD.equals(f.name)
                        && "Ljava/io/File;".equals(f.desc));
        assertTrue("then test it is a directory",
                after.get(2) instanceof MethodInsnNode m
                        && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/io/File".equals(m.owner)
                        && "isDirectory".equals(m.name)
                        && "()Z".equals(m.desc));
        assertTrue("and branch away when it is not",
                after.get(3) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFEQ);
    }

    /**
     * The refusal block carries its own {@code F_FULL} frame declaring exactly the one local that
     * is live at the branch. This is the assertion tied to the {@code VerifyError}: a branch from
     * the method head arrives with slots 1 and 2 as {@code top}, and vanilla's own recorded frame at
     * its terminal return declares them as {@code List} and {@code int}.
     */
    @Test
    public void theRefusalBlockDeclaresItsOwnFrameWithOnlyTheLiveLocal() throws Exception {
        byte[] vanilla = realVanillaClass();
        MethodNode patched = methodOf(patchOf(vanilla), ServerResourcePackDirGuardPatch.METHOD_NAME,
                ServerResourcePackDirGuardPatch.METHOD_DESC);

        FrameNode refusalFrame = null;
        for (AbstractInsnNode n = patched.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof FrameNode f && f.type == Opcodes.F_FULL && f.local.size() == 1) {
                refusalFrame = f;
            }
        }
        assertNotNull("the refusal block must declare its own F_FULL frame; without one the branch "
                + "target has no recorded frame and the class does not verify", refusalFrame);
        assertEquals("and it must declare exactly one local", 0, refusalFrame.stack.size());
        assertEquals("the live local at the branch is the receiver", INTERNAL,
                String.valueOf(refusalFrame.local.get(0)));
    }

    /**
     * Not one of vanilla's instructions may be dropped, reordered or rewritten. A rewrite that
     * changed bytes for some other reason would still satisfy the counters above.
     */
    @Test
    public void everyVanillaInstructionSurvivesInOrder() throws Exception {
        byte[] vanilla = realVanillaClass();
        MethodNode before = methodOf(vanilla, ServerResourcePackDirGuardPatch.METHOD_NAME,
                ServerResourcePackDirGuardPatch.METHOD_DESC);
        MethodNode after = methodOf(patchOf(vanilla), ServerResourcePackDirGuardPatch.METHOD_NAME,
                ServerResourcePackDirGuardPatch.METHOD_DESC);

        List<String> b = new ArrayList<>();
        for (AbstractInsnNode n : realInstructions(before)) {
            b.add(n.getClass().getSimpleName() + ":" + n.getOpcode());
        }
        List<String> a = new ArrayList<>();
        for (AbstractInsnNode n : realInstructions(after)) {
            a.add(n.getClass().getSimpleName() + ":" + n.getOpcode());
        }

        int idx = 0;
        for (String s : a) {
            if (idx < b.size() && b.get(idx).equals(s)) {
                idx++;
            }
        }
        assertEquals("every vanilla instruction must survive, in its original order", b.size(), idx);
    }

    /** Re-applying to already-patched bytes must decline, so the guard cannot be stacked. */
    @Test
    public void theGuardIsNotStackedTwice() throws Exception {
        byte[] vanilla = realVanillaClass();
        ServerResourcePackDirGuardPatch patch = new ServerResourcePackDirGuardPatch();

        byte[] once = patchOf(vanilla);
        assertNull("applying the already-patched bytes again must yield null (unchanged): the guard "
                + "cannot be injected twice", patch.transform(once));
    }

    /** The transform must decline a class it does not recognise rather than rewrite it blindly. */
    @Test
    public void theTransformDeclinesBytesItDoesNotRecognise() {
        ServerResourcePackDirGuardPatch patch = new ServerResourcePackDirGuardPatch();

        assertNull("null input", patch.transform(null));
        assertNull("empty input", patch.transform(new byte[0]));
        assertNull("garbage that is not a classfile", patch.transform(new byte[] {1, 2, 3, 4}));
    }
}