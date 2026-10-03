package net.marcloud.mcp.core.eval;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

/**
 * The one {@link Enclosure.CellGrid} that reads a LIVE client world, and the place the cost of
 * measuring enclosure on a running game is written down.
 *
 * <p><b>Why this class exists at all.</b> {@link Enclosure.CellGrid} takes a cell lookup, and the
 * eval substrate supplies one for free ({@code SimWorld}'s own grid). A live client has no such
 * thing: its cells live in chunk storage, and the honest price of asking is
 * {@code isBlockLoaded} + {@code getBlockState} per cell, which is why {@code LocalGrid} keeps a
 * {@link LocalGrid#NAME_UNREADABLE} sentinel and why {@code WorldScanner.blockName:126-140} reads
 * {@code isBlockLoaded} FIRST rather than letting {@code getBlockState} manufacture air for a
 * chunk it cannot see. This class keeps that order, because dropping it is how an unreadable
 * position becomes a phantom block.
 *
 * <p><b>The two seams, and why they are not one.</b> The definition of enclosure asks two questions
 * and they have wildly different prices on a live world:
 *
 * <ul>
 *   <li><b>"can a body walk into this cell?"</b> Eight cells per sample, and there is no cache:
 *       vanilla keeps no solidity map, so this is {@code isBlockLoaded} + {@code getBlockState}
 *       eight times, unconditionally. {@link Enclosure#at} reads eight cells because that IS the
 *       definition, and the {@link Enclosure.Verdict} record exists so a failure can say
 *       "3 of 8 sides open" rather than a bare false.</li>
 *   <li><b>"can this cell see the sky?"</b> {@link Chunk#canSeeSky} answers it in one comparison
 *       against {@code heightMap[z << 4 | x]}, and {@code heightMap} is exactly the column scan
 *       {@link Enclosure.CellGrid#skyClosedAt} walks: {@code Chunk.generateHeightMap:210-241} walks
 *       down from the top of the world and stops at the first cell whose
 *       {@code getLightOpacity() != 0}, storing the cell just above it. {@code
 *       Chunk.relightBlock} is called from {@code Chunk.setBlockState:738,743} whenever a block's
 *       opacity changes, so a roof the body just built under is in the map on the next read. The
 *       walk costs up to 191 cells on an open plain at y=64; this costs zero.</li>
 * </ul>
 *
 * <p><b>The read order that is not negotiable.</b> {@code isBlockLoaded} before
 * {@code getBlockState}, and an unreadable cell answers {@code null} rather than air. Air and
 * "could not read" are different facts and {@code Enclosure} treats them differently on purpose:
 * a null cell is not {@link Enclosure.CellGrid#solidAt} solid, so it counts as a missing wall.
 * That is the SAFE direction -- an unreadable neighbour must never read as a closed side, or the
 * ledger would record a wall that nobody verified.
 *
 * <p><b>Why the world is a settable field and not a constructor-only argument.</b> It WAS final,
 * and the wiring had to build a fresh view on every tick to pick up a changed world -- two
 * allocations per tick on the game's own hot path, for an object that is nothing but a
 * {@code WorldClient} reference. This repo has already decided what it thinks of that:
 * {@code EventBus} carries a per-class dispatch cache purely so {@code publish} -- documented
 * there as "on the per-tick / per-packet game-thread path" -- does "no per-event type filtering
 * or allocation" ({@code EventBus.java:20-26}). A per-tick allocation is not automatically
 * wrong ({@code Timeline} records one Entry per event on purpose), but it is wrong when it buys
 * nothing: this object is pure plumbing, so the wiring builds it ONCE and calls
 * {@link #world(WorldClient)} when the client changes worlds. The mutability is confined by the
 * same constraint the class already documents -- GAME THREAD ONLY, written and read inside one
 * tick handler -- which is also the only thread that ever sees it.
 *
 * <p><b>Threading.</b> Every method here reads live chunk state and is therefore GAME-THREAD ONLY,
 * the same constraint {@code WorldViewCapture} documents on its class. {@link NightShelter} is the
 * only caller, and it is driven from the tick seam.
 */
public final class WorldBlockView implements Enclosure.CellGrid {

    /**
     * The live world this view reads.
     *
     * <p>Null until the first {@link #world(WorldClient)}, and null-tolerant on purpose: the
     * client is not in a world at startup and between worlds, and every method below already had
     * to answer "I could not read that cell" for an unloaded chunk. A null world answers it the
     * same way, so the tick handler needs no separate null branch and no separate state.
     */
    private volatile WorldClient world;

    public WorldBlockView(WorldClient world) {
        this.world = world;
    }

    /**
     * Point this view at a world, or at null when the client is not in one.
     *
     * <p>Exists so the wiring can build the view once and re-point it, instead of allocating a
     * view per tick. {@code NightShelter} calls this only from its tick handler, i.e. on the game
     * thread, so the {@code volatile} costs one store per tick on a path that already does eight
     * block reads, and buys the guarantee that a reader on another thread sees a whole world
     * reference rather than a torn one.
     */
    public void world(WorldClient world) {
        this.world = world;
    }

    /**
     * The vanilla block at a cell, or null for air AND for a cell that could not be read.
     *
     * <p>{@code isBlockLoaded} first, in the order {@code WorldScanner.blockName} documents and
     * for the reason it gives: {@code getBlockState} answers air for a position it cannot see, so
     * reading first manufactures the air and a later census counts a volume nobody looked at.
     *
     * <p>A null world answers null, which is the same answer an unloaded chunk gets. This is why
     * the field is settable rather than constructor-final, and why that is safe: the one caller
     * re-points this view at null exactly when {@code NightShelter.onTick} has already decided
     * not to sample, so the branch is unreachable on the tick path -- but a public grid must not
     * answer a NullPointerException to a caller that did nothing wrong.
     */
    @Override
    public Block at(int bx, int by, int bz) {
        if (world == null) {
            return null;
        }
        BlockPos pos = new BlockPos(bx, by, bz);
        return world.isBlockLoaded(pos) ? world.getBlockState(pos).getBlock() : null;
    }

    /**
     * The sky half, from the chunk's own height map rather than by walking the column.
     *
     * <p>{@code canSeeSky} returns {@code pos.getY() >= heightMap[z << 4 | x]}, and
     * {@code heightMap} holds the y just above the topmost light-blocking cell in that column, so
     * {@code !canSeeSky(head)} is "there is a light-blocking cell at or above the head" --
     * {@link Enclosure.CellGrid#skyClosedAt}'s own predicate, asked of the cache vanilla already
     * maintains for it.
     *
     * <p><b>The loaded-chunk guard, and why {@code isBlockLoaded} is not it.</b> On a client,
     * {@code ChunkProviderClient.chunkExists} returns {@code true} for every coordinate
     * ({@code ChunkProviderClient.java:42-45}), so {@code World.isBlockLoaded} short-circuits to
     * true for any in-bounds position ({@code World.java:322-325}) and tells us nothing. What
     * actually distinguishes "no chunk here" is that
     * {@code ChunkProviderClient.provideChunk} hands back its shared {@link EmptyChunk}
     * ({@code ChunkProviderClient.java:83-87}) -- and that chunk answers {@code canSeeSky} with
     * {@code false} unconditionally ({@code EmptyChunk.java:102-104}), so without the explicit
     * {@code instanceof} below, {@code !canSeeSky} is {@code TRUE} for a chunk that does not
     * exist and an unloaded column is reported as <b>roofed</b>. That is the one direction this
     * whole class exists to refuse, and it was live in the wave-22 draft.
     *
     * <p>The good news from the same trace: {@code provideChunk} never GENERATES or requests a
     * chunk, it only looks one up, so the cost question the audit left open ("confirm it does
     * not generate a chunk") is settled -- it does not.
     */
    @Override
    public boolean skyClosedAt(int bx, int headY, int bz) {
        if (world == null) {
            return false;
        }
        BlockPos head = new BlockPos(bx, headY, bz);
        if (!world.isBlockLoaded(head)) {
            return false;
        }
        return skyClosedIn(world.getChunkFromBlockCoords(head), head);
    }

    /**
     * THE SKY PREDICATE, on its own so a test exercises the real line rather than a copy of it.
     *
     * <p>Extracted because the guard inside it is the whole safety argument of this class, and a
     * test that re-spells the predicate in its own source proves nothing: the first version of
     * {@code AnUnloadedColumnIsNotAShelterTest} inlined both the guarded and the unguarded form
     * and stayed green when the guard was deleted from here. The substitution is now visible at
     * the call site AND reachable from a test, which is the same reason
     * {@code Enclosure.CellGrid.skyClosedAt} was extracted in the first place.
     *
     * @param chunk the chunk {@code provideChunk} handed back, which for coordinates with no
     *              chunk is the provider's shared {@link EmptyChunk} and NOT null
     * @return whether that chunk's column above {@code head} is closed: false for null, false for
     *         an {@link EmptyChunk}, and otherwise {@code !canSeeSky(head)}
     */
    static boolean skyClosedIn(Chunk chunk, BlockPos head) {
        // An absent chunk arrives as the shared EmptyChunk, and it reports canSeeSky=false for
        // every coordinate -- which would invert into "roofed". Read the column only when a real
        // chunk is there.
        return chunk != null && !(chunk instanceof EmptyChunk) && !chunk.canSeeSky(head);
    }
}