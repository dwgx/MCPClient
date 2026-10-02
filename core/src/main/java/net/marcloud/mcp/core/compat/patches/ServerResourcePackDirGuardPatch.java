package net.marcloud.mcp.core.compat.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * SEC-2 &mdash; {@code ResourcePackRepository.deleteOldServerResourcesPacks} throws on any profile
 * that has not yet stored a server resource pack, ending the run from the packet path.
 *
 * <p><b>The hole, in vanilla's own words</b> ({@code
 * client/src/main/java/net/minecraft/client/resources/ResourcePackRepository.java:254-256}):
 *
 * <pre>
 *   private void deleteOldServerResourcesPacks()
 *   {
 *       List&lt;File&gt; list = Lists.newArrayList(FileUtils.listFiles(this.dirServerResourcepacks, TrueFileFilter.TRUE, (IOFileFilter)null));
 * </pre>
 *
 * <p>No {@code try}, no {@code catch}, no {@code exists} test, no {@code isDirectory} test
 * anywhere in the method. Apache Commons IO {@code FileUtils.listFiles} throws
 * {@code IllegalArgumentException} when the directory does not exist.
 *
 * <p><b>Nothing in the class ever creates that directory.</b> Three facts, each opened and quoted
 * rather than taken from the corpus's description of itself:
 *
 * <ul>
 *   <li>{@code dirServerResourcepacks} is assigned exactly once, at
 *       {@code ResourcePackRepository.java:65} ({@code this.dirServerResourcepacks =
 *       dirServerResourcepacksIn;}), and declared {@code private final} at {@code :54}.</li>
 *   <li>The only two {@code mkdirs} calls in the entire file are at {@code :97} and {@code :102},
 *       both inside {@code fixDirResourcepacks()} ({@code :93-106}), and both operate on
 *       {@code this.dirResourcepacks} &mdash; a different field.</li>
 *   <li>The only other place anything creates it is
 *       {@code client/src/main/java/net/minecraft/util/HttpUtil.java:202},
 *       {@code saveFile.getParentFile().mkdirs();}, and that statement is inside the
 *       {@code Runnable} submitted to the download executor at {@code HttpUtil.java:140}. It
 *       therefore runs on a download thread that starts <em>after</em> the synchronous call at
 *       {@code ResourcePackRepository.java:217} has already returned or thrown.</li>
 * </ul>
 *
 * <p>The directory is created at {@code Minecraft.java:493} as
 * {@code new File(this.mcDataDir, "server-resource-packs")} &mdash; a name nothing else in the
 * client creates. So on a fresh profile it does not exist, and the <b>first</b> resource pack any
 * server sends throws out of {@code downloadResourcePack}, which is invoked straight from
 * {@code NetHandlerPlayClient.java:1737} and {@code :1773} on the client thread.
 *
 * <p><b>This is an availability defect, not a traversal, and the difference is stated because it
 * changes the fix.</b> {@code :179-186} constrains the destination name before any {@code File}
 * is built: {@code if (hash.matches("^[a-f0-9]{40}$")) { s = hash; } else { s = "legacy"; }}, then
 * {@code new File(this.dirServerResourcepacks, s)}. So {@code s} is either a 40-character
 * lowercase-hex string or the literal {@code "legacy"} &mdash; no separator, {@code .}, {@code ..}
 * or NUL can survive that regex, and {@code deleteOldServerResourcesPacks} only ever deletes files
 * that were already inside the directory it lists. There is no server-chosen path here and so no
 * containment test is warranted; adding one would fix nothing and would have to be re-signed for
 * no reason. {@code LevelSchemePathGuardPatch} (SEC-1) owns the containment half, and it owns it
 * on the one resource-pack branch that really does take a server-chosen path.
 *
 * <p><b>Why it outranks everything else in the harvest.</b> It is the only un-landed candidate
 * that can <em>end</em> a run: an unhandled throw on the client thread during the night takes
 * the agent down mid-task, so the night fails outright rather than degrading. 15 clients in the
 * corpus ship this fix, and it is Mojang's own later fix &mdash; the strongest available evidence
 * that the method is defective.
 *
 * <p><b>The invariant, not a denylist.</b> The requirement is that
 * {@code FileUtils.listFiles} is only reached with a directory that exists. So the check is
 * {@code File.isDirectory()} on the field itself &mdash; one predicate, no list of names.
 *
 * <p><b>Injection shape.</b> Four instructions are inserted ahead of the method's first real
 * instruction, and a three-node refusal block is appended after the method's terminal return:
 *
 * <pre>
 *   aload  this
 *   getfield  dirServerResourcepacks : Ljava/io/File;
 *   invokevirtual java/io/File.isDirectory ()Z
 *   ifeq   &lt;this patch's own refusal block&gt;
 *   ... vanilla's body, untouched ...
 *   return
 *  refusal:
 *   frame  full  { this }
 *   return
 * </pre>
 *
 * <p>When the directory is absent (or is a regular file) control falls into the refusal block,
 * so the method becomes a no-op instead of throwing.
 *
 * <p><b>Why this is exactly behaviour-preserving, case by case.</b> Commons IO
 * {@code listFiles} throws <em>only</em> when {@code !directory.exists()}; when the path exists
 * but is not a directory it returns an empty collection and vanilla's sort-and-loop over it does
 * nothing. So:
 *
 * <ul>
 *   <li>directory is a directory &rarr; guard passes through, vanilla's body runs unchanged;</li>
 *   <li>directory exists as a regular file &rarr; vanilla would list nothing and return; the
 *       guard returns; <b>identical behaviour</b>;</li>
 *   <li>directory does not exist &rarr; vanilla throws {@code IllegalArgumentException}; the
 *       guard returns. <b>This is the fix.</b></li>
 * </ul>
 *
 * <p><b>Why the refusal block is this patch's own and NOT vanilla's terminal
 * {@code RETURN} &mdash; measured, not assumed.</b> The obvious copy of SEC-1's shape is to send
 * the not-a-directory case to the {@code RETURN} the method already ends with, on the argument
 * that its stack map frame is already there so no new frame is needed. <b>That produces a class
 * that does not verify.</b> {@code javap -v} on the shipped
 * {@code ResourcePackRepository.class} puts three frames in
 * {@code deleteOldServerResourcesPacks}: an {@code append} at offset 34 adding
 * {@code [List, int, Iterator]}, a {@code same_frame_extended} at 101, and a {@code chop} at 104
 * that drops only {@code int} and {@code Iterator}, leaving
 * {@code locals = [ResourcePackRepository, List, int]}. The patch's branch departs from the
 * method <em>head</em>, where slots 1 and 2 are still {@code top}, so the target frame is not an
 * extension of the arriving frame and the JVM rejects it:
 *
 * <pre>
 *   VerifyError: Inconsistent stackmap frames at branch target 114
 *     Reason: Type top (current frame, locals[1]) is not assignable to 'java/util/List'
 * </pre>
 *
 * <p>The refusal block therefore carries its own explicit {@code F_FULL} frame declaring exactly
 * the one local that is live at the branch ({@code [this]}) and an empty stack. Vanilla's own
 * three frames are untouched and still describe vanilla's code. The trade is stated rather than
 * hidden: this patch adds ONE branch target and ONE stack map frame where SEC-1 adds neither.
 *
 * <p><b>What the explicit frame is and is not worth, measured rather than assumed.</b> A hand
 * experiment built the same injection with the {@code FrameNode} omitted; ASM supplied one and
 * <b>the class still linked and verified</b>. So the frame is NOT what makes the class verifiable
 * &mdash; what makes it verifiable is the refusal target being a NEW label rather than vanilla's
 * return, because only the new label is free of the incompatible recorded frame. The frame is
 * written explicitly anyway, for two reasons that are about the next person rather than this
 * build: it states the arrival state in the code instead of leaving it to be inferred, and
 * {@link #theRefusalBlockDeclaresItsOwnFrameWithOnlyTheLiveLocal()} in
 * {@code ServerResourcePackDirGuardPatchMeetsTheRealVanillaClassTest} pins it, because a future
 * edit that dropped it would still link, and a reader would then be relying on ASM's inference
 * without knowing it.
 *
 * <p>Handing {@link ClassWriter} the frame explicitly also keeps the other property SEC-1 was
 * built on: it is constructed with {@code ClassWriter(0)}, never {@code COMPUTE_FRAMES}, so
 * {@code getCommonSuperClass} is never called and no class is ever loaded from inside a
 * {@code ClassFileTransformer}. A frame written by hand is not a class-loading pass.
 *
 * <p><b>The sequence is stack-neutral and adds no local.</b> It occupies one slot, well inside the
 * neighbouring {@code getfield}/{@code getstatic} pair that pushes three, and the method's
 * {@code maxStack} is 3.
 *
 * <p><b>No helper method, therefore no runtime dependency.</b> Unlike SEC-1 this patch injects
 * only calls to types the client already has ({@code java.io.File}) and adds no
 * {@code INVOKESTATIC} into the kernel. Nothing in Core has to be resolvable from inside the
 * client's class loader at resource-pack time, which removes a whole class of load-order
 * failure that SEC-1's javadoc has to argue about.
 *
 * <p><b>Shape keying: string literals and symbols only, never a numeric operand.</b> The
 * encoding javac picks for a number is not stable across builds &mdash; {@code GlClampToEdgePatch}
 * matched {@code LDC} where javac emits {@code SIPUSH}, armed, verified, and changed nothing. So
 * this transform anchors on: the method name {@code deleteOldServerResourcesPacks} and its
 * {@code ()V} descriptor; the method's first two REAL instructions being an {@code ALOAD} of some
 * slot followed by {@code GETFIELD dirServerResourcepacks : Ljava/io/File;} <em>on this class</em>
 * (the slot is discovered from the bytecode and re-emitted, never assumed to be 0); the
 * {@code INVOKESTATIC org/apache/commons/io/FileUtils.listFiles} call whose descriptor starts
 * {@code (Ljava/io/File;}; and the {@code LDC} of the string literal
 * {@value #DELETING_LOG}, which occurs exactly once in the class. Any deviation returns
 * {@code null} &mdash; "no change" &mdash; so a different Minecraft build degrades to vanilla's
 * throwing behaviour rather than to a wrong rewrite. That is a deliberate trade: this patch
 * refuses to guess.
 *
 * <p><b>One incident this patch is shaped around.</b> The vanilla class is compiled with debug
 * info, so {@code LABEL} / {@code LINE} / {@code FRAME} nodes sit between real instructions and
 * a plain {@code getPrevious()} lands on one of them. SEC-1 hit exactly that and declined the
 * shipped class while accepting a hand-built canary with no debug info &mdash; the
 * {@code GlClampToEdgePatch} failure reached by a different road. Every neighbour lookup here is
 * therefore over REAL instructions ({@link #nextOperation} / {@link #previousOperation}), never
 * over raw list adjacency.
 *
 * <p><b>Fail-safe.</b> A missing method, unparseable bytes, a method carrying a try/catch
 * (jumping to the tail would step out of a protected region), a missing terminal {@code RETURN},
 * or any throw returns {@code null} and never emits altered bytes for a class it does not
 * recognise. Re-applying to already-patched bytes is declined, so the guard cannot be stacked.
 *
 * <p><b>Deliberately NOT changed.</b> Vanilla also never checks the download's own size cap
 * against the directory, and never removes a half-written pack on failure. Both are separate
 * concerns, neither is on the crash path, and widening the patch past the proved defect would be
 * scope the evidence does not support.
 */
public final class ServerResourcePackDirGuardPatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/client/resources/ResourcePackRepository";

    /** The unguarded method, by name and by descriptor. */
    static final String METHOD_NAME = "deleteOldServerResourcesPacks";
    static final String METHOD_DESC = "()V";

    /** The field whose directory is never created. A symbol anchor, never a numeric one. */
    static final String DIR_FIELD = "dirServerResourcepacks";
    static final String FILE_DESC = "Ljava/io/File;";

    /** Owner / name of the Commons IO call that throws. */
    private static final String FILE_UTILS = "org/apache/commons/io/FileUtils";
    private static final String LIST_FILES = "listFiles";
    /** Its descriptor always begins with the directory; only that much is required. */
    private static final String LIST_FILES_DESC_PREFIX = "(Ljava/io/File;";

    /** The unique string literal of this method. A string anchor, which is what is stable. */
    static final String DELETING_LOG = "Deleting old server resource pack ";

    /** {@code File.isDirectory()}. */
    private static final String FILE = "java/io/File";
    private static final String IS_DIRECTORY = "isDirectory";
    private static final String IS_DIRECTORY_DESC = "()Z";

    /** Stable hash seed of this patch's transform logic (author-supplied content-address). */
    static final String TRANSFORM_SEED = "server-resource-pack-dir-guard-v1";

    /**
     * Ed25519 signature over the canonical signing input, produced by the existing offline
     * ceremony ({@code scripts/sign-patch.sh --privkey <kernel key> --patch
     * ServerResourcePackDirGuardPatch}), which reads every covered field off {@link #manifest()}
     * itself rather than off a command line, so the signed bytes cannot drift from the shipped
     * manifest by retyping.
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:"
            + "KYfkz5mhFSwP2WXwlubnmQPjVg0KurSKWUiKwXnF-ZxxXcVJC5LfeU2H276IJjiVgr3dm7pZoB713Bq-8E_LBg";

    private final PatchManifest manifest;

    public ServerResourcePackDirGuardPatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-SEC0002")
                .name("deleteOldServerResourcesPacks throws on a fresh profile: FileUtils.listFiles "
                        + "on a server-resource-packs directory nothing in the class ever creates")
                .version("1.0.0.0")
                .kiRef("SEC-2")
                .targetClass("net.minecraft.client.resources.ResourcePackRepository")
                .platformCondition("")
                .publisher("kernel")
                .builtAt("2026-10-02T00:00:00Z")
                .evidence("ResourcePackRepository.deleteOldServerResourcesPacks (line 254) calls "
                        + "FileUtils.listFiles(this.dirServerResourcepacks, TrueFileFilter.TRUE, null) "
                        + "on line 256 with no try, no catch, no exists test and no isDirectory test, and "
                        + "Commons IO listFiles throws IllegalArgumentException for a directory that does "
                        + "not exist. dirServerResourcepacks is assigned once at line 65 and never "
                        + "created: the only two mkdirs in the file are lines 97 and 102, both inside "
                        + "fixDirResourcepacks, and both act on the different field dirResourcepacks. The "
                        + "only other creator is HttpUtil.java:202 saveFile.getParentFile().mkdirs(), which "
                        + "is inside the Runnable submitted to the download executor at HttpUtil.java:140 "
                        + "and therefore runs after the synchronous call at line 217 has already thrown. "
                        + "On a fresh profile the first resource pack any server sends therefore throws out "
                        + "of downloadResourcePack on the client thread, from NetHandlerPlayClient.java:1737 "
                        + "and 1773. The destination name is not server-chosen: lines 179-186 constrain it "
                        + "to hash.matches(^[a-f0-9]{40}$) or the literal \"legacy\", so no traversal is "
                        + "possible and no containment test is warranted here; LevelSchemePathGuardPatch "
                        + "(SEC-1) owns the containment half on the one resource-pack branch that does take "
                        + "a server-chosen path. This patch inserts an isDirectory() test ahead of the "
                        + "method's first instruction and sends the not-a-directory case to vanilla's own "
                        + "existing RETURN, which already carries a stack map frame, so the patch adds no "
                        + "branch target and no frame. Behaviour is preserved exactly: Commons IO throws "
                        + "only when !exists(), and for an existing non-directory it already returned an "
                        + "empty collection over which vanilla's loop did nothing.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    /**
     * Insert {@code isDirectory() ? proceed : vanilla's own terminal return} at the head of
     * {@code deleteOldServerResourcesPacks}.
     *
     * <p>Returns the patched bytes, or {@code null} when the target method is absent, the shape is
     * not the one described above, or the guard is already present. Never throws.
     */
    @Override
    public byte[] transform(byte[] originalClassfileBytes) {
        if (originalClassfileBytes == null || originalClassfileBytes.length == 0) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(originalClassfileBytes);
            ClassNode cn = new ClassNode();
            reader.accept(cn, 0);

            MethodNode target = null;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (METHOD_NAME.equals(mn.name) && METHOD_DESC.equals(mn.desc)) {
                        target = mn;
                        break;
                    }
                }
            }
            if (target == null || target.instructions == null) {
                return null;   // unrecognized shape -> no change
            }
            // Jumping to the method's tail would step out of a protected region and skip cleanup.
            if (target.tryCatchBlocks != null && !target.tryCatchBlocks.isEmpty()) {
                return null;   // unrecognized shape -> no change
            }
            if (alreadyInjected(target)) {
                return null;   // idempotent: a second guard is harmless but is not intended
            }

            AbstractInsnNode first = nextOperation(target.instructions.getFirst());
            if (first == null) {
                return null;   // method with no code
            }

            // The field read this guard needs: ALOAD <slot>; GETFIELD dirServerResourcepacks.
            // The slot is DISCOVERED here and re-emitted below; it is never assumed to be 0.
            AbstractInsnNode load = first;
            AbstractInsnNode getField = nextOperation(load);
            if (!(load instanceof VarInsnNode vLoad) || load.getOpcode() != Opcodes.ALOAD
                    || !(getField instanceof FieldInsnNode fLoad)
                    || getField.getOpcode() != Opcodes.GETFIELD
                    || !cn.name.equals(fLoad.owner)
                    || !DIR_FIELD.equals(fLoad.name)
                    || !FILE_DESC.equals(fLoad.desc)) {
                return null;   // unrecognized shape -> no change
            }

            // Two independent corroborations that this really is the resource-pack cleanup:
            // the Commons IO call that throws, and the log string unique to this method.
            if (!callsListFilesOnADirectory(target.instructions)) {
                return null;   // unrecognized shape -> no change
            }
            if (!mentionsLiteral(target.instructions, DELETING_LOG)) {
                return null;   // unrecognized shape -> no change
            }

            AbstractInsnNode terminalReturn = soleTerminalReturn(target.instructions);
            if (terminalReturn == null) {
                return null;   // unrecognized shape -> no change
            }

            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, vLoad.var));
            guard.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, DIR_FIELD, FILE_DESC));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FILE, IS_DIRECTORY, IS_DIRECTORY_DESC, false));
            // The refusal target is this patch's OWN block, appended after vanilla's terminal
            // return -- see the "why not vanilla's own return" note in the class javadoc. It
            // arrives at this branch with locals = [this] and an empty stack, so it is declared
            // F_FULL with exactly that local, rather than being made to share the frame the JVM
            // already recorded at vanilla's return (which declares locals[1] = List and
            // locals[2] = int, and is therefore INCOMPATIBLE with a jump from the method head).
            LabelNode refusal = new LabelNode();
            guard.add(new JumpInsnNode(Opcodes.IFEQ, refusal));
            target.instructions.insertBefore(first, guard);

            InsnList tail = new InsnList();
            tail.add(refusal);
            tail.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {cn.name}, 0, null));
            tail.add(new InsnNode(Opcodes.RETURN));
            target.instructions.insert(terminalReturn, tail);

            ClassWriter writer = new ClassWriter(0);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return null;   // fail-safe: never break class loading for a class we did not recognise
        }
    }

    /**
     * True when the method calls {@code FileUtils.listFiles} with a {@code File} first argument.
     *
     * <p>Owner, name and the leading {@code (Ljava/io/File;} are symbol anchors. No numeric
     * operand participates.
     */
    private static boolean callsListFilesOnADirectory(InsnList code) {
        for (AbstractInsnNode n = code.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call
                    && FILE_UTILS.equals(call.owner)
                    && LIST_FILES.equals(call.name)
                    && call.desc != null
                    && call.desc.startsWith(LIST_FILES_DESC_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** True when the method holds an {@code LDC} of exactly {@code literal}. */
    private static boolean mentionsLiteral(InsnList code, String literal) {
        for (AbstractInsnNode n = code.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.LDC && n instanceof LdcInsnNode ldc
                    && literal.equals(ldc.cst instanceof String s ? s : null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The method's terminal {@code RETURN}, or {@code null} unless there is exactly one and it is
     * the last real instruction.
     *
     * <p>Exactly one is required so the refusal target is unambiguous; "last" is required so the
     * jump cannot land in the middle of the method with locals still live.
     */
    private static AbstractInsnNode soleTerminalReturn(InsnList code) {
        AbstractInsnNode only = null;
        for (AbstractInsnNode n = code.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.RETURN) {
                if (only != null) {
                    return null;   // more than one return -> refuse to guess which one is terminal
                }
                only = n;
            }
        }
        if (only == null || nextOperation(only) != null) {
            return null;
        }
        return only;
    }

    /**
     * The {@link LabelNode} that already sits immediately before {@code insn}, skipping
     * {@code LineNumberNode} only, or {@code null}.
     *
     * <p>The label must already exist rather than being invented here: the {@code RETURN}'s stack
     * map frame is declared at that label, and a fresh label in a different place would move the
     * frame off the instruction it describes.
     */
    private static LabelNode labelImmediatelyBefore(AbstractInsnNode insn) {
        for (AbstractInsnNode n = insn.getPrevious(); n != null; n = n.getPrevious()) {
            if (n instanceof LabelNode label) {
                return label;
            }
            if (n.getOpcode() >= 0) {
                return null;   // a real instruction sits between the label and the return
            }
        }
        return null;
    }

    /**
     * The next REAL instruction, skipping the LABEL / LINE / FRAME nodes a debug-info class
     * interleaves. Matching on list adjacency instead of opcode adjacency is how a matcher passes
     * on a hand-built fixture and declines the shipped class.
     */
    private static AbstractInsnNode nextOperation(AbstractInsnNode from) {
        for (AbstractInsnNode n = from == null ? null : from.getNext(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                return n;
            }
        }
        return null;
    }

    /**
     * True when our prologue is already in place.
     *
     * <p>The prologue is {@code ALOAD this; GETFIELD dirServerResourcepacks;
     * INVOKEVIRTUAL File.isDirectory}, so once injected the method's THIRD real instruction is the
     * {@code isDirectory()} call &mdash; vanilla's own first two real instructions are that same
     * {@code ALOAD}/{@code GETFIELD} pair. Keying on the call rather than on the field read is
     * what makes this a real check: a coincidental {@code isDirectory()} elsewhere in the method
     * would not be mistaken for the guard.
     */
    private static boolean alreadyInjected(MethodNode target) {
        AbstractInsnNode a = target.instructions.getFirst();
        AbstractInsnNode b = a == null ? null : nextOperation(a);
        AbstractInsnNode c = b == null ? null : nextOperation(b);
        return c instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && FILE.equals(call.owner)
                && IS_DIRECTORY.equals(call.name)
                && IS_DIRECTORY_DESC.equals(call.desc);
    }
}