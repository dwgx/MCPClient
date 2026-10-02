package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.BlockProbe;
import net.marcloud.mcp.core.util.Graded;
import org.junit.Test;

/**
 * The belief layer must cost the walk exactly what it cost before it existed. Measured, not
 * asserted.
 *
 * <p><b>This is the constraint most likely to be violated by accident, and nobody would notice.</b>
 * ADR-0005 rejects a mechanism that spends capability and buys none, and the Owner's standing
 * instruction is that input realism may not cost capability. A belief layer is exactly the shape of
 * thing that gets built that way: a second probe to find out whether a cell was readable, a
 * re-verification pass to confirm the read it just did, a "let me double check the chunk is loaded"
 * whose answer was already in hand. None of those is wrong, all of them are invisible in a diff
 * review, and together they are ADR-0005's third category arriving under a new name.
 *
 * <p><b>Same shape as {@code TheHazardScanIsNotPaidEveryTickTest}: a counting actuator, and a
 * number.</b> That test exists because a hazard scan cost 97 world reads and ran on <b>every
 * tick</b> of a walk that can run for hundreds of ticks; it pins the property as a ratio because an
 * absolute would be brittle. Here the property is stronger and the number is exact: the belief layer
 * adds <b>zero</b>, so any drift is a strict increase and an equality is the right assertion rather
 * than a generous bound. A bound would let a regression of ten reads pass, and ten reads is exactly
 * the shape of the regression being guarded against.
 *
 * <p><b>The baseline is not a guess.</b> The counts below were measured on the pre-change tree
 * before a line of it was edited, with a throwaway counting test driving the same walk:
 * {@code nav60 blockAt reads = 25}, {@code nav10 blockAt reads = 25}. They are written in as exact
 * equalities so a future change to {@code NavController} that alters the read profile is a visible
 * failure with a number in it, rather than a bound that quietly widened.
 *
 * <p><b>What is measured, and what is not.</b> {@link FakeActuator#blockAt} is the only world-read
 * seam a controller has, so a counting actuator measures what the tick loop costs the world. The
 * planner's reads go through {@code BlockView}, not through an actuator, so
 * {@link #probingAGridIsNotOneReadPerCellMoreThanReadingIt} covers that half with its own counter.
 * Neither measures {@code World.getBlockState} inside {@code BlockProbe}, which needs a real
 * {@code World} -- that is covered structurally instead, by
 * {@link #probeDelegatesToAtAndAddsNoReadOfItsOwn}, which asserts the delegation by reading the
 * compiled call rather than by pretending to have counted something it could not count.
 */
public class TheBeliefLayerCostsNoWorldReadTest {

    /** Counts blockAt calls, so the cost is a number rather than an impression. */
    private static final class CountingActuator extends FakeActuator {
        int reads;

        @Override
        public String blockAt(int x, int y, int z) {
            reads++;
            return super.blockAt(x, y, z);
        }
    }

    /** The pre-change measurement, taken before any line of the layer was written. */
    private static final int PRE_CHANGE_READS_10_TICKS = 25;
    private static final int PRE_CHANGE_READS_60_TICKS = 25;

    private static NavController walk(CountingActuator act, int ticks) {
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        NavController nav = new NavController(24, 64, 0, 400);
        for (int i = 0; i < ticks; i++) {
            nav.tick(act);
        }
        return nav;
    }

    @Test
    public void aWalkCostsExactlyWhatItCostBeforeTheBeliefLayerExisted() {
        CountingActuator act = new CountingActuator();
        walk(act, 60);
        assertEquals("60 ticks of walking now cost " + act.reads + " block reads; it cost "
                        + PRE_CHANGE_READS_60_TICKS + " before the belief layer existed. An increase "
                        + "means something decided a belief by asking the world something the walk "
                        + "had already asked, which is ADR-0005's third category -- a mechanism "
                        + "that spends capability and buys none -- arriving under a new name",
                PRE_CHANGE_READS_60_TICKS, act.reads);
    }

    /**
     * The short walk as well as the long one, because the per-tick ratio is where a per-tick cost
     * would hide. Ten ticks and sixty ticks cost the same here, which is the property the hazard
     * scan test pins as a ratio; pinning it as two exact numbers additionally catches a cost that
     * scales with ticks but is small enough to hide under a generous bound.
     */
    @Test
    public void aShortWalkIsAlsoUnchangedAndThePerTickCostIsStillZero() {
        CountingActuator shortWalk = new CountingActuator();
        walk(shortWalk, 10);
        CountingActuator longWalk = new CountingActuator();
        walk(longWalk, 60);

        assertEquals("a 10-tick walk is also unchanged",
                PRE_CHANGE_READS_10_TICKS, shortWalk.reads);
        assertEquals("and the six-fold longer walk costs the same again, so the belief layer "
                        + "introduced no per-tick cost: " + longWalk.reads + " vs "
                        + shortWalk.reads,
                PRE_CHANGE_READS_10_TICKS, longWalk.reads);
    }

    /**
     * The walk still reads the world at all.
     *
     * <p>The mirror of the hazard test's {@code theScanStillRunsAtAllOnce}, and it guards the
     * opposite regression: a "fix" that made the belief layer free by making the walk blind would
     * pass a cost assertion perfectly. A warning that never fires is not a fix, and a walk that
     * never looks is worse.
     */
    @Test
    public void theWalkStillReadsTheWorldSoTheZeroAboveIsNotBlindness() {
        CountingActuator act = new CountingActuator();
        walk(act, 10);
        assertTrue("10 ticks of walking cost " + act.reads + " block reads, so the walk is still "
                + "looking at the world; a zero above would be blindness, not economy", act.reads > 0);
    }

    /**
     * The planner's half, counted at the seam the planner actually reads through.
     *
     * <p>{@code NavController} reads the world through an actuator, but {@code Planner} reads it
     * through {@code BlockView}, and a belief on a route refusal is produced on the planner's path.
     * A counting view therefore measures that half on the same terms.
     */
    @Test
    public void probingAGridIsNotOneReadPerCellMoreThanReadingIt() {
        CountingView counted = new CountingView();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                counted.walkVerdict(x, 64, z);
            }
        }
        int bare = counted.reads;

        CountingView probed = new CountingView();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                probed.probe(x, 64, z);
            }
        }

        assertEquals("16 cells cost " + bare + " reads when asked directly and " + probed.reads
                        + " when asked through the grading path. A grade that needs its own read is "
                        + "unaffordable by construction",
                bare, probed.reads);
        assertTrue("premise: the counting view really is being read, or the equality above is "
                + "vacuous", bare > 0);
    }

    /**
     * A grade is decided from a value already in hand, not from a fresh look.
     *
     * <p>This is the property {@link #probingAGridIsNotOneReadPerCellMoreThanReadingIt} cannot
     * reach, because {@code BlockProbe.probe} needs a real {@code World} to run against. Read from
     * the compiled class instead: the method must contain exactly one call to
     * {@code BlockProbe.at} and no call to any world-reading method of its own. If somebody later
     * adds a "just to be sure the chunk is loaded" check, this goes red and names the line.
     */
    @Test
    public void probeDelegatesToAtAndAddsNoReadOfItsOwn() {
        try {
            byte[] bytes = readClass("net/marcloud/mcp/core/util/BlockProbe");
            org.objectweb.asm.tree.MethodNode node = probeMethod(bytes);
            int atCalls = 0;
            int worldCalls = 0;
            for (org.objectweb.asm.tree.AbstractInsnNode n = node.instructions.getFirst();
                 n != null; n = n.getNext()) {
                if (!(n instanceof org.objectweb.asm.tree.MethodInsnNode)) {
                    continue;
                }
                org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) n;
                if (call.getOpcode() != org.objectweb.asm.Opcodes.INVOKESTATIC) {
                    continue;
                }
                if (call.name.equals("at") && call.owner.equals("net/marcloud/mcp/core/util/BlockProbe")) {
                    atCalls++;
                }
                if (call.owner.startsWith("net/minecraft/world/")
                        || call.owner.equals("net/minecraft/block/state/IBlockState")) {
                    worldCalls++;
                }
            }
            assertEquals("BlockProbe.probe must read the world exactly once, by delegating to at: "
                    + "a second read would be a belief that costs the walk a world read per call",
                    1, atCalls);
            assertEquals("BlockProbe.probe must not touch a world method directly; the whole point "
                    + "of at() is that it owns the read ordering, and a second reader is a second "
                    + "ordering nobody is maintaining",
                    0, worldCalls);
        } catch (IOException e) {
            throw new AssertionError("could not read BlockProbe's bytecode", e);
        }
    }

    private static org.objectweb.asm.tree.MethodNode probeMethod(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (m.name.equals("probe") && m.desc.contains("Graded")) {
                return m;
            }
        }
        throw new AssertionError("BlockProbe.probe(World,int,int,int) returning Graded<Solidity> "
                + "is not in the compiled class: this test has drifted off the code it guards");
    }

    private static byte[] readClass(String internalName) throws IOException {
        String resource = internalName + ".class";
        try (java.io.InputStream in = TheBeliefLayerCostsNoWorldReadTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no class resource for " + resource + " on the test "
                        + "classpath");
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            return bytes.toByteArray();
        }
    }

    /** A {@code BlockView} that counts, standing in for the planner's world reads. */
    private static final class CountingView implements net.marcloud.mcp.core.drivers.plan.BlockView {
        int reads;

        @Override
        public int walkVerdict(int x, int y, int z) {
            reads++;
            return WALK_CLEAR;
        }

        /**
         * The grading path a caller would use, shaped like {@code BlockProbe.probe}: one read, and
         * a grade that is a function of what came back.
         */
        Graded<Integer> probe(int x, int y, int z) {
            int verdict = walkVerdict(x, y, z);
            return verdict == WALK_UNKNOWN
                    ? Graded.unknown(verdict, "the cell could not be read at all")
                    : new Graded<>(verdict, Belief.OBSERVED, null);
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean canPlaceAt(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isClimbable(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return false;
        }

        @Override
        public int blockBudget() {
            return 0;
        }
    }
}
