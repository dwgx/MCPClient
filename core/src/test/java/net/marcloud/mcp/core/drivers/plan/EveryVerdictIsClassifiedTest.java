package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The occupancy rule, tested over the WHOLE verdict vocabulary rather than the three codes the
 * other tests happen to produce.
 *
 * <p>Why this file exists: {@code BlockView.isPassable} is a {@code default} method, and until
 * now the only test of it fed it {@code WALK_LAVA}, {@code WALK_WATER} and {@code WALK_UNKNOWN}.
 * Rewriting the body as {@code return verdict >= 0;} — which admits a FENCE and an OPEN
 * TRAPDOOR, two codes the method's own javadoc promises to refuse — turns the entire
 * 1209-test core suite green. That is the exact mutation class this repository has been bitten
 * by five times, recorded at the head of
 * {@code LocalGridColumnsArePinnedAtTheirBoundaryTest}: each time a boundary test was written
 * for the values the sampler happened to produce, and the untested side was free to move.
 *
 * <p>So the rule is pinned by a TABLE over the vocabulary, which means a new code added to
 * {@code BlockView} must be classified here or this test fails on the enumeration rather than
 * silently inheriting "passable".
 */
public class EveryVerdictIsClassifiedTest {

    /** One row per code the interface declares, and what a body may do in that cell. */
    private static final Object[][] VOCABULARY = {
        // code, passable?, what it is
        {BlockView.WALK_CLEAR, Boolean.TRUE, "clear ground"},
        {BlockView.WALK_WATER, Boolean.TRUE, "wading: wet, not fatal"},
        {BlockView.WALK_BLOCKED, Boolean.FALSE, "a solid block or a closed wooden door"},
        {BlockView.WALK_LAVA, Boolean.FALSE, "lava: a body in it dies"},
        {BlockView.WALK_FENCE, Boolean.FALSE, "a fence or wall, 1.5 blocks tall"},
        {BlockView.WALK_OPEN_TRAPDOOR, Boolean.FALSE, "an open trapdoor standing in the cell"},
        {BlockView.WALK_UNKNOWN, Boolean.FALSE, "an unread cell: not a statement about terrain"},
    };

    /** Answers whatever the code is, so the assertion is about the RULE and not about a fake. */
    private static BlockView answering(final int code) {
        return new BlockView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return code == BlockView.WALK_BLOCKED;
            }

            @Override
            public int walkVerdict(int x, int y, int z) {
                return code;
            }

            @Override
            public boolean canPlaceAt(int x, int y, int z) {
                return true;
            }
            @Override
            public int blockBudget() {
                return 0; // never reached: this fake answers only walkVerdict/isPassable
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
    }

    @Test
    public void everyVerdictTheInterfaceDeclaresIsClassifiedByTheOccupancyRule() {
        for (Object[] row : VOCABULARY) {
            int code = (Integer) row[0];
            boolean expected = (Boolean) row[1];
            String what = (String) row[2];
            assertTrue(what + " (code " + code + "): isPassable disagrees with this table",
                    expected == answering(code).isPassable(0, 0, 0));
        }
    }

    @Test
    public void aFenceIsNotPassableAndThatIsTheHalfNobodyPinned() {
        // Spelled out separately because it is the mutation's whole point: a fence is 1.5 blocks
        // tall, admitting it puts a body through a fence, and before this file the code was free
        // to admit it with the entire suite green.
        assertFalse("a fence is 1.5 blocks tall; a body may not occupy the cell",
                answering(BlockView.WALK_FENCE).isPassable(0, 0, 0));
    }

    @Test
    public void anOpenTrapdoorIsNotPassableAndThatIsTheOtherHalf() {
        // An OPEN trapdoor is a vertical panel standing in the cell. A CLOSED one is not: it is
        // the floor, which is why vanilla's -4 is specifically the open case.
        assertFalse("an open trapdoor stands in the cell; a closed one lies flat and is not this code",
                answering(BlockView.WALK_OPEN_TRAPDOOR).isPassable(0, 0, 0));
    }

    @Test
    public void theTableCoversEveryCodeTheInterfaceDeclares() {
        // The enumeration guard. Adding a code to BlockView without classifying it here is a
        // compile-clean change that would otherwise leave the new code's behaviour undecided.
        int declared = 0;
        for (java.lang.reflect.Field f : BlockView.class.getFields()) {
            if (f.getName().startsWith("WALK_")) {
                declared++;
            }
        }
        assertTrue("BlockView now declares " + declared + " WALK_* codes but this table classifies "
                + VOCABULARY.length + ". Classify the new one here before relying on isPassable "
                + "for it.", VOCABULARY.length >= declared);
    }
}
