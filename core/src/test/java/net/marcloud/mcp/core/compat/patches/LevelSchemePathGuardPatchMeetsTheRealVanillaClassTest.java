package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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
 * The containment patch, run against the class it is actually going to meet.
 *
 * <p><b>The lesson this test is built around.</b> {@code GlClampToEdgePatch} matched {@code LDC}
 * where javac emits {@code SIPUSH}. Its unit test used a hand-built canary that emitted
 * {@code LDC}, so the test passed, the patch armed, and the transform returned {@code null} on
 * every real load for a whole session. A fixture built from the same construction as the code
 * under test is a mirror, and a mirror reflects nothing.
 *
 * <p>So this file reads the compiled {@code NetHandlerPlayClient.class} &mdash; from
 * {@code client/target/classes} or, failing that, out of the shipped
 * {@code client/target/MCP-1.8.9.jar} &mdash; and drives the patch through the real
 * {@link CompatEngine} against it, asserting through the engine's applied-observable
 * ({@code transformRuns} / {@code targetsChanged} / {@code applyFailures}) that the bytes handed to
 * the JVM actually changed. "Armed" is authorisation; {@code targetsChanged > 0} is evidence.
 *
 * <p>The structural assertions exist because this injection adds a branch, which the pure "did the
 * bytes change" check would not notice. They pin three things a reviewer cannot check by reading:
 * that the guard landed exactly at vanilla's {@code isFile()} gate and loaded the right two
 * locals; that the refusal target is vanilla's OWN not-a-file block, so the patch invents no
 * branch target and no stack map frame; and that not one original instruction was dropped,
 * reordered or rewritten.
 *
 * <p><b>One detail that turned out to matter twice.</b> The vanilla class is compiled with debug
 * info, so {@code LABEL}, {@code LINE} and {@code FRAME} nodes are interleaved between real
 * instructions. Every structural assertion here therefore works on a list of real instructions only
 * ({@link #realInstructions}), never on raw list positions &mdash; as does the patch itself. A
 * matcher that steps with a bare {@code getPrevious()} finds a {@code LineNumberNode} where it
 * expected an {@code ASTORE}, declines the shipped class, and passes every hand-built canary.
 * That is the {@code SIPUSH} failure wearing a different hat.
 *
 * <p><b>On the signer.</b> This file measures the TRANSFORM, so it uses the same trust-everything
 * stand-in the other engine tests use rather than the real baked trust chain. The production arming
 * verdict is not asserted here at all: it lives in
 * {@code net.marcloud.mcp.core.compat.LevelSchemePathGuardPatchArmingTest}, which builds the real
 * {@link CompatEngine} from {@code Compat.defaultDatabase()} with a real
 * {@link net.marcloud.mcp.core.compat.Ed25519PatchSigner} and requires
 * {@code targetsChanged > 0}. Splitting it that way is what keeps each file honest: a stand-in
 * signer here cannot paper over a signature that does not verify, because no signature is involved
 * here.
 */
public class LevelSchemePathGuardPatchMeetsTheRealVanillaClassTest {

    private static final String INTERNAL = "net/minecraft/client/network/NetHandlerPlayClient";
    private static final String OTHER_INTERNAL = "net/minecraft/client/multiplayer/ServerData";

    /** A signer that trusts every bound manifest (same stand-in the other engine tests use). */
    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override public boolean verify(PatchManifest m) { return m != null && m.isBound(); }
        @Override public PatchManifest sign(PatchManifest m, String h) { return m.withTransform(h, "sig"); }
    };

    /**
     * {@code CompatEngine.apply} is package-private, so only its own package can dispatch without a
     * live Instrumentation. This test lives in the {@code patches} sub-package and must still read
     * the engine's own counters &mdash; which is the whole point of the exercise &mdash; so it
     * reaches the real method reflectively instead of re-implementing the dispatch the counters
     * hang off.
     */
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

    /**
     * Locate a compiled vanilla class, from the client module's output first and the shipped jar
     * second. Returns null when neither is present; the caller SKIPS rather than fails, because a
     * test that fails because someone skipped a build step teaches people to skip builds.
     */
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
        assumeTrue("no compiled NetHandlerPlayClient (build the client module, or produce "
                + "MCP-1.8.9.jar)", bytes != null);
        return bytes;
    }

    private static byte[] patchOf(byte[] vanilla) {
        byte[] out = new LevelSchemePathGuardPatch().transform(vanilla);
        assertNotNull("the transform must return patched bytes for the REAL vanilla class; null here "
                + "is the 'armed and did nothing' failure this test exists to catch", out);
        return out;
    }

    /**
     * THE ENGINE TEST. The patch goes through {@link CompatEngine} against the real class, and the
     * engine's own counters must show it changed bytes.
     *
     * <p>This is the assertion that would have caught the {@code SIPUSH} incident: there the
     * transform returned {@code null} on the real class, so "armed" would have been the only word
     * anyone had, and it meant nothing.
     */
    @Test
    public void thePatchChangesTheRealCompiledVanillaClassAndTheEngineCountsIt() throws Exception {
        byte[] vanilla = realVanillaClass();

        LevelSchemePathGuardPatch patch = new LevelSchemePathGuardPatch();
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
    }

    /** The guard sits exactly where the fix belongs: between building the candidate and testing it. */
    @Test
    public void theGuardSitsImmediatelyAboveVanillasIsFileGateAndLoadsTheRightLocals() throws Exception {
        byte[] vanilla = realVanillaClass();
        List<AbstractInsnNode> before = realInstructions(methodOf(vanilla));
        List<AbstractInsnNode> after = realInstructions(methodOf(patchOf(vanilla)));

        int vanillaIsFile = indexOfIsFile(before);
        assertTrue("premise: the vanilla method must have an isFile() gate", vanillaIsFile >= 1);
        assertTrue("premise: nothing sits between vanilla's aload of the candidate and isFile()",
                before.get(vanillaIsFile - 1) instanceof VarInsnNode bv
                        && bv.getOpcode() == Opcodes.ALOAD);
        int candSlot = ((VarInsnNode) before.get(vanillaIsFile - 1)).var;

        int guard = indexOfGuard(after);
        assertTrue("the guard call must be present in the patched method", guard >= 2);
        assertTrue("expected the guard's IFEQ immediately after the call",
                after.get(guard + 1) instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFEQ);
        assertEquals("then vanilla's own aload of the candidate, unchanged",
                before.get(vanillaIsFile - 1).getOpcode(), after.get(guard + 2).getOpcode());
        assertTrue("and vanilla's isFile() right after it",
                after.get(guard + 3) instanceof MethodInsnNode isFile
                        && "isFile".equals(isFile.name) && "()Z".equals(isFile.desc));

        // The guard must load the two locals vanilla just stored: the saves dir and the candidate.
        // Reading the wrong slots would make the check vacuous, so this is not cosmetic.
        int savesSlot = savesSlotOf(before);
        assertTrue("expected aload of the saves slot immediately before the guard call",
                after.get(guard - 2) instanceof VarInsnNode s && s.getOpcode() == Opcodes.ALOAD
                        && s.var == savesSlot);
        assertTrue("expected aload of the candidate slot immediately before the guard call",
                after.get(guard - 1) instanceof VarInsnNode c && c.getOpcode() == Opcodes.ALOAD
                        && c.var == candSlot);
    }

    /**
     * The refusal target is vanilla's OWN not-a-file block, which is what keeps the method
     * verifiable without recomputing frames: no new branch target means no new stack map frame.
     */
    @Test
    public void theRefusalTargetIsVanillasOwnNotAFileBlockSoNoNewFrameIsNeeded() throws Exception {
        byte[] vanilla = realVanillaClass();
        List<AbstractInsnNode> after = realInstructions(methodOf(patchOf(vanilla)));
        int guard = indexOfGuard(after);
        assumeTrue("the guard is not in the patched method", guard >= 0);

        JumpInsnNode refusal = (JumpInsnNode) after.get(guard + 1);
        AbstractInsnNode vanillaGate = after.get(guard + 4);
        assertTrue("expected vanilla's own IFEQ right after isFile()",
                vanillaGate instanceof JumpInsnNode gate && gate.getOpcode() == Opcodes.IFEQ);
        assertSame("the guard must jump to the block vanilla already had; a NEW label would need a "
                + "stack map frame the patch cannot compute without loading classes inside a "
                + "transformer", refusal.label, ((JumpInsnNode) vanillaGate).label);
    }

    /**
     * Nothing of vanilla is lost or reordered. An injection that quietly dropped or reshuffled an
     * instruction would still pass "targetsChanged &gt; 0", so the ORIGINAL sequence is pinned as a
     * subsequence of the patched one.
     */
    @Test
    public void everyOriginalInstructionSurvivesInOrderAndOnlyTheGuardIsAdded() throws Exception {
        byte[] vanilla = realVanillaClass();
        List<String> before = describeAll(realInstructions(methodOf(vanilla)));
        List<String> after = describeAll(realInstructions(methodOf(patchOf(vanilla))));

        assertEquals("exactly four instructions may be added: two aload, one invokestatic, one ifeq",
                before.size() + 4, after.size());

        int cursor = 0;
        for (String op : before) {
            int seen = indexOf(after, op, cursor);
            assertTrue("original instruction [" + op + "] vanished or was reordered at index " + cursor,
                    seen >= 0);
            cursor = seen + 1;
        }
    }

    /** Frames are carried through unchanged: the patch adds a branch but no new block entry. */
    @Test
    public void thePatchAddsNoStackMapFrame() throws Exception {
        byte[] vanilla = realVanillaClass();
        assertEquals("ClassWriter(0) writes the stored frames through; a new one would mean the "
                + "transform needed COMPUTE_FRAMES, which loads classes inside a transformer",
                countFrames(methodOf(vanilla)), countFrames(methodOf(patchOf(vanilla))));
    }

    /** Idempotent, and safe on a class it does not recognise. */
    @Test
    public void thePatchIsIdempotentAndDeclinesForeignClasses() throws Exception {
        byte[] vanilla = realVanillaClass();
        assertNull("a second application must decline, so the guard cannot be stacked twice",
                new LevelSchemePathGuardPatch().transform(patchOf(vanilla)));
        assertNull("and junk input must be a no-op, never a throw",
                new LevelSchemePathGuardPatch().transform(new byte[]{0, 1, 2, 3}));

        byte[] other = locate(OTHER_INTERNAL);
        assumeTrue("no compiled " + OTHER_INTERNAL, other != null);
        assertNull("a class without the anchored shape must be left untouched",
                new LevelSchemePathGuardPatch().transform(other));
    }


    // ---- helpers ------------------------------------------------------------

    /** Every real instruction (opcode &gt;= 0) of the method, in order, with debug nodes dropped. */
    private static List<AbstractInsnNode> realInstructions(MethodNode mn) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                out.add(n);
            }
        }
        return out;
    }

    private static MethodNode methodOf(byte[] classBytes) {
        assumeTrue("no class bytes to parse", classBytes != null);
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (LevelSchemePathGuardPatch.METHOD_NAME.equals(mn.name)
                    && LevelSchemePathGuardPatch.METHOD_DESC.equals(mn.desc)) {
                return mn;
            }
        }
        throw new AssertionError("no handleResourcePack in the parsed class");
    }

    private static int indexOfGuard(List<AbstractInsnNode> ops) {
        for (int i = 0; i < ops.size(); i++) {
            AbstractInsnNode n = ops.get(i);
            if (n.getOpcode() == Opcodes.INVOKESTATIC && n instanceof MethodInsnNode call
                    && LevelSchemePathGuardPatch.GUARD_OWNER.equals(call.owner)
                    && LevelSchemePathGuardPatch.GUARD_METHOD.equals(call.name)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfIsFile(List<AbstractInsnNode> ops) {
        for (int i = 0; i < ops.size(); i++) {
            AbstractInsnNode n = ops.get(i);
            if (n.getOpcode() == Opcodes.INVOKEVIRTUAL && n instanceof MethodInsnNode call
                    && "java/io/File".equals(call.owner) && "isFile".equals(call.name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The local vanilla stores {@code new File(mcDataDir, "saves")} into: the first
     * {@code File.<init>} after the {@code level://} branch, whose argument list ends in the literal
     * {@code "saves"}. -1 when the shape is not there.
     */
    private static int savesSlotOf(List<AbstractInsnNode> ops) {
        for (int i = 0; i < ops.size() - 2; i++) {
            AbstractInsnNode n = ops.get(i);
            if (n.getOpcode() == Opcodes.INVOKESPECIAL && n instanceof MethodInsnNode ctor
                    && "java/io/File".equals(ctor.owner) && "<init>".equals(ctor.name)
                    && ops.get(i - 1) instanceof AbstractInsnNode lit
                    && lit.getOpcode() == Opcodes.LDC
                    && "saves".equals(((org.objectweb.asm.tree.LdcInsnNode) lit).cst)
                    && ops.get(i + 1) instanceof VarInsnNode stored
                    && stored.getOpcode() == Opcodes.ASTORE) {
                return stored.var;
            }
        }
        return -1;
    }

    private static int countFrames(MethodNode mn) {
        int frames = 0;
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof FrameNode) {
                frames++;
            }
        }
        return frames;
    }

    /**
     * One string per real instruction, stable enough to compare before and after. asm-util's
     * {@code Printer.OPCODES} is not on the core classpath, so this uses the raw opcode plus the
     * operand that identifies the instruction.
     */
    private static List<String> describeAll(List<AbstractInsnNode> ops) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode n : ops) {
            out.add(describe(n));
        }
        return out;
    }

    private static String describe(AbstractInsnNode n) {
        return switch (n.getType()) {
            case AbstractInsnNode.VAR_INSN -> n.getOpcode() + ":var" + ((VarInsnNode) n).var;
            case AbstractInsnNode.METHOD_INSN -> n.getOpcode() + ":"
                    + ((MethodInsnNode) n).owner + "." + ((MethodInsnNode) n).name
                    + ((MethodInsnNode) n).desc;
            case AbstractInsnNode.JUMP_INSN -> n.getOpcode() + ":jump";
            default -> String.valueOf(n.getOpcode());
        };
    }

    private static int indexOf(List<String> ops, String op, int from) {
        for (int i = from; i < ops.size(); i++) {
            if (ops.get(i).equals(op)) {
                return i;
            }
        }
        return -1;
    }
}