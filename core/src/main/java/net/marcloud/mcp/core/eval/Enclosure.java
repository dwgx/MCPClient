package net.marcloud.mcp.core.eval;

import net.minecraft.block.Block;

/**
 * Whether a body is enclosed, measured from the block grid, with the definition written down
 * rather than implied.
 *
 * <p><b>The definition is the claim, so it is stated in full and the rejected alternatives are
 * named.</b> A player who says "I had a shelter" is not saying "there was a block above me". They
 * are saying two things at once: <em>I could not see the sky</em> and <em>nothing could get in
 * from the side</em>. Both halves are here, they are separate booleans, and neither is allowed to
 * stand in for the other:
 *
 * <ul>
 *   <li><b>Sky-closed.</b> Transcribed from vanilla's own question. {@code Chunk.canSeeSky}
 *       ({@code Chunk.java:904-910}) answers {@code pos.getY() >= heightMap[z << 4 | x]}, and
 *       {@code heightMap} is built by {@code Chunk.generateHeightMap:221-236}, which walks down
 *       from the top of the world and stops at the first cell whose
 *       {@code getLightOpacity() != 0}. So "this cell sees the sky" is exactly "there is no
 *       light-blocking cell at or above it", and the test here asks the same question from the
 *       other end: walk UP the player's own column from the head cell and stop at the first block
 *       with a non-zero light opacity. {@link Block#getLightOpacity} is
 *       {@code isOpaqueCube() ? 255 : 0} for an ordinary block ({@code Block.java:296}), so this
 *       is the same predicate vanilla stores, not a re-derivation of it.</li>
 *   <li><b>Side-closed.</b> All eight horizontal neighbours of the two cells a standing body
 *       occupies -- four sides at the feet cell and the same four at the head cell -- must be
 *       cells a body cannot walk into. "Cannot walk into" is the substrate's own
 *       {@code SimWorld.solid} rule ({@code SimWorld.java:980-982}), which is
 *       {@code getMaterial().blocksMovement() && isFullCube()}; the two come from
 *       {@code Material.blocksMovement} ({@code Material.java:110-113}) and
 *       {@code Block.isFullCube} ({@code Block.java:366-369}). Both heights are checked because a
 *       one-cell opening at head height is exactly how a mob walks in.</li>
 * </ul>
 *
 * <p><b>What was rejected, and why.</b>
 * <ul>
 *   <li><b>"Is there a block above the player."</b> It passes for a slab over a five-by-five hole
 *       the player is standing in the middle of, and for a roof the player can walk out from
 *       under in two steps. Neither is a shelter, and neither says anything about being
 *       reachable.</li>
 *   <li><b>"Can the player see the sky", on its own.</b> This is a LIGHT question in vanilla, not
 *       a safety one -- {@code Chunk.canSeeSky} exists to decide whether skylight reaches a cell.
 *       A player at the bottom of a one-wide, four-deep pit cannot see the sky and can be reached
 *       by anything that walks. Sky-closed alone is necessary and not sufficient.</li>
 *   <li><b>Light level (the number a player would actually feel).</b> Vanilla's hostile-spawn
 *       gate is a light level inside a spawn radius, and it is the truest version of "the player
 *       was safe". It cannot be evaluated here at all: this substrate has no lighting, and
 *       {@code SimWorld.KNOWN_GAPS} says so in those words rather than approximating it. Writing
 *       a light rule that nothing can evaluate would be a predicate that has only ever agreed
 *       with itself.</li>
 *   <li><b>Path reachability.</b> The most faithful definition available -- "can anything walk
 *       from outside to where the player is" -- needs a pathfinder over the grid, and it is a
 *       decision about what the world permits rather than a measurement of what the body has.
 *       That belongs to the planner, and this class is the instrument, not the policy. It is named
 *       here as the next definition rather than smuggled in as this one.</li>
 * </ul>
 *
 * <p><b>What this class cannot be, by construction.</b> It is a point measurement. A roof for one
 * tick is not a shelter, and a roof that appears on the last tick of the night is not a shelter
 * either. The region over the time window is {@link NightEnclosure}'s job, and joining this to a
 * health bar is {@code EvalSuite}'s.
 *
 * <p><b>The grid costs nothing through the controller seam.</b> {@link CellGrid#at} is a plain
 * cell lookup with no world handle and no counter behind it, so a caller can run this on every
 * tick of a walk without spending a read of the kind {@code TheBeliefLayerCostsNoWorldReadTest}
 * and {@code TheClockCostsTheWalkNoWorldReadTest} pin at exactly 25.
 */
public final class Enclosure {

    /**
     * The topmost block a world stores. {@code Chunk.storageArrays} is sixteen
     * {@code ExtendedBlockStorage} slots ({@code Chunk.java:109}) indexed by {@code y >> 4}
     * ({@code Chunk.java:428}), so the highest addressable cell is y = 255 and a column scan that
     * runs off the top of this is running off the top of the world.
     */
    public static final int SKY_TOP = 255;

    /**
     * How many block cells a standing body occupies.
     *
     * <p>{@code EntityPlayer:580} gives a width of 0.6 and a height of 1.8, and
     * {@code EntityPlayer:2328} puts the eye at 1.62. A body standing on a cell boundary therefore
     * fills the feet cell and the one above it and no more, so the enclosure test looks at two
     * cells, not one and not three.
     */
    public static final int BODY_CELLS = 2;

    /** The four horizontal neighbours of a cell, in the order {@link Verdict} counts them. */
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * A cell lookup. One method, so a caller can hand this a method reference and nothing else.
     *
     * <p>The default {@link #solidAt} is deliberately the substrate's own body-blocking rule and
     * not a second transcription of it, so the enclosure's idea of a wall and the physics' idea
     * of a wall cannot drift apart.
     */
    @FunctionalInterface
    public interface CellGrid {

        /** The vanilla block at a cell, or null for air. */
        Block at(int bx, int by, int bz);

        /**
         * Whether a body cannot walk into a cell: {@code SimWorld.solid}'s rule,
         * {@code getMaterial().blocksMovement() && isFullCube()}.
         */
        default boolean solidAt(int bx, int by, int bz) {
            Block b = at(bx, by, bz);
            return b != null && b.getMaterial().blocksMovement() && b.isFullCube();
        }
    }

    /**
     * One body's enclosure at one cell, with the two halves reported separately.
     *
     * @param skyClosed    whether the player's own column has a light-blocking cell above the head
     * @param wallsMissing how many of the eight horizontal neighbour cells a body could walk into;
     *                     zero is a closed side
     * @param wallsTotal   always {@code SIDES.length * BODY_CELLS}, carried so a report can print
     *                     "5 of 8 sides open" rather than a bare false
     */
    public record Verdict(boolean skyClosed, int wallsMissing, int wallsTotal) {

        /** Whether all eight neighbour cells block a body. */
        public boolean sideClosed() {
            return wallsMissing == 0;
        }

        /** Whether the player can neither see the sky nor be reached from the side. */
        public boolean enclosed() {
            return skyClosed && sideClosed();
        }

        /** One sentence, for a failure message that has to say WHICH half failed. */
        public String describe() {
            return "skyClosed=" + skyClosed + ", sides " + (wallsTotal - wallsMissing) + " of "
                    + wallsTotal + " closed";
        }
    }

    /**
     * The enclosure of a body whose feet occupy cell {@code (feetX, feetY, feetZ)}.
     *
     * @param grid  the cell lookup; never the controller seam
     * @param feetX the feet cell X
     * @param feetY the feet cell Y
     * @param feetZ the feet cell Z
     */
    public static Verdict at(CellGrid grid, int feetX, int feetY, int feetZ) {
        return new Verdict(skyClosed(grid, feetX, feetY, feetZ),
                openSides(grid, feetX, feetY, feetZ), SIDES.length * BODY_CELLS);
    }

    /**
     * Whether the cell cannot see the sky, asked the way {@code Chunk.canSeeSky} answers it.
     *
     * <p>The scan starts at the head cell rather than the feet cell because the body occupies
     * both and neither can be its own roof, and it stops at the first light-blocking cell rather
     * than reading a height map, because {@code SimWorld} has no chunk and therefore no
     * height map to read: {@code heightMap[z << 4 | x]} is a cache over exactly this scan
     * ({@code Chunk.generateHeightMap:221-236}) and recomputing it is the honest answer rather
     * than the cheap one. An enclosed body costs one or two cells of scan; an exposed body on an
     * open plain costs the whole column, which is stated here because it is the reason
     * {@link NightEnclosure} exposes a sampling period.
     */
    public static boolean skyClosed(CellGrid grid, int feetX, int feetY, int feetZ) {
        for (int y = feetY + BODY_CELLS - 1; y <= SKY_TOP; y++) {
            Block b = grid.at(feetX, y, feetZ);
            if (b != null && b.getLightOpacity() != 0) {
                return true;
            }
        }
        return false;
    }

    /** How many of the eight horizontal neighbour cells a body could walk into. */
    public static int openSides(CellGrid grid, int feetX, int feetY, int feetZ) {
        int open = 0;
        for (int body = 0; body < BODY_CELLS; body++) {
            for (int[] side : SIDES) {
                if (!grid.solidAt(feetX + side[0], feetY + body, feetZ + side[1])) {
                    open++;
                }
            }
        }
        return open;
    }

    private Enclosure() {
    }
}