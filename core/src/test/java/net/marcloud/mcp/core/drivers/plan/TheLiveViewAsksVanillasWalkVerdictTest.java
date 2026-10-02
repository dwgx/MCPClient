package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The rule "may a body occupy this cell" is written once, and the live view asks vanilla for it.
 *
 * <p><b>The defect this file exists for.</b> {@code LiveBlockView.isPassable} returned
 * {@code BlockProbe.Solidity.isEmptySpace()} -- "has no collision box" -- and lava has none, so the
 * live adapter read lava as room while the read side was telling the model {@code -2} for the same
 * cell. The planner-side consequences are driven behaviourally in
 * {@link LavaIsNeverPassableToARouteTest}; what cannot be driven there is the adapter itself, which
 * needs a live {@code World} and an {@code Entity}, and a fix whose live half is unpinned is the
 * half that silently reverts.
 *
 * <p><b>Why bytecode, and what that can and cannot prove.</b> The same reason
 * {@code LocalGridColumnsArePinnedAtTheirBoundaryTest} reads call sites: these methods are private
 * and world-bound, so nothing in {@code core/src/test} can call them, and reading the arguments a
 * call site actually passes fails on a mutation instead of agreeing with it. It proves the SHAPE --
 * which rule is asked, with which flags, and that the old predicate is gone -- not the answers on a
 * live world. The answers are pinned behaviourally by the sibling tests through the same interface,
 * which is why both halves exist.
 */
public class TheLiveViewAsksVanillasWalkVerdictTest {

    /** Internal name of the class under test, whose own call sites are read below. */
    private static final String LIVE_BLOCK_VIEW = "net/marcloud/mcp/core/drivers/plan/LiveBlockView";

    @Test
    public void theOccupancyRuleRefusesLavaAdmitsWaterAndRefusesTheUnread() {
        BlockView view = new BlockView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return false;
            }

            @Override
            public int walkVerdict(int x, int y, int z) {
                return x == 0 ? WALK_LAVA : x == 1 ? WALK_WATER : WALK_UNKNOWN;
            }

            @Override
            public boolean canPlaceAt(int x, int y, int z) {
                return false;
            }

            @Override
            public int blockBudget() {
                return 0;
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return false;
            }
        };

        assertFalse("lava is never a cell a body may occupy, whatever a collision-box test says "
                + "about it -- it has no collision box", view.isPassable(0, 0, 0));
        assertTrue("water is room: wet, not fatal", view.isPassable(1, 0, 0));
        assertFalse("and an unread cell is not room either -- a verdict nobody obtained is not a "
                + "verdict that says yes", view.isPassable(2, 0, 0));
    }

    @Test
    public void theLiveViewDoesNotGetItsOwnOpinionAboutWhichVerdictsAreSafe() {
        ClassNode cn = classNode();

        for (MethodNode m : cn.methods) {
            assertFalse("LiveBlockView must not override isPassable. The occupancy rule lives in "
                    + "BlockView and is derived from the verdict, precisely so an implementation "
                    + "supplies the world fact and not the policy -- the two-opinions arrangement "
                    + "is what let the read side say -2 while the planner walked into the lava",
                    m.name.equals("isPassable"));
        }

        MethodNode verdict = method("walkVerdict");
        assertTrue("walkVerdict must ask vanilla's own verdict rather than decide passability "
                + "itself; a second taxonomy is what this repo has paid for six times",
                calls(verdict, "vanillaVerdict"));
        assertTrue("and it must check for lava BEFORE delegating, because vanilla's answer for lava "
                + "depends on whether the entity is already in it -- a fact about the player, not "
                + "the cell", calls(verdict, "isLava"));
        assertFalse("and the old predicate must be gone from this path: isEmptySpace is true for "
                + "lava, water, tall grass and a torch, so a passability answer built on it is the "
                + "defect itself, not an approximation of it",
                calls(verdict, "isEmptySpace") || calls(verdict, "holdsPlayerUp"));
    }

    @Test
    public void theDelegationIsVanillasCallWithTheReadSidesFlags() {
        MethodNode vanilla = method("vanillaVerdict");
        AbstractInsnNode call = callTo(vanilla, "func_176170_a");
        assertNotNull("vanillaVerdict must delegate to WalkNodeProcessor.func_176170_a -- the call "
                + "LocalGrid.walkVerdict makes -- so the half that reports terrain and the half "
                + "that routes over it answer with one rule", call);

        List<Integer> flags = constantsBefore(call, 3);
        assertEquals("the three trailing flags (avoidWater, breakDoors, enterDoors) decide the whole "
                + "value domain of the verdict and must be the read side's, or the two sides "
                + "disagree about the codes they are comparing", 3, flags.size());
        assertEquals("avoidWater must be false: with it true vanilla answers -1 for any water "
                + "column, and -1 is documented nowhere in the model's legend -- it would receive an "
                + "unmapped code for standing in water", Integer.valueOf(0), flags.get(0));
        assertEquals("breakDoors must be false: the planner is not promised a verdict that assumes "
                + "the player will break through", Integer.valueOf(0), flags.get(1));
        assertEquals("enterDoors must be false, which is what makes 0 mean \"blocked\" of a closed "
                + "wooden door too", Integer.valueOf(0), flags.get(2));

        // The cell volume is this ONE cell, because Stance#hasRoom walks the body's two cells
        // itself; a size of 2 in Y here would judge the head block twice and the feet not at all.
        // constantsBefore PREPENDS as it walks backwards, so what comes back is in FORWARD
        // source order: the three sizes first, then the three flags. (The 3-constant read above
        // is the same list truncated to its tail, which is why the flag assertions there are
        // already in argument order.)
        List<Integer> trailing = constantsBefore(call, 6);
        assertEquals("the three size arguments and the three flags must all be constants this test "
                + "can read, since between them they decide which volume the verdict describes "
                + "and which value domain it can return", 6, trailing.size());
        assertEquals("one cell wide", Integer.valueOf(1), trailing.get(0));
        assertEquals("one cell tall -- hasRoom asks about feet and head separately",
                Integer.valueOf(1), trailing.get(1));
        assertEquals("and one cell deep", Integer.valueOf(1), trailing.get(2));
        assertEquals("then the three flags, which the read of them above already pins to false",
                Integer.valueOf(0), trailing.get(3));
        assertEquals(Integer.valueOf(0), trailing.get(4));
        assertEquals(Integer.valueOf(0), trailing.get(5));
    }

    @Test
    public void lavaIsDecidedByMaterialBeforeVanillaIsAsked() {
        MethodNode isLava = method("isLava");
        FieldInsnNode material = null;
        for (AbstractInsnNode n = isLava.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof FieldInsnNode f && f.owner.equals("net/minecraft/block/material/Material")
                    && f.name.equals("lava")) {
                material = f;
                break;
            }
        }
        assertNotNull("the lava check must read Material.lava. Anything narrower -- a block-name "
                + "comparison, or a registry identity -- is the taxonomy this repo keeps replacing, "
                + "and anything wider (any liquid) would refuse water as well",
                material);
        assertEquals("read as a static field, i.e. the material itself rather than a property of "
                + "the player", Opcodes.GETSTATIC, material.getOpcode());
    }

    // ---- reading the call sites of the private, world-bound methods ----

    private static MethodNode method(String name) {
        ClassNode cn = classNode();
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        throw new AssertionError("LiveBlockView." + name + " is gone, so the rule it carried is "
                + "gone with it -- and the file would still compile, which is why this is asserted "
                + "rather than skipped");
    }

    private static ClassNode classNode() {
        byte[] bytes = classBytes(LIVE_BLOCK_VIEW);
        assertNotNull("LiveBlockView.class must be readable from the test classpath: the arguments "
                + "its private, world-bound methods pass are unobservable any other way", bytes);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cn;
    }

    private static AbstractInsnNode callTo(MethodNode m, String calleeName) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.name.equals(calleeName)) {
                return call;
            }
        }
        return null;
    }

    private static boolean calls(MethodNode m, String calleeName) {
        return callTo(m, calleeName) != null;
    }

    /** The last {@code howMany} constant arguments a call receives, in argument order. */
    private static List<Integer> constantsBefore(AbstractInsnNode call, int howMany) {
        List<Integer> found = new ArrayList<>();
        for (AbstractInsnNode n = call.getPrevious(); n != null && found.size() < howMany;
                n = n.getPrevious()) {
            if (n.getOpcode() < 0) {
                continue;
            }
            Integer v = intConstant(n);
            if (v == null) {
                break;
            }
            found.add(0, v);
        }
        return found;
    }

    /**
     * The int a single instruction pushes, in any of the forms javac emits, or null when the
     * instruction is not a constant push at all.
     */
    private static Integer intConstant(AbstractInsnNode n) {
        if (n == null) {
            return null;
        }
        int op = n.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op - Opcodes.ICONST_0;
        }
        if (n instanceof IntInsnNode i && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
            return i.operand;
        }
        if (n instanceof LdcInsnNode l && l.cst instanceof Integer v) {
            return v;
        }
        return null;
    }

    /** Raw bytes of a class from the test classpath, or null when absent. */
    private static byte[] classBytes(String internalName) {
        String res = "/" + internalName + ".class";
        try (InputStream in = TheLiveViewAsksVanillasWalkVerdictTest.class
                .getResourceAsStream(res)) {
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
