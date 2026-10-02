package net.marcloud.mcp.core.compat.patches;

import java.io.File;
import java.io.IOException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * SEC-1 &mdash; the {@code level://} resource-pack branch turns a server-supplied string into a
 * local filesystem path with no validation at all.
 *
 * <p><b>The hole, in vanilla's own words</b> ({@code NetHandlerPlayClient.handleResourcePack},
 * {@code client/src/main/java/net/minecraft/client/network/NetHandlerPlayClient.java:1701-1731}):
 *
 * <pre>
 *   if (s.startsWith("level://")) {
 *       String s2 = s.substring("level://".length());
 *       File file1 = new File(this.gameController.mcDataDir, "saves");
 *       File file2 = new File(file1, s2);
 *       if (file2.isFile()) { ... setResourcePackInstance(file2) ... }
 *   } else {
 *       if (getCurrentServerData() != null &amp;&amp; getResourceMode() == ENABLED) { ... downloadResourcePack ... }
 *   }
 * </pre>
 *
 * <p>Everything after the scheme check is server-controlled and unchecked. {@code s} is
 * {@code S48PacketResourcePackSend.getURL()}, which any server can set to any string, so
 * {@code level://../../../../Users/me/.ssh/id_rsa} makes {@code file2} a path outside {@code saves/}
 * and {@code file2.isFile()} a local filesystem read.
 *
 * <p><b>Impact, stated without inflation.</b> A hostile or compromised server can make the client
 * READ an arbitrary local file and attempt to parse it as a resource pack. It is not a direct
 * exfiltration &mdash; the bytes are not sent back to the server. The realistic outcomes are: a
 * local file is read; a resource pack is loaded if that file happens to be a parseable zip; the
 * client crashes on a parse error; or a targeted existence oracle is observed through the
 * ACCEPTED / FAILED_DOWNLOAD status packets the client sends back. That last one is worth stating
 * plainly, because {@code C19PacketResourcePackStatus} IS a server-observable channel: "the file
 * exists" and "it is a valid zip" are both leakable one bit at a time.
 *
 * <p><b>Why the sibling branch is NOT also patched.</b> {@code downloadResourcePack}
 * ({@code ResourcePackRepository.java:175}) names its destination {@code new
 * File(dirServerResourcepacks, hash-or-"legacy")}, where {@code hash} is accepted only when it
 * matches {@code ^[a-f0-9]{40}$} and is otherwise replaced by the literal {@code "legacy"}; and the
 * bytes come from an HTTP fetch, not from disk. So the HTTP branch neither reads a local file nor
 * writes one at a server-chosen path. The {@code level://} branch is the only resource-pack path
 * that touches the local filesystem, and the only one with an unbounded path. Widening this patch
 * past that finding would be scope the evidence does not support.
 *
 * <p><b>What is deliberately NOT changed.</b> Vanilla's {@code level://} branch also never checks
 * the resource-pack hash, so a server can serve any file under {@code saves/} as a pack. That is
 * vanilla behaviour, and {@code level://} is a local-server convenience (a host or LAN server
 * shipping a pack out of its own world folder), not a transport-integrity feature &mdash; adding
 * hash enforcement would break that feature and is a different decision from closing a traversal.
 * Out of scope here, and named rather than silently left.
 *
 * <p><b>The invariant, not a denylist.</b> The requirement is that the resolved path stays inside
 * {@code saves/}. Rejecting {@code ".."} would not be that requirement: it enumerates one spelling
 * of the attack, it says nothing about symlinked directories, and it needs a new entry for every
 * encoding anyone thinks of later. So the check is a containment test on the CANONICAL path
 * ({@link #isInsideSaves(File, File)}), which collapses {@code ..}, {@code .}, redundant
 * separators, drive-relative forms and symlinks into one decision.
 *
 * <p><b>Injection shape.</b> Four instructions are inserted between {@code astore cand} and the
 * {@code aload cand} that feeds {@code File.isFile()}:
 *
 * <pre>
 *   aload saves
 *   aload cand
 *   invokestatic LevelSchemePathGuardPatch.isInsideSaves(File,File)Z
 *   ifeq    &lt;vanilla's own "not a file" else-branch&gt;
 * </pre>
 *
 * <p>Two properties make this cheap and safe to write. First, the refusal target is vanilla's
 * existing {@code ifeq} target &mdash; the block that sends {@code FAILED_DOWNLOAD} &mdash; so the
 * patch introduces NO new branch target and NO new stack map frame; the method's stored frames stay
 * valid and {@link ClassWriter} writes them through under {@code ClassWriter(0)}, which never
 * calls {@code getCommonSuperClass} and so never loads a class from inside a
 * {@code ClassFileTransformer}. Second, the sequence is stack-neutral (two slots, well within what
 * the neighbouring {@code new File; dup; ...} sequence already uses) and adds no local.
 *
 * <p><b>Shape keying.</b> The transform matches a chain of STRING literals and branch topology,
 * never a numeric operand: the {@code LDC "level://"} feeding {@code String.startsWith}, the
 * {@code String.substring} call that proves this really is the {@code level://} branch, exactly two
 * {@code File.<init>(String)} calls (the {@code saves} directory and the candidate), the literal
 * {@code "saves"} that names the first of them, and the {@code File.isFile()} immediately fed from
 * the candidate local. Any deviation returns {@code null} &mdash; "no change" &mdash; so a
 * different Minecraft build degrades to vanilla's vulnerable behaviour rather than to a wrong
 * rewrite. That is a deliberate trade: this patch refuses to guess.
 *
 * <p><b>Where the guard lives.</b> {@link #isInsideSaves(File, File)} is a static method on this
 * class rather than a separate top-level helper, so the patch stays one file. The injected
 * {@code INVOKESTATIC} needs this class on the client's classpath at resource-pack time; it is
 * there, because the engine instantiated this very patch object to run this very transform (the
 * same assumption {@code Ki11DwmHotkeyPatch} already makes for {@code DwmHotkey}).
 *
 * <p><b>Fail-safe.</b> A missing method, unparseable bytes, or any throw returns {@code null} and
 * never emits altered bytes for a class it does not recognise. Re-applying to already-patched
 * bytes is declined, so the guard cannot be stacked twice.
 *
 * <p><b>NOT ARMED AS SHIPPED.</b> {@link #KERNEL_SIGNATURE} is {@code null}. This repository does
 * not hold the kernel Ed25519 private key ({@code scripts/sign-patch.sh} takes it as an argument
 * and never stores it), and a patch arms if and only if its signature verifies under the baked-in
 * kernel public key. An unsigned patch is refused by {@code PatchSigner} with "signature not
 * trusted" &mdash; the fail-closed direction, and the same state {@code Ki11DwmHotkeyPatch} was in
 * before its signing ceremony ran. Shipping this fix therefore needs the two changes recorded in
 * {@code .ai-notes/docs/audits/2026-10-02-wave4-levelscheme.md}: the signing ceremony, and one
 * {@code db.register(...)} line in {@code Compat.defaultDatabase()}. Until then the hole is closed
 * in the patch layer but NOT live in the client.
 */
public final class LevelSchemePathGuardPatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/client/network/NetHandlerPlayClient";

    static final String METHOD_NAME = "handleResourcePack";
    static final String METHOD_DESC =
            "(Lnet/minecraft/network/play/server/S48PacketResourcePackSend;)V";

    /** The scheme literal the whole branch hangs on. A string anchor, never a numeric one. */
    static final String LEVEL_SCHEME = "level://";

    /** The directory the resolved path must stay inside. */
    static final String SAVES_DIR = "saves";

    private static final String FILE = "java/io/File";
    private static final String STRING = "java/lang/String";
    private static final String FILE_CTOR = "<init>";
    /**
     * The two {@code File} constructor descriptors this shape can arrive as.
     *
     * <p><b>Both call sites in the real class are {@code File(File, String)}.</b> The
     * instructions written into this file were first transcribed with the descriptor
     * {@code (Ljava/lang/String;)V}, taken from a hand-written note rather than from
     * {@code javap}. {@code javap -c} on {@code client/target/classes} and on the shipped
     * {@code client/target/MCP-1.8.9.jar} both say
     * {@code invokespecial java/io/File."<init>":(Ljava/io/File;Ljava/lang/String;)V} at offsets
     * 46 and 59, and so does ASM on the parsed tree. Keying on the wrong descriptor produced a
     * patch that armed, verified, and returned {@code null} on every real load &mdash; the exact
     * {@code GlClampToEdgePatch} failure, reached the same way. So the transform accepts EITHER
     * descriptor: the correct one, and the single-argument form some other build could compile
     * {@code new File(parent, child)} into.
     */
    private static final String[] FILE_CTOR_DESCS = {
            "(Ljava/io/File;Ljava/lang/String;)V",
            "(Ljava/lang/String;)V",
    };
    private static final String STARTS_WITH_DESC = "(Ljava/lang/String;)Z";
    private static final String SUBSTRING_DESC = "(I)Ljava/lang/String;";
    private static final String IS_FILE_DESC = "()Z";

    /** True when {@code desc} is one of the accepted {@code File} constructor descriptors. */
    private static boolean fileCtorDesc(String desc) {
        for (String accepted : FILE_CTOR_DESCS) {
            if (accepted.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    /** Owner of the injected call: this class. */
    static final String GUARD_OWNER =
            "net/marcloud/mcp/core/compat/patches/LevelSchemePathGuardPatch";
    static final String GUARD_METHOD = "isInsideSaves";
    static final String GUARD_DESC = "(Ljava/io/File;Ljava/io/File;)Z";

    /** Stable hash seed of this patch's transform logic (author-supplied content-address). */
    static final String TRANSFORM_SEED = "level-scheme-path-guard-v1";

    /**
     * Ed25519 signature over the canonical signing input, produced by the existing offline
     * ceremony ({@code scripts/sign-patch.sh --privkey <kernel key> --patch
     * LevelSchemePathGuardPatch}), which reads every covered field off {@link #manifest()}
     * itself rather than off a command line, so the signed bytes cannot drift from the shipped
     * manifest by retyping. Not decoration: {@link net.marcloud.mcp.core.compat.Ed25519PatchSigner}
     * recomputes the signing input from this manifest and {@link
     * net.marcloud.mcp.core.compat.CompatEngine} skips the patch when it does not match, so a
     * wrong value here is silently INERT rather than loudly broken.
     *
     * <p>The canonical input covers NINE fields (targetClass, contentHash, keyId, status,
     * kiRef, publisher, version, supersedes, platformCondition) — so any edit to one of those
     * in the manifest below invalidates this string and re-arms nothing until the ceremony is
     * re-run. See {@code LevelSchemePathGuardPatchArmingTest}, which fails loudly on exactly
     * that drift instead of letting a dead patch look armed.
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:"
            + "JTp43_UPj7TVy_8zFlOTczrffFcy7CrF5l9svwSArLz09LAWChM_pH8gYTlawjRwBIBcojp8JFufAlfzVAAqBg";

    private final PatchManifest manifest;

    public LevelSchemePathGuardPatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-SEC0001")
                .name("level:// resource pack URL reaches the filesystem with no containment check")
                .version("1.0.0.0")
                .kiRef("SEC-1")
                .targetClass("net.minecraft.client.network.NetHandlerPlayClient")
                .platformCondition("")
                .publisher("kernel")
                .builtAt("2026-10-02T00:00:00Z")
                .evidence("handleResourcePack(S48PacketResourcePackSend) takes packetIn.getURL() "
                        + "and, on the level:// branch, passes the substring after the scheme "
                        + "straight to new File(mcDataDir, \"saves\") then new File(that, s2) with "
                        + "no validation of the scheme remainder, of .., of the pack hash or of "
                        + "the content. A hostile or compromised server can therefore make the "
                        + "client read an arbitrary local file and attempt to parse it as a "
                        + "resource pack; the bytes are not returned to the server, but the "
                        + "ACCEPTED/FAILED_DOWNLOAD status packets give it an existence oracle. "
                        + "This patch inserts a containment check on the CANONICAL path before "
                        + "File.isFile(): the resolved candidate must be under the canonical saves "
                        + "directory, otherwise control falls into vanilla's existing "
                        + "not-a-file branch and FAILED_DOWNLOAD is sent. Containment on the "
                        + "resolved path, rather than a denylist of '..', because '..' alone is "
                        + "one spelling of the attack and covers neither symlinked directories "
                        + "nor other encodings. getCanonicalPath failures fail closed. The HTTP "
                        + "branch is deliberately untouched: downloadResourcePack names its "
                        + "destination new File(dirServerResourcepacks, hash-or-\"legacy\") where "
                        + "hash is accepted only when it matches ^[a-f0-9]{40}$, and its bytes come "
                        + "from a fetch, so it neither reads nor writes a server-chosen local path.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    /**
     * Insert {@code isInsideSaves(saves, candidate) ? proceed : vanilla's not-a-file branch} ahead of
     * the {@code File.isFile()} test in {@code handleResourcePack}.
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
            if (alreadyInjected(target)) {
                return null;   // idempotent: a second guard is harmless but is not intended
            }


            AbstractInsnNode schemeBranch = schemeCheckBranch(target.instructions);
            AbstractInsnNode isFile = isFileTest(schemeBranch);
            if (isFile == null) {
                return null;   // unrecognized shape -> no change
            }
            AbstractInsnNode afterIsFile = nextOperation(isFile);
            if (!(afterIsFile instanceof JumpInsnNode notAFile) || afterIsFile.getOpcode() != Opcodes.IFEQ) {
                return null;   // unrecognized shape -> no change
            }

            // Back off the exact vanilla shape around the isFile() test:
            //   astore cand          <- the new File(saves, s2)
            //   aload  cand          <- isFile - 1
            //   invokevirtual isFile <- isFile
            //
            // Real OPERATION neighbours, not list neighbours. The vanilla class is compiled with
            // debug info, so LABEL / LINE / FRAME nodes sit between the instructions and a plain
            // getPrevious() lands on one of them -- which is what made this matcher decline the
            // real class while accepting a hand-built canary that had no debug info. Same failure
            // mode as the SIPUSH incident, reached by a different road.
            AbstractInsnNode load = previousOperation(isFile);
            AbstractInsnNode store = load == null ? null : previousOperation(load);
            AbstractInsnNode candidateCtor = store == null ? null : previousOperation(store);
            if (load == null || load.getOpcode() != Opcodes.ALOAD || !(load instanceof VarInsnNode vLoad)
                    || store == null || store.getOpcode() != Opcodes.ASTORE || !(store instanceof VarInsnNode vStore)
                    || vStore.var != vLoad.var
                    || candidateCtor == null || candidateCtor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !(candidateCtor instanceof MethodInsnNode mCtor)
                    || !FILE_CTOR.equals(mCtor.name) || !fileCtorDesc(mCtor.desc)) {
                return null;   // unrecognized shape -> no change
            }
            int candSlot = vLoad.var;

            int savesSlot = savesSlot(schemeBranch, candidateCtor);
            if (savesSlot < 0) {
                return null;   // unrecognized shape -> no change
            }


            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, savesSlot));
            guard.add(new VarInsnNode(Opcodes.ALOAD, candSlot));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD_OWNER, GUARD_METHOD,
                    GUARD_DESC, false));
            // Vanilla's own refusal target: the block that sends FAILED_DOWNLOAD. Reusing it means
            // no new branch target and no new stack map frame.
            guard.add(new JumpInsnNode(Opcodes.IFEQ, notAFile.label));
            target.instructions.insertBefore(load, guard);

            ClassWriter writer = new ClassWriter(0);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return null;   // fail-safe: never break class loading for a class we did not recognise
        }
    }


    /**
     * The {@code IFEQ} guarding the whole {@code level://} branch &mdash; the instruction right after
     * {@code LDC "level://"; String.startsWith} &mdash; or {@code null} if the method has no such
     * branch.
     */
    private static AbstractInsnNode schemeCheckBranch(InsnList code) {
        for (AbstractInsnNode insn = code.getFirst(); insn != null; insn = insn.getNext()) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && insn instanceof MethodInsnNode startsWith
                    && STRING.equals(startsWith.owner)
                    && "startsWith".equals(startsWith.name)
                    && STARTS_WITH_DESC.equals(startsWith.desc)
                    && levelSchemeConstant(startsWith.getPrevious())
                    && next != null && next.getOpcode() == Opcodes.IFEQ) {
                return next;
            }
        }
        return null;
    }

    /**
     * The {@code File.isFile()} call that decides whether the {@code level://} candidate is loaded,
     * or {@code null} if the method does not have the anchored shape.
     *
     * <p>Walks forward from the scheme check and requires a {@code String.substring} call (proof
     * this really is the {@code level://} branch and not the HTTP one) before the {@code isFile()}
     * that must be immediately followed by the {@code IFEQ} onto the not-a-file block. Numeric
     * operands are never used as an anchor, because the encoding javac picks for a number is not
     * stable across builds; the constant-pool strings are.
     */
    private static AbstractInsnNode isFileTest(AbstractInsnNode schemeBranch) {
        boolean sawSubstring = false;
        for (AbstractInsnNode n = schemeBranch.getNext(); n != null; n = n.getNext()) {
            if (n.getOpcode() != Opcodes.INVOKEVIRTUAL || !(n instanceof MethodInsnNode call)) {
                continue;
            }
            if (STRING.equals(call.owner) && "substring".equals(call.name)
                    && SUBSTRING_DESC.equals(call.desc)) {
                sawSubstring = true;
            } else if (FILE.equals(call.owner) && "isFile".equals(call.name)
                    && IS_FILE_DESC.equals(call.desc)) {
                return sawSubstring ? n : null;
            }
        }
        return null;
    }

    /**
     * The local slot holding {@code new File(mcDataDir, "saves")}.
     *
     * <p>Requires exactly two {@code File.<init>(String)} calls between the scheme check and the
     * candidate constructor (inclusive), the first of them immediately storing into the returned
     * slot and taking its String argument from the literal {@code "saves"}. Returns -1 when the
     * count, the store, or the literal does not match, which is how an unfamiliar build is
     * declined instead of half-rewritten.
     */
    private static int savesSlot(AbstractInsnNode schemeBranch, AbstractInsnNode candidateCtor) {
        AbstractInsnNode first = null;
        AbstractInsnNode second = null;
        for (AbstractInsnNode n = schemeBranch.getNext(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.INVOKESPECIAL
                    && n instanceof MethodInsnNode ctor
                    && FILE.equals(ctor.owner)
                    && FILE_CTOR.equals(ctor.name)
                    && fileCtorDesc(ctor.desc)) {
                if (first == null) {
                    first = n;
                } else if (second == null) {
                    second = n;
                } else {
                    return -1;   // three or more: not the two-File shape
                }
            }
            if (n == candidateCtor) {
                break;
            }
        }
        if (first == null || second == null || second != candidateCtor) {
            return -1;
        }
        AbstractInsnNode store = first.getNext();
        if (store == null || store.getOpcode() != Opcodes.ASTORE || !(store instanceof VarInsnNode saved)) {
            return -1;
        }
        // The "saves" literal is the String this constructor consumed, i.e. the instruction
        // immediately before it: javac pushes new, dup, parent, "saves", then <init>.
        return SAVES_DIR.equals(stringConstant(previousOperation(first))) ? saved.var : -1;
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

    /** The previous REAL instruction; see {@link #nextOperation}. */
    private static AbstractInsnNode previousOperation(AbstractInsnNode from) {
        for (AbstractInsnNode n = from == null ? null : from.getPrevious(); n != null; n = n.getPrevious()) {
            if (n.getOpcode() >= 0) {
                return n;
            }
        }
        return null;
    }

    /** True when the instruction is an {@code LDC} of the exact string {@code "level://"}. */
    private static boolean levelSchemeConstant(AbstractInsnNode insn) {
        return LEVEL_SCHEME.equals(stringConstant(insn));
    }

    private static String stringConstant(AbstractInsnNode insn) {
        return insn != null && insn.getOpcode() == Opcodes.LDC && insn instanceof LdcInsnNode ldc
                ? ldc.cst instanceof String s ? s : null
                : null;
    }

    private static boolean alreadyInjected(MethodNode target) {
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC
                    && insn instanceof MethodInsnNode call
                    && GUARD_OWNER.equals(call.owner)
                    && GUARD_METHOD.equals(call.name)
                    && GUARD_DESC.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * THE CONTAINMENT TEST. True when {@code candidate}, resolved to its canonical (real) path, lies
     * inside {@code savesDir} &mdash; the invariant, not a spelling of an attack.
     *
     * <p>{@link File#getCanonicalPath()} is what makes this an invariant rather than a denylist:
     * it collapses {@code ..} and {@code .} segments, redundant separators and symlinked
     * directories into one real path, so {@code saves/link} pointing at {@code C:\Windows} is
     * rejected for the same reason {@code ../} is. Comparing with a trailing {@link File#separator}
     * means a sibling directory named {@code saves-evil} cannot pass as a child of {@code saves}.
     *
     * <p><b>Fails closed.</b> Any {@link IOException} from the canonicalisation (a malformed path
     * component on Windows) or any runtime failure returns {@code false}: an unresolvable path is
     * refused, not passed through. The cost is that a legitimate pack at a genuinely malformed path
     * is refused too &mdash; it falls into vanilla's not-a-file branch and the server sees
     * FAILED_DOWNLOAD. For a security boundary that is the direction to fail in.
     *
     * <p>Static and public because the patched bytecode calls it by {@code INVOKESTATIC} from the
     * client's resource-pack handler.
     */
    public static boolean isInsideSaves(File savesDir, File candidate) {
        if (savesDir == null || candidate == null) {
            return false;
        }
        try {
            String base = savesDir.getCanonicalPath();
            String target = candidate.getCanonicalPath();
            if (!base.endsWith(File.separator)) {
                base = base + File.separator;
            }
            return target.startsWith(base);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}