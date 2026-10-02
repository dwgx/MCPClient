package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.UnknownBlockStates;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Wave 21: an unknown block-state id that arrives off the wire must stop arriving as {@code null}
 * into nobody's knowledge.
 *
 * <p><b>The defect, and why it is not the one {@code 6389b29} closed.</b>
 * {@code ExtendedBlockStorage.get} answers {@code Blocks.air.getDefaultState()} for an id the
 * registry does not hold, and that slice made the answer countable. The two block-change packet
 * decoders have the same shape and a different answer: they store {@code null}, with no fallback at
 * all ({@code S23PacketBlockChange.java:33}, {@code S22PacketMultiBlockChange.java:43} before this
 * change).
 *
 * <p><b>Air and null are not the same failure, which is why this is a separate count and not a
 * second entry in the first one.</b> Air is a value: the renderer draws it, the walk planner walks
 * it, the eval scores it, and every consumer is wrong while nothing raises -- which is exactly why
 * the lie needed a census. A {@code null} does not survive its first dereference: it raises, and
 * what that raise does depends entirely on which thread frame catches it. A reader of the F3 line
 * asking "how much work is this client doing on a lie" and a reader asking "how many updates did
 * this client throw away" are asking different questions, and one number answering both would
 * answer neither. {@code nullArrivals()} and {@code fallbackReads()} are therefore separate
 * counters with separate id lists, and this file pins that they do not contaminate each other in
 * either direction -- see {@link #aNullArrivalDoesNotMoveTheReadCount()} and
 * {@link #aFallbackReadDoesNotMoveTheNullArrivalCount()}.
 *
 * <p><b>The behaviour is deliberately unchanged, and that is asserted, not assumed.</b>
 * {@link #anUnknownIdOnTheWireStillDecodesToNull()} pins that {@code readPacketData} still returns a
 * {@code null} state, because the air fallback this client already commits to carries a stated
 * compatibility reason (a 1.8.9 client on a newer server legitimately receives ids it has never
 * heard of, and refusing them would break the connection) and repeating that decision one layer up
 * without anyone having made it is precisely the substitution this slice was told not to perform.
 * A test that let the null become air would be enforcing a compatibility decision nobody voted on.
 * What changes is that the miss becomes countable, which is what
 * {@link #anUnknownIdOnTheWireStillDecodesToNullAndIsCounted()} and
 * {@link #theUnknownIdItselfIsNamedSoTheNumberCanBeActedOn()} hold the production sites to.
 *
 * <p><b>Why both directions are asserted.</b> A test that only proves the counter fires passes
 * against a counter that fires on every decode, which is worse than the silence it replaced: it
 * would report a healthy 1.8.9 client on a matching server as permanently broken, and the signal
 * would be worthless within a second of joining any world. So
 * {@link #aRegisteredIdOnTheWireIsNotCounted()} pins that the guard is narrow, and
 * {@link #aRegisteredIdOnTheWireIsNotListedEither()} pins the same narrowness on the id list --
 * a different code path inside the recording, which fails separately.
 *
 * <p><b>The ids are derived, never chosen.</b> {@link #firstUnregisteredStateId()} asks the live
 * registry for the lowest block id nothing is registered under and builds a wire id from it, so
 * this file cannot pass by accident against a tree that has since completed the vendor. If the
 * registry ever gains those ids the helper finds a different hole rather than going quietly blind,
 * and the precondition assertion says so out loud.
 *
 * <p><b>Both sites, and one test each.</b> {@code S23PacketBlockChange} and
 * {@code S22PacketMultiBlockChange} are separate production sites with separate blast radius: the
 * multi change's handler loop
 * ({@code NetHandlerPlayClient.java:734-742}) iterates every entry, so a raise on entry <i>i</i>
 * abandons entries <i>i+1..n</i> as well. One test per site is what makes the mutation table's two
 * directions separable -- removing the recording from one site reddens only that site's test.
 */
public final class VendoredUnknownBlockIdOffTheWireIsCountedTest {

    @BeforeClass
    public static void bootstrapRegistries() {
        // Block's static initialiser registers the ItemBlocks through Blocks' fields, and Blocks
        // refuses to initialise before Bootstrap has run, so the order is forced rather than
        // incidental.
        Bootstrap.register();
        assertTrue("bootstrap should have registered", Bootstrap.isRegistered());
    }

    @Before
    public void censusIsReadable() {
        // The census is process-wide by design -- that is what makes it worth having, since a
        // packet can be dropped before anyone asks about it -- so each test starts by proving the
        // number is READABLE at whatever value it currently holds rather than by resetting it.
        assertTrue("the null-arrival count must be readable from outside the decoders",
                UnknownBlockStates.nullArrivals() >= 0L);
    }

    /**
     * The lowest block id this tree registers nothing under, as a block-state id.
     *
     * <p>Derived from the live registry rather than hardcoded, because the finding this pins is a
     * GAP, and a gap named by a literal stops being a gap the day the hole is filled.
     */
    private static int firstUnregisteredStateId() {
        int highestRegistered = 0;

        for (Block block : Block.blockRegistry) {
            highestRegistered = Math.max(highestRegistered, Block.blockRegistry.getIDForObject(block));
        }

        int stateId = (highestRegistered + 1) << 4;
        assertNull("the id under test must really be unregistered, or this test proves nothing",
                Block.BLOCK_STATE_IDS.getByValue(stateId));
        return stateId;
    }

    /** One S23 as the wire carries it: a block pos, then the state id as a varint. */
    private static S23PacketBlockChange decodeSingle(int stateId) throws Exception {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeBlockPos(new BlockPos(10, 64, -20));
        buf.writeVarIntToBuffer(stateId);

        S23PacketBlockChange packet = new S23PacketBlockChange();
        packet.readPacketData(buf);
        return packet;
    }

    /**
     * One S22 as the wire carries it, with the single block change at {@code posCrammed} holding
     * {@code stateId}. The chunk coordinate is 0,0, so {@code getPos()} is the crammed value
     * unpacked, which is not what this file is about -- it is here so the packet decodes at all.
     */
    private static S22PacketMultiBlockChange decodeMulti(int stateId) throws Exception {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeInt(0);
        buf.writeInt(0);
        buf.writeVarIntToBuffer(1);
        buf.writeShort(0);
        buf.writeVarIntToBuffer(stateId);

        S22PacketMultiBlockChange packet = new S22PacketMultiBlockChange();
        packet.readPacketData(buf);
        return packet;
    }

    /**
     * The forward direction at site one, and the reason this slice exists: the null is unchanged
     * AND the miss is countable.
     */
    @Test
    public void anUnknownIdOnTheWireStillDecodesToNullAndIsCounted() throws Exception {
        int unknownId = firstUnregisteredStateId();
        long before = UnknownBlockStates.nullArrivals();

        S23PacketBlockChange packet = decodeSingle(unknownId);

        // The behaviour, unchanged. See the class javadoc: the null is a wire-behaviour decision
        // and this slice does not get to make it.
        assertNull("the decode must STILL answer null -- this is the vanilla answer and changing it "
                        + "is a compatibility decision this slice was explicitly told not to take",
                packet.getBlockState());
        assertEquals("but the miss must now be countable by a caller that is not the packet",
                before + 1L, UnknownBlockStates.nullArrivals());
    }

    /** The same two halves at site two, which is a different production line of code. */
    @Test
    public void theMultiBlockChangeCountsTheSameWayAndStillDecodesToNull() throws Exception {
        int unknownId = firstUnregisteredStateId();
        long before = UnknownBlockStates.nullArrivals();

        S22PacketMultiBlockChange packet = decodeMulti(unknownId);

        assertEquals("the multi change must hold exactly the one entry the wire declared",
                1, packet.getChangedBlocks().length);
        assertNull("and it must still answer null, for the same reason the single change does",
                packet.getChangedBlocks()[0].getBlockState());
        assertEquals("but its miss must be countable too, or the second site stays invisible",
                before + 1L, UnknownBlockStates.nullArrivals());
    }

    /**
     * Level two of the three the sibling slice offers: a count that cannot be acted on is half a
     * fix. The id is what tells the owner which registrations are missing, and here the id came
     * off the wire -- so this list is the one that names a block the client is being TOLD about
     * rather than one it stumbled into while reading a cell.
     */
    @Test
    public void theUnknownIdItselfIsNamedSoTheNumberCanBeActedOn() throws Exception {
        int unknownId = firstUnregisteredStateId();

        decodeSingle(unknownId);

        assertTrue("the id that arrived unresolvable must be named, or the count says only that "
                        + "something is wrong somewhere (ids="
                        + java.util.Arrays.toString(UnknownBlockStates.nullArrivalIds()) + ")",
                contains(UnknownBlockStates.nullArrivalIds(), unknownId));
    }

    /**
     * The reverse direction, and the reason this file is not a tautology: the guard must not be a
     * blanket. A counter that fires on every decode would report a healthy client as broken, and
     * the registry-miss signal would be worthless within a second of joining any server.
     */
    @Test
    public void aRegisteredIdOnTheWireIsNotCounted() throws Exception {
        // From the live registry, so this cannot rot into testing a copied constant.
        IBlockState stone = Blocks.stone.getDefaultState();
        int stoneId = Block.BLOCK_STATE_IDS.get(stone);
        assertTrue("stone must have a registered state id", stoneId >= 0);
        assertSame("the id under test must round-trip through the registry", stone,
                Block.BLOCK_STATE_IDS.getByValue(stoneId));

        long before = UnknownBlockStates.nullArrivals();

        assertSame("a registered id must still decode to its own block",
                stone, decodeSingle(stoneId).getBlockState());
        assertSame("through the multi change path too",
                stone, decodeMulti(stoneId).getChangedBlocks()[0].getBlockState());
        assertEquals("and neither must be counted as unknown -- a blanket count would make the "
                        + "signal worthless within a second of joining any server",
                before, UnknownBlockStates.nullArrivals());
    }

    /**
     * The same narrowness on the id LIST, which is a different code path inside the recording and
     * fails separately: a guard that checked the count but still fed every decode into the set
     * would leave the actionable list naming ids the client resolves perfectly well, and a reader
     * acting on that list would go register blocks that are already there.
     */
    @Test
    public void aRegisteredIdOnTheWireIsNotListedEither() throws Exception {
        int dirtId = Block.BLOCK_STATE_IDS.get(Blocks.dirt.getDefaultState());

        decodeSingle(dirtId);

        assertFalse("an id the registry resolves must never enter the list of ids the registry "
                        + "failed to resolve, or the list names blocks that are already registered",
                contains(UnknownBlockStates.nullArrivalIds(), dirtId));
    }

    /**
     * The separation itself, in the direction that protects the OLD census. A null arrival is not
     * a read, and folding it into {@code fallbackReads()} would make the read count claim work
     * this client is doing on a lie when it did none -- it dropped an update instead, which is a
     * different amount of nothing.
     */
    @Test
    public void aNullArrivalDoesNotMoveTheReadCount() throws Exception {
        long readsBefore = UnknownBlockStates.fallbackReads();

        decodeSingle(firstUnregisteredStateId());

        assertEquals("a decode that produced null is not a read that fell back, and counting it "
                        + "as one would describe two failures with one number",
                readsBefore, UnknownBlockStates.fallbackReads());
    }

    /** And in the direction that protects the NEW census from the old one's traffic. */
    @Test
    public void aFallbackReadDoesNotMoveTheNullArrivalCount() {
        long arrivalsBefore = UnknownBlockStates.nullArrivals();

        // The sibling slice's production site, driven for real: a stored unregistered id read back
        // through ExtendedBlockStorage.get.
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, true);
        int unknownId = firstUnregisteredStateId();
        storage.getData()[5 << 8 | 5 << 4 | 5] = (char) unknownId;
        storage.get(5, 5, 5);

        assertEquals("a read that fell back to air is not a decode that produced null; the two "
                        + "census halves must not contaminate each other",
                arrivalsBefore, UnknownBlockStates.nullArrivals());
    }

    /**
     * The reader contract, and the level the sibling slice calls inert if nobody reads it: the
     * count is reachable from outside the class that raises it, which is the whole point. A number
     * only the throwing site can see is not a fact, it is a comment.
     */
    @Test
    public void theCountIsReachableByACallerThatIsNotThePacketDecoder() throws Exception {
        // Measured as a DELTA, not an absolute, because the census is cumulative on purpose: a
        // chunk can be unloaded before anyone asks. An absolute-zero assertion would be asserting
        // that no earlier test ran, which is true of JUnit's ordering and nothing else.
        long atRest = UnknownBlockStates.nullArrivals();
        assertTrue("readable from outside the decoder at whatever value it holds", atRest >= 0L);

        decodeSingle(firstUnregisteredStateId());

        assertEquals("and a caller that is not S23PacketBlockChange sees it move",
                atRest + 1L, UnknownBlockStates.nullArrivals());
    }

    /**
     * The rendered line, which is what F3 actually shows. It is rendered every frame, so it has to
     * be truthful at zero rather than alarmist, and it has to carry the unit -- a reader who sees
     * "3" under a heading that does not say whether those were reads or arrivals cannot tell the
     * two failures apart, which is the entire reason this is a second line.
     */
    @Test
    public void theRenderedNullSummaryTracksItsCountAndNamesItsUnit() throws Exception {
        long atRest = UnknownBlockStates.nullArrivals();
        String atRestLine = UnknownBlockStates.summarizeNullArrivals();
        assertEquals("the at-rest wording and the zero count are one branch: if the count is zero "
                        + "the surface must say none, and if it is not the surface must not",
                atRest == 0L, atRestLine.endsWith("none"));

        int unknownId = firstUnregisteredStateId();
        long before = UnknownBlockStates.nullArrivals();
        decodeSingle(unknownId);

        String line = UnknownBlockStates.summarizeNullArrivals();
        assertTrue("the line must name the id it counted: " + line,
                line.contains(String.valueOf(unknownId)));
        assertTrue("and must carry the count so the size of the loss is visible: " + line
                        + " (expected " + (before + 1L) + ")",
                line.contains((before + 1L) + " arrivals"));
        assertFalse("a count above zero must not render the at-rest wording", line.endsWith("none"));
    }

    /**
     * The two F3 lines must be distinguishable by their own text, not only by their position.
     * This is the whole reason for a second line: a reader has to be able to answer "is the client
     * lying about cells, or is it dropping updates?" from the overlay without counting.
     */
    @Test
    public void theTwoRenderedLinesCannotBeConfusedWithEachOther() throws Exception {
        decodeSingle(firstUnregisteredStateId());

        String reads = UnknownBlockStates.summarize();
        String arrivals = UnknownBlockStates.summarizeNullArrivals();

        assertFalse("the air-fallback line and the null-arrival line must not be the same string, "
                        + "or a reader cannot tell the two failures apart",
                reads.equals(arrivals));
        assertTrue("the null-arrival line must say what its numbers are: " + arrivals,
                arrivals.startsWith("Unknown block ids off the wire:"));
        assertFalse("and the air-fallback line must not claim the arrivals unit: " + reads,
                reads.contains("arrivals"));
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