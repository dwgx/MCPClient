package net.marcloud.mcp.core.drivers.world;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import net.marcloud.mcp.core.util.BlockProbe;

/**
 * One position, read as a decision rather than as a block id.
 *
 * <p>The question this exists to answer is "can I stand here, and can something spawn here", and
 * neither half of it is a block name. Vanilla's own spawn gate
 * ({@code EntityMob.getCanSpawnHere}, {@code EntityMob.java:147-177}) ANDs three independent facts:
 *
 * <ul>
 *   <li>a solid floor underneath -- {@code getBlockPathWeight(pos) >= 0};</li>
 *   <li>room for a body -- {@code EntityLiving.isNotColliding()};</li>
 *   <li>{@code isValidLightLevel}, which needs the light AT the position to be 7 or below. Low
 *       light is what makes a cell spawnable, so the comparison runs the opposite way from the
 *       intuition, and thunder's override makes midday read as night.</li>
 * </ul>
 *
 * <p>An agent handed three numbers has to re-derive that conjunction itself, every time, and this
 * repo has got it wrong three separate ways -- twice by measuring light wrongly and once by
 * treating a constant-returning predicate as terrain. So the conjunction lives here, once, and the
 * numbers travel with it as the evidence.
 *
 * <p><b>Light, precisely.</b> {@code getLightSubtracted(pos, amount)} computes
 * {@code max(storedSky - amount, storedBlock)}. The stored skylight is ALREADY propagated -- it
 * accounts for what is overhead -- so {@code amount} is neither shade nor daylight, and to read the
 * stored value you pass {@code 0}. Passing 15 reports 0 at noon on open grass; passing 0 reports 15
 * seven blocks down. Either one inverts the spawn gate. The gate's two raw inputs are read
 * separately here ({@code getLightFor(SKY, pos)} and {@code getLightFromNeighbors(pos)}) so the
 * reported numbers are the ones the gate actually compares, not a convenient total.
 *
 * <p><b>Solidity is delegated.</b> The collision-box rule belongs to {@link BlockProbe}, which
 * already encodes why {@code Block.isFullCube()} cannot be used in 1.8.9 (it returns true
 * unconditionally and {@code BlockAir} does not override it, so air reads as solid). A second
 * implementation here would be a second answer to the same question, which is how this repo ended
 * up deleting six copies of one block-name rule.
 *
 * <p><b>Reference-free.</b> Every field is a primitive, String or enum, so the record crosses the
 * tool boundary without a live {@code net.minecraft} object and the handler can be tested headlessly.
 */
public final class BlockInspector {

    /**
     * Why a cell is or is not a legal mob spawn. The reason is the point, not the boolean.
     *
     * <p>{@code NO_FLOOR} and {@code NO_ROOM} are refusals of <i>now</i>, not of ever: a hole dug
     * under a shelter is where the mob comes in. That distinction is the whole reason this is an
     * enum and not a boolean.
     */
    public enum Spawn {
        /** Both light stores above 7, a solid floor below, and room for a body. */
        YES,
        /** The light store(s) are at or below 7. */
        LIGHT,
        /** No solid ground underneath. */
        NO_FLOOR,
        /** This cell, or the one above it, is not air. */
        NO_ROOM,
        /** The position could not be read; nothing is claimed about it. */
        UNKNOWN;

        public boolean allowed() {
            return this == YES || this == LIGHT;
        }

        /**
         * The sentence a model reads, carrying the numbers that produced it.
         *
         * <p>Formatted per call rather than baked into the constant: a message that says "both must
         * exceed 7" without saying what they actually were is the same failure as returning the
         * verdict alone -- the caller cannot tell a cell that missed by one from one that missed by
         * nine, and those want different amounts of work.
         */
        public String because(int lightAtPos, int blockLight, boolean thundering,
                boolean floorBelow, boolean roomForBody) {
            String light = "light at the cell=" + lightAtPos + " (must be 7 or below)"
                    + (thundering ? ", thunder forcing skylightSubtracted to 10 so midday "
                            + "reads as night" : "");
            return switch (this) {
                case YES -> "a hostile CAN spawn here: " + light
                        + ", solid floor below, room for a body";
                case LIGHT -> "a hostile can spawn here: " + light;
                case NO_FLOOR -> "a hostile cannot spawn here yet, and nothing stops it LATER: no "
                        + "solid floor below, and a mob needs solid ground under it";
                case NO_ROOM -> "a hostile cannot spawn here yet, and nothing stops it LATER: no "
                        + "room for a body in this cell or the one above";
                case UNKNOWN -> "the position could not be read; nothing is claimed about it";
            };
        }
    }

    /**
     * The whole report. Nulls never appear: an unreadable position is {@link Spawn#UNKNOWN} with
     * {@code block=null} and every boolean false, so a caller cannot mistake "could not read" for
     * "nothing there".
     */
    public record Report(
            int x, int y, int z,
            String block,
            String display,
            BlockProbe.Solidity solidity,
            boolean collisionBox,
            int skyLight,
            int blockLight,
            int effectiveLight,
            boolean seesSky,
            boolean floorBelow,
            boolean roomForBody,
            boolean thundering,
            Spawn spawn,
            String spawnWhy) {

        /** Whether a player could stand in this cell, as this report understands it. */
        public boolean standable() {
            return solidity == BlockProbe.Solidity.AIR && floorBelow && roomForBody;
        }
    }


    /**
     * Read one position.
     *
     * <p>Must be called on the game thread: {@code World.getBlockState} and the light store are
     * not thread-safe, and reading them off-thread is how this project produced a probe that
     * silently returned empty answers for hours.
     */
    public static Report inspect(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockProbe.Solidity solidity = BlockProbe.at(world, pos);
        if (world == null || solidity == BlockProbe.Solidity.UNKNOWN) {
            return new Report(x, y, z, null, null, BlockProbe.Solidity.UNKNOWN, false,
                    -1, -1, -1, false, false, false, false, Spawn.UNKNOWN,
                    "the position could not be read; nothing is claimed about it");
        }

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        boolean collision = block.getCollisionBoundingBox(world, pos, state) != null;

        int sky = world.getLightFor(EnumSkyBlock.SKY, pos);
        int blockLight = world.getLightFromNeighbors(pos);
        // Chunk.getLightSubtracted(pos, amount) is max(storedSky - amount, storedBlock), and World
        // exposes it only through a private path (World.java:617), so the same conjunction is
        // computed here from the two raw stores -- with amount 0, which is the only value that
        // reads the stored sky rather than a shade factor. Both numbers travel in the report, so
        // this is checkable rather than a second place to be wrong.
        int effective = Math.max(sky, blockLight);
        boolean seesSky = world.canBlockSeeSky(pos);

        boolean floorBelow = BlockProbe.at(world, pos.down()) == BlockProbe.Solidity.SOLID;
        boolean roomForBody = BlockProbe.at(world, pos) == BlockProbe.Solidity.AIR
                && BlockProbe.at(world, pos.up()) == BlockProbe.Solidity.AIR;

        boolean thundering = world.isThundering();
        int skylightAmount = skylightAmountFor(world, thundering);
        int gateLight = lightAtPosition(sky, blockLight, skylightAmount);

        Spawn spawn;
        String why;
        if (!floorBelow) {
            spawn = Spawn.NO_FLOOR;
        } else if (!roomForBody) {
            spawn = Spawn.NO_ROOM;
        } else {
            spawn = gatePasses(sky, blockLight, skylightAmount) ? Spawn.YES : Spawn.LIGHT;
        }
        why = spawn.because(gateLight, blockLight, thundering, floorBelow, roomForBody);

        return new Report(x, y, z, nameOf(block), displayOf(block), solidity, collision,
                sky, blockLight, effective, seesSky, floorBelow, roomForBody, thundering,
                spawn, why);
    }

    /**
     * {@code EntityMob.isValidLightLevel} ({@code EntityMob.java:145-165}), condensed.
     *
     * <p>Vanilla's shape, kept in its own order because each step can end the answer:
     *
     * <ol>
     *   <li>{@code getLightFor(SKY, pos) > rand(32)} ends it. The raw stored sky is compared with a
     *       0..31 draw, so only a value 1.8.9 never produces fails here. It is not the interesting
     *       test and must not be mistaken for one.</li>
     *   <li>{@code getLightFromNeighbors(pos)} is {@code getLight(pos, true)}, which bottoms out at
     *       {@code chunk.getLightSubtracted(pos, skylightSubtracted)}.</li>
     *   <li>Under thunder the SAME call is made again with {@code skylightSubtracted} forced to 10.
     *       That is the entire effect of a storm on spawning: at noon a stored sky of 15 is reduced
     *       to 5, which is under the threshold, so a thunderstorm opens midday spawns that a clear
     *       day closes. "Night is dangerous" is the half of this that gets remembered.</li>
     *   <li>The verdict is {@code i <= rand(8)}, so a cell spawns at 7 or below.</li>
     * </ol>
     *
     * <p>So the comparison is {@code <= 7}, not {@code > 7}. Transcribing it backwards inverts the
     * gate completely, and that is exactly what happened the first time this was written.
     *
     * @param storedSky      {@code getLightFor(SKY, pos)}: the propagated stored value
     * @param storedBlock    the block-light store on its own
     * @param skylightAmount {@code getSkylightSubtracted()}, or 10 while thundering
     */
    public static boolean gatePasses(int storedSky, int storedBlock, int skylightAmount) {
        return lightAtPosition(storedSky, storedBlock, skylightAmount) <= 7;
    }

    /** {@code chunk.getLightSubtracted}: the combined light the gate actually compares. */
    public static int lightAtPosition(int storedSky, int storedBlock, int skylightAmount) {
        return Math.max(storedSky - skylightAmount, storedBlock);
    }

    /**
     * The skylight factor the gate reads with: 10 under thunder, otherwise whatever the time of day
     * currently implies. {@code EntityMob} saves, forces and restores the world's own field, so a
     * storm overrides the hour rather than adding to it.
     */
    public static int skylightAmountFor(World world, boolean thundering) {
        return skylightAmountFor(world.getSkylightSubtracted(), thundering);
    }

    /**
     * The rule itself, with the world's own reading passed in rather than read here.
     *
     * <p>Separated so the thunder branch is reachable by a test. Reading it off the {@code World}
     * in the only version meant the branch was never exercised headlessly, and a mutation that
     * deleted it entirely ran green.
     */
    public static int skylightAmountFor(int currentAmount, boolean thundering) {
        return thundering ? 10 : currentAmount;
    }


    private static String nameOf(Block block) {
        try {
            return String.valueOf(Block.blockRegistry.getNameForObject(block));
        } catch (Throwable t) {
            return block.getClass().getSimpleName();
        }
    }

    private static String displayOf(Block block) {
        try {
            return block.getLocalizedName();
        } catch (Throwable t) {
            return nameOf(block);
        }
    }

    private BlockInspector() {
    }
}
