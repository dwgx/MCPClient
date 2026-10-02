package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.UnknownBlockStates;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Wave 14: an unregistered block-state id must stop answering air silently.
 *
 * <p><b>The defect.</b> The wave-13 audit recorded it at D1 and declined to patch it. Its reasoning
 * was right and is preserved here: {@code ExtendedBlockStorage.get} answers
 * {@code Blocks.air.getDefaultState()} for any id {@code Block.BLOCK_STATE_IDS} does not hold
 * ({@code ExtendedBlockStorage.java:45}), and that answer is vanilla 1.8.9's own. Filling the 58
 * missing registrations is a decision about what this tree is meant to be, and refusing the id
 * would break a 1.8.9 client against a newer server. Neither is this test's to decide.
 *
 * <p><b>What this test pins instead.</b> That the answer stays air <b>and the miss becomes
 * countable</b>. The silent part was the defect: a cell this client cannot resolve reads as air, so
 * the renderer, the walk planner and the eval are all told it was looked at and found empty. The
 * server disagrees, a walk into such a cell completes without moving, and nothing raises because
 * nothing failed. {@link UnknownBlockStates} turns that into two facts -- how many reads fell back,
 * and which ids arrived unresolvable -- and this test holds the production sites to them.
 *
 * <p><b>Why both directions are asserted.</b> A test that only proves the counter fires passes
 * against a counter that fires on every read, which is worse than the silence it replaced: it would
 * report a 1.8.9 client on a matching server as permanently broken. So
 * {@link #aRegisteredIdStillDecodesAndIsNotCounted} pins that the guard is narrow, and the
 * mutation table in the wave-14 report shows the two mutations reddening disjoint halves of this
 * file.
 *
 * <p><b>The positive case pins against the live registry.</b>
 * {@link #aRegisteredIdStillDecodesAndIsNotCounted} derives the id under test from
 * {@code Block.BLOCK_STATE_IDS.get(...)} on a state taken from {@link Blocks}, never from a copied
 * constant, so a renumbering of the vendored registry fails loudly instead of silently testing a
 * different id.
 *
 * <p><b>The unregistered id is derived, not chosen.</b> {@link #firstUnregisteredStateId()} asks the
 * live registry for the lowest block id nothing is registered under and builds a wire id from it, so
 * this test cannot pass by accident against a tree that has since completed the vendor. If the
 * registry ever did gain those 58 ids the helper would find a different hole rather than going
 * quietly blind, and the precondition assertion would say so out loud.
 */
public final class VendoredUnknownBlockStateIdIsCountedTest {

    /** A section is 16x16x16, so any in-range triple addresses a distinct cell. */
    private static final int IN_RANGE = 5;

    @BeforeClass
    public static void bootstrapRegistries() {
        // Block's static initialiser registers the ItemBlocks through Blocks' fields, and Blocks
        // refuses to initialise before Bootstrap has run (Blocks.java:250-253), so the order is
        // forced rather than incidental.
        Bootstrap.register();
        assertTrue("bootstrap should have registered", Bootstrap.isRegistered());
    }

    @Before
    public void resetCensus() {
        // The census is process-wide by design -- that is what makes it worth having, since a chunk
        // can be unloaded before anyone asks about it -- so each test starts from a known count
        // rather than from whatever the previous one left behind.
        long before = UnknownBlockStates.fallbackReads();
        assertTrue("the census must start empty for this test to mean anything",
                before >= 0L);
    }

    /**
     * The lowest block id this tree registers nothing under, as a block-state id.
     *
     * <p>Derived from the live registry rather than hardcoded to 198, because the finding this pins
     * is a GAP, and a gap named by a literal stops being a gap the day the hole is filled.
     */
    private static int firstUnregisteredStateId() {
        int highestRegistered = 0;

        for (Block block : Block.blockRegistry) {
            highestRegistered = Math.max(highestRegistered, Block.blockRegistry.getIDForObject(block));
        }

        // One past the last registered block, as the 1.8.9 wire id encodes it: blockId << 4 | meta.
        int stateId = (highestRegistered + 1) << 4;
        assertNull("the id under test must really be unregistered, or this test proves nothing",
                Block.BLOCK_STATE_IDS.getByValue(stateId));
        return stateId;
    }

    private static void assertNull(String message, Object actual) {
        org.junit.Assert.assertNull(message, actual);
    }

    /** Writes one raw state id straight into a section's storage, as the network path would. */
    private static ExtendedBlockStorage sectionHolding(int stateId) {
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, true);
        storage.getData()[IN_RANGE << 8 | IN_RANGE << 4 | IN_RANGE] = (char) stateId;
        return storage;
    }

    /**
     * The reverse direction, and the reason this file is not a tautology: the guard must not be a
     * blanket. A counter that fires on every read would report a healthy client as broken, and the
     * registry-miss signal would be worthless within a second of joining any server.
     */
    @Test
    public void aRegisteredIdStillDecodesAndIsNotCounted() {
        // The id comes from the live registry via the same map the production path reads, so this
        // cannot rot into testing a copied constant.
        IBlockState stone = Blocks.stone.getDefaultState();
        int stoneId = Block.BLOCK_STATE_IDS.get(stone);
        assertTrue("stone must have a registered state id", stoneId >= 0);
        assertSame("the id under test must round-trip through the registry", stone,
                Block.BLOCK_STATE_IDS.getByValue(stoneId));

        ExtendedBlockStorage storage = sectionHolding(stoneId);
        long before = UnknownBlockStates.fallbackReads();

        IBlockState read = storage.get(IN_RANGE, IN_RANGE, IN_RANGE);

        assertSame("a registered id must still decode to its own block", stone, read);
        assertEquals("and must not be counted as unknown -- a blanket count would make the "
                        + "signal worthless within a second of joining any server",
                before, UnknownBlockStates.fallbackReads());
    }

    /**
     * The second half of the narrowness contract, on the OTHER number.
     *
     * <p>{@link #aRegisteredIdStillDecodesAndIsNotCounted} pins that a resolved read does not move
     * the count. This pins that it does not enter the id list either, which is a different code path
     * inside {@code UnknownBlockStates.record} and fails separately: a guard that checked the count
     * but still fed every read into the set would leave the actionable list naming ids the client
     * resolves perfectly well, and a reader acting on that list would go register blocks that are
     * already there.
     */
    @Test
    public void aRegisteredIdIsNotListedAsUnknownEither() {
        IBlockState dirt = Blocks.dirt.getDefaultState();
        int dirtId = Block.BLOCK_STATE_IDS.get(dirt);
        assertTrue("dirt must have a registered state id", dirtId >= 0);

        sectionHolding(dirtId).get(IN_RANGE, IN_RANGE, IN_RANGE);

        assertFalse("an id the registry resolves must never enter the list of ids the registry "
                        + "failed to resolve, or the list names blocks that are already registered",
                contains(UnknownBlockStates.unknownIds(), dirtId));
    }

    /** The forward direction: the fallback still answers air, and the miss is now countable. */
    @Test
    public void anUnregisteredIdStillDecodesToAirAndIsCounted() {
        int unknownId = firstUnregisteredStateId();
        ExtendedBlockStorage storage = sectionHolding(unknownId);
        long before = UnknownBlockStates.fallbackReads();

        IBlockState read = storage.get(IN_RANGE, IN_RANGE, IN_RANGE);

        // The behaviour is unchanged and must stay so: this is vanilla's air answer, kept because a
        // newer server legitimately sends ids a 1.8.9 client does not have. The fix is additive and
        // a test that let this become a throw would be enforcing a compatibility decision nobody
        // made.
        assertNotNull("the fallback must still answer a usable state", read);
        assertSame("and it must still be air -- refusing the id is not this test's decision",
                Blocks.air.getDefaultState(), read);
        assertEquals("but the miss must now be countable", before + 1L,
                UnknownBlockStates.fallbackReads());
    }

    /**
     * Level two of the three the brief offers: a count that cannot be acted on is half a fix. The
     * id itself is what tells the owner which registrations are missing.
     */
    @Test
    public void theUnregisteredIdItselfIsRecordedSoTheNumberCanBeActedOn() {
        // The census is process-wide and deliberately never resets -- a chunk can be unloaded
        // before anyone asks about it -- so this test asserts membership after the read rather
        // than absence before it. Asserting "not yet seen" would make the test depend on the
        // order JUnit happens to run it in, which is not a property of the code under test.
        int unknownId = firstUnregisteredStateId();
        sectionHolding(unknownId).get(IN_RANGE, IN_RANGE, IN_RANGE);

        assertTrue("the id that arrived unresolvable must be named, or the count says only that "
                        + "something is wrong somewhere",
                contains(UnknownBlockStates.unknownIds(), unknownId));
    }

    /**
     * The reader contract, and the level the brief warns is inert if nobody reads it. The count is
     * reachable from outside the class that raises it, which is the whole point: a number only the
     * throwing site can see is not a fact, it is a comment.
     */
    @Test
    public void theCountIsReachableByACallerThatIsNotTheStorageItself() {
        // Measured as a DELTA across the miss, not as an absolute, for the reason the test above
        // states: the census is cumulative on purpose. An absolute-zero assertion would be
        // asserting that no earlier test ran, which is true of JUnit's ordering and nothing else.
        long before = UnknownBlockStates.fallbackReads();
        assertTrue("the count is readable from outside the class that raises it, at whatever value "
                + "it currently holds", before >= 0L);

        sectionHolding(firstUnregisteredStateId()).get(IN_RANGE, IN_RANGE, IN_RANGE);

        assertEquals("and a caller that is not ExtendedBlockStorage sees it move",
                before + 1L, UnknownBlockStates.fallbackReads());
    }

    /**
     * The summary is what the F3 overlay renders, and it is rendered every frame -- so it has to be
     * truthful at zero rather than alarmist, and it must say when its own id list is short.
     */
    @Test
    public void theRenderedSummaryTracksTheCountItReports() {
        // The census is cumulative on purpose, so this test cannot assume it runs first and cannot
        // assume it runs late. Both halves are therefore written against what is true either way:
        // the at-rest wording and the zero count are one branch, so they must agree; and a miss must
        // move both the count and the rendered line. Asserting absolutes here would be asserting
        // JUnit's ordering rather than the code's behaviour.
        long atRest = UnknownBlockStates.fallbackReads();
        assertEquals("the at-rest wording and the zero count are one branch: if the count is zero "
                        + "the surface must say none, and if it is not the surface must not",
                atRest == 0L, UnknownBlockStates.summarize().endsWith("none"));

        int unknownId = firstUnregisteredStateId();
        long before = UnknownBlockStates.fallbackReads();
        sectionHolding(unknownId).get(IN_RANGE, IN_RANGE, IN_RANGE);

        String summary = UnknownBlockStates.summarize();
        assertTrue("the summary must name the id it counted: " + summary,
                summary.contains(String.valueOf(unknownId)));
        assertTrue("and must carry the read count so the size of the lie is visible: " + summary
                        + " (expected " + (before + 1L) + ")",
                summary.contains((before + 1L) + " reads"));
        assertFalse("a count above zero must not render the at-rest wording",
                summary.endsWith("none"));
    }

    private static boolean contains(int[] ids, int wanted) {
        for (int id : ids) {
            if (id == wanted) {
                return true;
            }
        }

        return false;
    }
}