package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.BlockPos;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.UnknownBlockStates;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Wave 21: what the {@code null} these two decoders hand over actually DOES, measured rather than
 * assumed.
 *
 * <p><b>The finding this pins.</b> The null is not a slow poison that reaches a render path or a
 * pathfinder -- it does not travel at all. It raises at the first dereference, on the line
 * immediately after the position is validated, <b>before the chunk is consulted</b>:
 * {@code World.setBlockState} reads {@code newState.getBlock()} at
 * {@code World.java:358}, one line above the {@code chunk.setBlockState} call at
 * {@code World.java:359}. So no {@code null} is ever stored, nothing downstream ever sees one, and
 * the shape of the damage is entirely in what gets DROPPED.
 *
 * <p><b>Three consequences, each a different failure from an air fallback, and all three silent at
 * the layer where a reader would look:</b>
 *
 * <ol>
 *   <li><b>The update is lost and the cell keeps its previous value.</b> The server said the cell
 *       changed; the client renders whatever was there before.</li>
 *   <li><b>One unknown id can abandon the rest of a multi-block change.</b>
 *       {@code NetHandlerPlayClient.handleMultiBlockChange}
 *       ({@code NetHandlerPlayClient.java:734-742}) iterates every entry with no guard, so a raise
 *       on entry <i>i</i> means entries <i>i+1..n</i> are never applied. A 1.8.9 S22 carries up to
 *       64 entries, so a single unknown id can cost up to 63 legitimate updates that had nothing
 *       wrong with them.</li>
 *   <li><b>The cell the server named then reads as air, and the air-fallback census never sees
 *       it.</b> The write is dropped, so the cell is never written, so
 *       {@code ExtendedBlockStorage.get} is never asked to resolve anything --
 *       {@link UnknownBlockStates#fallbackReads()} does not move by one. This is the sharpest
 *       reason the two events need separate counts: the air lie has <b>two producers</b>, and the
 *       census built in {@code 6389b29} can only see one of them.</li>
 * </ol>
 *
 * <p><b>Why the raise is pinned and not merely reported.</b> "A null arrives and is dropped" is a
 * story a reader will attach to a raise they have seen in a log, and the attachment is wrong in
 * the direction that matters: the natural assumption is that the null poisons some later reader,
 * and the truth is that it poisons nothing at all and costs updates instead. A claim with no test
 * is a claim, and {@code docs/agency/failure-shapes.md} §3.10 says report it as unenforced or do
 * not report it.
 *
 * <p><b>The harness caches its chunks, and that is load-bearing.</b> An earlier version of this
 * file handed {@code provideChunk} a fresh {@code EmptyChunk} on every call, so writes landed in a
 * throwaway chunk and reads came back as air -- which would have made "the cell reads air" look
 * true for a reason that had nothing to do with the defect. The provider below keeps one chunk per
 * coordinate, so a cell that reads air after a dropped update reads air <b>because the update was
 * dropped</b>. {@link #aRegisteredWriteIsVisibleInThisHarness()} pins the harness itself against
 * that specific false pass.
 */
public final class VendoredUnknownBlockIdOnTheWireDropsTheUpdateTest {

    private static WorldClient clientWorld;

    /**
     * A chunk provider that CACHES. See the class javadoc: without the cache a write lands in a
     * throwaway chunk and every read answers air, which would make this file pass for the wrong
     * reason.
     */
    private static final class CachingChunkProvider implements IChunkProvider {
        private final World world;
        private final Map<Long, Chunk> chunks = new HashMap<Long, Chunk>();

        CachingChunkProvider(World world) {
            this.world = world;
        }

        public boolean chunkExists(int x, int z) {
            return true;
        }

        public Chunk provideChunk(int x, int z) {
            long key = ChunkCoordKey.of(x, z);
            Chunk chunk = this.chunks.get(Long.valueOf(key));

            if (chunk == null) {
                chunk = new Chunk(this.world, x, z);
                this.chunks.put(Long.valueOf(key), chunk);
            }

            return chunk;
        }

        public Chunk provideChunk(BlockPos pos) {
            return this.provideChunk(pos.getX() >> 4, pos.getZ() >> 4);
        }

        public void populate(IChunkProvider provider, int x, int z) { }

        public boolean populateChunk(IChunkProvider provider, Chunk chunk, int x, int z) {
            return false;
        }

        public boolean saveChunks(boolean all, IProgressUpdate callback) {
            return true;
        }

        public boolean unloadQueuedChunks() {
            return false;
        }

        public boolean canSave() {
            return false;
        }

        public String makeString() {
            return "test";
        }

        public List<BiomeGenBase.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) {
            return null;
        }

        public BlockPos getStrongholdGen(World world, String name, BlockPos pos) {
            return null;
        }

        public int getLoadedChunkCount() {
            return this.chunks.size();
        }

        public void recreateStructures(Chunk chunk, int x, int z) { }

        public void saveExtraData() { }
    }

    /** Packs a chunk coordinate the way the tree does elsewhere, without depending on the class. */
    private static final class ChunkCoordKey {
        static long of(int x, int z) {
            return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
        }
    }

    @BeforeClass
    public static void bootstrapRegistries() {
        Bootstrap.register();
        assertTrue("bootstrap should have registered", Bootstrap.isRegistered());

        WorldSettings settings = new WorldSettings(0L, WorldSettings.GameType.SURVIVAL,
                true, false, WorldType.DEFAULT);
        clientWorld = new WorldClient(null, settings, 0, EnumDifficulty.NORMAL, new Profiler());
        injectCachingProvider(clientWorld);
    }

    private static void injectCachingProvider(WorldClient world) {
        try {
            java.lang.reflect.Field field = World.class.getDeclaredField("chunkProvider");
            field.setAccessible(true);
            field.set(world, new CachingChunkProvider(world));
        } catch (Exception e) {
            throw new IllegalStateException("could not install the caching chunk provider", e);
        }
    }

    @Before
    public void freshCoordinates() {
        // Every test uses its own coordinates, so no test can pass on a cell a previous test wrote.
    }

    private static int firstUnregisteredStateId() {
        int highestRegistered = 0;

        for (Block block : Block.blockRegistry) {
            highestRegistered = Math.max(highestRegistered, Block.blockRegistry.getIDForObject(block));
        }

        return (highestRegistered + 1) << 4;
    }

    /** The production receiver, called exactly as {@code handleBlockChange} calls it. */
    private static S23PacketBlockChange decodeSingleUnknown(int stateId) throws Exception {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeBlockPos(new BlockPos(10, 64, -20));
        buf.writeVarIntToBuffer(stateId);

        S23PacketBlockChange packet = new S23PacketBlockChange();
        packet.readPacketData(buf);
        return packet;
    }

    /**
     * The harness control. Without this, "the cell reads air after a dropped update" is
     * unfalsifiable in this file -- an air answer is exactly what a harness that discards writes
     * also produces. This pins that a REGISTERED update is visible, so an air answer in the tests
     * below can only have come from the drop.
     */
    @Test
    public void aRegisteredWriteIsVisibleInThisHarness() {
        BlockPos pos = new BlockPos(200, 64, -200);

        assertTrue("a registered write must be accepted by the receiver under test",
                clientWorld.invalidateRegionAndSetBlock(pos, Blocks.stone.getDefaultState()));
        assertSame("and must be readable afterwards -- if this fails, every other air answer in "
                        + "this file is the harness's fault rather than the defect's",
                Blocks.stone.getDefaultState(), clientWorld.getBlockState(pos));
    }

    /**
     * Finding (1): the null raises at the first dereference, and the cell is left untouched. The
     * message names the line because the line IS the finding -- a future reader who assumes the
     * null reaches a later stage should be corrected here rather than by their own debugging.
     */
    @Test
    public void theNullRaisesBeforeTheCellIsWrittenAndLeavesItAlone() throws Exception {
        BlockPos pos = new BlockPos(210, 64, -200);
        clientWorld.invalidateRegionAndSetBlock(pos, Blocks.stone.getDefaultState());

        S23PacketBlockChange packet = decodeSingleUnknown(firstUnregisteredStateId());
        org.junit.Assert.assertNull("precondition: the decode still answers null", packet.getBlockState());

        try {
            clientWorld.invalidateRegionAndSetBlock(pos, packet.getBlockState());
            fail("the null must raise rather than be absorbed; if it no longer does, the "
                    + "wire-behaviour answer has changed and this file's finding is stale");
        } catch (NullPointerException expected) {
            // The claim is WHICH line raises. World.setBlockState reads newState.getBlock() at
            // World.java:358, one line above the chunk write at World.java:359, so the null
            // cannot have been stored and nothing downstream can be holding one.
            StackTraceElement[] trace = expected.getStackTrace();
            boolean raisedInWorld = false;

            for (StackTraceElement frame : trace) {
                if ("net.minecraft.world.World".equals(frame.getClassName())
                        && "setBlockState".equals(frame.getMethodName())) {
                    raisedInWorld = true;
                    break;
                }
            }

            assertTrue("the null must raise inside World.setBlockState, which is the line above "
                    + "the chunk write -- that ordering is why no null is ever stored: "
                    + firstWorldFrame(trace), raisedInWorld);
        }

        assertSame("and the cell must still hold what it held: the update was DROPPED, not applied",
                Blocks.stone.getDefaultState(), clientWorld.getBlockState(pos));
    }

    /**
     * Finding (3), and the reason the two censuses are separate numbers. The cell the server
     * named ends up reading as air -- and the air-fallback census does not move, because
     * {@code ExtendedBlockStorage.get} was never asked. An owner watching
     * {@link UnknownBlockStates#fallbackReads()} alone would see a healthy zero on a client that
     * is silently mis-rendering a cell.
     */
    @Test
    public void theDroppedCellReadsAsAirAndTheFallbackCensusNeverSeesIt() throws Exception {
        BlockPos pos = new BlockPos(220, 64, -200);
        long readsBefore = UnknownBlockStates.fallbackReads();
        long arrivalsBefore = UnknownBlockStates.nullArrivals();

        S23PacketBlockChange packet = decodeSingleUnknown(firstUnregisteredStateId());
        try {
            clientWorld.invalidateRegionAndSetBlock(pos, packet.getBlockState());
            fail("precondition: the drop is expected to raise");
        } catch (NullPointerException expected) {
            // expected
        }

        assertSame("the cell reads air, which is indistinguishable from a world that is really "
                        + "empty -- unless you have a count that says otherwise",
                Blocks.air.getDefaultState(), clientWorld.getBlockState(pos));
        assertEquals("and the air-fallback census must NOT have moved: the write was dropped "
                        + "before any cell was read, so no registry miss was ever asked about. "
                        + "This is the second producer of the same air lie, and the first census "
                        + "is blind to it by construction",
                readsBefore, UnknownBlockStates.fallbackReads());
        // A DELTA, not an absolute: the census is cumulative by design, so an absolute-zero
        // assertion here would be asserting that no earlier test in this JVM ran, which is true
        // of JUnit's ordering and nothing else. Measured across THIS decode, it holds whatever
        // the other tests left behind -- and unlike an absolute, it still goes red if the
        // recording at the decode site is removed.
        assertEquals("while the null-arrival census, which was raised at the decode site, did "
                        + "move by exactly one",
                arrivalsBefore + 1L, UnknownBlockStates.nullArrivals());
    }

    /**
     * Finding (2): the blast radius. This loop is the shape of
     * {@code handleMultiBlockChange}'s, driven against the real receiver, so the count of applied
     * updates is measured rather than argued.
     */
    @Test
    public void oneUnknownIdAbandonsEveryLaterEntryInTheSameBatch() throws Exception {
        // Entry 0 is a perfectly good stone the server told us about; entry 1 is the unknown id;
        // entry 2 is a perfectly good dirt. Only entry 2 is collateral damage.
        IBlockState[] entries = {
            Blocks.stone.getDefaultState(),
            decodeSingleUnknown(firstUnregisteredStateId()).getBlockState(),
            Blocks.dirt.getDefaultState(),
        };
        BlockPos[] positions = {
            new BlockPos(230, 64, -200),
            new BlockPos(231, 64, -200),
            new BlockPos(232, 64, -200),
        };

        int applied = 0;
        try {
            for (int i = 0; i < entries.length; ++i) {
                clientWorld.invalidateRegionAndSetBlock(positions[i], entries[i]);
                applied++;
            }

            fail("the batch must abort at the unknown id; if it completes, the receiver has "
                    + "started substituting and this file's finding is stale");
        } catch (NullPointerException expected) {
            assertEquals("the batch aborts at the unknown id, so everything after it is lost -- "
                            + "and an S22 carries up to 64 entries, so one unknown id can cost up "
                            + "to 63 updates that were themselves perfectly well formed",
                    1, applied);
        }

        assertSame("the entry BEFORE the unknown id was applied -- the raise is not a rollback",
                Blocks.stone.getDefaultState(), clientWorld.getBlockState(positions[0]));
        assertSame("and the entry AFTER it was never applied, so the server and the client now "
                        + "disagree about a block that had no problem at all",
                Blocks.air.getDefaultState(), clientWorld.getBlockState(positions[2]));
    }

    /**
     * The multi-change decoder is the site that makes finding (2) reachable, so it gets the same
     * treatment as the single one: a null among its entries, observed through the real handler
     * loop rather than asserted about.
     */
    @Test
    public void theMultiBlockChangeCarriesTheSameNullIntoTheSameAbort() throws Exception {
        int unknownId = firstUnregisteredStateId();
        int stoneId = Block.BLOCK_STATE_IDS.get(Blocks.stone.getDefaultState());

        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeInt(0);
        buf.writeInt(0);
        buf.writeVarIntToBuffer(2);
        buf.writeShort(0);
        buf.writeVarIntToBuffer(stoneId);
        buf.writeShort(1 << 12);
        buf.writeVarIntToBuffer(unknownId);

        S22PacketMultiBlockChange packet = new S22PacketMultiBlockChange();
        packet.readPacketData(buf);
        assertEquals("precondition: two entries", 2, packet.getChangedBlocks().length);

        int applied = 0;
        try {
            for (S22PacketMultiBlockChange.BlockUpdateData data : packet.getChangedBlocks()) {
                clientWorld.invalidateRegionAndSetBlock(data.getPos(), data.getBlockState());
                applied++;
            }
        } catch (NullPointerException expected) {
            // The handler's loop has no guard; this is the raise that ends it.
        }

        assertEquals("so the multi change applies only the entries before the unknown one",
                1, applied);
    }

    private static String firstWorldFrame(StackTraceElement[] trace) {
        for (StackTraceElement frame : trace) {
            if ("net.minecraft.world.World".equals(frame.getClassName())) {
                return frame.toString();
            }
        }

        return "(no World frame on the stack)";
    }
}