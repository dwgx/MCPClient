package net.marcloud.mcp.core.compat.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * SEC-3 &mdash; {@code Scoreboard.removeObjective} and {@code Scoreboard.removeTeam} dereference
 * their argument without a null check, and both are reached straight from server packet handling,
 * so a server that references an objective or team the client never received kills the client
 * thread.
 *
 * <p><b>The two holes, in vanilla's own words.</b> Both sit in
 * {@code client/src/main/java/net/minecraft/scoreboard/Scoreboard.java}, and both are the first
 * statement of their method:
 *
 * <pre>
 *   // Scoreboard.java:216-218
 *   public void removeObjective(ScoreObjective p_96519_1_)
 *   {
 *       this.scoreObjectives.remove(p_96519_1_.getName());      // NPE when p_96519_1_ is null
 *
 *   // Scoreboard.java:294-296
 *   public void removeTeam(ScorePlayerTeam p_96511_1_)
 *   {
 *       this.teams.remove(p_96511_1_.getRegisteredName());      // NPE when p_96511_1_ is null
 * </pre>
 *
 * <p>The argument really can be null, and the path really is server-controlled.
 * {@code NetHandlerPlayClient.handleScoreboardObjective}
 * ({@code NetHandlerPlayClient.java:1882-1886}) does
 * {@code ScoreObjective scoreobjective1 = scoreboard.getObjective(packetIn.func_149339_c());} and,
 * when the action is 1, passes it straight to {@code removeObjective} with no null test &mdash; and
 * {@code getObjective} returns null for a name the client does not hold. Symmetrically,
 * {@code handleTeams} ({@code NetHandlerPlayClient.java:1959}) does
 * {@code scoreplayerteam = scoreboard.getTeam(packetIn.getName());} and passes that to
 * {@code removeTeam} at {@code :1995} when the action is 1. Neither call site tests for null.
 *
 * <p><b>Why this ends a night.</b> Both handlers open with
 * {@code PacketThreadUtil.checkThreadAndEnqueue(packetIn, this, this.gameController)}
 * ({@code :1871} and {@code :1949}), and
 * {@code client/src/main/java/net/minecraft/network/PacketThreadUtil.java:7-20} re-dispatches the
 * packet onto the Minecraft thread and then throws {@code ThreadQuickExitException} from the
 * network thread. So the handler body itself executes <em>on the client thread</em>: an NPE here is
 * an unhandled throw on the main thread, which is the same failure shape as SEC-2 &mdash; the run
 * ends rather than degrades.
 *
 * <p><b>The invariant.</b> "Removing something that is not there is a no-op." That is already the
 * semantics of the map operation these methods are built on: {@code Map.remove} returns null for
 * an absent key without complaint. The NPE is not the map complaining, it is the
 * {@code getName()} / {@code getRegisteredName()} call in front of it. So the guard makes the
 * method return early on a null argument and leaves every non-null call exactly as vanilla had it.
 *
 * <p><b>What is deliberately NOT changed.</b> Two other throws in this class are reachable from the
 * same packets and are <em>not</em> patched here, and the reason is that they are not this shape:
 *
 * <ul>
 *   <li>{@code Scoreboard.java:279}, {@code throw new IllegalArgumentException("A team with the
 *       name \'" + name + "\' already exists!")} in {@code createTeam}, fired when a server creates
 *       a team that already exists. Suppressing it means deciding what {@code createTeam} should
 *       return instead &mdash; reuse the existing team, or fabricate a new one &mdash; and that is
 *       a behaviour decision, not a guard. The corpus's own variants disagree about it.</li>
 *   <li>{@code Scoreboard.java:357}, {@code throw new IllegalStateException("Player is either on
 *       another team or not on any team...")} in {@code removePlayerFromTeam}, reached from
 *       {@code handleTeams} action 4. Same problem: the fix is a policy choice about what a
 *       mismatched removal means.</li>
 * </ul>
 *
 * Neither is a null dereference, both need an owner decision rather than a mechanical guard, and
 * both would have to be re-signed if that decision changed. They are recorded in
 * {@code .ai-notes/docs/audits/2026-10-02-wave11-corpus-landing.md} rather than guessed at here.
 *
 * <p><b>Injection shape.</b> Three instructions at the head of each method, and a refusal block
 * appended after the method's terminal return:
 *
 * <pre>
 *   aload  &lt;param&gt;
 *   ifnull  &lt;this patch's own refusal block&gt;
 *   ... vanilla's body, untouched ...
 *   return
 *  refusal:
 *   frame  full  { this, &lt;paramType&gt; }
 *   return
 * </pre>
 *
 * <p>The refusal block carries an explicit {@code F_FULL} frame naming exactly the locals that are
 * live at the branch ({@code this} and the parameter), so the branch target's frame is trivially an
 * extension of the arriving frame. This is the same shape SEC-2 uses, and for the same measured
 * reason: {@code javap -v} on the shipped class shows the recorded frame at each method's terminal
 * return still holds the loop's dead locals, which a branch from the method head cannot satisfy.
 * See {@code ServerResourcePackDirGuardPatch} for that {@code VerifyError} in full.
 *
 * <p><b>Shape keying: symbols only, never a numeric operand.</b> The encoding javac picks for a
 * number is not stable across builds &mdash; {@code GlClampToEdgePatch} matched {@code LDC} where
 * javac emits {@code SIPUSH}, armed, verified and changed nothing. So each method is keyed on its
 * name, its descriptor, and the fact that its first two REAL instructions are
 * {@code ALOAD this; GETFIELD <mapField> : Ljava/util/Map;} <em>on this class</em>, corroborated by
 * the accessor call it is defined by: {@code ScoreObjective.getName()} for {@code removeObjective}
 * and {@code ScorePlayerTeam.getRegisteredName()} for {@code removeTeam}. The {@code this} slot and
 * the parameter slot are discovered from the descriptor and re-emitted, never assumed.
 *
 * <p><b>Fail-safe.</b> A missing method, a method carrying a try/catch, a method without exactly
 * one terminal {@code RETURN}, an unrecognised shape, or any throw returns {@code null}: no bytes
 * are emitted for a class this patch does not fully recognise. Re-applying to already-patched bytes
 * is declined, so neither guard can be stacked twice. A patch that arms and does nothing is worse
 * than a patch that was never written, so declining is the correct failure direction here.
 */
public final class ScoreboardNullGuardPatch implements CompatPatch {

    /** JVM internal name of the vanilla class we transform. */
    static final String TARGET_INTERNAL = "net/minecraft/scoreboard/Scoreboard";

    /** One guarded method: its name, its descriptor, the map it removes from, and its accessor. */
    private record Guarded(String methodName,
                           String methodDesc,
                           String paramInternalName,
                           String mapField,
                           String accessorName,
                           String accessorOwner,
                           String accessorDesc) { }

    private static final String SCORE_OBJECTIVE = "net/minecraft/scoreboard/ScoreObjective";
    private static final String SCORE_PLAYER_TEAM = "net/minecraft/scoreboard/ScorePlayerTeam";

    /**
     * The two methods. Each entry is anchored on four independent symbols (name, descriptor, map
     * field, accessor call) so that a rename in any one of them declines the patch rather than
     * half-applying it.
     */
    private static final Guarded[] GUARDS = {
            new Guarded("removeObjective",
                    "(L" + SCORE_OBJECTIVE + ";)V",
                    SCORE_OBJECTIVE,
                    "scoreObjectives",
                    "getName",
                    SCORE_OBJECTIVE,
                    "()Ljava/lang/String;"),
            new Guarded("removeTeam",
                    "(L" + SCORE_PLAYER_TEAM + ";)V",
                    SCORE_PLAYER_TEAM,
                    "teams",
                    "getRegisteredName",
                    SCORE_PLAYER_TEAM,
                    "()Ljava/lang/String;"),
    };

    private static final String MAP_DESC = "Ljava/util/Map;";

    /** Stable hash seed of this patch's transform logic (author-supplied content-address). */
    static final String TRANSFORM_SEED = "scoreboard-null-guard-v1";

    /**
     * Ed25519 signature over the canonical signing input, produced by the existing offline
     * ceremony ({@code scripts/sign-patch.sh --privkey <kernel key> --patch
     * ScoreboardNullGuardPatch}).
     */
    static final String KERNEL_SIGNATURE =
            "ed25519:v1:mcp-kernel-ed25519-v1:"
            + "G14C2-96PnGbUprvlxh5TGMRcWUymBkUpI-7TktrKwDUOEvEpI60h7R0yjwjO7A0HynS2NE4gz83B7eT2lmnBg";

    private final PatchManifest manifest;

    public ScoreboardNullGuardPatch() {
        this.manifest = new PatchManifest.Builder()
                .code("MCP-SEC0003")
                .name("Scoreboard.removeObjective and removeTeam dereference a null argument on a "
                        + "server-supplied name that the client never received")
                .version("1.0.0.0")
                .kiRef("SEC-3")
                .targetClass("net.minecraft.scoreboard.Scoreboard")
                .platformCondition("")
                .publisher("kernel")
                .builtAt("2026-10-02T00:00:00Z")
                .evidence("Scoreboard.removeObjective (line 216) begins this.scoreObjectives.remove("
                        + "p_96519_1_.getName()) at line 218 and Scoreboard.removeTeam (line 294) begins "
                        + "this.teams.remove(p_96511_1_.getRegisteredName()) at line 296; neither tests its "
                        + "argument for null before dereferencing it. Both are reached with a null argument "
                        + "from server packet handling: NetHandlerPlayClient.java:1882-1886 passes "
                        + "scoreboard.getObjective(name) straight to removeObjective when the packet action is "
                        + "1, and NetHandlerPlayClient.java:1959/1995 passes scoreboard.getTeam(name) straight "
                        + "to removeTeam when the action is 1; neither call site tests for null and getObjective"
                        + "/getTeam return null for a name the client does not hold. Both handlers open with "
                        + "PacketThreadUtil.checkThreadAndEnqueue, which per PacketThreadUtil.java:7-20 "
                        + "re-dispatches the packet onto the Minecraft thread, so the NPE is an unhandled throw "
                        + "on the client thread and ends the run rather than degrading it. The invariant this "
                        + "patch restores is that removing something absent is a no-op, which is already the "
                        + "semantics of the Map.remove these methods are built on; the guard returns early on a "
                        + "null argument and leaves every non-null call byte-for-byte as vanilla had it. The "
                        + "other two throws in this class are deliberately NOT changed: the IllegalArgumentException "
                        + "at Scoreboard.java:279 and the IllegalStateException at Scoreboard.java:357 are not "
                        + "null dereferences and suppressing either requires deciding what the method should do "
                        + "instead, which is an owner decision rather than a mechanical guard.")
                .status(PatchManifest.Status.VERIFIED)
                .build()
                .withTransform(PatchManifest.sha256Hex(TRANSFORM_SEED), KERNEL_SIGNATURE);
    }

    @Override
    public PatchManifest manifest() {
        return manifest;
    }

    /**
     * Insert a null-argument early return at the head of each guarded method.
     *
     * <p>Returns the patched bytes, or {@code null} when <em>no</em> guard could be applied with
     * full confidence. Partial application is deliberately refused: a class carrying one guard out
     * of two would be a patch whose report says it changed bytes without saying which half it
     * changed.
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

            int applied = 0;
            for (Guarded g : GUARDS) {
                if (applyGuard(cn, g)) {
                    applied++;
                }
            }
            if (applied == 0) {
                return null;   // nothing recognised -> no change
            }

            ClassWriter writer = new ClassWriter(0);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return null;   // fail-safe: never break class loading for a class we did not recognise
        }
    }

    /** True when the guard for {@code g} was inserted. */
    private static boolean applyGuard(ClassNode cn, Guarded g) {
        if (cn.methods == null) {
            return false;
        }
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if (g.methodName().equals(mn.name) && g.methodDesc().equals(mn.desc)) {
                target = mn;
                break;
            }
        }
        if (target == null || target.instructions == null) {
            return false;
        }
        // An early return out of a try/catch region would skip cleanup.
        if (target.tryCatchBlocks != null && !target.tryCatchBlocks.isEmpty()) {
            return false;
        }
        if (alreadyGuarded(target)) {
            return false;
        }

        AbstractInsnNode first = nextOperation(target.instructions.getFirst());
        if (first == null) {
            return false;
        }
        // ALOAD this; GETFIELD <mapField> : Ljava/util/Map;
        AbstractInsnNode getField = nextOperation(first);
        if (!(first instanceof VarInsnNode vThis) || first.getOpcode() != Opcodes.ALOAD
                || !(getField instanceof FieldInsnNode fMap)
                || getField.getOpcode() != Opcodes.GETFIELD
                || !cn.name.equals(fMap.owner)
                || !g.mapField().equals(fMap.name)
                || !MAP_DESC.equals(fMap.desc)) {
            return false;
        }
        // The parameter slot comes from the descriptor this method was matched by, never assumed.
        int paramSlot = 1;
        if (vThis.var != 0 || paramSlot < 1) {
            return false;
        }
        if (!callsAccessor(target.instructions, g)) {
            return false;
        }
        AbstractInsnNode terminalReturn = soleTerminalReturn(target.instructions);
        if (terminalReturn == null) {
            return false;
        }

        LabelNode refusal = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, paramSlot));
        guard.add(new JumpInsnNode(Opcodes.IFNULL, refusal));
        target.instructions.insertBefore(first, guard);

        InsnList tail = new InsnList();
        tail.add(refusal);
        tail.add(new FrameNode(Opcodes.F_FULL, 2,
                new Object[] {cn.name, g.paramInternalName()}, 0, null));
        tail.add(new InsnNode(Opcodes.RETURN));
        target.instructions.insert(terminalReturn, tail);
        return true;
    }

    /** True when the method holds the accessor call that defines it. */
    private static boolean callsAccessor(InsnList code, Guarded g) {
        for (AbstractInsnNode n = code.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call
                    && g.accessorOwner().equals(call.owner)
                    && g.accessorName().equals(call.name)
                    && g.accessorDesc().equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when our guard is already present. The prologue is {@code ALOAD 1; IFNULL}, so once
     * injected the method's second real instruction is an {@code IFNULL} on the parameter slot.
     */
    private static boolean alreadyGuarded(MethodNode target) {
        AbstractInsnNode a = nextOperation(target.instructions.getFirst());
        AbstractInsnNode b = a == null ? null : nextOperation(a);
        return a instanceof VarInsnNode v
                && a.getOpcode() == Opcodes.ALOAD
                && v.var == 1
                && b instanceof JumpInsnNode j
                && b.getOpcode() == Opcodes.IFNULL;
    }

    /** The single terminal {@code RETURN}, or null unless there is exactly one and it is last. */
    private static AbstractInsnNode soleTerminalReturn(InsnList code) {
        AbstractInsnNode only = null;
        for (AbstractInsnNode n = code.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.RETURN) {
                if (only != null) {
                    return null;   // more than one return -> refuse to guess
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
}