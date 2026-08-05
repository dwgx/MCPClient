package net.marcloud.mcp.core.util;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Reads one block and answers in THREE states, because vanilla answers in two and one of them is a
 * lie.
 *
 * <p><b>The defect this exists to make unrepeatable.</b> In 1.8.9 {@code World.getBlockState}
 * returns AIR for a position it cannot actually see: {@code isValid} failures answer air
 * (World.java:850-861,240-243) and an unloaded chunk resolves to {@code blankChunk}, an
 * {@code EmptyChunk} whose inherited lookup finds null storage and also answers air
 * (ChunkProviderClient.java:86). So "there is nothing here" and "I could not look here" arrive as
 * the same value, and every caller that treats air as a fact about terrain is wrong at the edge of
 * the loaded area.
 *
 * <p>Measured 2026-08-05: SEVEN files in core read {@code getBlockState} and NOT ONE of them asks
 * whether the chunk is loaded. This repo has already fixed this same conflation four times, but each
 * time at the REPORTING layer -- the {@code "unknown"} block-name sentinel, {@code effects},
 * {@code entities.left}, {@code self.air}. Those fixes stopped the lie from reaching the model. None
 * of them stopped it entering, because there was no shared way to read a block honestly. This is
 * that way.
 *
 * <p><b>Why a util and not a method on the planner.</b> The planner needs it, but so does anything
 * that decides where the player may step, dig, or build. A second copy of this rule is how the
 * BlockFinder name rule ended up implemented six times with three different failure answers
 * (handoff-2026-08-07 section 7). One rule, every path through it.
 *
 * <p>Every method takes the world explicitly and holds no state, so the same class serves the client
 * world and the server world. That matters more than it looks: the server validates and reverts what
 * the client predicts, so a caller that must not be rubber-banded has to ask the server side, and a
 * caller reporting what the player can see has to ask the client side. A util that captured one of
 * them would quietly pick a side (docs/debugging.md section 10 rule 2).
 */
public final class BlockProbe {

    private BlockProbe() {
    }

    /** What is at a position, with "could not look" kept separate from "nothing there". */
    public enum Solidity {
        /**
         * The position could not be read: chunk not loaded, out of world bounds, or the read threw.
         * NOT a statement about terrain. A caller must decide what to do about it and must not
         * silently fold it into either of the others.
         */
        UNKNOWN,
        /** Read successfully, and nothing with a collision body is there. */
        AIR,
        /** Read successfully, and something holds a player up. */
        SOLID;

        /** True only for a positively observed floor. UNKNOWN is not a floor. */
        public boolean holdsPlayerUp() {
            return this == SOLID;
        }

        /**
         * True only for positively observed emptiness.
         *
         * <p>UNKNOWN answers false here AND false to {@link #holdsPlayerUp()}, which is the point:
         * an unread cell is neither walkable nor standable, so a caller that only asks these two
         * questions gets safe behaviour without having to know the third state exists. A caller that
         * needs to distinguish "no route" from "no information" asks {@link #wasRead()}.
         */
        public boolean isEmptySpace() {
            return this == AIR;
        }

        /** Whether the answer is about the world at all. */
        public boolean wasRead() {
            return this != UNKNOWN;
        }
    }

    /**
     * The decision, as a pure function of the four facts a read produces.
     *
     * <p>Separated from {@link #at(World, BlockPos)} for one reason: {@code World} is abstract with a
     * constructor wanting a save handler, world info, a provider and a profiler, so nothing in
     * {@code core/src/test} can build one -- and a rule that cannot be tested headless is a rule
     * whose ordering nobody will ever check. The ordering is the whole point here (chunk-loaded
     * BEFORE the state read, because reading first has already manufactured the air that must be
     * distinguished), so it lives where a test can drive every combination.
     *
     * @param loaded          the chunk was loaded, i.e. the read was meaningful at all
     * @param stateReadable   a block state came back (null means the read failed)
     * @param air             the block's material is air
     * @param hasCollisionBox the block has a collision body, so it holds a player up
     */
    public static Solidity decide(boolean loaded, boolean stateReadable, boolean air,
                                  boolean hasCollisionBox) {
        if (!loaded || !stateReadable) {
            return Solidity.UNKNOWN;
        }
        if (air) {
            return Solidity.AIR;
        }
        return hasCollisionBox ? Solidity.SOLID : Solidity.AIR;
    }

    /**
     * Read one position.
     *
     * <p>The chunk check comes FIRST and is not optional: asking {@code getBlockState} before it
     * would already have produced the air that has to be distinguished, and no amount of inspecting
     * the result afterwards can recover which kind of air it was.
     */
    public static Solidity at(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return Solidity.UNKNOWN;
        }
        try {
            if (!world.isBlockLoaded(pos)) {
                return Solidity.UNKNOWN;
            }
            IBlockState state = world.getBlockState(pos);
            if (state == null) {
                return Solidity.UNKNOWN;
            }
            Block block = state.getBlock();
            if (block.getMaterial() == Material.air) {
                return Solidity.AIR;
            }
            // Collision box, NOT isFullCube(). Block.isFullCube() returns true unconditionally in
            // 1.8.9 and BlockAir does not override it, so a solidity test built on it reports air as
            // solid and can never fail -- this repo shipped a floor check with that bug and it
            // reported bad=0 while standing over a pit (handoff-2026-08-06 section 3(1)). The
            // collision box is also the rule LocalGrid.standable already uses, deliberately: vines,
            // ladders, torches, tall grass, rails and signs are non-solid and must not read as floor.
            return decide(true, true, false,
                    block.getCollisionBoundingBox(world, pos, state) != null);
        } catch (Throwable t) {
            // A read that threw is UNKNOWN, never SOLID and never AIR. Reporting solid would invent
            // a floor; reporting air would invent a hole. The reachable throw here is
            // ReportedException: Chunk.getBlockState wraps any storage-corruption throwable and
            // rethrows (Chunk.java:606-635).
            return Solidity.UNKNOWN;
        }
    }

    /** Convenience for integer coordinates. */
    public static Solidity at(World world, int x, int y, int z) {
        return at(world, new BlockPos(x, y, z));
    }

    /**
     * Whether a player body of {@code height} blocks fits at this position AND has a floor.
     *
     * <p>Kept here rather than in the planner because "can something stand here" is asked by digging,
     * building and navigation alike, and three copies would drift. An UNKNOWN anywhere in the column
     * makes the answer false: a stance that depends on a cell nobody read is not a stance.
     */
    public static boolean isStandable(World world, BlockPos feet, int height) {
        if (!at(world, feet.down()).holdsPlayerUp()) {
            return false;
        }
        for (int dy = 0; dy < height; dy++) {
            if (!at(world, feet.up(dy)).isEmptySpace()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every position in the inclusive box was readable.
     *
     * <p>For callers that must report "I could not see all of this" as distinct from a result. A
     * planner that returns "no route" for terrain it never read is making a claim about the world it
     * has not earned, and the caller cannot tell the difference without this.
     */
    public static boolean allRead(World world, BlockPos from, BlockPos to) {
        int x0 = Math.min(from.getX(), to.getX());
        int x1 = Math.max(from.getX(), to.getX());
        int y0 = Math.min(from.getY(), to.getY());
        int y1 = Math.max(from.getY(), to.getY());
        int z0 = Math.min(from.getZ(), to.getZ());
        int z1 = Math.max(from.getZ(), to.getZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (!at(world, x, y, z).wasRead()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
